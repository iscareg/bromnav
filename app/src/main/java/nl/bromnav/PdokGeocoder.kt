package nl.bromnav

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Dutch address search via PDOK Locatieserver (BAG). Call from a background thread. */
object PdokGeocoder {
    data class Address(val label: String, val pos: LatLon, val street: String = "", val houseNo: String = "")

    private const val BASE = "https://api.pdok.nl/bzk/locatieserver/search/v3_1/free"

    /** offline address book (loaded at app start); used before the online service */
    @Volatile var book: AddressBook? = null

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

    /**
     * Exact BAG lookup by postcode + house number ("17", "32A", "120-2"), as printed on receipts.
     * Returns null if PDOK has no such address.
     */
    fun byPostcode(postcode: String, houseNo: String, street: String? = null): Address? {
        book?.let { b ->
            b.lookupFuzzy(postcode, houseNo, street)?.let { return it }
            if (b.hasPostcode(postcode)) return null
        }
        if (!Regex("""^\d{4}\s?[A-Za-z]{2}$""").matches(postcode.trim())) return null
        return byPostcodeOnline(postcode, houseNo)
    }

    /** online BAG lookup (for postcodes outside the offline address book) */
    fun byPostcodeOnline(postcode: String, houseNo: String): Address? {
        val num = Regex("""^\d+""").find(houseNo)?.value ?: return null
        val q = "postcode:${postcode.uppercase()} AND huisnummer:$num"
        val url = "$BASE?q=${URLEncoder.encode(q, "UTF-8")}&fq=type:adres&rows=20&fl=weergavenaam,centroide_ll,straatnaam,huis_nlt"
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 6000; c.readTimeout = 6000
        c.setRequestProperty("User-Agent", "BromNav/0.1")
        try {
            val docs = JSONObject(c.inputStream.bufferedReader().readText()).getJSONObject("response").getJSONArray("docs")
            val all = (0 until docs.length()).mapNotNull { i ->
                val d = docs.getJSONObject(i)
                parsePoint(d.optString("centroide_ll"))?.let { Address(d.getString("weergavenaam"), it, d.optString("straatnaam"), d.optString("huis_nlt")) }
            }
            fun norm(s: String) = s.uppercase().replace(Regex("""[\s\-]"""), "")
            return all.firstOrNull { norm(it.houseNo) == norm(houseNo) } ?: all.firstOrNull { norm(it.houseNo) == num } ?: all.firstOrNull()
        } finally { c.disconnect() }
    }

    /** "POINT(6.57 53.21)" -> LatLon */
    fun parsePoint(s: String): LatLon? {
        val m = Regex("""POINT\(([-\d.]+) ([-\d.]+)\)""").find(s) ?: return null
        return LatLon(m.groupValues[2].toDouble(), m.groupValues[1].toDouble())
    }
}

/** OSM name search (komoot Photon), online fallback for places outside the local index. */
object PhotonGeocoder {
    fun search(q: String, near: LatLon, limit: Int = 5): List<PdokGeocoder.Address> {
        val url = "https://photon.komoot.io/api/?q=${URLEncoder.encode(q, "UTF-8")}&limit=$limit&lat=${near.lat}&lon=${near.lon}" +
            "&bbox=${MapData.MIN_LON},${MapData.MIN_LAT},${MapData.MAX_LON},${MapData.MAX_LAT}"
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 6000; c.readTimeout = 6000; c.setRequestProperty("User-Agent", "BromNav/0.3")
        try {
            val fs = JSONObject(c.inputStream.bufferedReader().readText()).getJSONArray("features")
            return (0 until fs.length()).mapNotNull { i ->
                val f = fs.getJSONObject(i); val p = f.getJSONObject("properties")
                val co = f.getJSONObject("geometry").getJSONArray("coordinates")
                val name = p.optString("name").ifEmpty { return@mapNotNull null }
                val street = p.optString("street"); val hn = p.optString("housenumber")
                val label = "🌐 $name" + listOf(listOf(street, hn).filter { it.isNotEmpty() }.joinToString(" "), p.optString("city"))
                    .filter { it.isNotEmpty() }.joinToString(", ").let { if (it.isNotEmpty()) " · $it" else "" }
                PdokGeocoder.Address(label, LatLon(co.getDouble(1), co.getDouble(0)), name, hn)
            }
        } finally { c.disconnect() }
    }
}

