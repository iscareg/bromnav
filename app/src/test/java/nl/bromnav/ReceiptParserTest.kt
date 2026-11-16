package nl.bromnav

import nl.bromnav.ReceiptParser.Line
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Synthetic receipts (made-up people/addresses) in the real layouts. */
class ReceiptParserTest {
    init { TestRegion.wabiSabi() }

    private fun lines(vararg t: String) = t.mapIndexed { i, s -> Line(s, 0, i * 40, 400, i * 40 + 30) }

    @Test fun dutchReceipt() {
        val r = ReceiptParser.parse(lines(
            "Bestelnummer: ABCD12 - 015",
            "Besteld op: za 14/11/2026 19:40",
            "Nieuwe klant: Nee",
            "Bron: Website",
            "Bezorgtijd: 20:25 (gevr.: ZSM)",
            "Betaalwijze: Online betaald",
            "Klant",
            "Jan Jansen",
            "Voorbeeldstraat 13",
            "9712 AB Groningen",
            "0612345678",
            "Voor bezorger: hard op deurbel 2 drukken,",
            "hij werkt niet altijd.",
            "Bestelling",
            "1× Plastic Tas € 0,10",
        ))!!
        assertEquals("9712AB", r.postcode); assertEquals("13", r.houseNo); assertEquals("Voorbeeldstraat", r.street)
        assertEquals("Groningen", r.city); assertEquals("Jan Jansen", r.name); assertEquals("0612345678", r.phone)
        assertEquals("ABCD12-015", r.orderNo); assertEquals("20:25", r.deliveryTime); assertTrue(r.asap)
        assertEquals("Voor bezorger: hard op deurbel 2 drukken, hij werkt niet altijd.", r.note)
        assertNull(r.requestedTime); assertEquals("online", r.payment)
    }

    @Test fun englishReceiptWithLetterAndSplitColumns() {
        // labels and values recognised as separate lines on the same row
        val ls = listOf(
            Line("Order nr:", 0, 0, 120, 30), Line("WXYZ99 - 008", 200, 2, 380, 32),
            Line("Ordered on:", 0, 40, 140, 70), Line("Fri 6/12/2026 4:05 PM", 200, 40, 400, 70),
            Line("Source:", 0, 80, 100, 110), Line("Just Eat", 200, 80, 300, 110),
            Line("Delivery time:", 0, 120, 160, 150), Line("17:30 (req.:17:30)", 200, 121, 400, 151),
            Line("Payment:", 0, 160, 110, 190), Line("Online paid", 200, 160, 330, 190),
            Line("Customer", 150, 200, 260, 230),
            Line("Piet Voorbeeld", 0, 240, 200, 270),
            Line("Teststraat 32A", 0, 280, 200, 310),
            Line("9713 XY Groningen", 0, 320, 240, 350),
            Line("+31612345678", 0, 360, 200, 390),
            Line("Order", 150, 400, 230, 430),
            Line("1× Plastic Tas", 0, 440, 200, 470),
        )
        val r = ReceiptParser.parse(ls)!!
        assertEquals("9713XY", r.postcode); assertEquals("32A", r.houseNo); assertEquals("Teststraat", r.street)
        assertEquals("WXYZ99-008", r.orderNo); assertEquals("17:30", r.deliveryTime); assertEquals(false, r.asap)
        assertEquals("+31612345678", r.phone); assertNull(r.note)
        assertEquals("17:30", r.requestedTime); assertEquals("online", r.payment)
    }

    @Test fun cashAndExactTime() {
        val r = ReceiptParser.parse(lines(
            "Bezorgtijd: 19:00 (gevr.: 19:00)", "Betaalwijze: Contant",
            "Klant", "Kees", "Molenweg 4", "9712 CD Groningen"))!!
        assertEquals("19:00", r.deliveryTime); assertEquals("19:00", r.requestedTime)
        assertEquals(false, r.asap); assertEquals("cash", r.payment)
    }

    @Test fun lowercasePostcodeAndNoHeaderAbove() {
        val r = ReceiptParser.parse(lines("Sanne de Vries", "Kade 57", "9744 zz Groningen", "0698765432", "Order"))!!
        assertEquals("9744ZZ", r.postcode); assertEquals("57", r.houseNo); assertEquals("Kade", r.street)
        assertNull(r.deliveryTime)
    }

    @Test fun houseNumberAddition() {
        val r = ReceiptParser.parse(lines("Klant", "A. Test", "Lange Laan 120-2", "9741 AA Groningen"))!!
        assertEquals("120-2", r.houseNo); assertEquals("Lange Laan", r.street)
    }

    @Test fun noAddressMeansNull() {
        assertNull(ReceiptParser.parse(lines("Bestelling", "1× Uramaki € 9,50", "Totaal € 9,50")))
    }

    @Test fun itemLinesAreNotStreets() {
        // "Box 48" style item lines above a postcode must not be taken as the street
        val r = ReceiptParser.parse(lines("Klant", "Kees", "Molenweg 4", "9712 CD Groningen"))
        assertNotNull(r); assertEquals("Molenweg", r!!.street)
    }

    @Test fun croppedHeaderAndItemAreNotANote() {
        val ls = listOf(
            Line("Sanne de Vries", 300, 250, 800, 350),
            Line("Kade 57", 340, 420, 700, 520),
            Line("9744 zz Groningen", 380, 560, 1000, 660),
            Line("0698765432", 420, 690, 860, 790),
            Line("Orde", 1110, 700, 1280, 800),
            Line("tic Tas", 910, 930, 1120, 1000),
        )
        val r = ReceiptParser.parse(ls)!!
        assertEquals("Kade", r.street); assertEquals("0698765432", r.phone); assertNull(r.note)
    }

    private fun ln(t: String, l: Int, top: Int, r: Int, b: Int) = ReceiptParser.Line(t, l, top, r, b)

    /** the restaurant's own address in the header is never taken; the customer block under "Klant" is */
    @Test fun restaurantHeaderIsSkipped() {
        val lines = listOf(
            ln("Schuitendiep 60", 472, 218, 649, 256), ln("9711RE Groningen", 452, 256, 658, 291), ln("050-2043069", 489, 294, 624, 319),
            ln("www.wabisabigroningen.nl", 407, 326, 699, 359), ln("Info", 532, 396, 572, 425),
            ln("Ordernr.", 325, 483, 402, 509), ln("11111ABC - 001", 498, 459, 688, 504), ln("Bezorgtijd:", 331, 621, 435, 650),
            ln("16:45 (gevr.:16:45)", 499, 597, 716, 644), ln("Klant", 520, 704, 568, 729), ln("Anna Voorbeeld", 336, 769, 459, 790),
            ln("Voorbeeldplein 9", 341, 784, 600, 819), ln("9746 ZX Groningen", 336, 819, 517, 840), ln("+31600000001", 335, 842, 476, 865))
        val r = ReceiptParser.parseFields(lines)!!
        assertEquals("9746ZX", r.postcode); assertEquals("9", r.houseNo); assertEquals("Voorbeeldplein", r.street)
        // misread restaurant header alone is not an address either
        assertEquals(null, ReceiptParser.parseFields(listOf(ln("Schuitendiep 60", 472, 218, 649, 256), ln("9711AE Groningen", 452, 256, 658, 291),
            ln("050-2043069", 489, 294, 624, 319)))!!.postcode)
    }

    /** ML Kit reads upside-down receipts with reversed boxes: still parsed */
    @Test fun upsideDownText() {
        val up = listOf(ln("Info", 863, 2468, 955, 2514), ln("22222DEF - 006", 620, 2289, 1027, 2363), ln("Ordernr.", 1260, 1945, 1365, 1981),
            ln("18:10 (gevr.:18:00)", 521, 1963, 1015, 2041), ln("Bezorgtijd:", 1207, 1716, 1367, 1762), ln("Klant", 870, 1759, 966, 1807),
            ln("Proefstraat 9a", 1070, 1574, 1355, 1625), ln("9745 CS Groningen", 975, 1498, 1353, 1561), ln("0600000002.", 1103, 1445, 1351, 1490),
            ln("Bestelling", 810, 1317, 1024, 1374))
        assertTrue(ReceiptParser.upsideDown(up))
        val r = ReceiptParser.parseFields(up)!!
        assertEquals("9745CS", r.postcode); assertEquals("9A", r.houseNo); assertEquals("18:00", r.requestedTime)
    }

    @Test fun lookalikePostcodeIsKept() {
        val r = ReceiptParser.parse(listOf(ReceiptParser.Line("Proefstraat 9a"), ReceiptParser.Line("9745 0S Groningen")))!!
        assertEquals("97450S", r.postcode)          // repaired later by the address book
    }

    @Test fun shortOrderNumber() {
        assertEquals("010", Stop.shortOrder("12345ABC-010"))
        assertEquals("006", Stop.shortOrder("22222DEF - 006"))
        assertEquals("007", Stop.shortOrder("7"))
        assertEquals("026", Stop.shortOrder("13579QRS-1026"))
    }
}
