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
) {
    /** street / number, parsed from the label if PDOK fields are missing (e.g. old data) */
    fun streetName() = street.ifEmpty { Regex("""^(.*?)\s+\d""").find(label)?.groupValues?.get(1) ?: label }
    fun number() = houseNo.ifEmpty { Regex("""\s(\d+[^,\s]*)""").find(label)?.groupValues?.get(1) ?: "" }

    fun toJson() = JSONObject().put("id", id).put("label", label).put("lat", pos.lat).put("lon", pos.lon)
        .put("orderNo", orderNo).put("deadline", deadline ?: -1L).put("delivered", delivered)
        .put("street", street).put("houseNo", houseNo)

    companion object {
        fun fromJson(o: JSONObject) = Stop(
            o.getLong("id"), o.getString("label"), LatLon(o.getDouble("lat"), o.getDouble("lon")),
            o.optString("orderNo"), o.optLong("deadline", -1L).takeIf { it > 0 }, o.optBoolean("delivered"),
            o.optString("street"), o.optString("houseNo"),
        )
    }
}

/** The current delivery run (stops in planned order) + the user's avoid points; survives restarts. */
class RunStore(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("run", Context.MODE_PRIVATE)
    val stops = ArrayList<Stop>()
    val nogos = ArrayList<NoGo>()

    init {
        JSONArray(prefs.getString("stops", "[]")).let { a -> for (i in 0 until a.length()) stops.add(Stop.fromJson(a.getJSONObject(i))) }
        JSONArray(prefs.getString("nogos", "[]")).let { a ->
            for (i in 0 until a.length()) a.getJSONObject(i).let { nogos.add(NoGo(LatLon(it.getDouble("lat"), it.getDouble("lon")), it.getInt("r"))) }
        }
    }

    fun save() {
        prefs.edit()
            .putString("stops", JSONArray().apply { stops.forEach { put(it.toJson()) } }.toString())
            .putString("nogos", JSONArray().apply { nogos.forEach { put(JSONObject().put("lat", it.p.lat).put("lon", it.p.lon).put("r", it.radiusM)) } }.toString())
            .apply()
    }

    fun pending() = stops.filter { !it.delivered }
}
