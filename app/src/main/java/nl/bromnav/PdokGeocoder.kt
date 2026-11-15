package nl.bromnav

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Dutch address search via PDOK Locatieserver (BAG). Call from a background thread. */
object PdokGeocoder {
    data class Address(val label: String, val pos: LatLon, val street: String = "", val houseNo: String = "")

    private const val BASE = "https://api.pdok.nl/bzk/locatieserver/search/v3_1/free"

    /** Searches the delivery municipality first; falls back to all of NL if nothing matches. */
    fun search(q: String, near: LatLon = Config.RESTAURANT, rows: Int = 8): List<Address> {
        val local = query(q, near, rows, "&fq=" + URLEncoder.encode("gemeentenaam:${Config.MUNICIPALITY}", "UTF-8"))
        return local.ifEmpty { query(q, near, rows, "") }
    }

    private fun query(q: String, near: LatLon, rows: Int, extra: String): List<Address> {
        val url = "$BASE?q=${URLEncoder.encode(q, "UTF-8")}&fq=type:adres$extra&rows=$rows" +
            "&fl=weergavenaam,centroide_ll,straatnaam,huis_nlt&lat=${near.lat}&lon=${near.lon}"
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 6000; c.readTimeout = 6000
        c.setRequestProperty("User-Agent", "BromNav/0.1")
        try {
            val body = c.inputStream.bufferedReader().readText()
            val docs = JSONObject(body).getJSONObject("response").getJSONArray("docs")
            return (0 until docs.length()).mapNotNull { i ->
                val d = docs.getJSONObject(i)
                parsePoint(d.optString("centroide_ll"))?.let { Address(d.getString("weergavenaam"), it, d.optString("straatnaam"), d.optString("huis_nlt")) }
            }
        } finally { c.disconnect() }
    }

    /** "POINT(6.57 53.21)" -> LatLon */
    fun parsePoint(s: String): LatLon? {
        val m = Regex("""POINT\(([-\d.]+) ([-\d.]+)\)""").find(s) ?: return null
        return LatLon(m.groupValues[2].toDouble(), m.groupValues[1].toDouble())
    }
}
