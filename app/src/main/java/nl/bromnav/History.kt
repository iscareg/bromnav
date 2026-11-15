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

/** Delivery history, stored as a small JSON file in app storage. */
class History(ctx: Context) {
    private val file = File(ctx.filesDir, "history.json")
    val orders = ArrayList<OrderRecord>()
    val laps = ArrayList<LapRecord>()

    init {
        if (file.exists()) runCatching {
            val o = JSONObject(file.readText())
            o.optJSONArray("orders")?.let { a -> for (i in 0 until a.length()) orders.add(OrderRecord.fromJson(a.getJSONObject(i))) }
            o.optJSONArray("laps")?.let { a -> for (i in 0 until a.length()) laps.add(LapRecord.fromJson(a.getJSONObject(i))) }
        }
    }

    /** Ignores impossible legs (> 60 km/h average = an accidental "Delivered" tap). */
    fun addOrder(r: OrderRecord): Boolean { if (r.avgKmh > 60) return false; orders.add(r); save(); return true }

    fun clear() { orders.clear(); laps.clear(); save() }
    fun removeLastOrder() { if (orders.isNotEmpty()) { orders.removeAt(orders.size - 1); save() } }
    fun removeLastLap() { if (laps.isNotEmpty()) { laps.removeAt(laps.size - 1); save() } }
    fun addLap(r: LapRecord) { laps.add(r); save() }

    private fun save() {
        val o = JSONObject()
            .put("orders", JSONArray().apply { orders.forEach { put(it.toJson()) } })
            .put("laps", JSONArray().apply { laps.forEach { put(it.toJson()) } })
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
