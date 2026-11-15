package nl.bromnav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class AddressBookTest {
    private val book = AddressBook.load("""
        9745CS	Proefstraat	Groningen	7:5320857:657178;9:5320854:657163;9A:5320857:657170
        9795KV	Proefweg	Groningen	44:5321100:654000;46:5321110:654010
        9796AC	Testlaan	Groningen	25:5320100:659000;25-27:5320101:659001
        9793GA	Laan van het Voorbeeld	Groningen	51:5319900:657000;53:5319910:657010
        9711RE	Schuitendiep	Groningen	60:5321822:657337
    """.trimIndent().byteInputStream())
    private val here = LatLon(53.2182, 6.5734)

    @Test fun exactAndFallback() {
        assertEquals("Proefstraat 9a, 9745CS Groningen", book.lookup("9745CS", "9a")!!.label)
        assertEquals("Proefstraat 9a, 9745CS Groningen", book.lookup("9745 cs", "9A")!!.label)
        assertEquals("46", book.lookup("9795KV", "46-1")!!.houseNo)               // room number -> the building
        assertEquals("25-27", book.lookup("9796AC", "25-27")!!.houseNo)
        assertNull(book.lookup("9745CS", "11"))                                  // never another number
        assertNull(book.lookup("1234AB", "1"))
    }

    @Test fun ocrLookalikesInPostcode() {
        assertEquals("9745CS", Regex("""\d{4}[A-Z]{2}""").find(book.lookupFuzzy("97450S", "9a", "Proefstraat")!!.label)!!.value)
        assertEquals("9745CS", Regex("""\d{4}[A-Z]{2}""").find(book.lookupFuzzy("9745OS", "9a", null)!!.label)!!.value)
        assertNull(book.lookupFuzzy("97450S", "9a", "Sluisstraat"))              // street on the receipt disagrees
    }

    @Test fun typedSearch() {
        assertEquals("Proefstraat 9a, 9745CS Groningen", book.search("proefstraat 9a", here)[0].label)
        assertEquals("Proefstraat 9a, 9745CS Groningen", book.search("proef 9a", here)[0].label)
        assertEquals("Laan van het Voorbeeld 53, 9793GA Groningen", book.search("laan voorb 53", here)[0].label)
        assertEquals("Proefstraat, 9745CS Groningen", book.search("Proefstraat", here)[0].label)
        assertEquals("Proefweg 46, 9795KV Groningen", book.search("9795 KV 46", here)[0].label)
        assertTrue(book.search("xq", here).isEmpty())
    }

    @Test fun streetComparison() {
        assertTrue(AddressBook.streetMatches("schildersslraat", "Schildersstraat"))
        assertTrue(AddressBook.streetMatches("Voorbeeldlaan", "Ter Voorbeeldlaan"))
        assertTrue(!AddressBook.streetMatches("Sluisstraat", "Proefstraat"))
    }

    /** the real book built by tools/build_addresses.py: every address delivered on the first shift is in it */
    @Test fun realBook() {
        val f = File(System.getProperty("user.home"), "bromnav/data/addresses.tsv.gz")
        assumeTrue(f.exists())
        val t0 = System.currentTimeMillis(); val real = AddressBook.load(f.inputStream()); val ms = System.currentTimeMillis() - t0
        println("${real.groupCount} streets loaded in $ms ms")
        for ((pc, no, street) in listOf(Triple("9799NR", "13", "Sluisstraat"), Triple("9798BT", "16", "Kaai"), Triple("9745CS", "9a", "Proefstraat"),
                Triple("9797PH", "39", "Schildersstraat"), Triple("9796AC", "25-27", "Testlaan"), Triple("9795KV", "46-1", "Proefweg"),
                Triple("9794XA", "27", "Ter Voorbeeldlaan"), Triple("9792BL", "20a", "Van Voorbeeldstraat"), Triple("9746ZX", "9", "Voorbeeldplein")))
            assertEquals("$pc $no", street, real.lookup(pc, no)?.street)
        val t1 = System.nanoTime(); val r = real.search("proefstraat 9", here); println("search ${(System.nanoTime() - t1) / 1_000_000} ms: ${r.map { it.label }}")
        assertEquals("Proefstraat", r[0].street)
    }
}
