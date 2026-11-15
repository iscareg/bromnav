package nl.bromnav

import btools.mapaccess.WayOverride
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import java.io.File

class RoadOverridesTest {
    private lateinit var dir: File
    private val a = RoadOverrides.id(186_573_000, 143_218_000)
    private val b = RoadOverrides.id(186_574_000, 143_219_000)
    private val c = RoadOverrides.id(186_575_000, 143_220_000)
    private fun entry(x: Long, y: Long, mode: Int) =
        RoadOverrides.Entry(x, y, mode, listOf(RoadOverrides.idToPos(x), RoadOverrides.idToPos(y)), "highway=cycleway")

    @Before fun setUp() { dir = kotlin.io.path.createTempDirectory("ovr").toFile(); RoadOverrides.init(dir); RoadOverrides.clear() }
    @After fun tearDown() { RoadOverrides.clear(); dir.deleteRecursively() }

    @Test fun idsAndPositions() {
        val p = RoadOverrides.idToPos(a)
        assertEquals(53.218, p.lat, 1e-9); assertEquals(6.573, p.lon, 1e-9)
        assertEquals(RoadOverrides.pairKey(a, b), RoadOverrides.pairKey(b, a))
        assert(RoadOverrides.pairKey(a, b) != RoadOverrides.pairKey(a, c))
    }

    @Test fun setGetRemoveBothDirections() {
        val v0 = RoadOverrides.version
        RoadOverrides.set(entry(a, b, RoadOverrides.ALLOW))
        assertEquals(RoadOverrides.ALLOW, RoadOverrides.get(b, a)?.mode)
        assertNull(RoadOverrides.get(a, c))
        RoadOverrides.set(entry(b, a, RoadOverrides.FORBID))          // same section, other direction: replaces
        assertEquals(1, RoadOverrides.all().size); assertEquals(RoadOverrides.FORBID, RoadOverrides.get(a, b)?.mode)
        RoadOverrides.remove(a, b)
        assertNull(RoadOverrides.get(a, b))
        assert(RoadOverrides.version > v0)
    }

    @Test fun persistedAcrossRestart() {
        RoadOverrides.set(entry(a, b, RoadOverrides.ALLOW_BOTH))
        RoadOverrides.set(entry(b, c, RoadOverrides.FORBID))
        RoadOverrides.init(dir)                                        // like a new app start
        assertEquals(2, RoadOverrides.all().size)
        assertEquals(RoadOverrides.ALLOW_BOTH, RoadOverrides.get(a, b)?.mode)
        assertEquals("highway=cycleway", RoadOverrides.get(c, b)?.tags)
        assertEquals(2, RoadOverrides.get(a, b)!!.geom.size)
    }

    @Test fun brokenFileIsIgnored() {
        File(dir, "road_overrides.json").writeText("{not json")
        RoadOverrides.init(dir)
        assertEquals(0, RoadOverrides.all().size)
    }

    @Test fun decoderHook() {
        val data = byteArrayOf(1, 2, 3)
        val h = RoadOverrides.hook
        assertSame(data, h.apply(data, 2, 186_573_000, 143_218_000, 186_574_000, 143_219_000, null))  // allowed, untouched
        assertNull(h.apply(data, 0, 186_573_000, 143_218_000, 186_574_000, 143_219_000, null))        // forbidden by profile
        RoadOverrides.set(entry(a, b, RoadOverrides.FORBID))
        assertNull(h.apply(data, 2, 186_574_000, 143_219_000, 186_573_000, 143_218_000, null))        // your forbid wins
        RoadOverrides.inspect.set(true)
        try { assertSame(data, h.apply(data, 0, 186_573_000, 143_218_000, 186_574_000, 143_219_000, null)) }  // road layer sees raw data
        finally { RoadOverrides.inspect.remove() }
        assertNotNull(WayOverride.hook)
    }

    @Test fun barriersOpenOnAllowedSectionsOnly() {
        val node = byteArrayOf(9)
        RoadOverrides.set(entry(a, b, RoadOverrides.ALLOW))
        assertNull(RoadOverrides.nodeHook.apply(node, 186_574_000, 143_219_000))                       // end of an allowed section
        assertSame(node, RoadOverrides.nodeHook.apply(node, 186_575_000, 143_220_000))
        RoadOverrides.set(entry(a, b, RoadOverrides.FORBID))
        assertSame(node, RoadOverrides.nodeHook.apply(node, 186_574_000, 143_219_000))
    }
}
