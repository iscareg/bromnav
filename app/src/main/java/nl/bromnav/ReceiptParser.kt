package nl.bromnav

/**
 * Parses OCR text of a Wabi-Sabi delivery receipt (Dutch or English layout).
 *
 * Layout (both languages):
 *   Order nr / Bestelnummer:   ABCD12 - 008
 *   Delivery time / Bezorgtijd: 17:30 (req.: 17:30)   |  20:25 (gevr.: ZSM)
 *   [Customer | Klant]
 *   <name>
 *   <street> <number>[letter]
 *   <postcode> <city>
 *   <phone>
 *   [optional note box for the courier]
 *   [Order | Bestelling]  items...
 *
 * The postcode line is the anchor (identical in both languages); everything else is found
 * relative to it or by its label, with pattern fallbacks because OCR splits columns unpredictably.
 */
object ReceiptParser {

    /** One OCR line with its box (any unit, only relative positions matter). */
    data class Line(val text: String, val left: Int = 0, val top: Int = 0, val right: Int = 0, val bottom: Int = 0) {
        val cy get() = (top + bottom) / 2
        val h get() = (bottom - top).coerceAtLeast(1)
    }

    data class Receipt(
        val postcode: String?,         // "1234AB"
        val houseNo: String?,          // "17", "32A", "13-2"
        val street: String?,           // as read by OCR (PDOK gives the official one)
        val city: String?,
        val name: String?,
        val phone: String?,
        val orderNo: String?,
        val deliveryTime: String?,     // "HH:MM" promised delivery time = deadline
        val asap: Boolean,             // requested "ZSM"/"ASAP"
        val note: String?,             // courier note ("Voor bezorger: ...")
    ) {
        val hasAddress get() = postcode != null && houseNo != null
        /** key used to decide that two scans read the same address */
        val key get() = "$postcode $houseNo"
    }

    private val POSTCODE = Regex("""^\s*([1-9]\d{3})\s?([A-Za-z]{2})\b\s*(.*)$""")
    private val STREET = Regex("""^\s*([A-Za-zÀ-ÿ][A-Za-zÀ-ÿ .'\-]*?[A-Za-zÀ-ÿ.])\s+(\d{1,5})\s*(?:([A-Za-z]{1,2})\b|[-/ ]\s*([A-Za-z0-9]{1,4}))?\s*$""")
    private val PHONE = Regex("""(\+31|0031|0)\s?[1-9](?:[\s-]?\d){8}""")
    private val TIME = Regex("""\b([01]?\d|2[0-3])[:.]([0-5]\d)\b""")
    private val ORDER_NO = Regex("""\b([A-Z0-9]{4,10}\s?-\s?\d{2,4})\b""")
    private val LBL_TIME = Regex("""(bezorgtijd|levertijd|delivery\s*time|bezorgd\s*om)""", RegexOption.IGNORE_CASE)
    private val LBL_ORDER = Regex("""(order\s*n[ro]\.?|bestel\s*n[ro]\.?|bestelnummer|ordernummer|order\s*number)""", RegexOption.IGNORE_CASE)
    private val ASAP = Regex("""\b(zsm|asap|z\.s\.m\.)\b""", RegexOption.IGNORE_CASE)
    private val HEADER = Regex("""^\s*(bestelling|order|klant|customer|info|items?)\s*$""", RegexOption.IGNORE_CASE)
    private val NOT_A_STREET = Regex("""(€|\d+\s*[x×]\b|stuks|\bst\)|\bnr\b|tijd|time|betaal|payment|bron|source)""", RegexOption.IGNORE_CASE)

    /** True when most (long enough) lines are wider than tall, i.e. the image is the right way round (or upside down). */
    fun isHorizontal(lines: List<Line>): Boolean {
        val boxed = lines.filter { it.text.length >= 4 && it.right > it.left }
        if (boxed.isEmpty()) return true
        return boxed.count { (it.right - it.left) > (it.bottom - it.top) } >= boxed.size * 0.7
    }

    /** Puts OCR lines in reading order: rows top to bottom, each row left to right. */
    fun readingOrder(lines: List<Line>): List<Line> {
        if (lines.isEmpty() || lines.all { it.bottom == 0 }) return lines   // no geometry (tests / plain text)
        val medH = lines.map { it.h }.sorted()[lines.size / 2]
        val rows = ArrayList<MutableList<Line>>()
        for (l in lines.sortedBy { it.cy }) {
            val row = rows.lastOrNull()
            if (row != null && Math.abs(l.cy - row.map { it.cy }.average()) < medH * 0.55) row.add(l) else rows.add(mutableListOf(l))
        }
        return rows.flatMap { r -> r.sortedBy { it.left } }
    }

    /** Full receipt only when the address was read in this frame. */
    fun parse(lines: List<Line>): Receipt? = parseFields(lines)?.takeIf { it.hasAddress }

    /** Whatever fields this frame shows (address, time, order nr can come from different frames). */
    fun parseFields(lines: List<Line>): Receipt? {
        if (!isHorizontal(lines)) return null
        val ls = readingOrder(lines.map { it.copy(text = it.text.replace('\u00A0', ' ').trim()) }.filter { it.text.isNotEmpty() })
        if (ls.isEmpty()) return null

        // --- address block (optional in a single frame: fields are collected over many frames)
        var postcode: String? = null; var city: String? = null; var street: String? = null; var houseNo: String? = null
        var name: String? = null; var phone: String? = null; var note: String? = null
        val pcIdx = ls.indices.filter { POSTCODE.matches(ls[it].text) }
            .maxByOrNull { i -> val rest = POSTCODE.find(ls[i].text)!!.groupValues[3]; if (rest.isNotBlank()) 2 else 1 } ?: -1
        if (pcIdx >= 0) {
            val pcM = POSTCODE.find(ls[pcIdx].text)!!
            postcode = pcM.groupValues[1] + pcM.groupValues[2].uppercase()
            city = pcM.groupValues[3].trim().takeIf { it.isNotEmpty() }?.replaceFirstChar { it.uppercase() }

            // street + number: nearest left-aligned line before the postcode that looks like "Street 12a"
            var stIdx = -1
            val pcLine = ls[pcIdx]
            fun aligned(l: Line) = pcLine.bottom == 0 || Math.abs(l.left - pcLine.left) < pcLine.h * 2.5
            for (i in pcIdx - 1 downTo maxOf(0, pcIdx - 4)) {
                val t = ls[i].text
                if (STREET.matches(t) && !NOT_A_STREET.containsMatchIn(t) && aligned(ls[i])) { stIdx = i; break }
            }
            if (stIdx >= 0) {
                val m = STREET.find(ls[stIdx].text)!!
                street = m.groupValues[1].trim()
                houseNo = m.groupValues[2] + (m.groupValues[3].ifEmpty { m.groupValues[4].let { if (it.isNotEmpty()) "-$it" else "" } }).uppercase()
            } else {
                // OCR may join "Street 12" and the postcode on one line
                val joined = Regex("""^(.*?[A-Za-zÀ-ÿ.])\s+(\d{1,5})\s*([A-Za-z]{0,2})\s+([1-9]\d{3})\s?([A-Za-z]{2})\b""").find(ls[pcIdx].text)
                if (joined != null) { street = joined.groupValues[1].trim(); houseNo = joined.groupValues[2] + joined.groupValues[3].uppercase() }
            }
            if (houseNo == null) postcode = null   // a postcode without a street line is not an address

            // name: line just above the street that has no digits and is not a header
            name = (if (stIdx > 0) ls[stIdx - 1].text else null)
                ?.takeIf { it.none(Char::isDigit) && !HEADER.matches(it) && it.length in 2..40 }

            // phone: first phone-looking line after the postcode
            var phoneIdx = -1
            for (i in pcIdx + 1..minOf(ls.size - 1, pcIdx + 3)) {
                PHONE.find(ls[i].text)?.let { phone = it.value.replace(Regex("""[\s-]"""), ""); phoneIdx = i }
                if (phone != null) break
            }

            // courier note: lines after phone (or postcode) until the next section header
            val noteStart = if (phoneIdx >= 0) phoneIdx + 1 else pcIdx + 1
            val noteLines = ArrayList<String>()
            val rowRef = if (phoneIdx >= 0) ls[phoneIdx] else pcLine
            for (i in noteStart until ls.size) {
                val t = ls[i].text
                // something to the right on the phone's own row (e.g. a section header) is not a note
                if (rowRef.bottom != 0 && Math.abs(ls[i].cy - rowRef.cy) < rowRef.h * 0.5) continue
                // the note box starts at the address block's left edge; text further left is not on the receipt
                if (pcLine.bottom != 0 && ls[i].left < pcLine.left - pcLine.h * 1.5) continue
                if (HEADER.matches(t) || Regex("""^\s*(\d+\s*[x×]\s|orde?r?\b|bestel)""", RegexOption.IGNORE_CASE).containsMatchIn(t) ||
                    t.contains('€') || Regex("""\b(tas|stuks|st\))\b""", RegexOption.IGNORE_CASE).containsMatchIn(t)) break
                if (t.length < 3 || t.count(Char::isLetter) < t.length / 2) continue   // QR-code noise
                noteLines.add(t)
                if (noteLines.size >= 6) break
            }
            // a real courier note is a sentence, not OCR fragments
            note = noteLines.joinToString(" ").takeIf { n -> n.length >= 15 && n.split(' ').count { it.length >= 2 } >= 3 }
        }

        // --- delivery time: label + value (same line, or a line to the right / just below)
        var time: String? = null
        var asap = false
        val timeLabel = ls.indexOfFirst { LBL_TIME.containsMatchIn(it.text) }
        if (timeLabel >= 0) {
            val v = valueFor(ls, timeLabel, LBL_TIME)
            v?.let { TIME.find(it) }?.let { time = fmt(it) }
            if (v != null && ASAP.containsMatchIn(v)) asap = true
        }
        if (time == null) {
            // pattern fallback: "20:25 (gevr.: ZSM)" / "17:30 (req.:17:30)"
            ls.firstOrNull { Regex("""\d[:.]\d\d\s*\(\s*(gevr|req)""", RegexOption.IGNORE_CASE).containsMatchIn(it.text) }?.let { l ->
                TIME.find(l.text)?.let { time = fmt(it) }
                if (ASAP.containsMatchIn(l.text)) asap = true
            }
        }
        if (!asap) asap = ls.any { Regex("""\((gevr|req)[^)]*(zsm|asap)""", RegexOption.IGNORE_CASE).containsMatchIn(it.text) }

        // --- order number
        var orderNo: String? = null
        val orderLabel = ls.indexOfFirst { LBL_ORDER.containsMatchIn(it.text) }
        if (orderLabel >= 0) orderNo = valueFor(ls, orderLabel, LBL_ORDER)?.let { ORDER_NO.find(it.uppercase())?.value }
        if (orderNo == null) orderNo = ls.firstNotNullOfOrNull { ORDER_NO.find(it.text)?.value?.takeIf { v -> v.any(Char::isLetter) } }
        orderNo = orderNo?.replace(Regex("""\s*-\s*"""), "-")

        return Receipt(postcode, houseNo, street, city, name, phone, orderNo, time, asap, note)
    }

    /** Text of the value belonging to label line [i]: rest of the same line, else the line to its right. */
    private fun valueFor(ls: List<Line>, i: Int, label: Regex): String? {
        val l = ls[i]
        val rest = l.text.substring(label.find(l.text)!!.range.last + 1).trimStart(':', ' ', '.')
        if (rest.isNotBlank()) return rest
        // a line on the same row, to the right of the label
        ls.filter { it !== l && Math.abs(it.cy - l.cy) < l.h * 0.7 && it.left > l.left }
            .minByOrNull { it.left }?.let { return it.text }
        // OCR order fallback: next line
        return ls.getOrNull(i + 1)?.text
    }

    private fun fmt(m: MatchResult) = "%02d:%s".format(m.groupValues[1].toInt(), m.groupValues[2])
}
