package nl.bromnav

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Recently chosen addresses (most recent first), shown in the search dialog before typing. One per app mode. */
class SearchHistory(ctx: Context, name: String = "search_history") {
    private val prefs = ctx.getSharedPreferences(name, Context.MODE_PRIVATE)
    private val max = 15

    fun all(): List<PdokGeocoder.Address> {
        val a = JSONArray(prefs.getString("items", "[]"))
        return (0 until a.length()).map { i -> a.getJSONObject(i).let {
            PdokGeocoder.Address(it.getString("label"), LatLon(it.getDouble("lat"), it.getDouble("lon")), it.optString("street"), it.optString("houseNo"))
        } }
    }

    fun add(x: PdokGeocoder.Address) {
        val items = listOf(x) + all().filter { it.label != x.label }
        val a = JSONArray()
        items.take(max).forEach { a.put(JSONObject().put("label", it.label).put("lat", it.pos.lat).put("lon", it.pos.lon).put("street", it.street).put("houseNo", it.houseNo)) }
        prefs.edit().putString("items", a.toString()).apply()
    }
}
