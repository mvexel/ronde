package net.mvexel.intervalswatch.shared

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant

/**
 * Converts a recorded FIT activity into GPX 1.1 (track points with time, elevation and heart rate).
 * Reads only the record message (global 20) with a small decoder; records without a position are skipped.
 */
object FitToGpx {

    private const val FIT_EPOCH_OFFSET = 631_065_600L
    private const val SEMICIRCLE = 180.0 / 2_147_483_648.0

    private class Def(val global: Int, val big: Boolean, val fields: List<IntArray>, val devBytes: Int) {
        val size = fields.sumOf { it[1] } + devBytes
    }

    private class Point(val time: Long?, val lat: Double, val lon: Double, val ele: Double?, val hr: Int?)

    fun convert(fit: ByteArray, name: String = "Ronde"): String {
        val points = decode(fit)
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        sb.append("""<gpx version="1.1" creator="Ronde" xmlns="http://www.topografix.com/GPX/1/1" """)
            .append("""xmlns:gpxtpx="http://www.garmin.com/xmlschemas/TrackPointExtension/v1">""").append('\n')
        points.firstOrNull { it.time != null }?.let {
            sb.append("  <metadata><time>").append(Instant.ofEpochSecond(it.time!!)).append("</time></metadata>\n")
        }
        sb.append("  <trk>\n    <name>").append(escape(name)).append("</name>\n    <trkseg>\n")
        for (p in points) {
            sb.append("      <trkpt lat=\"").append(fmt(p.lat)).append("\" lon=\"").append(fmt(p.lon)).append("\">")
            p.ele?.let { sb.append("<ele>").append("%.1f".format(java.util.Locale.ROOT, it)).append("</ele>") }
            p.time?.let { sb.append("<time>").append(Instant.ofEpochSecond(it)).append("</time>") }
            p.hr?.let { sb.append("<extensions><gpxtpx:TrackPointExtension><gpxtpx:hr>").append(it)
                .append("</gpxtpx:hr></gpxtpx:TrackPointExtension></extensions>") }
            sb.append("</trkpt>\n")
        }
        sb.append("    </trkseg>\n  </trk>\n</gpx>\n")
        return sb.toString()
    }

    private fun fmt(d: Double) = "%.7f".format(java.util.Locale.ROOT, d)

    private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun decode(fit: ByteArray): List<Point> {
        require(fit.size >= 14 && String(fit, 8, 4) == ".FIT") { "not a FIT file" }
        val headerSize = fit[0].toInt() and 0xFF
        val end = minOf(fit.size - 2, headerSize + ByteBuffer.wrap(fit).order(ByteOrder.LITTLE_ENDIAN).getInt(4))
        val defs = HashMap<Int, Def>()
        val out = ArrayList<Point>()
        var p = headerSize
        while (p < end) {
            val h = fit[p++].toInt() and 0xFF
            if (h and 0x80 != 0) { // compressed timestamp header: data message, local type in bits 5-6
                val def = defs[(h shr 5) and 0x03] ?: break
                p += def.size
                continue
            }
            val local = h and 0x0F
            if (h and 0x40 != 0) {
                val big = fit[p + 1].toInt() == 1
                val bb = ByteBuffer.wrap(fit).order(if (big) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN)
                val global = bb.getShort(p + 2).toInt() and 0xFFFF
                val n = fit[p + 4].toInt() and 0xFF
                val fields = List(n) { i ->
                    intArrayOf(fit[p + 5 + i * 3].toInt() and 0xFF, fit[p + 6 + i * 3].toInt() and 0xFF)
                }
                p += 5 + n * 3
                var dev = 0
                if (h and 0x20 != 0) {
                    val dn = fit[p++].toInt() and 0xFF
                    for (i in 0 until dn) dev += fit[p + i * 3 + 1].toInt() and 0xFF
                    p += dn * 3
                }
                defs[local] = Def(global, big, fields, dev)
            } else {
                val def = defs[local] ?: break
                if (def.global == 20) readRecord(fit, p, def)?.let { out.add(it) }
                p += def.size
            }
        }
        return out
    }

    private fun readRecord(fit: ByteArray, start: Int, def: Def): Point? {
        val bb = ByteBuffer.wrap(fit).order(if (def.big) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN)
        var ts: Long? = null; var lat: Int? = null; var lon: Int? = null; var alt: Int? = null; var hr: Int? = null
        var p = start
        for ((num, size) in def.fields.map { it[0] to it[1] }) {
            when (num) {
                253 -> bb.getInt(p).toLong().and(0xFFFFFFFFL).takeIf { it != 0xFFFFFFFFL }?.let { ts = it }
                0 -> bb.getInt(p).takeIf { it != 0x7FFFFFFF }?.let { lat = it }
                1 -> bb.getInt(p).takeIf { it != 0x7FFFFFFF }?.let { lon = it }
                2 -> (bb.getShort(p).toInt() and 0xFFFF).takeIf { it != 0xFFFF }?.let { alt = it }
                3 -> (fit[p].toInt() and 0xFF).takeIf { it != 0xFF }?.let { hr = it }
            }
            p += size
        }
        val la = lat ?: return null
        val lo = lon ?: return null
        return Point(ts?.let { it + FIT_EPOCH_OFFSET }, la * SEMICIRCLE, lo * SEMICIRCLE, alt?.let { it / 5.0 - 500 }, hr)
    }
}
