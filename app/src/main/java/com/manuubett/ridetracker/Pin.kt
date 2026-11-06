package com.manuubett.ridetracker

import android.content.SharedPreferences
import android.util.Base64
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * The shop PIN. Only a salted hash is stored. The hashing is slow on
 * purpose, so call set() and check() from a background thread.
 */
object Pin {

    private const val ITERATIONS = 10000

    fun isSet(prefs: SharedPreferences): Boolean =
        !prefs.getString("pin_hash", "").isNullOrEmpty()

    private fun hash(pin: String, salt: ByteArray): String {
        val spec = PBEKeySpec(pin.toCharArray(), salt, ITERATIONS, 256)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1")

        return Base64.encodeToString(factory.generateSecret(spec).encoded, Base64.NO_WRAP)
    }

    fun set(prefs: SharedPreferences, pin: String) {
        val salt = ByteArray(16)
        SecureRandom().nextBytes(salt)

        prefs.edit()
            .putString("pin_salt", Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString("pin_hash", hash(pin, salt))
            .apply()
    }

    fun check(prefs: SharedPreferences, pin: String): Boolean {
        val saltText = prefs.getString("pin_salt", "") ?: ""
        val saved = prefs.getString("pin_hash", "") ?: ""

        if (saltText.isEmpty() || saved.isEmpty()) return true

        val salt = Base64.decode(saltText, Base64.NO_WRAP)

        return hash(pin, salt) == saved
    }

    fun clear(prefs: SharedPreferences) {
        prefs.edit().remove("pin_salt").remove("pin_hash").apply()
    }
}
