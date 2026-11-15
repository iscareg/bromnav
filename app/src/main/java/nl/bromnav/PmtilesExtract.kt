package nl.bromnav

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan

/**
 * Cuts a bounding box out of a (remote) PMTiles v3 archive into a small local archive, fetching
 * only the needed directories and tiles with HTTP range requests. Lets the phone build its own
 * offline map from the public Protomaps world build, without a PC.
 *
 * Spec: https://github.com/protomaps/PMTiles/blob/main/spec/v3/spec.md
 */
class PmtilesExtract(private val src: RangeSource) {

    interface RangeSource { fun read(offset: Long, length: Int): ByteArray }

    class FileSource(f: File) : RangeSource {
        private val raf = RandomAccessFile(f, "r")
        override fun read(offset: Long, length: Int): ByteArray {
            val b = ByteArray(length); raf.seek(offset); raf.readFully(b); return b
        }
    }

    class HttpSource(private val url: String) : RangeSource {
        var requests = 0; private set
        var bytes = 0L; private set
        override fun read(offset: Long, length: Int): ByteArray {
            var lastErr: Exception? = null
            repeat(4) { attempt ->
                try {
                    val c = URL(url).openConnection() as HttpURLConnection
                    c.connectTimeout = 15000; c.readTimeout = 30000
                    c.setRequestProperty("Range", "bytes=$offset-${offset + length - 1}")
                    c.setRequestProperty("User-Agent", "BromNav")
                    try {
                        if (c.responseCode != 206) throw java.io.IOException("HTTP ${c.responseCode} for range request")
                        val out = ByteArray(length); var n = 0
                        c.inputStream.use { inp -> while (n < length) { val r = inp.read(out, n, length - n); if (r < 0) break; n += r } }
                        if (n != length) throw java.io.IOException("short read $n/$length")
                        requests++; bytes += length
                        return out
                    } finally { c.disconnect() }
                } catch (e: Exception) { lastErr = e; Thread.sleep(1000L * (attempt + 1)) }
            }
            throw lastErr!!
        }
    }

    data class Header(
        val rootOff: Long, val rootLen: Long, val metaOff: Long, val metaLen: Long,
        val leafOff: Long, val leafLen: Long, val dataOff: Long, val dataLen: Long,
        val internalComp: Int, val tileComp: Int, val tileType: Int, val minZoom: Int, val maxZoom: Int,
    )

    data class Entry(val tileId: Long, val offset: Long, val length: Int, val runLength: Int)

    /** progress(fraction 0..1, message) */
    fun extract(out: File, minLon: Double, minLat: Double, maxLon: Double, maxLat: Double, maxZoom: Int,
                progress: (Double, String) -> Unit = { _, _ -> }) {
        val head = src.read(0, 16384)
        val h = parseHeader(head)
        val root = parseDir(decomp(if (h.rootOff + h.rootLen <= head.size) head.copyOfRange(h.rootOff.toInt(), (h.rootOff + h.rootLen).toInt())
                                   else src.read(h.rootOff, h.rootLen.toInt()), h.internalComp))
        val zMax = minOf(maxZoom, h.maxZoom)

        // 1. tile ids inside the box, all zooms
        val ids = ArrayList<Long>()
        for (z in h.minZoom..zMax) {
            val (x0, y0) = lonLatToTile(minLon, maxLat, z); val (x1, y1) = lonLatToTile(maxLon, minLat, z)
            for (x in x0..x1) for (y in y0..y1) ids.add(zxyToId(z, x, y))
        }
        ids.sort()
        progress(0.02, "Finding ${ids.size} tiles…")

        // 2. resolve each id to (offset, length) through root + leaf directories (leaves cached)
        val leafCache = HashMap<Long, List<Entry>>()
        val found = ArrayList<Pair<Long, Entry>>()   // tileId -> content entry
        for ((k, id) in ids.withIndex()) {
            var dir = root; var e: Entry? = null
            for (depth in 0 until 4) {
                e = find(dir, id) ?: break
                if (e.runLength > 0) break
                val leaf = e
                dir = leafCache.getOrPut(leaf.offset) { parseDir(decomp(src.read(h.leafOff + leaf.offset, leaf.length), h.internalComp)) }
                e = null
            }
            if (e != null && e.runLength > 0) found.add(id to e)
            if (k % 2000 == 0) progress(0.02 + 0.08 * k / ids.size, "Reading directories… ${leafCache.size} parts")
        }

        // 3. download unique tile contents in merged range requests, write them to a temp file
        val contents = found.map { it.second.offset to it.second.length }.distinct().sortedBy { it.first }
        val tmp = File(out.path + ".data"); val newOff = HashMap<Long, Long>()
        var written = 0L; val total = contents.sumOf { it.second.toLong() }.coerceAtLeast(1)
        tmp.outputStream().buffered(1 shl 16).use { os ->
            var i = 0
            while (i < contents.size) {
                // merge neighbouring contents into one request (small gaps are cheaper than requests)
                var j = i; val start = contents[i].first; var end = start + contents[i].second
                while (j + 1 < contents.size && contents[j + 1].first - end < 64 * 1024 && contents[j + 1].first + contents[j + 1].second - start < 8 * 1024 * 1024) {
                    j++; end = maxOf(end, contents[j].first + contents[j].second)
                }
                val chunk = src.read(h.dataOff + start, (end - start).toInt())
                for (k in i..j) {
                    val (o, l) = contents[k]
                    newOff[o] = written
                    os.write(chunk, (o - start).toInt(), l); written += l
                }
                i = j + 1
                progress(0.1 + 0.85 * written / total, "Downloading map… ${written / 1_000_000} / ${total / 1_000_000} MB")
            }
        }

        // 4. new directory (runs of identical consecutive tiles collapsed)
        val entries = ArrayList<Entry>()
        for ((id, e) in found) {
            val no = newOff[e.offset]!!
            val last = entries.lastOrNull()
            if (last != null && last.offset == no && last.length == e.length && last.tileId + last.runLength == id)
                entries[entries.size - 1] = last.copy(runLength = last.runLength + 1)
            else entries.add(Entry(id, no, e.length, 1))
        }
        val (rootBytes, leafBytes) = buildDirectories(entries, h.internalComp)
        val meta = src.read(h.metaOff, h.metaLen.toInt())

        // 5. assemble: header | root | metadata | leaves | tile data
        val rootOff = 127L; val metaOff = rootOff + rootBytes.size; val leafOff = metaOff + meta.size
        val dataOff = leafOff + leafBytes.size
        val hb = ByteBuffer.allocate(127).order(ByteOrder.LITTLE_ENDIAN)
        hb.put("PMTiles".toByteArray()); hb.put(3)
        hb.putLong(rootOff); hb.putLong(rootBytes.size.toLong()); hb.putLong(metaOff); hb.putLong(meta.size.toLong())
        hb.putLong(leafOff); hb.putLong(leafBytes.size.toLong()); hb.putLong(dataOff); hb.putLong(written)
        hb.putLong(found.size.toLong()); hb.putLong(entries.size.toLong()); hb.putLong(contents.size.toLong())
        hb.put(1); hb.put(h.internalComp.toByte()); hb.put(h.tileComp.toByte()); hb.put(h.tileType.toByte())
        hb.put(h.minZoom.toByte()); hb.put(zMax.toByte())
        hb.putInt((minLon * 1e7).toInt()); hb.putInt((minLat * 1e7).toInt()); hb.putInt((maxLon * 1e7).toInt()); hb.putInt((maxLat * 1e7).toInt())
        hb.put(minOf(zMax, 12).toByte()); hb.putInt(((minLon + maxLon) / 2 * 1e7).toInt()); hb.putInt(((minLat + maxLat) / 2 * 1e7).toInt())
        out.outputStream().buffered(1 shl 16).use { os ->
            os.write(hb.array()); os.write(rootBytes); os.write(meta); os.write(leafBytes)
            tmp.inputStream().use { it.copyTo(os, 1 shl 16) }
        }
        tmp.delete()
        progress(1.0, "Map ready: ${found.size} tiles, ${out.length() / 1_000_000} MB")
    }

    // ------------------------------------------------------------------ format helpers

    companion object {
        fun parseHeader(b: ByteArray): Header {
            require(String(b, 0, 7) == "PMTiles" && b[7].toInt() == 3) { "not a PMTiles v3 archive" }
            val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
            return Header(bb.getLong(8), bb.getLong(16), bb.getLong(24), bb.getLong(32), bb.getLong(40), bb.getLong(48),
                bb.getLong(56), bb.getLong(64), b[97].toInt(), b[98].toInt(), b[99].toInt(), b[100].toInt(), b[101].toInt())
        }

        fun decomp(b: ByteArray, comp: Int): ByteArray = when (comp) {
            1, 0 -> b
            2 -> GZIPInputStream(ByteArrayInputStream(b)).readBytes()
            else -> throw IllegalArgumentException("unsupported internal compression $comp")
        }

        private fun comp(b: ByteArray, comp: Int): ByteArray = when (comp) {
            1, 0 -> b
            2 -> ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(b) } }.toByteArray()
            else -> throw IllegalArgumentException("unsupported internal compression $comp")
        }

        fun parseDir(b: ByteArray): List<Entry> {
            var p = 0
            fun varint(): Long { var r = 0L; var s = 0; while (true) { val x = b[p++].toInt() and 0xff; r = r or ((x and 0x7f).toLong() shl s); if (x < 0x80) return r; s += 7 } }
            val n = varint().toInt()
            val ids = LongArray(n); var last = 0L
            for (i in 0 until n) { last += varint(); ids[i] = last }
            val runs = IntArray(n) { varint().toInt() }
            val lens = IntArray(n) { varint().toInt() }
            val offs = LongArray(n)
            for (i in 0 until n) {
                val v = varint()
                offs[i] = if (v == 0L && i > 0) offs[i - 1] + lens[i - 1] else v - 1
            }
            return List(n) { Entry(ids[it], offs[it], lens[it], runs[it]) }
        }

        fun serializeDir(es: List<Entry>): ByteArray {
            val o = ByteArrayOutputStream()
            fun varint(v0: Long) { var v = v0; while (v >= 0x80) { o.write(((v and 0x7f) or 0x80).toInt()); v = v ushr 7 }; o.write(v.toInt()) }
            varint(es.size.toLong())
            var last = 0L
            for (e in es) { varint(e.tileId - last); last = e.tileId }
            for (e in es) varint(e.runLength.toLong())
            for (e in es) varint(e.length.toLong())
            for ((i, e) in es.withIndex()) {
                if (i > 0 && e.offset == es[i - 1].offset + es[i - 1].length) varint(0) else varint(e.offset + 1)
            }
            return o.toByteArray()
        }

        /** Root directory (must fit in the first 16 KiB with the header) + leaf directories. */
        fun buildDirectories(entries: List<Entry>, ic: Int): Pair<ByteArray, ByteArray> {
            val single = comp(serializeDir(entries), ic)
            if (single.size <= 16384 - 127) return single to ByteArray(0)
            var leafSize = 4096
            while (true) {
                val leaves = ByteArrayOutputStream(); val rootEntries = ArrayList<Entry>()
                for (chunk in entries.chunked(leafSize)) {
                    val lb = comp(serializeDir(chunk), ic)
                    rootEntries.add(Entry(chunk[0].tileId, leaves.size().toLong(), lb.size, 0))
                    leaves.write(lb)
                }
                val rb = comp(serializeDir(rootEntries), ic)
                if (rb.size <= 16384 - 127) return rb to leaves.toByteArray()
                leafSize *= 2
            }
        }

        fun find(dir: List<Entry>, id: Long): Entry? {
            var lo = 0; var hi = dir.size - 1
            while (lo <= hi) {
                val m = (lo + hi) ushr 1
                val c = dir[m].tileId.compareTo(id)
                if (c == 0) return dir[m]
                if (c < 0) lo = m + 1 else hi = m - 1
            }
            // hi = last entry with tileId < id
            if (hi >= 0) {
                val e = dir[hi]
                if (e.runLength == 0) return e                       // leaf pointer covering this range
                if (id - e.tileId < e.runLength) return e            // inside a run
            }
            return null
        }

        fun zxyToId(z: Int, x0: Int, y0: Int): Long {
            var acc = 0L
            for (t in 0 until z) acc += 1L shl (2 * t)
            var x = x0; var y = y0; var d = 0L
            var s = (1 shl z) / 2
            while (s > 0) {
                val rx = if ((x and s) > 0) 1 else 0
                val ry = if ((y and s) > 0) 1 else 0
                d += s.toLong() * s * ((3 * rx) xor ry)
                if (ry == 0) {
                    if (rx == 1) { x = s - 1 - x; y = s - 1 - y }
                    val t = x; x = y; y = t
                }
                s /= 2
            }
            return acc + d
        }

        fun lonLatToTile(lon: Double, lat: Double, z: Int): Pair<Int, Int> {
            val n = 1 shl z
            val x = floor((lon + 180) / 360 * n).toInt().coerceIn(0, n - 1)
            val r = Math.toRadians(lat)
            val y = floor((1 - ln(tan(r) + 1 / kotlin.math.cos(r)) / PI) / 2 * n).toInt().coerceIn(0, n - 1)
            return x to y
        }
    }
}
