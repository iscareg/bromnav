package nl.bromnav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeedModelTest {
    private class MapStore : SpeedModel.Store {
        val m = HashMap<String, String>()
        override fun get(key: String) = m[key]
        override fun put(key: String, value: String) { m[key] = value }
        override fun clear() = m.clear()
    }

    @Test fun neutralUntilThreeSamples() {
        val s = SpeedModel(MapStore())
        assertEquals(1.0, s.factor(1000.0), 0.0)
        s.record(1000.0, 100.0, 120.0); s.record(1000.0, 100.0, 130.0)
        assertEquals(1.0, s.factor(1000.0), 0.0)
        s.record(1000.0, 100.0, 140.0)
        assertEquals(1.3, s.factor(1000.0), 1e-9)          // median of 1.2, 1.3, 1.4
    }

    @Test fun bandsAreSeparate() {
        val s = SpeedModel(MapStore())
        repeat(3) { s.record(800.0, 100.0, 150.0) }          // short legs: slow
        repeat(3) { s.record(5000.0, 600.0, 540.0) }         // long legs: faster than the estimate
        assertEquals(1.5, s.factor(500.0), 1e-9)
        assertEquals(1.0, s.factor(2500.0), 0.0)
        assertEquals(0.9, s.factor(6000.0), 1e-9)
    }

    @Test fun nonsenseIsIgnored() {
        val s = SpeedModel(MapStore())
        assertFalse(s.record(100.0, 100.0, 100.0))           // too short
        assertFalse(s.record(1000.0, 10.0, 10.0))            // estimate too small
        assertFalse(s.record(1000.0, 100.0, 20.0))           // impossibly fast
        assertFalse(s.record(1000.0, 100.0, 400.0))          // a long break
        assertEquals(0, s.sampleCount())
    }

    @Test fun undoAndPersistence() {
        val store = MapStore()
        val s = SpeedModel(store)
        repeat(3) { s.record(1000.0, 100.0, 110.0) }
        assertTrue(s.record(1000.0, 100.0, 300.0))
        s.removeLast(1000.0)                                   // undo the last "Delivered"
        assertEquals(1.1, s.factor(1000.0), 1e-9)
        val reloaded = SpeedModel(store)                       // samples survive a restart
        assertEquals(3, reloaded.sampleCount())
        assertEquals(1.1, reloaded.factor(1000.0), 1e-9)
    }

    @Test fun keepsLast30() {
        val s = SpeedModel(MapStore())
        repeat(40) { s.record(1000.0, 100.0, 100.0 + it) }
        assertEquals(30, s.sampleCount())
        assertEquals(1.25, s.factor(1000.0), 1e-9)           // samples 1.10..1.39 remain, median 1.25
    }

    @Test fun clear() {
        val s = SpeedModel(MapStore())
        repeat(3) { s.record(1000.0, 100.0, 150.0) }
        s.clear()
        assertEquals(0, s.sampleCount()); assertEquals(1.0, s.factor(1000.0), 0.0)
    }
}
