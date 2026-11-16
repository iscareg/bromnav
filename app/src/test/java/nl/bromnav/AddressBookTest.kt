package nl.bromnav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Address book with made-up streets (no real customer addresses in tests). */
class AddressBookTest {
    init { TestRegion.wabiSabi() }
    private val book = AddressBook.load("""
        9901CS	Voorbeeldstraat	Groningen	7:5320857:657178;9:5320854:657163;9A:5320857:657170
        9902KV	Proefweg	Groningen	44:5321100:654000;46:5321110:654010
        9903AC	Testlaan	Groningen	25:5320100:659000;25-27:5320101:659001
        9904GA	Laan van het Voorbeeld	Groningen	51:5319900:657000;53:5319910:657010
        9711RE	Schuitendiep	Groningen	60:5321822:657337
    """.trimIndent().byteInputStream())
    private val here = LatLon(53.2182, 6.5734)

    @Test fun exactAndFallback() {
        assertEquals("Voorbeeldstraat 9a, 9901CS Groningen", book.lookup("9901CS", "9a")!!.label)
        assertEquals("Voorbeeldstraat 9a, 9901CS Groningen", book.lookup("9901 cs", "9A")!!.label)
        assertEquals("46", book.lookup("9902KV", "46-1")!!.houseNo)               // room number -> the building
        assertEquals("25-27", book.lookup("9903AC", "25-27")!!.houseNo)
        assertNull(book.lookup("9901CS", "11"))                                  // never another number
        assertNull(book.lookup("1234AB", "1"))
    }

    @Test fun ocrLookalikesInPostcode() {
        assertEquals("9901CS", Regex("""\d{4}[A-Z]{2}""").find(book.lookupFuzzy("99010S", "9a", "Voorbeeldstraat")!!.label)!!.value)
        assertEquals("9901CS", Regex("""\d{4}[A-Z]{2}""").find(book.lookupFuzzy("9901OS", "9a", null)!!.label)!!.value)
        assertNull(book.lookupFuzzy("99010S", "9a", "Proefweg"))                 // street on the receipt disagrees
    }

    @Test fun typedSearch() {
        assertEquals("Voorbeeldstraat 9a, 9901CS Groningen", book.search("voorbeeldstraat 9a", here)[0].label)
        assertEquals("Voorbeeldstraat 9a, 9901CS Groningen", book.search("voorb 9a", here)[0].label)
        assertEquals("Laan van het Voorbeeld 53, 9904GA Groningen", book.search("laan voorb 53", here)[0].label)
        assertEquals("Voorbeeldstraat, 9901CS Groningen", book.search("Voorbeeldstraat", here)[0].label)
        assertEquals("Proefweg 46, 9902KV Groningen", book.search("9902 KV 46", here)[0].label)
        assertTrue(book.search("xq", here).isEmpty())
    }

    @Test fun nearestAddress() {
        assertEquals("Voorbeeldstraat 9a", book.nearest(LatLon(53.20857, 6.57170)))
        assertNull(book.nearest(LatLon(52.0, 5.0)))
    }

    @Test fun streetComparison() {
        assertTrue(AddressBook.streetMatches("voorbeeldslraat", "Voorbeeldstraat"))  // OCR misread letter
        assertTrue(AddressBook.streetMatches("Voorbeeldlaan", "Ter Voorbeeldlaan"))         // dropped first word
        assertTrue(!AddressBook.streetMatches("Proefweg", "Voorbeeldstraat"))
    }

    /** the real book built by tools/build_addresses.py: public buildings must be in it */
    @Test fun realBook() {
        val f = File(System.getProperty("user.home"), "bromnav/data/addresses.tsv.gz")
        assumeTrue(f.exists())
        val t0 = System.currentTimeMillis(); val real = AddressBook.load(f.inputStream()); val ms = System.currentTimeMillis() - t0
        println("${real.groupCount} streets loaded in $ms ms")
        for ((pc, no, street) in listOf(Triple("9712HN", "1", "Grote Markt"), Triple("9726AE", "4", "Stationsplein"),
                Triple("9747AG", "4", "Nijenborgh"), Triple("9712JG", "12", "Martinikerkhof"), Triple("9712GL", "44", "Oude Boteringestraat")))
            assertEquals("$pc $no", street, real.lookup(pc, no)?.street)
        val t1 = System.nanoTime(); val r = real.search("martinikerkhof 12", here); println("search ${(System.nanoTime() - t1) / 1_000_000} ms: ${r.map { it.label }}")
        assertEquals("Martinikerkhof", r[0].street)
    }
}
