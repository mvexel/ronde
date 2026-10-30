package net.mvexel.intervalswatch.shared

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Minimal FIT activity encoder (file_id, event, record, lap, session, activity), written from the
 * public FIT file structure so no Garmin SDK licence is involved. Little-endian, CRC-16 per spec.
 */
object FitWriter {
    private const val FIT_EPOCH_OFFSET = 631_065_600L // 1989-12-31 minus unix epoch, seconds
    private const val SEMICIRCLE = 2_147_483_648.0 / 180.0

    private const val U8 = 0x02
    private const val ENUM = 0x00
    private const val U16 = 0x84
    private const val S32 = 0x85
    private const val U32 = 0x86
    private const val U32Z = 0x8C

    private class Field(val num: Int, val size: Int, val type: Int)

    private val FILE_ID = listOf(Field(0, 1, ENUM), Field(1, 2, U16), Field(2, 2, U16), Field(3, 4, U32Z), Field(4, 4, U32))
    private val EVENT = listOf(Field(253, 4, U32), Field(0, 1, ENUM), Field(1, 1, ENUM))
    private val RECORD = listOf(
        Field(253, 4, U32), Field(0, 4, S32), Field(1, 4, S32), Field(2, 2, U16), Field(3, 1, U8),
        Field(4, 1, U8), Field(5, 4, U32), Field(6, 2, U16), Field(7, 2, U16),
    )
    private val LAP = listOf(
        Field(253, 4, U32), Field(2, 4, U32), Field(7, 4, U32), Field(8, 4, U32), Field(9, 4, U32),
        Field(254, 2, U16), Field(0, 1, ENUM), Field(1, 1, ENUM), Field(25, 1, ENUM), Field(24, 1, ENUM),
    )
    private val SESSION = listOf(
        Field(253, 4, U32), Field(2, 4, U32), Field(7, 4, U32), Field(8, 4, U32), Field(9, 4, U32),
        Field(5, 1, ENUM), Field(6, 1, ENUM), Field(25, 2, U16), Field(26, 2, U16), Field(254, 2, U16),
        Field(0, 1, ENUM), Field(1, 1, ENUM), Field(16, 1, U8), Field(17, 1, U8), Field(20, 2, U16),
        Field(21, 2, U16), Field(14, 2, U16), Field(18, 1, U8),
    )
    private val ACTIVITY = listOf(
        Field(253, 4, U32), Field(0, 4, U32), Field(1, 2, U16), Field(2, 1, ENUM), Field(3, 1, ENUM), Field(4, 1, ENUM),
    )

    fun encode(rec: Recording): ByteArray {
        val body = ByteArrayOutputStream()
        fun fit(tSec: Double) = (rec.startEpochMs / 1000 + tSec.roundToLong() - FIT_EPOCH_OFFSET).coerceAtLeast(0)
        fun ms(s: Double) = (s * 1000).roundToLong()
        fun cm(m: Double) = (m * 100).roundToLong()

        fun define(local: Int, global: Int, fields: List<Field>) {
            body.write(0x40 or local); body.write(0)
            body.write(0); body.write(global and 0xFF); body.write(global shr 8)
            body.write(fields.size)
            fields.forEach { body.write(it.num); body.write(it.size); body.write(it.type) }
        }
        fun data(local: Int, fields: List<Field>, vararg values: Long?) {
            body.write(local)
            fields.forEachIndexed { i, f ->
                val v = values[i]
                val raw = v ?: when (f.size) { 1 -> 0xFF; 2 -> 0xFFFF; else -> 0xFFFFFFFFL }.toLong()
                // S32 invalid is 0x7FFFFFFF, not all ones.
                val out = if (v == null && f.type == S32) 0x7FFFFFFFL else raw
                for (b in 0 until f.size) body.write(((out shr (8 * b)) and 0xFF).toInt())
            }
        }

        val startFit = fit(0.0)
        val endFit = fit(rec.totalSec)
        val sport = when (rec.sport) { Sport.RUN -> 1L; Sport.RIDE -> 2L; Sport.OTHER -> 0L }
        val subSport = when {
            rec.sport == Sport.RUN && rec.indoor -> 1L
            rec.sport == Sport.RIDE && rec.indoor -> 6L
            else -> 0L
        }

        define(0, 0, FILE_ID)
        data(0, FILE_ID, 4, 255, 0, 1, startFit)

        define(1, 21, EVENT)
        data(1, EVENT, startFit, 0, 0)

        define(2, 20, RECORD)
        for (s in rec.samples) {
            data(
                2, RECORD, fit(s.t),
                s.lat?.let { (it * SEMICIRCLE).roundToLong() },
                s.lon?.let { (it * SEMICIRCLE).roundToLong() },
                s.altM?.let { ((it + 500) * 5).roundToLong().coerceIn(0, 0xFFFE) },
                s.hr?.toLong()?.coerceIn(0, 254),
                s.cadence?.toLong()?.coerceIn(0, 254),
                cm(s.distanceM),
                s.speed?.let { (it * 1000).roundToLong().coerceIn(0, 0xFFFE) },
                s.power?.toLong()?.coerceIn(0, 0xFFFE),
            )
        }

        define(3, 19, LAP)
        rec.laps.forEachIndexed { i, l ->
            val intensity = when (l.kind) {
                StepKind.REST -> 1L; StepKind.WARMUP -> 2L; StepKind.COOLDOWN -> 3L; else -> 0L
            }
            data(
                3, LAP, fit(l.endSec), fit(l.startSec), ms(l.endSec - l.startSec), ms(l.endSec - l.startSec),
                cm(l.endM - l.startM), i.toLong(), 9, 1, sport, intensity,
            )
        }

        data(1, EVENT, endFit, 0, 4)

        fun avg(v: List<Int>) = if (v.isEmpty()) null else v.average().roundToInt().toLong()
        val hr = rec.samples.mapNotNull { it.hr }
        val pw = rec.samples.mapNotNull { it.power }
        val cad = rec.samples.mapNotNull { it.cadence }
        define(4, 18, SESSION)
        data(
            4, SESSION, endFit, startFit, ms(rec.totalSec), ms(rec.totalSec), cm(rec.totalM),
            sport, subSport, 0, rec.laps.size.toLong(), 0, 8, 1,
            avg(hr), hr.maxOrNull()?.toLong(), avg(pw), pw.maxOrNull()?.toLong(),
            if (rec.totalSec > 0) (rec.totalM / rec.totalSec * 1000).roundToLong().coerceIn(0, 0xFFFE) else null,
            avg(cad),
        )

        define(5, 34, ACTIVITY)
        data(5, ACTIVITY, endFit, ms(rec.totalSec), 1, 0, 26, 1)

        val bytes = body.toByteArray()
        val header = ByteBuffer.allocate(14).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(14); put(0x20); putShort(2132); putInt(bytes.size)
            put('.'.code.toByte()); put('F'.code.toByte()); put('I'.code.toByte()); put('T'.code.toByte())
            putShort(crc(array(), 0, 12).toShort())
        }.array()
        val out = ByteArray(14 + bytes.size + 2)
        System.arraycopy(header, 0, out, 0, 14)
        System.arraycopy(bytes, 0, out, 14, bytes.size)
        val c = crc(out, 0, 14 + bytes.size)
        out[out.size - 2] = c.toByte()
        out[out.size - 1] = (c shr 8).toByte()
        return out
    }

    private val CRC_TABLE = intArrayOf(
        0x0000, 0xCC01, 0xD801, 0x1400, 0xF001, 0x3C00, 0x2800, 0xE401,
        0xA001, 0x6C00, 0x7800, 0xB401, 0x5000, 0x9C01, 0x8801, 0x4400,
    )

    fun crc(data: ByteArray, from: Int, to: Int): Int {
        var crc = 0
        for (i in from until to) {
            val b = data[i].toInt() and 0xFF
            var tmp = CRC_TABLE[crc and 0xF]
            crc = (crc shr 4) and 0x0FFF
            crc = crc xor tmp xor CRC_TABLE[b and 0xF]
            tmp = CRC_TABLE[crc and 0xF]
            crc = (crc shr 4) and 0x0FFF
            crc = crc xor tmp xor CRC_TABLE[(b shr 4) and 0xF]
        }
        return crc
    }
}
