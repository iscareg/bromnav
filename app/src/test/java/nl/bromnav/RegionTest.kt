package nl.bromnav

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RegionTest {
    @After fun tearDown() = TestRegion.wabiSabi()

    @Test fun routingTilesPerCity() {
        assertEquals(listOf("E5_N50"), Cities.byName("Groningen")!!.segments())
        assertEquals(listOf("E5_N50"), Cities.byName("Maastricht")!!.segments())
        assertEquals(listOf("E0_N50"), Cities.byName("Rotterdam")!!.segments())
        assertEquals(setOf("E0_N50", "E5_N50"), Cities.byName("Amsterdam")!!.segments().toSet())   // straddles 5° E
    }

    @Test fun citiesAreSane() {
        assertTrue(Cities.all.size >= 20)
        assertEquals(Cities.all.size, Cities.all.map { it.key }.toSet().size)        // unique map file names
        for (c in Cities.all) {
            assertTrue(c.name, c.minLat < c.maxLat && c.minLon < c.maxLon)
            assertTrue(c.name, c.center.lat in 50.7..53.6 && c.center.lon in 3.3..7.3)  // the Netherlands
            assertTrue(c.name, c.areaKm2 in 20.0..1500.0)
            assertTrue(c.name, c.mapMinLat < c.minLat && c.mapMaxLon > c.maxLon)
        }
    }

    @Test fun nothingSetUp() {
        Region.setForTest(null)
        assertFalse(Region.hasCity); assertFalse(Region.hasRestaurant)
        assertEquals("", Config.MUNICIPALITY)
        assertFalse(ReceiptParser.isRestaurant("9711RE", "60", "Schuitendiep"))      // no restaurant: nothing is excluded
        // a receipt is still parsed normally
        val r = ReceiptParser.parse(listOf(ReceiptParser.Line("Schuitendiep 60"), ReceiptParser.Line("9711RE Groningen")))!!
        assertEquals("9711RE", r.postcode)
    }

    @Test fun anotherRestaurant() {
        Region.setForTest(Cities.byName("Utrecht"), "Pizza Roma", LatLon(52.09, 5.12), "3511AB", "12", "Oudegracht", "", "")
        assertEquals(LatLon(52.09, 5.12), Config.RESTAURANT); assertEquals("Utrecht", Config.MUNICIPALITY)
        assertTrue(ReceiptParser.isRestaurant("3511AB", "12"))
        assertTrue(ReceiptParser.isRestaurant("3511AE", "12", "Oudegracht"))           // misread postcode letter
        assertFalse(ReceiptParser.isRestaurant("3511AB", "48", "Oudegracht"))          // a neighbour is a customer
        assertFalse(ReceiptParser.isRestaurant("9711RE", "60", "Schuitendiep"))        // Wabi-Sabi is a customer here
    }
}
