package nl.bromnav

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Opt-in regression check on real scanner frames from a shift log (customer data: never in git).
 * BROMNAV_OCR_TRUTH=<file with lines: frame.json TAB postcode|number TAB orderNo> ./gradlew testDebugUnitTest
 * Compares the parser now with what the app parsed live (stored in the frame file).
 */
class OcrFramesEval {
    @Test fun shiftFrames() {
        val truthFile = System.getenv("BROMNAV_OCR_TRUTH")?.let { File(it) }
        assumeTrue(truthFile?.exists() == true)
        var n = 0; var nowOk = 0; var nowWrong = 0; var nowRest = 0; var liveOk = 0; var liveWrong = 0; var liveRest = 0
        var ordNow = 0; var ordLive = 0; var ordN = 0
        for (row in truthFile!!.readLines()) {
            val (path, truth, order) = row.split('\t').let { Triple(it[0], it[1], it.getOrElse(2) { "" }) }
            val o = org.json.JSONObject(File(path).readText())
            val ls = o.getJSONArray("lines")
            val lines = (0 until ls.length()).map { i ->
                val s = ls.getString(i); val sp = s.indexOf(' '); val b = s.substring(0, sp).split(',').map { it.toInt() }
                ReceiptParser.Line(s.substring(sp + 1), b[0], b[1], b[2], b[3])
            }
            n++
            val now = ReceiptParser.parseFields(lines)
            val nowKey = now?.takeIf { it.hasAddress }?.let { "${it.postcode}|${Regex("^\\d+").find(it.houseNo!!)?.value}" }
            val live = o.getJSONObject("parsed")
            val liveKey = if (live.isNull("postcode") || live.isNull("houseNo")) null else "${live.getString("postcode")}|${Regex("^\\d+").find(live.getString("houseNo"))?.value}"
            fun count(k: String?, ok: () -> Unit, wrong: () -> Unit, rest: () -> Unit) {
                if (k == null) return
                if (k == truth) ok() else { wrong(); if (k.startsWith(Config.RESTAURANT_POSTCODE + "|" + Config.RESTAURANT_HOUSENO)) rest() }
            }
            count(nowKey, { nowOk++ }, { nowWrong++ }, { nowRest++ })
            count(liveKey, { liveOk++ }, { liveWrong++ }, { liveRest++ })
            if (nowKey != null && nowKey != truth) println("now wrong: $path -> $nowKey (truth $truth)")
            if (order.length >= 3) {
                ordN++
                val short = order.takeLast(3)
                if (now?.orderNo?.takeLast(3) == short) ordNow++
                if (!live.isNull("orderNo") && live.getString("orderNo").takeLast(3) == short) ordLive++
            }
        }
        println("frames $n | address now: ok $nowOk wrong $nowWrong (restaurant $nowRest) | live app: ok $liveOk wrong $liveWrong (restaurant $liveRest)")
        println("order (last 3 digits) frames $ordN: now $ordNow, live $ordLive")
    }
}
