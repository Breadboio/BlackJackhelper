package com.breadlab.blackjackadvisor

import android.content.Context
import android.graphics.Rect

/** Pure validity + fraction→pixel conversion. JVM-tested; no Android types. */
object CalibrationMath {
    /** Returns [left,top,right,bottom] in pixels, or null if the rect is invalid. */
    fun toPixels(l: Float, t: Float, r: Float, b: Float, w: Int, h: Int): IntArray? {
        if (l < 0f || t < 0f || r <= l || b <= t) return null
        return intArrayOf((w * l).toInt(), (h * t).toInt(), (w * r).toInt(), (h * b).toInt())
    }
}

/**
 * Single owner of the calibration SharedPreferences (3 rects as fractions).
 * Thin Android shell over [CalibrationMath]. Read-through (never caches) so
 * re-calibration takes effect on the next captured frame.
 */
class CalibrationStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Saved fractions for a zone, or null if absent/invalid. [l,t,r,b]. */
    fun fractions(zone: Zone): FloatArray? {
        val k = zone.keys
        val l = prefs.getFloat(k[0], -1f); val t = prefs.getFloat(k[1], -1f)
        val r = prefs.getFloat(k[2], -1f); val b = prefs.getFloat(k[3], -1f)
        if (l < 0f || t < 0f || r <= l || b <= t) return null
        return floatArrayOf(l, t, r, b)
    }

    /** Pixel Rect for a zone at the given screen size, or null if uncalibrated. */
    fun rect(zone: Zone, w: Int, h: Int): Rect? {
        val f = fractions(zone) ?: return null
        val p = CalibrationMath.toPixels(f[0], f[1], f[2], f[3], w, h) ?: return null
        return Rect(p[0], p[1], p[2], p[3])
    }

    fun save(dealer: FloatArray, player: FloatArray, balance: FloatArray) {
        prefs.edit().apply {
            putZone(Zone.DEALER, dealer); putZone(Zone.PLAYER, player); putZone(Zone.BALANCE, balance)
        }.apply()
    }

    fun clear() {
        prefs.edit().apply {
            Zone.values().forEach { z -> z.keys.forEach { remove(it) } }
        }.apply()
    }

    private fun android.content.SharedPreferences.Editor.putZone(z: Zone, f: FloatArray) {
        putFloat(z.keys[0], f[0]); putFloat(z.keys[1], f[1])
        putFloat(z.keys[2], f[2]); putFloat(z.keys[3], f[3])
    }

    enum class Zone(val keys: Array<String>) {
        DEALER(arrayOf("cal_dealer_left", "cal_dealer_top", "cal_dealer_right", "cal_dealer_bottom")),
        PLAYER(arrayOf("cal_player_left", "cal_player_top", "cal_player_right", "cal_player_bottom")),
        BALANCE(arrayOf("cal_balance_left", "cal_balance_top", "cal_balance_right", "cal_balance_bottom")),
    }

    companion object { const val PREFS_NAME = "bja_prefs" }
}
