package nl.bromnav

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One delivered order. */
data class OrderRecord(
    val runId: Long,
    val orderNo: String,
    val label: String,
    val distM: Double,        // GPS-measured distance of the leg to this order
    val predictedS: Double,   // BRouter estimate for the leg
    val actualS: Double,      // leg start -> "Delivered" tap
    val deadline: Long?,      // from the receipt, epoch ms
    val deliveredAt: Long,
) {
    val lateMin: Long? get() = deadline?.let { (deliveredAt - it) / 60_000 }
    val avgKmh: Double get() = if (actualS > 0) distM / actualS * 3.6 else 0.0

    fun toJson() = JSONObject().put("runId", runId).put("orderNo", orderNo).put("label", label).put("distM", distM)
        .put("predictedS", predictedS).put("actualS", actualS).put("deadline", deadline ?: -1L).put("deliveredAt", deliveredAt)

    companion object {
        fun fromJson(o: JSONObject) = OrderRecord(
            o.getLong("runId"), o.optString("orderNo"), o.optString("label"), o.getDouble("distM"),
            o.getDouble("predictedS"), o.getDouble("actualS"), o.optLong("deadline", -1).takeIf { it > 0 }, o.getLong("deliveredAt"),
        )
    }
}

/** One complete lap: leave the restaurant, deliver, come back. */
data class LapRecord(val runId: Long, val startMs: Long, val endMs: Long, val orders: Int, val distM: Double) {
    val durationS get() = (endMs - startMs) / 1000.0
    fun toJson() = JSONObject().put("runId", runId).put("startMs", startMs).put("endMs", endMs).put("orders", orders).put("distM", distM)
    companion object {
        fun fromJson(o: JSONObject) = LapRecord(o.getLong("runId"), o.getLong("startMs"), o.getLong("endMs"), o.getInt("orders"), o.getDouble("distM"))
    }
}

/** One simple-navigation trip: from where you started to the destination you chose. */
data class TripRecord(val startMs: Long, val endMs: Long, val fromLabel: String, val from: LatLon,
                      val toLabel: String, val to: LatLon, val distM: Double, val arrived: Boolean) {
    val durationS get() = (endMs - startMs) / 1000.0
    val avgKmh get() = if (durationS > 0) distM / durationS * 3.6 else 0.0
    fun toJson() = JSONObject().put("startMs", startMs).put("endMs", endMs).put("fromLabel", fromLabel).put("fLat", from.lat).put("fLon", from.lon)
        .put("toLabel", toLabel).put("tLat", to.lat).put("tLon", to.lon).put("distM", distM).put("arrived", arrived)
    companion object {
        fun fromJson(o: JSONObject) = TripRecord(o.getLong("startMs"), o.getLong("endMs"), o.optString("fromLabel"),
            LatLon(o.getDouble("fLat"), o.getDouble("fLon")), o.optString("toLabel"), LatLon(o.getDouble("tLat"), o.getDouble("tLon")),
            o.getDouble("distM"), o.optBoolean("arrived", true))
    }
}

/** Statistics over simple-navigation trips. */
object TripStats {
    /** places closer than this count as the same address */
    const val SAME_PLACE_M = 150.0

    data class Place(val label: String, val pos: LatLon, val trips: List<TripRecord>) {
        val count get() = trips.size
        val avgDurationS get() = trips.map { it.durationS }.average()
        val lastMs get() = trips.maxOf { it.endMs }
    }

    data class Route(val from: String, val to: String, val trips: List<TripRecord>) {
        val count get() = trips.size
        val avgDurationS get() = trips.map { it.durationS }.average()
        val bestDurationS get() = trips.minOf { it.durationS }
        val avgKmh get() = trips.sumOf { it.distM } / trips.sumOf { it.durationS }.coerceAtLeast(1.0) * 3.6
    }

    /** destinations, most visited first */
    fun places(trips: List<TripRecord>): List<Place> {
        val groups = ArrayList<MutableList<TripRecord>>()
        for (t in trips.sortedBy { it.startMs }) {
            groups.firstOrNull { g -> Geo.dist(g[0].to, t.to) < SAME_PLACE_M }?.add(t) ?: groups.add(mutableListOf(t))
        }
        return groups.map { g -> Place(g.last().toLabel, g[0].to, g) }.sortedWith(compareByDescending<Place> { it.count }.thenByDescending { it.lastMs })
    }

    /** the same start -> finish ridden more than [minTimes] times: a "run" to compare your times */
    fun routes(trips: List<TripRecord>, minTimes: Int = 3): List<Route> {
        val groups = ArrayList<MutableList<TripRecord>>()
        for (t in trips.filter { it.arrived }.sortedBy { it.startMs }) {
            groups.firstOrNull { g -> Geo.dist(g[0].from, t.from) < SAME_PLACE_M && Geo.dist(g[0].to, t.to) < SAME_PLACE_M }?.add(t)
                ?: groups.add(mutableListOf(t))
        }
        return groups.filter { it.size > minTimes }.map { g -> Route(g.last().fromLabel, g.last().toLabel, g) }.sortedByDescending { it.count }
    }
}

/** Delivery history, stored as a small JSON file in app storage. */
class History(ctx: Context) {
    private val file = File(ctx.filesDir, "history.json")
    val orders = ArrayList<OrderRecord>()
    val laps = ArrayList<LapRecord>()
    val trips = ArrayList<TripRecord>()

    init {
        if (file.exists()) runCatching {
            val o = JSONObject(file.readText())
            o.optJSONArray("orders")?.let { a -> for (i in 0 until a.length()) orders.add(OrderRecord.fromJson(a.getJSONObject(i))) }
            o.optJSONArray("laps")?.let { a -> for (i in 0 until a.length()) laps.add(LapRecord.fromJson(a.getJSONObject(i))) }
            o.optJSONArray("trips")?.let { a -> for (i in 0 until a.length()) trips.add(TripRecord.fromJson(a.getJSONObject(i))) }
        }
    }

    /** Ignores impossible legs (> 60 km/h average = an accidental "Delivered" tap). */
    fun addOrder(r: OrderRecord): Boolean { if (r.avgKmh > 60) return false; orders.add(r); save(); return true }

    fun clear() { orders.clear(); laps.clear(); trips.clear(); save() }
    /** a simple-navigation trip; ignores tiny or impossible ones (GPS jumps, End right after Go) */
    fun addTrip(t: TripRecord): Boolean {
        if (t.distM < 200 || t.durationS < 30 || t.avgKmh > 60) return false
        trips.add(t); save(); return true
    }
    fun removeLastOrder() { if (orders.isNotEmpty()) { orders.removeAt(orders.size - 1); save() } }
    fun removeLastLap() { if (laps.isNotEmpty()) { laps.removeAt(laps.size - 1); save() } }
    fun addLap(r: LapRecord) { laps.add(r); save() }

    private fun save() {
        val o = JSONObject()
            .put("orders", JSONArray().apply { orders.forEach { put(it.toJson()) } })
            .put("laps", JSONArray().apply { laps.forEach { put(it.toJson()) } })
            .put("trips", JSONArray().apply { trips.forEach { put(it.toJson()) } })
        val tmp = File(file.path + ".tmp"); tmp.writeText(o.toString()); tmp.renameTo(file)
    }
}

/** GTA-style urgency stars, 0..5. */
object Urgency {
    /**
     * @param etaS app's (personal) estimated seconds until arrival at the stop
     * @param leftS seconds until the receipt deadline
     * 5 = already later than the app ETA allows (leftS < etaS); 0 = leftS >= 2 * etaS.
     */
    fun stars(etaS: Double, leftS: Double): Int {
        if (leftS < etaS) return 5
        if (etaS <= 0) return 0
        val r = leftS / etaS          // 1 .. 2 maps to 4 .. 1 stars
        if (r >= 2.0) return 0
        return (1 + ((2.0 - r) * 4).toInt()).coerceIn(1, 4)
    }
}
