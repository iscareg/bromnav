package nl.bromnav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.GZIPOutputStream

class PoiIndexTest {
    private val here = LatLon(53.2182, 6.5734)

    private fun index(vararg rows: String): PoiIndex {
        val bo = ByteArrayOutputStream(); GZIPOutputStream(bo).use { it.write(rows.joinToString("\n").toByteArray()) }
        return PoiIndex.load(ByteArrayInputStream(bo.toByteArray()))
    }

    private val idx = index(
        "Albert Heijn\tshop\tsupermarket\t\tAH\tVismarkt\t1\t9711KS\tGroningen\t53.2160\t6.5650",
        "Albert Heijn XL\tshop\tsupermarket\t\t\tSontweg\t2\t\tGroningen\t53.2050\t6.6000",
        "Pizzeria Napoli\tamenity\trestaurant\tpizza;italian\t\tOosterstraat\t5\t\tGroningen\t53.2170\t6.5700",
        "Apotheek Centrum\tamenity\tpharmacy\t\t\tHerestraat\t8\t\tGroningen\t53.2150\t6.5680",
        "Café de Sleutel\tamenity\tcafe\t\t\t\t\t\tGroningen\t53.2190\t6.5720",
        "Martinitoren\thistoric\tbuilding\t\tMartinikerk tower\t\t\t\tGroningen\t53.2193\t6.5684",
    )

    @Test fun prefixWords() = assertEquals("Albert Heijn", idx.search("alb hei", here)[0].poi.name)
    @Test fun typo() = assertEquals("Martinitoren", idx.search("martinitorn", here)[0].poi.name)
    @Test fun accents() = assertEquals("Café de Sleutel", idx.search("cafe sleutel", here)[0].poi.name)
    @Test fun dutchCategory() = assertEquals("Apotheek Centrum", idx.search("apotheek", here)[0].poi.name)
    @Test fun cuisine() = assertEquals("Pizzeria Napoli", idx.search("pizza", here)[0].poi.name)
    @Test fun brandAlias() = assertEquals("Albert Heijn", idx.search("ah", here)[0].poi.name)
    @Test fun nearerFirst() {
        val r = idx.search("albert heijn", here)
        assertEquals("Albert Heijn", r[0].poi.name); assertEquals("Albert Heijn XL", r[1].poi.name)
    }
    @Test fun noGarbage() = assertTrue(idx.search("xyzzy", here).isEmpty())

    /** Real data (if built on this PC): timing + a few sanity queries. */
    @Test fun realData() {
        val f = File(System.getProperty("user.home"), "bromnav/data/pois.tsv.gz")
        assumeTrue(f.exists())
        val t0 = System.nanoTime(); val real = PoiIndex.load(f.inputStream()); val tLoad = (System.nanoTime() - t0) / 1e6
        val t1 = System.nanoTime()
        for (q in listOf("albert heijn", "martini", "pizza", "apotheek", "station", "vismarkt", "kfc", "jumbo"))
            println("%-14s -> %s".format(q, real.search(q, here, 3).joinToString(" | ") { "${it.poi.icon} ${it.poi.name} (${(it.distM / 100).toInt() / 10.0} km)" }))
        val tSearch = (System.nanoTime() - t1) / 1e6 / 8
        println("${real.size} places, load ${tLoad.toInt()} ms, search ${tSearch.toInt()} ms/query (PC)")
        assertTrue(real.size > 5000)
    }
}
