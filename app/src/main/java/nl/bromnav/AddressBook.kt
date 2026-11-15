package nl.bromnav

import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * Offline Dutch address book of the delivery area (from OpenStreetMap, which carries the BAG
 * address register): postcode + house number -> street and position, and street search for typed
 * addresses. Lets the receipt scanner and the address search work without internet.
 * Data: one line per postcode + street, see tools/build_addresses.py:
 *   postcode <TAB> street <TAB> city <TAB> number:lat5:lon5;number:lat5:lon5;...
 */
class AddressBook private constructor(private val groups: List<Group>) {

    class Group(val postcode: String, val street: String, val city: String, private val raw: String) {
        val streetNorm = PoiIndex.norm(street)
        /** house numbers parsed on first use (most groups are never looked at) */
        val numbers: List<Pair<String, LatLon>> by lazy {
            raw.split(';').mapNotNull { e ->
                val p = e.split(':'); if (p.size != 3) return@mapNotNull null
                val lat = p[1].toIntOrNull() ?: return@mapNotNull null; val lon = p[2].toIntOrNull() ?: return@mapNotNull null
                p[0] to LatLon(lat / 1e5, lon / 1e5)
            }
        }
        fun address(num: String, pos: LatLon): PdokGeocoder.Address {
            val no = num.lowercase()                      // PDOK style: "9a", "62-1"
            return PdokGeocoder.Address("$street $no, $postcode ${city.ifEmpty { "Groningen" }}", pos, street, no)
        }
    }

    private val byPostcode = groups.groupBy { it.postcode }
    val size get() = groups.sumOf { it.numbers.size }
    val groupCount get() = groups.size

    fun hasPostcode(pc: String) = byPostcode.containsKey(pc.uppercase())

    /**
     * Exact lookup as printed on a receipt: "9745CS" + "9a" / "46-1" / "25-27".
     * Falls back to the plain number ("46-1" -> 46) but never to another number.
     */
    fun lookup(postcode: String, houseNo: String): PdokGeocoder.Address? {
        val gs = byPostcode[postcode.uppercase().replace(" ", "")] ?: return null
        val want = norm(houseNo)
        val base = Regex("""^\d+""").find(houseNo)?.value ?: return null
        for (g in gs) g.numbers.firstOrNull { norm(it.first) == want }?.let { return g.address(it.first, it.second) }
        for (g in gs) g.numbers.firstOrNull { it.first == base }?.let { return g.address(it.first, it.second) }
        return null
    }

    /**
     * Lookup that repairs OCR look-alikes in the postcode letters ("9745 0S" -> 9745CS). Among the
     * variants that exist with this house number, the one whose street matches [street] wins.
     */
    fun lookupFuzzy(postcode: String, houseNo: String, street: String?): PdokGeocoder.Address? {
        val pc = postcode.uppercase().replace(" ", "")
        if (pc.length != 6) return null
        lookup(pc, houseNo)?.let { if (street == null || streetMatches(street, it.street)) return it }
        val opts = pc.substring(4).map { c -> (LOOKALIKE[c] ?: "") + c }
        val hits = ArrayList<PdokGeocoder.Address>()
        for (a in opts[0]) for (b in opts[1]) {
            val v = pc.substring(0, 4) + a + b
            if (v == pc || !v.substring(4).all { it in 'A'..'Z' }) continue
            lookup(v, houseNo)?.let { hits.add(it) }
        }
        return hits.firstOrNull { street != null && streetMatches(street, it.street) } ?: hits.singleOrNull()?.takeIf { street == null }
    }

    /**
     * Typed search: "proefstraat 9a", "proef 9", "9745cs 9a", "9745 CS", "Laan van het Voorbeeld".
     * Without a number one hit per street (its first address); nearest first.
     */
    fun search(q: String, near: LatLon, limit: Int = 6): List<PdokGeocoder.Address> {
        val s = q.trim()
        Regex("""^([1-9]\d{3})\s?([A-Za-z]{2})\b\s*(\S+)?""").find(s)?.let { m ->
            val pc = m.groupValues[1] + m.groupValues[2].uppercase()
            val num = m.groupValues[3]
            if (num.isNotEmpty()) return listOfNotNull(lookup(pc, num))
            return (byPostcode[pc] ?: emptyList()).mapNotNull { g -> g.numbers.firstOrNull()?.let { g.address(it.first, it.second) } }.take(limit)
        }
        val m = Regex("""^(.*?[A-Za-zÀ-ÿ.'])\s*(\d+\s?[A-Za-z]?(?:[-/]\w+)?)?\s*(?:,.*)?$""").find(s) ?: return emptyList()
        val words = PoiIndex.norm(m.groupValues[1]).split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty() || words.joinToString("").length < 3) return emptyList()
        val num = m.groupValues[2].replace(" ", "")
        val out = ArrayList<Pair<PdokGeocoder.Address, Double>>()
        for (g in groups) {
            val sw = g.streetNorm.split(' ')
            // every typed word must start a word of the street name ("laan voorb" -> Laan van het Voorbeeld)
            if (!words.all { w -> sw.any { it.startsWith(w) } }) continue
            val exact = g.streetNorm == words.joinToString(" ")
            if (num.isNotEmpty()) {
                val want = norm(num); val base = Regex("""^\d+""").find(num)?.value
                val hit = g.numbers.firstOrNull { norm(it.first) == want } ?: g.numbers.firstOrNull { it.first == base } ?: continue
                out.add(g.address(hit.first, hit.second) to Geo.dist(near, hit.second) - if (exact) 1e6 else 0.0)
            } else {
                val first = g.numbers.firstOrNull() ?: continue
                out.add(PdokGeocoder.Address("${g.street}, ${g.postcode} ${g.city.ifEmpty { "Groningen" }}", first.second, g.street, "") to
                    Geo.dist(near, first.second) - if (exact) 1e6 else 0.0)
            }
        }
        // one hit per street + number (a street spans several postcodes)
        return out.sortedBy { it.second }.map { it.first }.distinctBy { it.street + "|" + it.houseNo + "|" + (if (it.houseNo.isEmpty()) "" else it.label) }.take(limit)
    }

    companion object {
        private fun norm(n: String) = n.uppercase().replace(Regex("""[\s]"""), "")

        /** characters OCR confuses with each postcode letter */
        private val LOOKALIKE = mapOf('0' to "OCDQ", 'O' to "CDQ", 'C' to "OG", 'D' to "O", 'Q' to "O", '5' to "S", 'S' to "5",
            '8' to "B", 'B' to "R", '1' to "IL", 'I' to "LT", 'L' to "I", '2' to "Z", 'Z' to "2", '6' to "G", 'G' to "C",
            'U' to "VW", 'V' to "UY", 'E' to "F", 'F' to "E", 'H' to "N", 'N' to "HM", 'M' to "N", 'K' to "X", 'X' to "K")

        /** OCR'd street vs official name: tolerant of a few misread letters and dropped words */
        fun streetMatches(read: String, official: String): Boolean {
            val a = PoiIndex.norm(read).replace(" ", ""); val b = PoiIndex.norm(official).replace(" ", "")
            if (a.isEmpty() || b.isEmpty() || b.contains(a) || a.contains(b)) return true
            return PoiIndex.lev(a, b, b.length, minOf(a.length, b.length)) <= maxOf(2, b.length / 4)
        }

        /** Accepts gzip or plain text (the APK packer un-gzips .gz assets). */
        fun load(input: InputStream): AddressBook {
            val list = ArrayList<Group>(12000)
            val bin = java.io.BufferedInputStream(input, 1 shl 16)
            bin.mark(2); val gz = bin.read() == 0x1f && bin.read() == 0x8b; bin.reset()
            (if (gz) GZIPInputStream(bin) else bin).bufferedReader().useLines { lines ->
                for (line in lines) {
                    val f = line.split('\t')
                    if (f.size == 4 && f[0].length == 6) list.add(Group(f[0], f[1], f[2], f[3]))
                }
            }
            return AddressBook(list)
        }
    }
}
