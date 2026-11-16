package nl.bromnav

import android.content.Context
import kotlin.math.floor

/** A city you can work in: its municipality area (address book, places) and a larger map area. */
data class City(val name: String, val municipality: String, val minLat: Double, val minLon: Double, val maxLat: Double, val maxLon: Double) {
    val center get() = LatLon((minLat + maxLat) / 2, (minLon + maxLon) / 2)
    /** file name of its offline map */
    val key get() = name.lowercase().replace(Regex("""[^a-z]"""), "")
    /** offline map / routing area: the municipality plus ~8 km around it */
    val mapMinLat get() = minLat - 0.07; val mapMaxLat get() = maxLat + 0.07
    val mapMinLon get() = minLon - 0.11; val mapMaxLon get() = maxLon + 0.11
    val areaKm2 get() = (maxLat - minLat) * 111.2 * (maxLon - minLon) * 111.2 * Math.cos(Math.toRadians(center.lat))
    val mapAreaKm2 get() = (mapMaxLat - mapMinLat) * 111.2 * (mapMaxLon - mapMinLon) * 111.2 * Math.cos(Math.toRadians(center.lat))

    /** BRouter routing tiles (5° x 5°) the map area needs, e.g. ["E5_N50"] */
    fun segments(): List<String> {
        val out = LinkedHashSet<String>()
        var lon = floor(mapMinLon / 5) * 5
        while (lon <= mapMaxLon) {
            var lat = floor(mapMinLat / 5) * 5
            while (lat <= mapMaxLat) {
                out.add((if (lon < 0) "W${(-lon).toInt()}" else "E${lon.toInt()}") + "_" + (if (lat < 0) "S${(-lat).toInt()}" else "N${lat.toInt()}"))
                lat += 5
            }
            lon += 5
        }
        return out.toList()
    }
}

object Cities {
    /** the main Dutch cities (municipality area, roughly) */
    val all = listOf(
        City("Amsterdam", "Amsterdam", 52.28, 4.73, 52.43, 5.07),
        City("Rotterdam", "Rotterdam", 51.86, 4.35, 51.99, 4.60),
        City("Den Haag", "'s-Gravenhage", 52.00, 4.18, 52.13, 4.40),
        City("Utrecht", "Utrecht", 52.03, 4.97, 52.14, 5.20),
        City("Eindhoven", "Eindhoven", 51.39, 5.38, 51.50, 5.56),
        City("Groningen", "Groningen", 53.10, 6.40, 53.33, 6.82),
        City("Tilburg", "Tilburg", 51.51, 4.98, 51.62, 5.16),
        City("Almere", "Almere", 52.30, 5.08, 52.44, 5.35),
        City("Breda", "Breda", 51.53, 4.68, 51.64, 4.87),
        City("Nijmegen", "Nijmegen", 51.79, 5.75, 51.88, 5.92),
        City("Apeldoorn", "Apeldoorn", 52.13, 5.85, 52.27, 6.05),
        City("Haarlem", "Haarlem", 52.34, 4.58, 52.42, 4.70),
        City("Arnhem", "Arnhem", 51.94, 5.82, 52.03, 5.99),
        City("Enschede", "Enschede", 52.16, 6.80, 52.27, 6.99),
        City("Amersfoort", "Amersfoort", 52.12, 5.33, 52.20, 5.45),
        City("Zwolle", "Zwolle", 52.46, 6.03, 52.56, 6.18),
        City("'s-Hertogenbosch", "'s-Hertogenbosch", 51.65, 5.22, 51.75, 5.38),
        City("Leiden", "Leiden", 52.13, 4.44, 52.18, 4.53),
        City("Maastricht", "Maastricht", 50.80, 5.63, 50.90, 5.77),
        City("Leeuwarden", "Leeuwarden", 53.16, 5.73, 53.24, 5.88),
        City("Delft", "Delft", 51.97, 4.32, 52.03, 4.41),
        City("Dordrecht", "Dordrecht", 51.77, 4.62, 51.83, 4.75),
    )
    fun byName(n: String?) = all.firstOrNull { it.name == n }
}

/** Where you work: the chosen city and your restaurant (start and end of every run). Stored on the phone. */
object Region {
    @Volatile var city: City? = null; private set
    @Volatile var restaurantName = ""; private set
    @Volatile var restaurantPos: LatLon? = null; private set
    /** the restaurant's address as printed in the receipt header (never a customer address) */
    @Volatile var restaurantPostcode = ""; private set
    @Volatile var restaurantHouseNo = ""; private set
    @Volatile var restaurantStreet = ""; private set
    /** optional: digits of its phone number / a word of its website, also printed in the receipt header */
    @Volatile var restaurantPhoneDigits = ""; private set
    @Volatile var restaurantWeb = ""; private set

    val hasCity get() = city != null
    val hasRestaurant get() = restaurantPos != null

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("region", Context.MODE_PRIVATE)

    fun load(ctx: Context) {
        val p = prefs(ctx)
        city = Cities.byName(p.getString("city", null))
        // installs from before the city choice (offline Groningen map present): keep working in Groningen
        if (city == null && java.io.File(ctx.getExternalFilesDir(null), "tiles/groningen.pmtiles").exists()) {
            city = Cities.byName("Groningen"); p.edit().putString("city", "Groningen").apply()
        }
        restaurantName = p.getString("rName", "") ?: ""
        restaurantPos = if (p.contains("rLatB")) LatLon(java.lang.Double.longBitsToDouble(p.getLong("rLatB", 0)),
            java.lang.Double.longBitsToDouble(p.getLong("rLonB", 0))) else null
        restaurantPostcode = p.getString("rPc", "") ?: ""; restaurantHouseNo = p.getString("rNo", "") ?: ""
        restaurantStreet = p.getString("rStreet", "") ?: ""
        restaurantPhoneDigits = p.getString("rPhone", "") ?: ""; restaurantWeb = p.getString("rWeb", "") ?: ""
    }

    fun setCity(ctx: Context, c: City) { city = c; prefs(ctx).edit().putString("city", c.name).apply() }

    fun setRestaurant(ctx: Context, name: String, pos: LatLon, postcode: String, houseNo: String, street: String, phone: String, web: String) {
        restaurantName = name; restaurantPos = pos; restaurantPostcode = postcode.uppercase().replace(" ", "")
        restaurantHouseNo = houseNo; restaurantStreet = street
        restaurantPhoneDigits = phone.filter(Char::isDigit).let { if (it.length > 7) it.takeLast(7) else it }
        restaurantWeb = web.lowercase().removePrefix("https://").removePrefix("http://").removePrefix("www.").substringBefore('.')
        prefs(ctx).edit().putString("rName", name)
            .putLong("rLatB", java.lang.Double.doubleToRawLongBits(pos.lat)).putLong("rLonB", java.lang.Double.doubleToRawLongBits(pos.lon))
            .putString("rPc", restaurantPostcode).putString("rNo", houseNo).putString("rStreet", street)
            .putString("rPhone", restaurantPhoneDigits).putString("rWeb", restaurantWeb).apply()
    }

    /** for tests */
    fun setForTest(c: City?, name: String = "", pos: LatLon? = null, postcode: String = "", houseNo: String = "", street: String = "", phone: String = "", web: String = "") {
        city = c; restaurantName = name; restaurantPos = pos; restaurantPostcode = postcode; restaurantHouseNo = houseNo; restaurantStreet = street
        restaurantPhoneDigits = phone; restaurantWeb = web
    }
}
