package nl.bromnav

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class Stop(
    val id: Long,
    var label: String,
    var pos: LatLon,
    var orderNo: String = "",
    /** epoch millis, or null */
    var deadline: Long? = null,
    var delivered: Boolean = false,
    var street: String = "",
    var houseNo: String = "",
    /** order cancelled/disabled by the user: kept in the list, ignored for routing */
    var cancelled: Boolean = false,
    var phone: String = "",
    /** courier note from the receipt ("Voor bezorger: ...") */
    var note: String = "",
    /** customer name (for the doorbell); never written to history */
    var name: String = "",
) {
    /** street / number, parsed from the label if PDOK fields are missing (e.g. old data) */
    fun streetName() = street.ifEmpty { Regex("""^(.*?)\s+\d""").find(label)?.groupValues?.get(1) ?: label }
    fun number() = houseNo.ifEmpty { Regex("""\s(\d+[^,\s]*)""").find(label)?.groupValues?.get(1) ?: "" }

    /** "1234AB 17": identifies the address of a receipt */
    fun key() = "${Regex("""\d{4}[A-Z]{2}""").find(label)?.value} ${houseNo.uppercase()}"

    fun toJson() = JSONObject().put("id", id).put("label", label).put("lat", pos.lat).put("lon", pos.lon)
        .put("orderNo", orderNo).put("deadline", deadline ?: -1L).put("delivered", delivered)
        .put("street", street).put("houseNo", houseNo).put("cancelled", cancelled)
        .put("phone", phone).put("note", note).put("name", name)

    companion object {
        fun fromJson(o: JSONObject) = Stop(
            o.getLong("id"), o.getString("label"), LatLon(o.getDouble("lat"), o.getDouble("lon")),
            o.optString("orderNo"), o.optLong("deadline", -1L).takeIf { it > 0 }, o.optBoolean("delivered"),
            o.optString("street"), o.optString("houseNo"), o.optBoolean("cancelled"),
            o.optString("phone"), o.optString("note"), o.optString("name"),
        )
    }
}

/** The current delivery run (stops in planned order) + the user's avoid points; survives restarts. */
class RunStore(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("run", Context.MODE_PRIVATE)
    val stops = ArrayList<Stop>()
    val nogos = ArrayList<NoGo>()
    /** true once the user re-ordered stops by hand: "Go" then keeps their order */
    var manualOrder = false

    init {
        manualOrder = prefs.getBoolean("manual", false)
        JSONArray(prefs.getString("stops", "[]")).let { a -> for (i in 0 until a.length()) stops.add(Stop.fromJson(a.getJSONObject(i))) }
        JSONArray(prefs.getString("nogos", "[]")).let { a ->
            for (i in 0 until a.length()) a.getJSONObject(i).let { nogos.add(NoGo(LatLon(it.getDouble("lat"), it.getDouble("lon")), it.getInt("r"))) }
        }
    }

    fun save() {
        prefs.edit()
            .putString("stops", JSONArray().apply { stops.forEach { put(it.toJson()) } }.toString())
            .putBoolean("manual", manualOrder)
            .putString("nogos", JSONArray().apply { nogos.forEach { put(JSONObject().put("lat", it.p.lat).put("lon", it.p.lon).put("r", it.radiusM)) } }.toString())
            .apply()
    }

    fun pending() = stops.filter { !it.delivered && !it.cancelled }
    fun cancelled() = stops.filter { it.cancelled && !it.delivered }

    /** Moves pending stop [from] to pending position [to] (0-based, among pending stops). */
    fun movePending(from: Int, to: Int) {
        val p = pending()
        if (from !in p.indices || to !in p.indices || from == to) return
        val reordered = p.toMutableList().apply { add(to, removeAt(from)) }
        val rest = stops.filter { it.delivered || it.cancelled }
        stops.clear(); stops.addAll(rest); stops.addAll(reordered)
        manualOrder = true
        save()
    }
}
