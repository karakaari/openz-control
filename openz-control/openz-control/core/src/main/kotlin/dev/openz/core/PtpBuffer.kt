package dev.openz.core

import java.io.ByteArrayOutputStream

/** Thrown for malformed PTP data, transport trouble, or a non-OK response from the camera. */
class PtpException(message: String, val responseCode: Int? = null) : Exception(message)

/** A property value. Numeric types use [num]; strings use [text]. */
data class PropValue(val num: Long = 0, val text: String? = null)

/** Little-endian reader over a byte array (PTP is always little-endian). */
class PtpReader(private val data: ByteArray, start: Int = 0, private val end: Int = data.size) {
    var pos = start
        private set
    val remaining: Int get() = end - pos

    private fun need(n: Int) {
        if (remaining < n) throw PtpException("Truncated PTP data: need $n bytes, have $remaining")
    }

    fun u8(): Int { need(1); return data[pos++].toInt() and 0xFF }

    fun u16(): Int {
        need(2)
        val v = (data[pos].toInt() and 0xFF) or ((data[pos + 1].toInt() and 0xFF) shl 8)
        pos += 2
        return v
    }

    fun u32(): Long {
        need(4)
        var v = 0L
        for (i in 0 until 4) v = v or ((data[pos + i].toLong() and 0xFF) shl (8 * i))
        pos += 4
        return v
    }

    fun u64(): Long {
        need(8)
        var v = 0L
        for (i in 0 until 8) v = v or ((data[pos + i].toLong() and 0xFF) shl (8 * i))
        pos += 8
        return v
    }

    fun i8(): Long = u8().toByte().toLong()
    fun i16(): Long = u16().toShort().toLong()
    fun i32(): Long = u32().toInt().toLong()

    /** PTP string: u8 character count (including the terminator), then UTF-16LE characters. */
    fun string(): String {
        val n = u8()
        if (n == 0) return ""
        val sb = StringBuilder()
        repeat(n) {
            val c = u16()
            if (c != 0) sb.append(c.toChar())
        }
        return sb.toString()
    }

    fun u16Array(): List<Int> {
        val n = u32()
        if (n > remaining / 2) throw PtpException("Bad array length $n")
        return List(n.toInt()) { u16() }
    }

    fun u32Array(): List<Long> {
        val n = u32()
        if (n > remaining / 4) throw PtpException("Bad array length $n")
        return List(n.toInt()) { u32() }
    }

    fun rest(): ByteArray {
        val out = data.copyOfRange(pos, end)
        pos = end
        return out
    }

    fun value(type: Int): PropValue = when (type) {
        DataType.INT8 -> PropValue(i8())
        DataType.UINT8 -> PropValue(u8().toLong())
        DataType.INT16 -> PropValue(i16())
        DataType.UINT16 -> PropValue(u16().toLong())
        DataType.INT32 -> PropValue(i32())
        DataType.UINT32 -> PropValue(u32())
        DataType.INT64, DataType.UINT64 -> PropValue(u64())
        DataType.STRING -> PropValue(0, string())
        else -> throw PtpException("Unsupported property data type 0x${type.toString(16)}")
    }
}

/** Little-endian writer. */
class PtpWriter {
    private val out = ByteArrayOutputStream()

    fun u8(v: Int) = apply { out.write(v and 0xFF) }
    fun u16(v: Int) = apply { out.write(v and 0xFF); out.write((v shr 8) and 0xFF) }
    fun u32(v: Long) = apply { for (i in 0 until 4) out.write(((v shr (8 * i)) and 0xFF).toInt()) }
    fun u64(v: Long) = apply { for (i in 0 until 8) out.write(((v shr (8 * i)) and 0xFF).toInt()) }
    fun bytes(b: ByteArray) = apply { out.write(b) }

    fun string(s: String) = apply {
        if (s.isEmpty()) {
            u8(0)
        } else {
            u8(s.length + 1)
            s.forEach { u16(it.code) }
            u16(0)
        }
    }

    fun toByteArray(): ByteArray = out.toByteArray()
}
