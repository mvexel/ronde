package net.mvexel.intervalswatch.shared

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Reads a FIT activity written by [FitWriter] back into a [Recording] (samples, laps, pauses), so the phone can show
 * a Ronde run from the file it keeps instead of downloading streams. Only the messages and fields [FitWriter] writes
 * are read; other messages are skipped. Sample times are whole seconds (FIT timestamps are).
 */
object FitReader {

    private const val SEMICIRCLE = 180.0 / 2_147_483_648.0

    private class Def(val global: Int, val big: Boolean, val fields: List<IntArray>, val devBytes: Int) {
        val size = fields.sumOf { it[1] } + devBytes
    }

    /** Field number to raw value; invalid values are left out. */
    private class Msg(val global: Int, val values: Map<Int, Long>) {
        operator fun get(n: Int) = values[n]
    }

    fun read(fit: ByteArray, name: String = "Ronde"): Recording {
        val msgs = decode(fit)
        val fileId = msgs.firstOrNull { it.global == 0 }
        val records = msgs.filter { it.global == 20 && it[253] != null }
        val startFit = fileId?.get(4) ?: records.firstOrNull()?.get(253) ?: error("FIT file has no start time")
        val session = msgs.lastOrNull { it.global == 18 }
        val sport = when (session?.get(5)) { 1L -> Sport.RUN; 2L -> Sport.RIDE; 11L -> Sport.WALK; else -> Sport.OTHER }
        val indoor = when (sport) {
            Sport.RUN -> session?.get(6) == 1L
            Sport.RIDE -> session?.get(6) == 6L
            else -> false
        }
        val stepsPerStride = if (sport == Sport.RUN || sport == Sport.WALK) 2 else 1

        val samples = records.map { r ->
            RecSample(
                t = (r[253]!! - startFit).toDouble(),
                distanceM = (r[5] ?: 0L) / 100.0,
                lat = r[0]?.let { it.toInt() * SEMICIRCLE },
                lon = r[1]?.let { it.toInt() * SEMICIRCLE },
                altM = r[2]?.let { it / 5.0 - 500 },
                speed = r[6]?.let { it / 1000.0 },
                hr = r[3]?.toInt(),
                power = r[7]?.toInt(),
                cadence = r[4]?.let { (it * stepsPerStride).toInt() },
            )
        }

        var lapStartM = 0.0
        val laps = msgs.filter { it.global == 19 }.map { l ->
            val start = ((l[2] ?: startFit) - startFit).toDouble()
            val end = l[7]?.let { start + it / 1000.0 } ?: ((l[253] ?: startFit) - startFit).toDouble()
            val dist = (l[9] ?: 0L) / 100.0
            val kind = when (l[23]) { 1L -> StepKind.REST; 2L -> StepKind.WARMUP; 3L -> StepKind.COOLDOWN; 0L -> StepKind.ACTIVE; else -> null }
            // wkt_step_index (71); files from before it was written have none.
            RecLap(start, end, lapStartM, lapStartM + dist, kind, l[71]?.toInt()).also { lapStartM += dist }
        }

        // Timer events: stop_all (4) opens a pause, start (0) closes it. A trailing stop_all is the end, not a pause.
        val pauses = mutableListOf<RecPause>()
        var stoppedAt: Double? = null
        for (e in msgs.filter { it.global == 21 && it[0] == 0L }) {
            val t = ((e[253] ?: continue) - startFit).toDouble()
            when (e[1]) {
                4L, 1L -> if (stoppedAt == null) stoppedAt = t
                0L -> stoppedAt?.let { if (t > it) pauses += RecPause(it, t); stoppedAt = null }
            }
        }

        val elapsed = session?.get(7)?.let { it / 1000.0 } ?: samples.lastOrNull()?.t ?: 0.0
        val totalM = session?.get(9)?.let { it / 100.0 } ?: samples.lastOrNull()?.distanceM ?: 0.0
        return Recording(name, sport, indoor, (startFit + FIT_EPOCH_OFFSET) * 1000, samples, laps, pauses, elapsed, totalM)
    }

    private const val FIT_EPOCH_OFFSET = 631_065_600L

    private fun decode(fit: ByteArray): List<Msg> {
        require(fit.size >= 14 && String(fit, 8, 4, Charsets.US_ASCII) == ".FIT") { "not a FIT file" }
        val headerSize = fit[0].toInt() and 0xFF
        val end = minOf(fit.size - 2, headerSize + ByteBuffer.wrap(fit).order(ByteOrder.LITTLE_ENDIAN).getInt(4))
        val defs = HashMap<Int, Def>()
        val out = ArrayList<Msg>()
        var p = headerSize
        while (p < end) {
            val h = fit[p++].toInt() and 0xFF
            if (h and 0x80 != 0) {
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
                    intArrayOf(fit[p + 5 + i * 3].toInt() and 0xFF, fit[p + 6 + i * 3].toInt() and 0xFF, fit[p + 7 + i * 3].toInt() and 0xFF)
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
                if (p + def.size > end) break
                out += readMsg(fit, p, def)
                p += def.size
            }
        }
        return out
    }

    private fun readMsg(fit: ByteArray, start: Int, def: Def): Msg {
        val bb = ByteBuffer.wrap(fit).order(if (def.big) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN)
        val values = HashMap<Int, Long>()
        var p = start
        for (f in def.fields) {
            val (num, size, type) = Triple(f[0], f[1], f[2])
            val signed = type == 0x83 || type == 0x85 || type == 0x01
            val v: Long? = when (size) {
                1 -> (fit[p].toLong() and 0xFF).let { if (signed) it.takeIf { x -> x != 0x7FL }?.toByte()?.toLong() else it.takeIf { x -> x != 0xFFL } }
                2 -> (bb.getShort(p).toLong() and 0xFFFF).let { if (signed) it.takeIf { x -> x != 0x7FFFL }?.toShort()?.toLong() else it.takeIf { x -> x != 0xFFFFL } }
                4 -> (bb.getInt(p).toLong() and 0xFFFFFFFFL).let {
                    when {
                        signed -> it.takeIf { x -> x != 0x7FFFFFFFL }?.toInt()?.toLong()
                        type == 0x8C -> it.takeIf { x -> x != 0L }
                        else -> it.takeIf { x -> x != 0xFFFFFFFFL }
                    }
                }
                else -> null
            }
            if (v != null) values[num] = v
            p += size
        }
        return Msg(def.global, values)
    }
}
