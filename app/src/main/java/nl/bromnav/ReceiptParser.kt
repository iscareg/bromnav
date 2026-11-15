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
        val requestedTime: String? = null, // "(req.: 17:30)": customer asked for an exact time -> not earlier
        val payment: String? = null,   // "online" | "cash"
    ) {
        val hasAddress get() = postcode != null && houseNo != null
        /** key used to decide that two scans read the same address */
        val key get() = "$postcode $houseNo"
    }

    // letters may come out as look-alike digits ("9745 0S" for 9745 CS); the address book fixes them
    private val POSTCODE = Regex("""^\s*([1-9]\d{3})\s?((?=[A-Za-z0-9]{2}\b)(?:[A-Za-z][A-Za-z0-9]|[0-9][A-Za-z]))\b\s*(.*)$""")
    private val STREET = Regex("""^\s*([A-Za-zÀ-ÿ][A-Za-zÀ-ÿ .'\-]*?[A-Za-zÀ-ÿ.])\s+(\d{1,5})\s*(?:([A-Za-z]{1,2})\b|[-/ ]\s*([A-Za-z0-9]{1,4}))?\s*$""")
    private val PHONE = Regex("""(\+31|0031|0)\s?[1-9](?:[\s-]?\d){8}""")
    private val TIME = Regex("""\b([01]?\d|2[0-3])[:.]([0-5]\d)\b""")
    private val ORDER_NO = Regex("""\b([A-Z0-9]{4,10}\s?-\s?\d{2,4})\b""")
    private val LBL_TIME = Regex("""(bezorgtijd|levertijd|delivery\s*time|bezorgd\s*om)""", RegexOption.IGNORE_CASE)
    private val LBL_ORDER = Regex("""(order\s*n[ro]\.?|bestel\s*n[ro]\.?|bestelnummer|ordernummer|order\s*number)""", RegexOption.IGNORE_CASE)
    private val LBL_PAY = Regex("""(betaalwijze|betaling|payment)""", RegexOption.IGNORE_CASE)
    private val REQUESTED = Regex("""\(\s*(gevr|req)[a-z]*\.?\s*:?\s*([^)]*)\)""", RegexOption.IGNORE_CASE)
    private val ASAP = Regex("""\b(zsm|asap|z\.s\.m\.)\b""", RegexOption.IGNORE_CASE)
    private val CUSTOMER = Regex("""^\s*(klant|customer|klantgegevens|bezorgadres|delivery\s*address)\s*:?\s*$""", RegexOption.IGNORE_CASE)
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
    fun parseFields(lines0: List<Line>): Receipt? {
        if (!isHorizontal(lines0)) return null
        // ML Kit also reads upside-down text, but then "above/below" is reversed: turn the boxes round
        val lines = if (upsideDown(lines0)) lines0.map { it.copy(left = -it.right, right = -it.left, top = -it.bottom, bottom = -it.top) } else lines0
        val ls = readingOrder(lines.map { it.copy(text = it.text.replace('\u00A0', ' ').trim()) }.filter { it.text.isNotEmpty() })
        if (ls.isEmpty()) return null

        // --- address block (optional in a single frame: fields are collected over many frames)
        var postcode: String? = null; var city: String? = null; var street: String? = null; var houseNo: String? = null
        var name: String? = null; var phone: String? = null; var note: String? = null
        // postcode candidates: the customer block (under a "Klant"/"Customer" header in the same column)
        // first; the restaurant's own address in the receipt header is never the customer's
        fun underCustomerHeader(i: Int): Boolean = (maxOf(0, i - 6) until i).any { h ->
            CUSTOMER.matches(ls[h].text) && (ls[i].bottom == 0 || (ls[h].left < ls[i].right && ls[h].right > ls[i].left))
        }
        // the restaurant block: followed by its phone number / website
        fun restaurantBlock(i: Int) = (i + 1..minOf(ls.size - 1, i + 3)).any { k ->
            ls[k].text.filter(Char::isDigit).contains(Config.RESTAURANT_PHONE_DIGITS) || ls[k].text.lowercase().contains(Config.RESTAURANT_WEB)
        }
        // two receipts in view: the one in the middle of the picture is meant
        val midX = if (ls.all { it.bottom == 0 }) 0.0 else (ls.minOf { it.left } + ls.maxOf { it.right }) / 2.0
        val cands = ls.indices.filter { POSTCODE.matches(ls[it].text) && !restaurantBlock(it) }
            .sortedWith(compareBy({ if (underCustomerHeader(it)) 0 else 1 }, { Math.abs((ls[it].left + ls[it].right) / 2.0 - midX) },
                { if (POSTCODE.find(ls[it].text)!!.groupValues[3].isNotBlank()) 0 else 1 }, { it }))
        var pcIdx = -1
        for (ci in cands) {
            val (st, no) = streetFor(ls, ci) ?: continue
            val pcM = POSTCODE.find(ls[ci].text)!!
            val pc = pcM.groupValues[1] + pcM.groupValues[2].uppercase()
            if (isRestaurant(pc, no, st)) continue
            pcIdx = ci; street = st; houseNo = no; postcode = pc
            city = pcM.groupValues[3].trim().takeIf { it.isNotEmpty() }?.replaceFirstChar { it.uppercase() }
            break
        }
        if (pcIdx >= 0) {

            val pcLine = ls[pcIdx]
            val stIdx = streetIdx(ls, pcIdx)
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
        // requested part: "(gevr.: ZSM)" = as soon as possible, "(req.:17:30)" = exact time
        var requestedTime: String? = null
        for (l in ls) {
            val m = REQUESTED.find(l.text) ?: continue
            val v = m.groupValues[2]
            if (ASAP.containsMatchIn(v)) { asap = true; break }
            TIME.find(v)?.let { requestedTime = fmt(it) }
            if (requestedTime != null) break
        }

        // payment: "Betaalwijze: Online betaald" / "Payment: Online paid" / Contant
        fun classify(v: String): String? = when {
            Regex("""(online|betaald|paid|ideal|prepaid|vooraf)""", RegexOption.IGNORE_CASE).containsMatchIn(v) -> "online"
            Regex("""(contant|cash)""", RegexOption.IGNORE_CASE).containsMatchIn(v) -> "cash"
            else -> null   // Wabi-Sabi only has prepaid online or cash
        }
        var payment: String? = null
        val payLabel = ls.indexOfFirst { LBL_PAY.containsMatchIn(it.text) }
        if (payLabel >= 0) payment = valueFor(ls, payLabel, LBL_PAY)?.let { classify(it) }
        if (payment == null) payment = ls.firstNotNullOfOrNull { l ->
            if (Regex("""^(online\s+(betaald|paid)|contant|cash)\b""", RegexOption.IGNORE_CASE).containsMatchIn(l.text)) classify(l.text) else null
        }

        // --- order number
        var orderNo: String? = null
        val orderLabel = ls.indexOfFirst { LBL_ORDER.containsMatchIn(it.text) }
        if (orderLabel >= 0) orderNo = valueFor(ls, orderLabel, LBL_ORDER)?.let { ORDER_NO.find(it.uppercase())?.value }
        if (orderNo == null) orderNo = ls.firstNotNullOfOrNull { ORDER_NO.find(it.text)?.value?.takeIf { v -> v.any(Char::isLetter) } }
        orderNo = orderNo?.replace(Regex("""\s*-\s*"""), "-")

        return Receipt(postcode, houseNo, street, city, name, phone, orderNo, time, asap, note, requestedTime, payment)
    }

    /** Fixed order of things on every receipt, top to bottom (rank, pattern). */
    private val LANDMARKS = listOf(
        0 to Regex("""^\s*info\s*$""", RegexOption.IGNORE_CASE),
        1 to Regex("""^\s*(order\s*n[ro]|ordernr|bestelnummer)""", RegexOption.IGNORE_CASE),
        2 to Regex("""^\s*(besteld\s*op|ordered)""", RegexOption.IGNORE_CASE),
        3 to Regex("""^\s*(bezorgtijd|delivery\s*time)""", RegexOption.IGNORE_CASE),
        4 to Regex("""^\s*(betaalwijze|payment)""", RegexOption.IGNORE_CASE),
        5 to Regex("""^\s*(klant|customer)\s*$""", RegexOption.IGNORE_CASE),
        7 to Regex("""^\s*(bestelling|order)\s*$""", RegexOption.IGNORE_CASE),
    )

    /** true when the landmarks appear bottom-to-top (text read upside down) */
    fun upsideDown(lines: List<Line>): Boolean {
        if (lines.all { it.bottom == 0 }) return false
        val marks = ArrayList<Pair<Int, Int>>()                       // rank, centre y
        for (l in lines) LANDMARKS.firstOrNull { it.second.containsMatchIn(l.text) }?.let { marks.add(it.first to l.cy) }
        // postcode line directly above its own "Street 12" line: also reversed
        for ((i, l) in lines.withIndex()) if (POSTCODE.matches(l.text)) {
            lines.filter { it !== l && STREET.matches(it.text) && !NOT_A_STREET.containsMatchIn(it.text) && Math.abs(it.left - l.left) < l.h * 2.5 &&
                Math.abs(it.cy - l.cy) < l.h * 2.2 }.forEach { marks.add(if (it.cy < l.cy) (5 to it.cy) else (5 to it.cy)); marks.add(6 to l.cy) }
        }
        var agree = 0; var disagree = 0
        for (a in marks.indices) for (b in marks.indices) {
            val (ra, ya) = marks[a]; val (rb, yb) = marks[b]
            if (ra < rb) { if (ya < yb) agree++ else if (ya > yb) disagree++ }
        }
        return disagree > agree
    }

    /** The restaurant's own address (printed in the receipt header). */
    fun isRestaurant(postcode: String, houseNo: String, street: String? = null): Boolean {
        val base = Regex("""^\d+""").find(houseNo)?.value
        if (postcode == Config.RESTAURANT_POSTCODE && base == Config.RESTAURANT_HOUSENO) return true
        // misread restaurant header ("9711AE 60", "Schuitendiep 0"): restaurant street + 9711 postcode + a number like 60
        return street != null && PoiIndex.lev(PoiIndex.norm(street), Config.RESTAURANT_STREET.lowercase(), Config.RESTAURANT_STREET.length, 2) <= 2 &&
            postcode.startsWith(Config.RESTAURANT_POSTCODE.take(4)) && (base == null || base.length <= 2 && (base.contains('6') || base == "0"))
    }

    /** index of the "Street 12a" line belonging to postcode line [pcIdx], or -1 */
    private fun streetIdx(ls: List<Line>, pcIdx: Int): Int {
        val pcLine = ls[pcIdx]
        fun aligned(l: Line) = pcLine.bottom == 0 || Math.abs(l.left - pcLine.left) < pcLine.h * 2.5
        for (i in pcIdx - 1 downTo maxOf(0, pcIdx - 4)) {
            val t = ls[i].text
            if (STREET.matches(t) && !NOT_A_STREET.containsMatchIn(t) && aligned(ls[i])) return i
        }
        return -1
    }

    /** street + house number for postcode line [pcIdx] (separate line above, or joined on the same line) */
    private fun streetFor(ls: List<Line>, pcIdx: Int): Pair<String, String>? {
        val stIdx = streetIdx(ls, pcIdx)
        if (stIdx >= 0) {
            val m = STREET.find(ls[stIdx].text)!!
            return m.groupValues[1].trim() to (m.groupValues[2] + (m.groupValues[3].ifEmpty { m.groupValues[4].let { if (it.isNotEmpty()) "-$it" else "" } }).uppercase())
        }
        // OCR may join "Street 12" and the postcode on one line
        val joined = Regex("""^(.*?[A-Za-zÀ-ÿ.])\s+(\d{1,5})\s*([A-Za-z]{0,2})\s+([1-9]\d{3})\s?([A-Za-z]{2})\b""").find(ls[pcIdx].text) ?: return null
        return joined.groupValues[1].trim() to (joined.groupValues[2] + joined.groupValues[3].uppercase())
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
