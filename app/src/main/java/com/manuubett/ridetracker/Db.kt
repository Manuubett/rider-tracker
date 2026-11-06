package com.manuubett.ridetracker

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class Bike(val id: Long, val name: String)

class Ride(
    val id: Long,
    val bikeId: Long,
    val bikeName: String,
    val student: String,
    val ident: String,
    val startMs: Long,
    val endMs: Long,      // 0 while the bike is still out
    val minutes: Int,
    val amount: Int,
    val paid: Boolean,
    val paidMs: Long
)

/**
 * All records stay on the phone (works with no internet).
 *
 * A ride is "out" while endMs is 0, "owed" when it has ended and is not
 * paid, and "paid" once the money is received.
 */
class Db(context: Context) : SQLiteOpenHelper(context, "rides.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE bikes(" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "name TEXT NOT NULL," +
                "active INTEGER NOT NULL DEFAULT 1)"
        )

        db.execSQL(
            "CREATE TABLE rides(" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "bike_id INTEGER NOT NULL," +
                "bike_name TEXT NOT NULL," +
                "student TEXT NOT NULL," +
                "ident TEXT NOT NULL," +
                "start_ms INTEGER NOT NULL," +
                "end_ms INTEGER NOT NULL DEFAULT 0," +
                "minutes INTEGER NOT NULL DEFAULT 0," +
                "amount INTEGER NOT NULL DEFAULT 0," +
                "paid INTEGER NOT NULL DEFAULT 0," +
                "paid_ms INTEGER NOT NULL DEFAULT 0)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Version 1 only
    }

    // ---- Bikes ----

    fun addBike(name: String): Long {
        val v = ContentValues()
        v.put("name", name)
        v.put("active", 1)
        return writableDatabase.insert("bikes", null, v)
    }

    fun renameBike(id: Long, name: String) {
        val v = ContentValues()
        v.put("name", name)
        writableDatabase.update("bikes", v, "id=?", arrayOf(id.toString()))
    }

    // The bike is hidden, but its old rides keep the name they had
    fun removeBike(id: Long) {
        val v = ContentValues()
        v.put("active", 0)
        writableDatabase.update("bikes", v, "id=?", arrayOf(id.toString()))
    }

    fun bikes(): List<Bike> {
        val out = ArrayList<Bike>()

        readableDatabase.rawQuery(
            "SELECT id, name FROM bikes WHERE active=1 ORDER BY id", null
        ).use { c ->
            while (c.moveToNext()) out.add(Bike(c.getLong(0), c.getString(1)))
        }

        return out
    }

    // ---- Rides ----

    private fun readRide(c: android.database.Cursor): Ride {
        return Ride(
            c.getLong(0),
            c.getLong(1),
            c.getString(2),
            c.getString(3),
            c.getString(4),
            c.getLong(5),
            c.getLong(6),
            c.getInt(7),
            c.getInt(8),
            c.getInt(9) == 1,
            c.getLong(10)
        )
    }

    private val rideColumns =
        "id, bike_id, bike_name, student, ident, start_ms, end_ms, minutes, amount, paid, paid_ms"

    fun startRide(bike: Bike, student: String, ident: String, startMs: Long): Long {
        val v = ContentValues()
        v.put("bike_id", bike.id)
        v.put("bike_name", bike.name)
        v.put("student", student)
        v.put("ident", ident)
        v.put("start_ms", startMs)
        return writableDatabase.insert("rides", null, v)
    }

    fun finishRide(id: Long, endMs: Long, minutes: Int, amount: Int, paid: Boolean) {
        val v = ContentValues()
        v.put("end_ms", endMs)
        v.put("minutes", minutes)
        v.put("amount", amount)
        v.put("paid", if (paid) 1 else 0)
        v.put("paid_ms", if (paid) endMs else 0L)
        writableDatabase.update("rides", v, "id=?", arrayOf(id.toString()))
    }

    fun markPaid(id: Long, whenMs: Long) {
        val v = ContentValues()
        v.put("paid", 1)
        v.put("paid_ms", whenMs)
        writableDatabase.update("rides", v, "id=?", arrayOf(id.toString()))
    }

    // Bikes that are out now, by bike id
    fun openRides(): Map<Long, Ride> {
        val out = HashMap<Long, Ride>()

        readableDatabase.rawQuery(
            "SELECT $rideColumns FROM rides WHERE end_ms=0", null
        ).use { c ->
            while (c.moveToNext()) {
                val r = readRide(c)
                out[r.bikeId] = r
            }
        }

        return out
    }

    fun debts(): List<Ride> {
        val out = ArrayList<Ride>()

        readableDatabase.rawQuery(
            "SELECT $rideColumns FROM rides WHERE end_ms>0 AND paid=0 ORDER BY end_ms DESC", null
        ).use { c ->
            while (c.moveToNext()) out.add(readRide(c))
        }

        return out
    }

    fun history(limit: Int): List<Ride> {
        val out = ArrayList<Ride>()

        readableDatabase.rawQuery(
            "SELECT $rideColumns FROM rides ORDER BY id DESC LIMIT $limit", null
        ).use { c ->
            while (c.moveToNext()) out.add(readRide(c))
        }

        return out
    }

    // ---- Totals ----

    private fun oneNumber(sql: String, args: Array<String>?): Long {
        val c = readableDatabase.rawQuery(sql, args)

        try {
            return if (c.moveToFirst()) c.getLong(0) else 0L
        } finally {
            c.close()
        }
    }

    // Money received between two moments (counted on the day it was paid)
    fun income(from: Long, to: Long): Long = oneNumber(
        "SELECT COALESCE(SUM(amount),0) FROM rides WHERE paid=1 AND paid_ms>=? AND paid_ms<?",
        arrayOf(from.toString(), to.toString())
    )

    // Rides that ended between two moments
    fun rideCount(from: Long, to: Long): Int = oneNumber(
        "SELECT COUNT(*) FROM rides WHERE end_ms>0 AND end_ms>=? AND end_ms<?",
        arrayOf(from.toString(), to.toString())
    ).toInt()

    // Number of unpaid rides and the money still owed
    fun owedCount(): Int = oneNumber(
        "SELECT COUNT(*) FROM rides WHERE end_ms>0 AND paid=0", null
    ).toInt()

    fun owedTotal(): Long = oneNumber(
        "SELECT COALESCE(SUM(amount),0) FROM rides WHERE end_ms>0 AND paid=0", null
    )

    // Money received per bike between two moments, best first
    fun incomeByBike(from: Long, to: Long): List<Pair<String, Long>> {
        val out = ArrayList<Pair<String, Long>>()

        readableDatabase.rawQuery(
            "SELECT bike_name, SUM(amount) AS total FROM rides " +
                "WHERE paid=1 AND paid_ms>=? AND paid_ms<? " +
                "GROUP BY bike_name ORDER BY total DESC",
            arrayOf(from.toString(), to.toString())
        ).use { c ->
            while (c.moveToNext()) out.add(Pair(c.getString(0), c.getLong(1)))
        }

        return out
    }
}
