package net.mvexel.intervalswatch.mobile.data.db

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** DoubleArray <-> little-endian IEEE 754 bytes, for stream blobs. */
object StreamCodec {
    fun encode(values: DoubleArray): ByteArray {
        val buf = ByteBuffer.allocate(values.size * 8).order(ByteOrder.LITTLE_ENDIAN)
        values.forEach { buf.putDouble(it) }
        return buf.array()
    }

    /** Trailing bytes that do not make a whole double are ignored. */
    fun decode(bytes: ByteArray): DoubleArray {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return DoubleArray(bytes.size / 8) { buf.getDouble() }
    }
}

/** BooleanArray <-> one byte per sample (1 = true), for the `moving` stream. */
object MovingCodec {
    fun encode(values: BooleanArray): ByteArray = ByteArray(values.size) { if (values[it]) 1 else 0 }

    fun decode(bytes: ByteArray): BooleanArray = BooleanArray(bytes.size) { bytes[it] != 0.toByte() }
}
