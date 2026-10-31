package dev.openz.core

/**
 * An in-memory "camera" that speaks just enough PTP to exercise the session and Nikon layer.
 * Responses can be delivered in tiny chunks to test container reassembly.
 */
class FakeCamera(private val chunkSize: Int = 64 * 1024) : PtpTransport {
    val log = ArrayList<Pair<Int, List<Long>>>() // (opcode, params) of every command received
    val setValues = HashMap<Int, ByteArray>()

    var liveViewStatus = 0L
    var jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 1, 2, 3, 0xFF.toByte(), 0xD9.toByte())
    var fileBytes = ByteArray(0)

    private val inbox = ArrayDeque<Byte>() // bytes queued for the host to read
    private var pendingCommand: PtpContainer? = null
    private var sessionOpen = false

    // folder tree: storage 0x10001 -> root(0xFFFFFFFF) holds folder 100 holding files 200, 201
    private val folder = 100L
    private val files = mapOf(200L to "DSC_0001.NEF", 201L to "DSC_0002.MOV")

    override fun write(data: ByteArray) {
        val c = PtpContainer.decode(data)
        when (c.type) {
            ContainerType.COMMAND -> {
                val r = PtpReader(c.payload)
                val params = ArrayList<Long>()
                while (r.remaining >= 4) params.add(r.u32())
                log.add(c.code to params)
                // SetDevicePropValue is followed by a data container; wait for it.
                if (c.code == Op.SET_DEVICE_PROP_VALUE) pendingCommand = c else handle(c, params)
            }
            ContainerType.DATA -> {
                val cmd = pendingCommand ?: error("data container without command")
                pendingCommand = null
                setValues[cmd.payload.let { PtpReader(it).u32().toInt() }] = c.payload
                respond(cmd, Rc.OK)
            }
        }
    }

    override fun read(maxLength: Int): ByteArray {
        val n = minOf(maxLength, chunkSize, inbox.size)
        return ByteArray(n) { inbox.removeFirst() }
    }

    override fun close() = Unit

    // ---- helpers -----------------------------------------------------------------------------

    private fun enqueue(bytes: ByteArray) = bytes.forEach { inbox.addLast(it) }

    private fun respond(cmd: PtpContainer, code: Int, vararg params: Long) {
        val w = PtpWriter()
        params.forEach { w.u32(it) }
        enqueue(PtpContainer(ContainerType.RESPONSE, code, cmd.transactionId, w.toByteArray()).encode())
    }

    private fun data(cmd: PtpContainer, payload: ByteArray) =
        enqueue(PtpContainer(ContainerType.DATA, cmd.code, cmd.transactionId, payload).encode())

    private fun handle(cmd: PtpContainer, p: List<Long>) {
        when (cmd.code) {
            Op.OPEN_SESSION -> {
                if (sessionOpen) return respond(cmd, Rc.SESSION_ALREADY_OPEN)
                sessionOpen = true
                respond(cmd, Rc.OK)
            }
            Op.CLOSE_SESSION -> { sessionOpen = false; respond(cmd, Rc.OK) }
            Op.GET_DEVICE_INFO -> { data(cmd, deviceInfo()); respond(cmd, Rc.OK) }
            Op.GET_DEVICE_PROP_DESC -> {
                val d = propDesc(p[0].toInt())
                if (d == null) respond(cmd, 0x200A) else { data(cmd, d); respond(cmd, Rc.OK) }
            }
            Op.NIKON_START_LIVE_VIEW -> { liveViewStatus = 1; respond(cmd, Rc.OK) }
            Op.NIKON_END_LIVE_VIEW -> { liveViewStatus = 0; respond(cmd, Rc.OK) }
            Op.NIKON_GET_LIVE_VIEW_IMG -> {
                data(cmd, ByteArray(16) { 7 } + jpeg) // 16-byte fake vendor header
                respond(cmd, Rc.OK)
            }
            Op.NIKON_START_MOVIE_REC, Op.NIKON_END_MOVIE_REC -> respond(cmd, Rc.OK)
            Op.GET_STORAGE_IDS -> {
                data(cmd, PtpWriter().u32(1).u32(0x10001).toByteArray()); respond(cmd, Rc.OK)
            }
            Op.GET_OBJECT_HANDLES -> {
                val format = p[1].toInt()
                val parent = p[2]
                val list = when {
                    parent == NikonCamera.ROOT && format == CameraObject.FORMAT_ASSOCIATION -> listOf(folder)
                    parent == NikonCamera.ROOT -> listOf(folder)
                    parent == folder && format == CameraObject.FORMAT_ASSOCIATION -> emptyList()
                    parent == folder -> files.keys.toList()
                    else -> emptyList()
                }
                val w = PtpWriter().u32(list.size.toLong())
                list.forEach { w.u32(it) }
                data(cmd, w.toByteArray()); respond(cmd, Rc.OK)
            }
            Op.GET_OBJECT_INFO -> {
                val name = files[p[0]] ?: return respond(cmd, 0x2009)
                data(cmd, objectInfo(name, fileBytes.size.toLong())); respond(cmd, Rc.OK)
            }
            Op.GET_PARTIAL_OBJECT -> {
                val off = p[1].toInt()
                val len = minOf(p[2].toInt(), fileBytes.size - off)
                data(cmd, fileBytes.copyOfRange(off, off + len)); respond(cmd, Rc.OK)
            }
            else -> respond(cmd, Rc.OPERATION_NOT_SUPPORTED)
        }
    }

    private fun deviceInfo(): ByteArray {
        val w = PtpWriter()
        w.u16(100).u32(0xA).u16(100).string("Nikon & Sony PTP Extensions").u16(0)
        val ops = listOf(
            Op.GET_DEVICE_INFO, Op.OPEN_SESSION, Op.GET_DEVICE_PROP_DESC, Op.SET_DEVICE_PROP_VALUE,
            Op.NIKON_START_LIVE_VIEW, Op.NIKON_GET_LIVE_VIEW_IMG,
        )
        w.u32(ops.size.toLong()); ops.forEach { w.u16(it) }
        w.u32(0) // events
        val props = listOf(Prop.EXPOSURE_INDEX, Prop.EXPOSURE_TIME, Prop.NIKON_LIVE_VIEW_STATUS)
        w.u32(props.size.toLong()); props.forEach { w.u16(it) }
        w.u32(0).u32(0) // capture / image formats
        w.string("Nikon Corporation").string("Z 8").string("C:01.40").string("1234567")
        return w.toByteArray()
    }

    private fun propDesc(code: Int): ByteArray? = when (code) {
        Prop.EXPOSURE_INDEX -> PtpWriter()
            .u16(code).u16(DataType.UINT16).u8(1).u16(100).u16(400).u8(2)
            .u16(4).u16(100).u16(400).u16(800).u16(1600).toByteArray()
        Prop.EXPOSURE_TIME -> PtpWriter()
            .u16(code).u16(DataType.UINT32).u8(1).u32(0x00010032).u32(0x0001003C).u8(2)
            .u16(2).u32(0x0001003C).u32(0x00010064).toByteArray()
        Prop.NIKON_LIVE_VIEW_STATUS -> PtpWriter()
            .u16(code).u16(DataType.UINT8).u8(0).u8(0).u8(liveViewStatus.toInt()).u8(0).toByteArray()
        Prop.EXPOSURE_BIAS -> PtpWriter() // int16 range -3 EV .. +3 EV in 1 EV steps
            .u16(code).u16(DataType.INT16).u8(1).u16(0).u16(0).u8(1)
            .u16(-3000).u16(3000).u16(1000).toByteArray()
        Prop.NIKON_LIVE_VIEW_SELECTOR -> PtpWriter()
            .u16(code).u16(DataType.UINT8).u8(1).u8(0).u8(0).u8(0).toByteArray()
        else -> null
    }

    private fun objectInfo(name: String, size: Long): ByteArray {
        val w = PtpWriter()
        w.u32(0x10001).u16(0x300D).u16(0).u32(size)
        w.u16(0).u32(0).u32(0).u32(0)
        w.u32(3840).u32(2160).u32(0)
        w.u32(folder).u16(0).u32(0).u32(0)
        w.string(name).string("20261031T091500").string("")
        return w.toByteArray()
    }
}
