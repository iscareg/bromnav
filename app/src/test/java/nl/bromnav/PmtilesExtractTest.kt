package nl.bromnav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class PmtilesExtractTest {

    @Test fun hilbertIdsMatchSpecExamples() {
        assertEquals(0L, PmtilesExtract.zxyToId(0, 0, 0))
        assertEquals(1L, PmtilesExtract.zxyToId(1, 0, 0))
        assertEquals(2L, PmtilesExtract.zxyToId(1, 0, 1))
        assertEquals(3L, PmtilesExtract.zxyToId(1, 1, 1))
        assertEquals(4L, PmtilesExtract.zxyToId(1, 1, 0))
        assertEquals(5L, PmtilesExtract.zxyToId(2, 0, 0))
        assertEquals(19078479L, PmtilesExtract.zxyToId(12, 3423, 1763))
    }

    @Test fun directoryRoundTrip() {
        val es = listOf(PmtilesExtract.Entry(5, 0, 100, 1), PmtilesExtract.Entry(6, 100, 50, 3), PmtilesExtract.Entry(20, 7, 9, 1))
        assertEquals(es, PmtilesExtract.parseDir(PmtilesExtract.serializeDir(es)))
    }

    /** Cuts Groningen city out of the local province extract (only when the PC data is present). */
    @Test fun extractFromLocalArchive() {
        val srcFile = File(System.getProperty("user.home"), "bromnav/data/tiles/groningen.pmtiles")
        assumeTrue(srcFile.exists())
        val out = File(System.getProperty("java.io.tmpdir"), "bromnav-test-city.pmtiles")
        PmtilesExtract(PmtilesExtract.FileSource(srcFile)).extract(out, 6.50, 53.19, 6.63, 53.25, 15)
        assertTrue(out.length() > 100_000)
        val h = PmtilesExtract.parseHeader(out.readBytes().copyOf(127))
        assertEquals(15, h.maxZoom)
    }
}

class PmtilesRemoteTest {
    /** Network test (opt-in): BROMNAV_NET_TEST=1 ./gradlew testDebugUnitTest --tests '*PmtilesRemoteTest*' */
    @Test fun extractFromProtomapsBuild() {
        assumeTrue(System.getenv("BROMNAV_NET_TEST") == "1")
        val key = java.net.URL("https://build-metadata.protomaps.dev/builds.json").readText()
            .let { Regex(""""key"\s*:\s*"([^"]+\.pmtiles)"""").findAll(it).last().groupValues[1] }
        val src = PmtilesExtract.HttpSource("https://build.protomaps.com/$key")
        val out = File(System.getProperty("java.io.tmpdir"), "bromnav-remote-city.pmtiles")
        PmtilesExtract(src).extract(out, 6.50, 53.19, 6.63, 53.25, 15)
        println("remote $key: ${src.requests} requests, ${src.bytes / 1000} kB -> ${out.length() / 1000} kB")
        assertTrue(out.length() > 100_000)
    }
}
