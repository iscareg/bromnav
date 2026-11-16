package nl.bromnav

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/** Embedded routing with the real Groningen data on this PC (skipped when the data is absent). */
class EngineTest {
    init { TestRegion.wabiSabi() }
    private val home = File(System.getProperty("user.home"), "bromnav")
    private val seg = File(home, "data/segments")
    private val profile = File(home, "app/src/main/assets/profiles/bromfiets.brf")
    private val restaurant = Config.RESTAURANT
    private val targets = listOf(
        LatLon(53.2405, 6.5365),      // Zernike
        LatLon(53.2050, 6.6000),      // south-east
        LatLon(53.1950, 6.5400),      // south-west
        LatLon(53.2350, 6.6100),      // north-east
        LatLon(53.2297, 6.5550),      // Groningen Noord
        LatLon(53.2105, 6.5640),      // Hoofdstation
    )
    private lateinit var engine: BRouterEngine

    @Before fun setUp() {
        assumeTrue(File(seg, "E5_N50.rd5").exists())
        RoadOverrides.init(kotlin.io.path.createTempDirectory("eng").toFile()); RoadOverrides.clear()
        engine = BRouterEngine(seg, profile)
    }
    @After fun tearDown() { Vehicle.current = Vehicle.YELLOW }

    private fun illegal(v: Vehicle, t: Map<String, String>): Boolean {
        val hw = t["highway"] ?: ""
        if (t["motorroad"] == "yes" || hw.startsWith("motorway")) return true
        return when (v) {
            Vehicle.YELLOW -> t["moped"] in setOf("no", "use_sidepath") ||
                (hw in setOf("cycleway", "footway", "path", "pedestrian") && t["moped"] !in setOf("yes", "designated"))
            Vehicle.BLUE -> t["mofa"] in setOf("no", "use_sidepath") ||
                (hw in setOf("footway", "pedestrian") && t["mofa"] !in setOf("yes", "designated") && t["moped"] !in setOf("yes", "designated") && t["bicycle"] !in setOf("yes", "designated"))
            Vehicle.EBIKE, Vehicle.BIKE -> t["bicycle"] in setOf("no", "use_sidepath", "dismount") ||
                (hw in setOf("footway", "pedestrian") && t["bicycle"] !in setOf("yes", "designated", "permissive", "destination"))
        }
    }

    @Test fun everyVehicleRoutesLegally() {
        for (v in Vehicle.entries) {
            Vehicle.current = v
            for (t in targets) {
                val r = engine.route(listOf(restaurant, t))
                val bad = r.segs.filter { illegal(v, it.tags) }
                assertTrue("${v.short} -> $t uses ${bad.map { it.tags }}", bad.isEmpty())
            }
        }
    }

    @Test fun slowVehiclesTakeLonger() {
        val t = targets[1]
        Vehicle.current = Vehicle.YELLOW; val brom = engine.route(listOf(restaurant, t))
        Vehicle.current = Vehicle.BLUE; val snor = engine.route(listOf(restaurant, t))
        Vehicle.current = Vehicle.EBIKE; val bike = engine.route(listOf(restaurant, t))
        Vehicle.current = Vehicle.BIKE; val pedal = engine.route(listOf(restaurant, t))
        assertTrue("a bicycle without motor is slower than an e-bike", pedal.timeS > bike.timeS)
        assertTrue(pedal.lengthM / pedal.timeS.toDouble() * 3.6 <= 18.5)
        println("to south-east: bromfiets ${brom.lengthM} m ${brom.timeS} s, snorfiets ${snor.lengthM} m ${snor.timeS} s, e-bike ${bike.lengthM} m ${bike.timeS} s")
        assertTrue(brom.timeS < snor.timeS); assertTrue(brom.timeS < bike.timeS)
        // average speeds stay within each vehicle's limit
        assertTrue(brom.lengthM / brom.timeS.toDouble() * 3.6 <= 45.5)
        assertTrue(snor.lengthM / snor.timeS.toDouble() * 3.6 <= 25.5)
    }

    /** address point inside the Zernike campus, enclosed by access=private roads */
    @Test fun unreachableDestinationEndsNearby() {
        val nijenborgh4 = LatLon(53.23843162, 6.53781278)
        val t0 = System.currentTimeMillis()
        val r = engine.route(listOf(restaurant, nijenborgh4))
        println("Nijenborgh 4: ${r.lengthM} m, walk ${r.walkM} m, ${System.currentTimeMillis() - t0} ms")
        assertTrue(r.walkM in 1..350)
        assertEquals(0, r.startWalkM)
    }

    @Test fun normalRouteHasNoWalk() {
        val r = engine.route(listOf(restaurant, targets[0]))
        assertEquals(0, r.walkM); assertEquals(0, r.startWalkM)
    }

    @Test fun noGoCircleIsAvoided() {
        val base = engine.route(listOf(restaurant, targets[0]))
        val mid = base.pts[base.pts.size / 2]
        val r = engine.route(listOf(restaurant, targets[0]), listOf(NoGo(mid, 20)))
        assertFalse(r.pts.any { Geo.dist(it, mid) < 15 })
    }
}
