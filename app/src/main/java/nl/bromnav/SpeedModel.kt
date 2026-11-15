package nl.bromnav

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Learns how you actually ride compared with BRouter's estimate.
 * factor = actual seconds / predicted seconds, kept as the median of the last 30 legs per
 * distance band (short legs have relatively more stop-and-go, parking, finding the door).
 */
class SpeedModel(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("speed", Context.MODE_PRIVATE)
    private val bands = doubleArrayOf(1500.0, 4000.0) // <1.5 km, 1.5-4 km, >4 km

    private fun band(m: Double) = bands.indexOfFirst { m < it }.let { if (it < 0) bands.size else it }

    private fun samples(b: Int): MutableList<Double> {
        val a = JSONArray(prefs.getString("b$b", "[]"))
        return MutableList(a.length()) { a.getDouble(it) }
    }

    fun factor(distM: Double): Double {
        val s = samples(band(distM)).sorted()
        if (s.size < 3) return 1.0
        return s[s.size / 2]
    }

    fun sampleCount(): Int = (0..bands.size).sumOf { samples(it).size }

    /** Record one finished leg. Ignores nonsense (GPS lost, long break). */
    fun record(distM: Double, predictedS: Double, actualS: Double) {
        if (distM < 300 || predictedS < 30) return
        val f = actualS / predictedS
        if (f < 0.5 || f > 3.0) return
        val b = band(distM)
        val s = samples(b); s.add(f)
        while (s.size > 30) s.removeAt(0)
        prefs.edit().putString("b$b", JSONArray(s).toString()).apply()
    }

    fun describe(): String = JSONObject().apply {
        for (b in 0..bands.size) put("band$b", "%.2f (%d)".format(factor(if (b == 0) 0.0 else if (b == 1) 2000.0 else 5000.0), samples(b).size))
    }.toString()
}
