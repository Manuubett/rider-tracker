package com.manuubett.ridetracker

import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

class MainActivity : Activity() {

    private val orange = Color.parseColor("#EA580C")
    private val green = Color.parseColor("#15803D")
    private val ink = Color.parseColor("#0F172A")
    private val grey = Color.parseColor("#64748B")
    private val slate = Color.parseColor("#475569")
    private val red = Color.parseColor("#DC2626")
    private val bg = Color.parseColor("#F1F5F9")

    private lateinit var prefs: SharedPreferences
    private lateinit var db: Db
    private lateinit var gate: PayGate
    private lateinit var root: LinearLayout

    private val handler = Handler(Looper.getMainLooper())

    private var screen = "main"
    private var unlocked = false
    private var allowed = false
    private var checking = true
    private var lastGateAt = 0L
    private var lastStopAt = 0L
    private var downloadId = -1L
    private var updateText = "Check for updates"

    // Bikes that are out: the text that must keep counting
    private class LiveRow(val startMs: Long, val view: TextView)

    private val live = ArrayList<LiveRow>()

    private val tick = object : Runnable {
        override fun run() {
            for (row in live) row.view.text = liveText(row.startMs)
            handler.postDelayed(this, 15_000L)
        }
    }

    private val downloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val id = intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L) ?: -1L
            if (id == downloadId && id != -1L) installDownloaded()
        }
    }

    // ---- Settings ----

    private val pricePerMinute: Int get() = prefs.getInt("price", 1)
    private val freeMinutes: Int get() = prefs.getInt("free", 0)
    private val shopName: String get() = prefs.getString("shop", "") ?: ""

    // ---- Life cycle ----

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        prefs = getSharedPreferences("ridetracker_settings", Context.MODE_PRIVATE)
        db = Db(this)
        gate = PayGate(this, appId = "ridetracker")

        val scroll = ScrollView(this)
        scroll.setBackgroundColor(bg)
        scroll.isFillViewport = true

        root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(16), dp(24), dp(16), dp(24))

        scroll.addView(root)
        setContentView(scroll)

        try {
            val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)

            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(downloadReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                registerReceiver(downloadReceiver, filter)
            }
        } catch (e: Exception) {
            // The download still happens; the user opens it from notifications
        }

        render()
    }

    override fun onResume() {
        super.onResume()

        // Ask for the PIN again after the app was away for 2 minutes
        if (Pin.isSet(prefs) && unlocked &&
            System.currentTimeMillis() - lastStopAt > 120_000L
        ) {
            unlocked = false
        }

        render()
        ensureGate(false)
    }

    override fun onStop() {
        lastStopAt = System.currentTimeMillis()
        handler.removeCallbacks(tick)
        super.onStop()
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(downloadReceiver)
        } catch (e: Exception) {
            // Not registered
        }

        handler.removeCallbacks(tick)
        super.onDestroy()
    }

    override fun onBackPressed() {
        if (screen != "main") {
            screen = "main"
            render()
        } else {
            super.onBackPressed()
        }
    }

    // ---- Subscription ----

    private fun ensureGate(force: Boolean) {
        val now = System.currentTimeMillis()

        if (Pin.isSet(prefs) && !unlocked) return
        if (!force && allowed && now - lastGateAt < 30 * 60_000L) return

        checking = true
        render()

        gate.check(
            onDenied = {
                allowed = false
                checking = false
                render()
            },
            onAllowed = {
                allowed = true
                checking = false
                lastGateAt = System.currentTimeMillis()
                render()
            }
        )
    }

    // ---- Drawing ----

    private fun render() {
        handler.removeCallbacks(tick)
        live.clear()
        root.removeAllViews()

        if (Pin.isSet(prefs) && !unlocked) {
            lockScreen()
            return
        }

        title()

        if (checking && !allowed) {
            note("Checking your subscription…")
            footer()
            return
        }

        if (!allowed) {
            val c = card()
            c.addView(heading("Subscription needed"))
            c.addView(body("Your free trial or subscription has ended. Your records are safe on this phone."))
            c.addView(button("Subscribe or renew", orange) { ensureGate(true) })
            footer()
            return
        }

        when (screen) {
            "debts" -> debtsScreen()
            "history" -> historyScreen()
            "report" -> reportScreen()
            "settings" -> settingsScreen()
            else -> mainScreen()
        }

        footer()

        if (live.isNotEmpty()) handler.postDelayed(tick, 15_000L)
    }

    private fun title() {
        val t = TextView(this)
        t.text = "Ride Tracker"
        t.textSize = 26f
        t.setTypeface(null, Typeface.BOLD)
        t.setTextColor(orange)
        root.addView(t)

        val s = TextView(this)
        s.text = if (shopName.isEmpty()) "Bike hire timing and billing" else shopName
        s.textSize = 14f
        s.setTextColor(grey)
        s.setPadding(0, 0, 0, dp(12))
        root.addView(s)
    }

    private fun backBar(label: String) {
        val b = Button(this)
        b.text = "←  $label"
        b.isAllCaps = false
        b.setTextColor(orange)
        b.textSize = 16f
        b.setBackgroundColor(Color.TRANSPARENT)
        b.gravity = Gravity.START or Gravity.CENTER_VERTICAL
        b.setPadding(0, 0, 0, 0)
        b.setOnClickListener {
            screen = "main"
            render()
        }
        root.addView(b, LinearLayout.LayoutParams(-2, dp(44)))
    }

    // ---- Lock ----

    private fun lockScreen() {
        val c = card()
        c.addView(heading("Ride Tracker is locked"))
        c.addView(body("Enter the shop PIN."))

        val pin = EditText(this)
        pin.hint = "PIN"
        pin.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        pin.gravity = Gravity.CENTER
        pin.textSize = 22f
        c.addView(pin)

        val msg = TextView(this)
        msg.setTextColor(red)
        msg.textSize = 13f
        c.addView(msg)

        val unlock = button("Unlock", orange) {}
        unlock.setOnClickListener {
            val typed = pin.text.toString()
            unlock.isEnabled = false

            thread {
                val ok = try {
                    Pin.check(prefs, typed)
                } catch (e: Exception) {
                    false
                }

                runOnUiThread {
                    if (ok) {
                        unlocked = true
                        render()
                        ensureGate(false)
                    } else {
                        unlock.isEnabled = true
                        pin.setText("")
                        msg.text = "Wrong PIN. Try again."
                    }
                }
            }
        }
        c.addView(unlock)

        c.addView(
            body(
                "Forgot the PIN? Ask the person who set it. Reinstalling the app erases all records."
            )
        )
    }

    // ---- Main screen ----

    private fun mainScreen() {
        val now = System.currentTimeMillis()
        val dayStart = startOfDay(now)
        val dayEnd = dayStart + 86_400_000L

        val open = db.openRides()
        val bikes = db.bikes()

        // Summary
        val s = card()
        s.addView(heading("Today"))
        s.addView(
            body(
                "Collected: ${money(db.income(dayStart, dayEnd))}\n" +
                    "Rides finished: ${db.rideCount(dayStart, dayEnd)}\n" +
                    "Bikes out now: ${open.size} of ${bikes.size}"
            )
        )

        // First-run hint
        if (!Pin.isSet(prefs)) {
            s.addView(body("Tip: set a PIN in Settings so only the shop can open the records."))
        }

        // Bikes
        val c = card()
        c.addView(heading("Bikes"))

        if (bikes.isEmpty()) {
            c.addView(body("Add your bikes to begin."))
        }

        for (b in bikes) {
            val ride = open[b.id]

            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding(0, dp(8), 0, dp(8))

            val left = LinearLayout(this)
            left.orientation = LinearLayout.VERTICAL

            val name = TextView(this)
            name.text = b.name
            name.textSize = 17f
            name.setTypeface(null, Typeface.BOLD)
            name.setTextColor(ink)
            left.addView(name)

            val status = TextView(this)
            status.textSize = 13f

            if (ride == null) {
                status.text = "Free"
                status.setTextColor(green)
            } else {
                status.text = liveText(ride.startMs)
                status.setTextColor(orange)
                live.add(LiveRow(ride.startMs, status))

                val who = TextView(this)
                who.text = "${ride.student} · since ${fmtClock(ride.startMs)}"
                who.textSize = 12f
                who.setTextColor(grey)
                left.addView(who)
            }

            left.addView(status)
            row.addView(left, LinearLayout.LayoutParams(0, -2, 1f))

            if (ride == null) {
                row.addView(pill("Give out", green) { giveOut(b) })
            } else {
                row.addView(pill("Return", orange) { giveBack(b, ride) })
            }

            row.setOnLongClickListener {
                bikeOptions(b, ride != null)
                true
            }

            c.addView(row)
        }

        c.addView(button("Add bike", slate) { addBikeDialog() })

        if (bikes.size < 5) {
            c.addView(button("Add many bikes at once", slate) { addManyDialog() })
        }

        if (bikes.isNotEmpty()) {
            c.addView(body("Tip: press and hold a bike to rename or remove it."))
        }

        // Navigation
        val n = card()
        val owed = db.owedCount()

        n.addView(
            button(
                if (owed == 0) "Who owes money" else "Who owes money ($owed · ${money(db.owedTotal())})",
                if (owed == 0) slate else red
            ) {
                screen = "debts"
                render()
            }
        )
        n.addView(button("History", slate) { screen = "history"; render() })
        n.addView(button("Reports", slate) { screen = "report"; render() })
        n.addView(button("Settings", slate) { screen = "settings"; render() })
    }

    private fun giveOut(b: Bike) {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(20), dp(8), dp(20), 0)

        val name = EditText(this)
        name.hint = "Student name"
        name.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
        name.setSingleLine()
        box.addView(name)

        val ident = EditText(this)
        ident.hint = "ID number or phone"
        ident.inputType = InputType.TYPE_CLASS_TEXT
        ident.setSingleLine()
        box.addView(ident)

        val dialog = AlertDialog.Builder(this)
            .setTitle("Give out ${b.name}")
            .setView(box)
            .setPositiveButton("Start timer", null)
            .setNegativeButton("Cancel", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val n = name.text.toString().trim()
                val i = ident.text.toString().trim()

                if (n.isEmpty() || i.isEmpty()) {
                    toast("Enter the name and the ID or phone")
                } else {
                    db.startRide(b, n, i, System.currentTimeMillis())
                    dialog.dismiss()
                    render()
                }
            }
        }

        dialog.show()
    }

    private fun giveBack(b: Bike, r: Ride) {
        val end = System.currentTimeMillis()
        val minutes = minutesFor(end - r.startMs)
        val amount = amountFor(minutes)

        val message =
            "${r.student} (${r.ident})\n\n" +
                "Out: ${fmtClock(r.startMs)}\n" +
                "Back: ${fmtClock(end)}\n" +
                "Time: $minutes min\n\n" +
                "Amount: ${money(amount)}"

        AlertDialog.Builder(this)
            .setTitle("Return ${b.name}")
            .setMessage(message)
            .setCancelable(false)
            .setPositiveButton("Paid") { _, _ ->
                db.finishRide(r.id, end, minutes, amount, true)
                render()
            }
            .setNegativeButton("Owes") { _, _ ->
                db.finishRide(r.id, end, minutes, amount, false)
                render()
            }
            .setNeutralButton("Not yet", null)
            .show()
    }

    private fun bikeOptions(b: Bike, isOut: Boolean) {
        val items = arrayOf("Rename", "Remove bike")

        AlertDialog.Builder(this)
            .setTitle(b.name)
            .setItems(items) { _, which ->
                if (which == 0) {
                    ask("Rename bike", "Bike name", b.name, false) {
                        db.renameBike(b.id, it)
                        render()
                    }
                } else if (isOut) {
                    toast("This bike is out. Return it first.")
                } else {
                    AlertDialog.Builder(this)
                        .setTitle("Remove ${b.name}?")
                        .setMessage("Old rides stay in the history.")
                        .setPositiveButton("Remove") { _, _ ->
                            db.removeBike(b.id)
                            render()
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun addBikeDialog() {
        val next = db.bikes().size + 1

        ask("Add bike", "Bike name", "Bike $next", false) {
            db.addBike(it)
            render()
        }
    }

    private fun addManyDialog() {
        ask("How many bikes?", "e.g. 10", "", true) {
            val count = (it.toIntOrNull() ?: 0).coerceIn(0, 100)
            val start = db.bikes().size

            for (i in 1..count) db.addBike("Bike ${start + i}")

            render()
        }
    }

    // ---- Who owes money ----

    private fun debtsScreen() {
        backBar("Who owes money")

        val list = db.debts()
        val c = card()

        c.addView(heading("Owed: ${money(db.owedTotal())}"))

        if (list.isEmpty()) {
            c.addView(body("Nobody owes money."))
            return
        }

        c.addView(body("Tap a name when they pay."))

        for (r in list) {
            c.addView(rideRow(r) { debtDialog(r) })
        }
    }

    private fun debtDialog(r: Ride) {
        val phone = r.ident.filter { it.isDigit() || it == '+' }

        val b = AlertDialog.Builder(this)
            .setTitle("${r.student} owes ${money(r.amount)}")
            .setMessage(
                "${r.bikeName} · ${r.minutes} min\n" +
                    "${fmtDate(r.startMs)}\n" +
                    "ID / phone: ${r.ident}"
            )
            .setPositiveButton("Mark paid") { _, _ ->
                db.markPaid(r.id, System.currentTimeMillis())
                render()
            }
            .setNegativeButton("Close", null)

        if (phone.filter { it.isDigit() }.length >= 9) {
            b.setNeutralButton("Call") { _, _ ->
                try {
                    startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone")))
                } catch (e: Exception) {
                    toast("Could not open the dialer")
                }
            }
        }

        b.show()
    }

    // ---- History ----

    private fun historyScreen() {
        backBar("History")

        val list = db.history(200)
        val c = card()

        c.addView(heading("Last ${list.size} rides"))

        if (list.isEmpty()) {
            c.addView(body("No rides yet."))
            return
        }

        c.addView(button("Share as spreadsheet (CSV)", slate) { exportCsv() })

        for (r in list) {
            c.addView(rideRow(r) {
                AlertDialog.Builder(this)
                    .setTitle(r.student)
                    .setMessage(
                        "Bike: ${r.bikeName}\n" +
                            "ID / phone: ${r.ident}\n" +
                            "Out: ${fmtDate(r.startMs)}\n" +
                            (if (r.endMs > 0) "Back: ${fmtDate(r.endMs)}\n" else "") +
                            "Time: ${r.minutes} min\n" +
                            "Amount: ${money(r.amount)}\n" +
                            "Status: ${statusOf(r)}" +
                            (if (r.paid) "\nPaid: ${fmtDate(r.paidMs)}" else "")
                    )
                    .setPositiveButton("OK", null)
                    .show()
            })
        }
    }

    private fun rideRow(r: Ride, click: () -> Unit): View {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.VERTICAL
        row.setPadding(0, dp(10), 0, dp(10))

        val top = TextView(this)
        top.text = "${r.student} · ${r.bikeName}"
        top.textSize = 16f
        top.setTypeface(null, Typeface.BOLD)
        top.setTextColor(ink)
        row.addView(top)

        val sub = TextView(this)
        sub.text = "${fmtDate(r.startMs)} · ${if (r.endMs > 0) "${r.minutes} min · ${money(r.amount)}" else "still out"} · ${statusOf(r)}"
        sub.textSize = 13f
        sub.setTextColor(if (statusOf(r) == "Owes") red else grey)
        row.addView(sub)

        row.setOnClickListener { click() }

        return row
    }

    private fun statusOf(r: Ride): String =
        if (r.endMs == 0L) "Out" else if (r.paid) "Paid" else "Owes"

    private fun exportCsv() {
        val sb = StringBuilder()
        sb.append("Out,Back,Bike,Student,ID or phone,Minutes,Amount,Status\n")

        for (r in db.history(5000)) {
            sb.append(csv(fmtDate(r.startMs))).append(',')
            sb.append(csv(if (r.endMs > 0) fmtDate(r.endMs) else "")).append(',')
            sb.append(csv(r.bikeName)).append(',')
            sb.append(csv(r.student)).append(',')
            sb.append(csv(r.ident)).append(',')
            sb.append(r.minutes).append(',')
            sb.append(r.amount).append(',')
            sb.append(statusOf(r)).append('\n')
        }

        val i = Intent(Intent.ACTION_SEND)
        i.type = "text/plain"
        i.putExtra(Intent.EXTRA_SUBJECT, "Ride Tracker history")
        i.putExtra(Intent.EXTRA_TEXT, sb.toString())

        startActivity(Intent.createChooser(i, "Share history"))
    }

    private fun csv(text: String): String = "\"" + text.replace("\"", "\"\"") + "\""

    // ---- Reports ----

    private fun reportScreen() {
        backBar("Reports")

        val now = System.currentTimeMillis()
        val today = startOfDay(now)
        val day = 86_400_000L

        val c = card()
        c.addView(heading("Money collected"))
        c.addView(
            body(
                "Today: ${money(db.income(today, today + day))}\n" +
                    "Last 7 days: ${money(db.income(today - 6 * day, today + day))}\n" +
                    "Last 30 days: ${money(db.income(today - 29 * day, today + day))}\n" +
                    "Still owed: ${money(db.owedTotal())}"
            )
        )

        val d = card()
        d.addView(heading("Last 7 days"))

        val fmt = SimpleDateFormat("EEE d MMM", Locale.getDefault())

        for (i in 0..6) {
            val from = today - i * day
            val rides = db.rideCount(from, from + day)
            val collected = db.income(from, from + day)

            val line = TextView(this)
            line.text = "${fmt.format(Date(from))}:  ${money(collected)}  ·  $rides rides"
            line.textSize = 14f
            line.setTextColor(ink)
            line.setPadding(0, dp(4), 0, dp(4))
            d.addView(line)
        }

        val e = card()
        e.addView(heading("Best bikes (30 days)"))

        val bikes = db.incomeByBike(today - 29 * day, today + day)

        if (bikes.isEmpty()) {
            e.addView(body("No payments yet."))
        }

        for ((name, total) in bikes) {
            val line = TextView(this)
            line.text = "$name:  ${money(total)}"
            line.textSize = 14f
            line.setTextColor(ink)
            line.setPadding(0, dp(4), 0, dp(4))
            e.addView(line)
        }
    }

    // ---- Settings ----

    private fun settingsScreen() {
        backBar("Settings")

        val c = card()
        c.addView(heading("Shop"))

        c.addView(
            settingRow("Shop name", if (shopName.isEmpty()) "Not set" else shopName) {
                ask("Shop name", "Name shown at the top", shopName, false) {
                    prefs.edit().putString("shop", it).apply()
                    render()
                }
            }
        )

        c.addView(
            settingRow("Price per minute", "KES $pricePerMinute") {
                ask("Price per minute (KES)", "e.g. 1", pricePerMinute.toString(), true) {
                    val v = it.toIntOrNull() ?: 0

                    if (v < 1) {
                        toast("Enter 1 or more")
                    } else {
                        prefs.edit().putInt("price", v).apply()
                        render()
                    }
                }
            }
        )

        c.addView(
            settingRow("Free minutes at the start", "$freeMinutes min") {
                ask("Free minutes", "0 for none", freeMinutes.toString(), true) {
                    prefs.edit().putInt("free", (it.toIntOrNull() ?: 0).coerceIn(0, 120)).apply()
                    render()
                }
            }
        )

        c.addView(body("Partial minutes are rounded up. A ride is always at least 1 minute."))

        val p = card()
        p.addView(heading("Security"))

        if (Pin.isSet(prefs)) {
            p.addView(button("Change PIN", slate) { pinDialog() })
            p.addView(button("Remove PIN", red) {
                Pin.clear(prefs)
                toast("PIN removed")
                render()
            })
        } else {
            p.addView(body("A PIN keeps the student records private."))
            p.addView(button("Set a PIN", orange) { pinDialog() })
        }

        val u = card()
        u.addView(heading("App"))
        u.addView(button(updateText, slate) { checkUpdate() })

        u.addView(button("How it works", slate) {
            AlertDialog.Builder(this)
                .setTitle("How it works")
                .setMessage(
                    "1. Tap Give out and enter the student's name and ID or phone. The timer starts.\n\n" +
                        "2. When the bike comes back, tap Return. You see the minutes and the amount.\n\n" +
                        "3. Tap Paid when you have received the money, or Owes to keep it on the list.\n\n" +
                        "Records stay on this phone and work without internet."
                )
                .setPositiveButton("OK", null)
                .show()
        })
    }

    private fun pinDialog() {
        ask("New PIN (4 to 8 digits)", "PIN", "", true) { first ->
            if (first.length < 4 || first.length > 8) {
                toast("The PIN must be 4 to 8 digits")
            } else {
                ask("Type the PIN again", "PIN", "", true) { second ->
                    if (first != second) {
                        toast("The two PINs did not match")
                    } else {
                        thread {
                            try {
                                Pin.set(prefs, first)
                            } catch (e: Exception) {
                                // Ignore: the check below reports it
                            }

                            runOnUiThread {
                                unlocked = true
                                toast(if (Pin.isSet(prefs)) "PIN saved. Do not forget it." else "Could not save the PIN")
                                render()
                            }
                        }
                    }
                }
            }
        }
    }

    private fun settingRow(label: String, value: String, click: () -> Unit): View {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.VERTICAL
        row.setPadding(0, dp(10), 0, dp(10))

        val a = TextView(this)
        a.text = label
        a.textSize = 16f
        a.setTextColor(ink)
        row.addView(a)

        val b = TextView(this)
        b.text = value
        b.textSize = 13f
        b.setTextColor(orange)
        row.addView(b)

        row.setOnClickListener { click() }

        return row
    }

    // ---- Updates ----

    private fun checkUpdate() {
        updateText = "Checking…"
        render()

        thread {
            try {
                val latest = UpdateChecker.fetchLatest()
                val mine = UpdateChecker.installedVersion(this)

                runOnUiThread {
                    if (latest != null && UpdateChecker.isNewer(latest.version, mine)) {
                        updateText = "Update ${latest.version} available"
                        render()

                        AlertDialog.Builder(this)
                            .setTitle("Update ${latest.version}")
                            .setMessage(if (latest.notes.isEmpty()) "A new version is ready." else latest.notes)
                            .setPositiveButton("Download") { _, _ -> startDownload(latest.docId) }
                            .setNegativeButton("Later", null)
                            .show()
                    } else {
                        updateText = "Up to date ($mine)"
                        render()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    updateText = "Check for updates"
                    render()
                    toast("Could not check. Try again with data on.")
                }
            }
        }
    }

    private fun startDownload(docId: String) {
        toast("Downloading…")

        thread {
            try {
                val url = UpdateChecker.requestDownloadUrl(docId)

                runOnUiThread {
                    val file = File(getExternalFilesDir(null), "RideTracker-update.apk")
                    if (file.exists()) file.delete()

                    val req = DownloadManager.Request(Uri.parse(url))
                        .setTitle("Ride Tracker update")
                        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                        .setDestinationInExternalFilesDir(this, null, "RideTracker-update.apk")

                    val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                    downloadId = dm.enqueue(req)
                }
            } catch (e: Exception) {
                runOnUiThread { toast("Download failed. Try again.") }
            }
        }
    }

    private fun installDownloaded() {
        val file = File(getExternalFilesDir(null), "RideTracker-update.apk")
        if (!file.exists()) return

        if (Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
            toast("Allow Ride Tracker to install apps, then download again")
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:$packageName")
                )
            )
            return
        }

        // targetSdk 28 lets us use a plain file URI
        val i = Intent(Intent.ACTION_VIEW)
        i.setDataAndType(Uri.fromFile(file), "application/vnd.android.package-archive")
        i.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        startActivity(i)
    }

    // ---- Money and time ----

    private fun minutesFor(ms: Long): Int {
        val m = Math.ceil(ms / 60000.0).toInt()
        return if (m < 1) 1 else m
    }

    private fun amountFor(minutes: Int): Int {
        val billable = minutes - freeMinutes
        return if (billable <= 0) 0 else billable * pricePerMinute
    }

    private fun liveText(startMs: Long): String {
        val minutes = minutesFor(System.currentTimeMillis() - startMs)
        return "Out $minutes min · ${money(amountFor(minutes))}"
    }

    private fun money(v: Number): String = "KES " + String.format(Locale.US, "%,d", v.toLong())

    private fun fmtClock(ms: Long): String =
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))

    private fun fmtDate(ms: Long): String =
        SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()).format(Date(ms))

    private fun startOfDay(ms: Long): Long {
        val c = Calendar.getInstance()
        c.timeInMillis = ms
        c.set(Calendar.HOUR_OF_DAY, 0)
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    // ---- Small helpers ----

    private fun ask(
        title: String,
        hint: String,
        initial: String,
        number: Boolean,
        ok: (String) -> Unit
    ) {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(20), dp(8), dp(20), 0)

        val input = EditText(this)
        input.hint = hint
        input.setText(initial)
        input.setSingleLine()
        input.inputType = if (number) {
            InputType.TYPE_CLASS_NUMBER
        } else {
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
        }
        box.addView(input)

        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(box)
            .setPositiveButton("OK") { _, _ ->
                val text = input.text.toString().trim()
                if (text.isNotEmpty()) ok(text)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun footer() {
        val f = TextView(this)
        f.text = "Ride Tracker build ${UpdateChecker.installedVersion(this)}\nPowered by dehemanuelssolutions.co.ke"
        f.textSize = 12f
        f.gravity = Gravity.CENTER
        f.setTextColor(grey)
        f.setPadding(0, dp(20), 0, 0)
        f.setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://dehemanuelssolutions.co.ke/")))
        }
        root.addView(f)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private fun card(): LinearLayout {
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        c.setPadding(dp(16), dp(14), dp(16), dp(14))

        val shape = GradientDrawable()
        shape.setColor(Color.WHITE)
        shape.cornerRadius = dp(14).toFloat()
        c.background = shape

        val lp = LinearLayout.LayoutParams(-1, -2)
        lp.bottomMargin = dp(12)
        root.addView(c, lp)

        return c
    }

    private fun heading(text: String): TextView {
        val t = TextView(this)
        t.text = text
        t.textSize = 18f
        t.setTypeface(null, Typeface.BOLD)
        t.setTextColor(ink)
        t.setPadding(0, 0, 0, dp(6))
        return t
    }

    private fun body(text: String): TextView {
        val t = TextView(this)
        t.text = text
        t.textSize = 14f
        t.setTextColor(grey)
        t.setPadding(0, dp(2), 0, dp(6))
        return t
    }

    private fun note(text: String) {
        val c = card()
        c.addView(body(text))
    }

    private fun button(text: String, color: Int, click: () -> Unit): Button {
        val b = Button(this)
        b.text = text
        b.isAllCaps = false
        b.setTextColor(Color.WHITE)
        b.textSize = 15f

        val shape = GradientDrawable()
        shape.setColor(color)
        shape.cornerRadius = dp(10).toFloat()
        b.background = shape

        val lp = LinearLayout.LayoutParams(-1, dp(48))
        lp.topMargin = dp(8)
        b.layoutParams = lp
        b.setOnClickListener { click() }

        return b
    }

    // A smaller button that sits at the end of a row
    private fun pill(text: String, color: Int, click: () -> Unit): Button {
        val b = Button(this)
        b.text = text
        b.isAllCaps = false
        b.setTextColor(Color.WHITE)
        b.textSize = 14f
        b.setPadding(dp(16), 0, dp(16), 0)

        val shape = GradientDrawable()
        shape.setColor(color)
        shape.cornerRadius = dp(20).toFloat()
        b.background = shape

        b.layoutParams = LinearLayout.LayoutParams(-2, dp(42))
        b.setOnClickListener { click() }

        return b
    }
}
