package nl.bromnav

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Offline data on the phone: BRouter routing segments + the PMTiles map of the delivery area.
 * Sources: bundled in the release APK (installed on first start), or downloaded from the
 * internet (BRouter segments from brouter.de, map cut out of the public Protomaps world build).
 */
object MapData {
    /** BRouter 5°x5° segments covering the area (Groningen = E5_N50). */
    val SEGMENTS = listOf("E5_N50")
    const val SEGMENT_URL = "https://brouter.de/brouter/segments4/"
    const val TILES_NAME = "groningen.pmtiles"
    /** Map area (lon/lat) and max zoom of the offline map. */
    const val MIN_LON = 6.15; const val MIN_LAT = 52.95; const val MAX_LON = 7.25; const val MAX_LAT = 53.55
    const val MAX_ZOOM = 15

    fun segDir(ctx: Context) = File(ctx.getExternalFilesDir(null), "segments").apply { mkdirs() }
    fun tilesFile(ctx: Context) = File(ctx.getExternalFilesDir(null), "tiles/$TILES_NAME").apply { parentFile?.mkdirs() }

    fun haveData(ctx: Context) = SEGMENTS.all { File(segDir(ctx), "$it.rd5").exists() } && tilesFile(ctx).exists()

    fun hasBundled(ctx: Context) = runCatching { ctx.assets.list("mapdata")?.contains(TILES_NAME) == true }.getOrDefault(false)

    /** progress(fraction, message) is called from the worker thread. */
    fun installBundled(ctx: Context, progress: (Double, String) -> Unit) {
        val names = SEGMENTS.map { "$it.rd5" } + TILES_NAME
        val sizes = names.map { n -> runCatching { ctx.assets.openFd("mapdata/$n").use { it.length } }.getOrDefault(100_000_000L) }
        val total = sizes.sum().toDouble(); var done = 0L
        for (n in names) {
            val dst = if (n == TILES_NAME) tilesFile(ctx) else File(segDir(ctx), n)
            val tmp = File(dst.path + ".new")
            ctx.assets.open("mapdata/$n").use { inp ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(1 shl 16)
                    while (true) {
                        val r = inp.read(buf); if (r < 0) break
                        out.write(buf, 0, r); done += r
                        if (done % (4 shl 20) < buf.size) progress(done / total, "Installing offline maps… ${done / 1_000_000} / ${(total / 1_000_000).toInt()} MB")
                    }
                }
            }
            tmp.renameTo(dst)
        }
        progress(1.0, "Offline maps installed")
    }

    /** Fresh routing data (BRouter rebuilds the segments weekly from OpenStreetMap). */
    fun downloadRouting(ctx: Context, progress: (Double, String) -> Unit) {
        for ((i, seg) in SEGMENTS.withIndex()) {
            val dst = File(segDir(ctx), "$seg.rd5"); val tmp = File(dst.path + ".new")
            val c = URL(SEGMENT_URL + "$seg.rd5").openConnection() as HttpURLConnection
            c.connectTimeout = 15000; c.readTimeout = 60000; c.setRequestProperty("User-Agent", "BromNav")
            try {
                val total = c.contentLengthLong.coerceAtLeast(1)
                var done = 0L
                c.inputStream.use { inp -> tmp.outputStream().use { out ->
                    val buf = ByteArray(1 shl 16)
                    while (true) {
                        val r = inp.read(buf); if (r < 0) break
                        out.write(buf, 0, r); done += r
                        if (done % (2 shl 20) < buf.size)
                            progress((i + done.toDouble() / total) / SEGMENTS.size, "Routing data $seg… ${done / 1_000_000} / ${total / 1_000_000} MB")
                    }
                } }
                if (done < total) throw java.io.IOException("download incomplete")
            } finally { c.disconnect() }
            tmp.renameTo(dst)
        }
    }

    /** Fresh map: cut the area out of the newest Protomaps world build (range requests, ~90 MB). */
    fun downloadMap(ctx: Context, progress: (Double, String) -> Unit) {
        progress(0.0, "Looking for the newest map build…")
        val key = URL("https://build-metadata.protomaps.dev/builds.json").readText()
            .let { Regex(""""key"\s*:\s*"([^"]+\.pmtiles)"""").findAll(it).last().groupValues[1] }
        val dst = tilesFile(ctx); val tmp = File(dst.path + ".new")
        PmtilesExtract(PmtilesExtract.HttpSource("https://build.protomaps.com/$key"))
            .extract(tmp, MIN_LON, MIN_LAT, MAX_LON, MAX_LAT, MAX_ZOOM, progress)
        tmp.renameTo(dst)
    }
}
