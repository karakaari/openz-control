package dev.openz.core

import java.io.ByteArrayOutputStream

/** Byte pipe to the camera. On Android this is a USB bulk in/out endpoint pair. */
interface PtpTransport {
    /** Write all of [data]. Throws java.io.IOException on failure. */
    fun write(data: ByteArray)

    /**
     * Read up to [maxLength] bytes. May return fewer (a short packet) or an empty array (a
     * zero-length packet). Throws java.io.IOException on failure or timeout.
     */
    fun read(maxLength: Int): ByteArray

    fun close()
}

data class PtpContainer(val type: Int, val code: Int, val transactionId: Long, val payload: ByteArray) {
    fun encode(): ByteArray = PtpWriter()
        .u32((HEADER_SIZE + payload.size).toLong())
        .u16(type)
        .u16(code)
        .u32(transactionId)
        .bytes(payload)
        .toByteArray()

    companion object {
        const val HEADER_SIZE = 12

        fun decode(bytes: ByteArray, length: Int = bytes.size): PtpContainer {
            val r = PtpReader(bytes, 0, length)
            val len = r.u32()
            if (len < HEADER_SIZE || len > length) throw PtpException("Bad container length $len")
            val type = r.u16()
            val code = r.u16()
            val tx = r.u32()
            return PtpContainer(type, code, tx, bytes.copyOfRange(HEADER_SIZE, len.toInt()))
        }
    }
}

data class PtpResponse(val code: Int, val params: List<Long>, val data: ByteArray?) {
    val ok: Boolean get() = code == Rc.OK

    fun requireOk(what: String): PtpResponse {
        if (!ok) throw PtpException("$what failed: ${Rc.name(code)}", code)
        return this
    }
}

/**
 * A PTP session: one command at a time, strictly request/response. Not thread-safe; callers must
 * serialise access (the Android app funnels everything through a single-thread dispatcher).
 */
class PtpSession(private val transport: PtpTransport) {
    private var nextTx = 0L
    private var pending = ByteArray(0)

    var isOpen = false
        private set

    fun open(sessionId: Long = 1) {
        nextTx = 0
        var r = raw(Op.OPEN_SESSION, listOf(sessionId), null)
        if (r.code == Rc.SESSION_ALREADY_OPEN) {
            runCatching { raw(Op.CLOSE_SESSION, emptyList(), null) }
            nextTx = 0
            r = raw(Op.OPEN_SESSION, listOf(sessionId), null)
        }
        r.requireOk("OpenSession")
        isOpen = true
    }

    fun close() {
        if (isOpen) runCatching { raw(Op.CLOSE_SESSION, emptyList(), null) }
        isOpen = false
        transport.close()
    }

    /** Run one operation. A non-OK response code is returned, not thrown; use [PtpResponse.requireOk]. */
    fun transaction(code: Int, params: List<Long> = emptyList(), dataOut: ByteArray? = null): PtpResponse {
        if (!isOpen) throw PtpException("Session is not open")
        return raw(code, params, dataOut)
    }

    private fun raw(code: Int, params: List<Long>, dataOut: ByteArray?): PtpResponse {
        val tx = nextTx++
        val w = PtpWriter()
        params.forEach { w.u32(it) }
        transport.write(PtpContainer(ContainerType.COMMAND, code, tx, w.toByteArray()).encode())
        if (dataOut != null) {
            transport.write(PtpContainer(ContainerType.DATA, code, tx, dataOut).encode())
        }

        var data: ByteArray? = null
        while (true) {
            val c = readContainer()
            when (c.type) {
                ContainerType.DATA -> data = c.payload
                ContainerType.RESPONSE -> {
                    val r = PtpReader(c.payload)
                    val rp = ArrayList<Long>()
                    while (r.remaining >= 4) rp.add(r.u32())
                    return PtpResponse(c.code, rp, data)
                }
                else -> Unit // events and anything unexpected are ignored
            }
        }
    }

    private fun readContainer(): PtpContainer {
        val buf = ByteArrayOutputStream()
        buf.write(pending)
        pending = ByteArray(0)

        var emptyReads = 0
        fun fill() {
            val chunk = transport.read(READ_CHUNK)
            if (chunk.isEmpty()) {
                if (++emptyReads > MAX_EMPTY_READS) throw PtpException("Camera stopped responding")
            } else {
                emptyReads = 0
                buf.write(chunk)
            }
        }

        while (buf.size() < PtpContainer.HEADER_SIZE) fill()
        val head = PtpReader(buf.toByteArray(), 0, PtpContainer.HEADER_SIZE)
        val length = head.u32()
        if (length < PtpContainer.HEADER_SIZE) throw PtpException("Bad container length $length")
        if (length > Int.MAX_VALUE) {
            throw PtpException("Container larger than 2 GiB; use partial reads for big files")
        }
        while (buf.size() < length) fill()

        val all = buf.toByteArray()
        val len = length.toInt()
        pending = if (all.size > len) all.copyOfRange(len, all.size) else ByteArray(0)
        return PtpContainer.decode(all, len)
    }

    private companion object {
        const val READ_CHUNK = 64 * 1024
        const val MAX_EMPTY_READS = 5
    }
}
