package nl.bromnav

import java.io.InputStream
import java.text.Normalizer
import java.util.zip.GZIPInputStream
import kotlin.math.ln

/**
 * Offline "smart" search over named places from OpenStreetMap (shops, restaurants, landmarks...).
 *  - accent-insensitive, word-prefix matching ("alb hei" -> Albert Heijn)
 *  - typo-tolerant (1 edit for words >= 4 letters, 2 for >= 7)
 *  - category words in Dutch and English ("pizza", "apotheek", "supermarkt", "tankstation" ...)
 *  - ranked by match quality and distance from you
 * Data: tab-separated, see tools/build_pois.py (name, key, value, cuisine, alt names, street,
 * housenumber, postcode, city, lat, lon).
 */
class PoiIndex private constructor(private val items: List<Poi>) {

    class Poi(
        val name: String, val key: String, val value: String, val street: String, val houseNo: String,
        val postcode: String, val city: String, val pos: LatLon,
        val nameTokens: List<String>, val nameNorm: String, val tagWords: List<String>,
    ) {
        val icon get() = icon(key, value)
        val address get() = listOf(listOf(street, houseNo).filter { it.isNotEmpty() }.joinToString(" "), city)
            .filter { it.isNotEmpty() }.joinToString(", ")
        val kindLabel get() = value.replace('_', ' ')
    }

    data class Hit(val poi: Poi, val score: Double, val distM: Double)

    val size get() = items.size

    fun search(query: String, near: LatLon, limit: Int = 8): List<Hit> {
        val q = norm(query).split(' ').filter { it.isNotEmpty() }
        if (q.isEmpty()) return emptyList()
        val qn = q.joinToString(" ")
        val hits = ArrayList<Hit>()
        for (p in items) {
            var score = 0.0; var misses = 0
            for (t in q) {
                val s = maxOf(tokenScore(t, p.nameTokens), 0.9 * tokenScore(t, p.tagWords))
                if (s <= 0) { misses++; if (misses > (if (q.size >= 3) 1 else 0)) break } else score += s
            }
            // every word must match (one miss allowed for long queries)
            if (misses > (if (q.size >= 3) 1 else 0)) continue
            score -= misses * 2.0
            if (p.nameNorm == qn) score += 1.5 else if (p.nameNorm.startsWith(qn)) score += 1.0
            val d = Geo.dist(near, p.pos)
            score -= 1.2 * ln(1 + d / 1000)                  // nearer is better (strong: you want the one close by)
            if (p.key == "tourism" && p.value == "information") score -= 1.5   // info boards: rarely the target
            hits.add(Hit(p, score, d))
        }
        // the same place is often mapped several times (station, its bike parking, its car park...)
        val out = ArrayList<Hit>()
        for (h in hits.sortedByDescending { it.score }) {
            if (out.none { it.poi.nameNorm == h.poi.nameNorm && Geo.dist(it.poi.pos, h.poi.pos) < 150 }) out.add(h)
            if (out.size >= limit) break
        }
        return out
    }

    companion object {
        private val catCache = HashMap<String, List<String>>()
        private val cuisineCache = HashMap<String, List<String>>()

        /** Accepts gzip or plain text (the APK packer un-gzips .gz assets). */
        fun load(input: InputStream): PoiIndex {
            val list = ArrayList<Poi>(20000)
            val bin = java.io.BufferedInputStream(input, 1 shl 16)
            bin.mark(2); val gz = bin.read() == 0x1f && bin.read() == 0x8b; bin.reset()
            (if (gz) GZIPInputStream(bin) else bin).bufferedReader().useLines { lines ->
                for (line in lines) {
                    val f = line.split('\t')
                    if (f.size < 11) continue
                    val lat = f[9].toDoubleOrNull() ?: continue; val lon = f[10].toDoubleOrNull() ?: continue
                    val nameNorm = norm(f[0])
                    val tags = ArrayList<String>(catCache.getOrPut(f[1] + "=" + f[2]) { categoryWords(f[1], f[2]) })
                    if (f[3].isNotEmpty()) for (c in f[3].split(';')) tags.addAll(cuisineCache.getOrPut(c) { cuisineWords(c) })
                    if (f[4].isNotEmpty()) for (a in f[4].split(';')) for (w in norm(a).split(' ')) if (w.isNotEmpty()) tags.add(w)
                    list.add(Poi(f[0], f[1], f[2], f[5], f[6], f[7], f[8], LatLon(lat, lon),
                        nameNorm.split(' ').filter { it.isNotEmpty() }, nameNorm, tags))
                }
            }
            return PoiIndex(list)
        }

        /** lower case, no accents, only letters/digits separated by single spaces (fast: hot path) */
        fun norm(s: String): String {
            val src = if (s.any { it.code > 127 }) Normalizer.normalize(s, Normalizer.Form.NFD) else s
            val sb = StringBuilder(src.length); var space = false
            for (ch in src) {
                val c = ch.lowercaseChar()
                when {
                    c in 'a'..'z' || c in '0'..'9' -> { if (space && sb.isNotEmpty()) sb.append(' '); sb.append(c); space = false }
                    c == '\'' || Character.getType(c) == Character.NON_SPACING_MARK.toInt() -> {}
                    else -> space = true
                }
            }
            return sb.toString()
        }

        /** 3 exact word, 2.4 word prefix, 1.6 typo (fuzzy), 1.0 inside a word, 0 no match */
        fun tokenScore(t: String, words: List<String>): Double {
            var best = 0.0
            for (w in words) {
                val s = when {
                    w == t -> 3.0
                    t.length >= 2 && w.startsWith(t) -> 2.4
                    t.length >= 4 && w[0] == t[0] && fuzzy(t, w) -> 1.6   // typos rarely hit the first letter
                    t.length >= 4 && w.contains(t) -> 1.0
                    else -> 0.0
                }
                if (s > best) { best = s; if (s >= 3.0) break }
            }
            return best
        }

        /** typo match against the whole word or its prefix of similar length */
        private fun fuzzy(t: String, w: String): Boolean {
            val maxEd = if (t.length >= 7) 2 else 1
            if (Math.abs(w.length - t.length) <= maxEd && lev(t, w, maxEd) <= maxEd) return true
            if (w.length > t.length) return lev(t, w.substring(0, t.length), maxEd) <= maxEd
            return false
        }

        private fun lev(a: String, b: String, cap: Int): Int {
            var prev = IntArray(b.length + 1) { it }
            for (i in 1..a.length) {
                val cur = IntArray(b.length + 1); cur[0] = i; var rowMin = i
                for (j in 1..b.length) {
                    cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
                    rowMin = minOf(rowMin, cur[j])
                }
                if (rowMin > cap) return cap + 1
                prev = cur
            }
            return prev[b.length]
        }

        private val CATEGORY = mapOf(
            "restaurant" to "restaurant eten diner", "fast_food" to "fastfood snackbar snack friet patat afhaal",
            "cafe" to "cafe koffie coffee lunch", "bar" to "bar kroeg cafe", "pub" to "pub kroeg bar cafe", "biergarten" to "bar",
            "ice_cream" to "ijs ijssalon icecream", "pharmacy" to "apotheek pharmacy", "hospital" to "ziekenhuis hospital",
            "clinic" to "kliniek clinic", "doctors" to "huisarts dokter doctor", "dentist" to "tandarts dentist",
            "school" to "school", "university" to "universiteit university uni", "college" to "hogeschool college",
            "kindergarten" to "kinderopvang creche", "fuel" to "tankstation benzine gas station pomp",
            "parking" to "parkeren parking garage", "bicycle_parking" to "fietsenstalling", "bank" to "bank",
            "atm" to "pinautomaat geldautomaat atm pinnen", "post_office" to "postkantoor post", "police" to "politie police",
            "fire_station" to "brandweer", "townhall" to "gemeentehuis stadhuis", "library" to "bibliotheek library",
            "cinema" to "bioscoop cinema film", "theatre" to "theater schouwburg", "place_of_worship" to "kerk church moskee",
            "supermarket" to "supermarkt supermarket boodschappen", "convenience" to "buurtwinkel avondwinkel",
            "bakery" to "bakker bakkerij bakery brood", "butcher" to "slager butcher", "greengrocer" to "groenteboer",
            "clothes" to "kleding clothes", "shoes" to "schoenen shoes", "hairdresser" to "kapper kapsalon hairdresser",
            "chemist" to "drogist drogisterij", "florist" to "bloemist bloemen", "bicycle" to "fietsenwinkel fiets bike",
            "car" to "autogarage auto car", "car_repair" to "garage autogarage", "electronics" to "elektronica",
            "mobile_phone" to "telefoon gsm", "furniture" to "meubels", "doityourself" to "bouwmarkt doe het zelf",
            "hardware" to "ijzerwaren bouwmarkt", "laundry" to "wasserette", "alcohol" to "slijterij drank",
            "hotel" to "hotel", "hostel" to "hostel", "museum" to "museum", "station" to "station trein train ns",
            "sports_centre" to "sportcentrum sporthal", "fitness_centre" to "sportschool gym fitness",
            "swimming_pool" to "zwembad", "park" to "park", "neighbourhood" to "wijk buurt", "suburb" to "wijk",
            "village" to "dorp", "hamlet" to "gehucht", "square" to "plein markt", "community_centre" to "buurthuis",
        )
        private val CUISINE = mapOf(
            "pizza" to "pizza pizzeria", "sushi" to "sushi japans", "japanese" to "japans sushi", "chinese" to "chinees",
            "kebab" to "kebab shoarma doner", "turkish" to "turks", "burger" to "burger hamburger", "indian" to "indiaas",
            "thai" to "thais", "italian" to "italiaans", "asian" to "aziatisch", "friture" to "friet patat snackbar",
            "chicken" to "kip chicken", "vegan" to "vegan", "vegetarian" to "vegetarisch", "greek" to "grieks",
            "surinamese" to "surinaams", "indonesian" to "indonesisch", "mexican" to "mexicaans", "vietnamese" to "vietnamees",
        )

        fun categoryWords(key: String, value: String): List<String> =
            norm((CATEGORY[value] ?: value.replace('_', ' ')) + " " + key).split(' ').filter { it.isNotEmpty() }.distinct()

        fun cuisineWords(c: String): List<String> = norm(CUISINE[c.trim()] ?: c.replace('_', ' ')).split(' ').filter { it.isNotEmpty() }

        fun icon(key: String, value: String): String = when (value) {
            "restaurant" -> "🍽"; "fast_food" -> "🍔"; "cafe" -> "☕"; "bar", "pub" -> "🍺"; "ice_cream" -> "🍦"
            "supermarket", "convenience" -> "🛒"; "bakery" -> "🥖"; "pharmacy", "chemist" -> "💊"
            "hospital", "clinic", "doctors", "dentist" -> "🏥"; "school", "kindergarten" -> "🏫"
            "university", "college" -> "🎓"; "fuel" -> "⛽"; "parking", "bicycle_parking" -> "🅿"; "hotel", "hostel" -> "🏨"
            "station" -> "🚉"; "museum" -> "🏛"; "place_of_worship" -> "⛪"; "park" -> "🌳"; "cinema" -> "🎬"
            else -> when (key) { "shop" -> "🛍"; "place" -> "🏘"; "office" -> "🏢"; "leisure" -> "⚽"; "tourism" -> "📸"; "historic" -> "🏰"; else -> "📍" }
        }
    }
}
