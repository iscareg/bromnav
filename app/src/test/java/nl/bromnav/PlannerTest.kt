package nl.bromnav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/** Travel-time matrix + ordering with the real engine (skipped without routing data). */
class PlannerTest {
    init { TestRegion.wabiSabi() }
    private val home = File(System.getProperty("user.home"), "bromnav")
    private val seg = File(home, "data/segments")
    private lateinit var planner: Planner
    private val store = object : SpeedModel.Store {
        val m = HashMap<String, String>()
        override fun get(key: String) = m[key]; override fun put(key: String, value: String) { m[key] = value }; override fun clear() = m.clear()
    }

    @Before fun setUp() {
        assumeTrue(File(seg, "E5_N50.rd5").exists())
        RoadOverrides.init(kotlin.io.path.createTempDirectory("pl").toFile()); RoadOverrides.clear()
        Vehicle.current = Vehicle.YELLOW
        planner = Planner(BRouterEngine(seg, File(home, "app/src/main/assets/profiles/bromfiets.brf")), SpeedModel(store))
    }

    private fun stop(id: Long, lat: Double, lon: Double, deadline: Long? = null, earliest: Long? = null) =
        Stop(id, "stop $id", LatLon(lat, lon), deadline = deadline, earliest = earliest)

    @Test fun ordersAndEtas() {
        val now = 1_800_000_000_000L
        val stops = listOf(stop(1, 53.2297, 6.5550), stop(2, 53.2050, 6.6000), stop(3, 53.2105, 6.5640), stop(4, 53.2350, 6.6100))
        val t0 = System.currentTimeMillis()
        planner.warm(stops, emptyList())
        val warmMs = System.currentTimeMillis() - t0
        val t1 = System.currentTimeMillis()
        val plan = planner.optimise(Config.RESTAURANT, stops, emptyList(), now)
        val optMs = System.currentTimeMillis() - t1
        println("warm ${stops.size} stops: $warmMs ms, optimise (cached legs): $optMs ms, order ${plan.order.map { it.id }}, total ${plan.totalS.toInt()} s")
        assertEquals(stops.toSet(), plan.order.toSet())
        assertTrue("legs must come from the cache", optMs < warmMs)
        val etas = planner.etas(plan.order, 300.0, emptyList(), now)
        for (i in 1 until etas.size) assertTrue(etas[i] > etas[i - 1])
        assertEquals(now + 300_000, etas[0])
    }

    @Test fun exactTimeOrderIsNotEarly() {
        val now = 1_800_000_000_000L
        val late = now + 3_600_000                               // requested in one hour
        val stops = listOf(stop(1, 53.2297, 6.5550, deadline = late + 600_000, earliest = late), stop(2, 53.2105, 6.5640))
        val plan = planner.optimise(Config.RESTAURANT, stops, emptyList(), now)
        assertEquals(listOf(2L, 1L), plan.order.map { it.id })   // the ASAP order first, then wait for the exact one
        val etas = planner.etas(plan.order, 200.0, emptyList(), now)
        assertTrue(etas[1] >= late)
    }

    @Test fun cacheIsResetWhenRoadsChange() {
        val a = Config.RESTAURANT; val b = LatLon(53.2405, 6.5365)
        val l1 = planner.leg(a, b, emptyList())
        val e = RoadOverrides.Entry(1, 2, RoadOverrides.FORBID, emptyList(), "")
        RoadOverrides.set(e)                                      // any change bumps the version
        val t0 = System.nanoTime(); planner.leg(a, b, emptyList()); val ms = (System.nanoTime() - t0) / 1e6
        assertTrue("recomputed after a road correction ($ms ms)", ms > 1)
        RoadOverrides.clear()
        assertEquals(l1.timeS, planner.leg(a, b, emptyList()).timeS, 0.0)
    }
}
