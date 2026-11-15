package nl.bromnav

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Learns how you actually ride compared with BRouter's estimate.
 * factor = actual seconds / predicted seconds, kept as the median of the last 30 legs per
 * distance band (short legs have relatively more stop-and-go, parking, finding the door).
 * Samples are kept in memory (read once from storage); safe to use from several threads.
 */
class SpeedModel(private val store: Store) {

    /** Persistent key/value storage (SharedPreferences in the app, a map in tests). */
    interface Store {
        fun get(key: String): String?
        fun put(key: String, value: String)
        fun clear()
    }

    constructor(ctx: Context) : this(ctx.getSharedPreferences("speed", Context.MODE_PRIVATE).let { p ->
        object : Store {
            override fun get(key: String) = p.getString(key, null)
            override fun put(key: String, value: String) { p.edit().putString(key, value).apply() }
            override fun clear() { p.edit().clear().apply() }
        }
    })

    private val bands = doubleArrayOf(1500.0, 4000.0) // <1.5 km, 1.5-4 km, >4 km
    private val cache = HashMap<Int, MutableList<Double>>()
    private val medians = HashMap<Int, Double>()

    private fun band(m: Double) = bands.indexOfFirst { m < it }.let { if (it < 0) bands.size else it }

    private fun samples(b: Int): MutableList<Double> = cache.getOrPut(b) {
        val a = runCatching { JSONArray(store.get("b$b") ?: "[]") }.getOrElse { JSONArray() }
        MutableList(a.length()) { a.getDouble(it) }
    }

    private fun changed(b: Int) { medians.remove(b); store.put("b$b", JSONArray(samples(b)).toString()) }

    @Synchronized fun factor(distM: Double): Double {
        val b = band(distM)
        return medians.getOrPut(b) {
            val s = samples(b).sorted()
            if (s.size < 3) 1.0 else s[s.size / 2]
        }
    }

    @Synchronized fun sampleCount(): Int = (0..bands.size).sumOf { samples(it).size }

    /** Record one finished leg. Ignores nonsense (GPS lost, long break). @return true if stored (so it can be undone) */
    @Synchronized fun record(distM: Double, predictedS: Double, actualS: Double): Boolean {
        if (distM < 300 || predictedS < 30) return false
        val f = actualS / predictedS
        if (f < 0.5 || f > 3.0) return false
        val b = band(distM)
        val s = samples(b); s.add(f)
        while (s.size > 30) s.removeAt(0)
        changed(b)
        return true
    }

    @Synchronized fun removeLast(distM: Double) {
        val b = band(distM); val s = samples(b)
        if (s.isNotEmpty()) { s.removeAt(s.size - 1); changed(b) }
    }

    @Synchronized fun clear() { cache.clear(); medians.clear(); store.clear() }

    fun describe(): String = JSONObject().apply {
        for (b in 0..bands.size) put("band$b", "%.2f (%d)".format(factor(if (b == 0) 0.0 else if (b == 1) 2000.0 else 5000.0), synchronized(this@SpeedModel) { samples(b).size }))
    }.toString()
}
