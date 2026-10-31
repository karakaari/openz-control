package dev.openz.core

import java.io.OutputStream

/**
 * High-level Nikon Z control on top of a [PtpSession].
 *
 * Status: written from the public PTP spec and public reverse-engineered Nikon extensions. It has
 * NOT been run against real hardware. Every vendor-specific step is isolated here so it can be
 * corrected quickly once real traces exist.
 */
class NikonCamera(
    private val session: PtpSession,
    val props: PropertyMap = PropertyMap(),
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
) {
    lateinit var info: DeviceInfo
        private set

    var liveViewRunning = false
        private set
    var recording = false
        private set

    fun connect(): DeviceInfo {
        session.open()
        val r = session.transaction(Op.GET_DEVICE_INFO).requireOk("GetDeviceInfo")
        info = DeviceInfo.parse(r.data ?: throw PtpException("GetDeviceInfo returned no data"))
        return info
    }

    fun disconnect() {
        runCatching { if (recording) stopRecording() }
        runCatching { if (liveViewRunning) stopLiveView() }
        session.close()
    }

    fun supports(op: Int): Boolean = op in info.operations

    // ---- properties -------------------------------------------------------------------------

    fun getProp(code: Int): PropDesc {
        val r = session.transaction(Op.GET_DEVICE_PROP_DESC, listOf(code.toLong()))
            .requireOk("GetDevicePropDesc(${Prop.name(code)})")
        return PropDesc.parse(r.data ?: throw PtpException("No descriptor for ${Prop.name(code)}"))
    }

    /** Set [desc]'s property to [value]. Returns the camera's descriptor after the change. */
    fun setProp(desc: PropDesc, value: Long): PropDesc {
        if (!desc.writable) throw PtpException("${Prop.name(desc.code)} is read-only right now")
        session.transaction(
            Op.SET_DEVICE_PROP_VALUE,
            listOf(desc.code.toLong()),
            DataType.encode(desc.dataType, value),
        ).requireOk("SetDevicePropValue(${Prop.name(desc.code)})")
        return getProp(desc.code)
    }

    /** Read every property the camera advertises. Properties that fail to read are skipped. */
    fun readAllProps(): List<PropDesc> =
        info.deviceProps.mapNotNull { runCatching { getProp(it) }.getOrNull() }

    // ---- live view and recording -----------------------------------------------------------

    fun startLiveView(movieMode: Boolean = true) {
        // Selecting photo/movie live view; not every body exposes this, so failure is tolerated.
        runCatching {
            setProp(getProp(props.liveViewSelector), if (movieMode) 1 else 0)
        }
        val r = session.transaction(Op.NIKON_START_LIVE_VIEW)
        if (r.code != Rc.OK && r.code != Rc.DEVICE_BUSY) r.requireOk("StartLiveView")

        // Wait (up to ~3 s) for the camera to report live view as active.
        repeat(30) {
            val status = runCatching { getProp(props.liveViewStatus).current.num }.getOrNull()
            if (status == null || status == 1L) {
                liveViewRunning = true
                return
            }
            sleep(100)
        }
        throw PtpException("Camera did not enter live view (is the mode switch set to video?)")
    }

    fun stopLiveView() {
        session.transaction(Op.NIKON_END_LIVE_VIEW)
        liveViewRunning = false
    }

    /** One live-view JPEG, or null if the camera is busy and the caller should just retry. */
    fun liveViewFrame(): ByteArray? {
        val r = session.transaction(Op.NIKON_GET_LIVE_VIEW_IMG)
        if (r.code == Rc.DEVICE_BUSY) return null
        r.requireOk("GetLiveViewImg")
        return extractJpeg(r.data ?: return null)
    }

    fun startRecording() {
        session.transaction(Op.NIKON_START_MOVIE_REC).requireOk("StartMovieRec")
        recording = true
    }

    fun stopRecording() {
        session.transaction(Op.NIKON_END_MOVIE_REC).requireOk("EndMovieRec")
        recording = false
    }

    // ---- gallery ----------------------------------------------------------------------------

    /** Handles of every file on every card, walking folders. Cheap: no per-file info calls. */
    fun listHandles(): List<Pair<Long, Long>> {
        val storages = session.transaction(Op.GET_STORAGE_IDS).requireOk("GetStorageIDs")
            .data?.let { PtpReader(it).u32Array() } ?: emptyList()
        val out = ArrayList<Pair<Long, Long>>() // (storageId, handle)
        for (sid in storages) walk(sid, ROOT, 0, out)
        return out
    }

    private fun walk(storage: Long, parent: Long, depth: Int, out: MutableList<Pair<Long, Long>>) {
        if (depth > 6) return
        val folders = handlesIn(storage, CameraObject.FORMAT_ASSOCIATION, parent).toSet()
        for (h in handlesIn(storage, 0, parent)) {
            if (h in folders) walk(storage, h, depth + 1, out) else out.add(storage to h)
        }
    }

    private fun handlesIn(storage: Long, format: Int, parent: Long): List<Long> {
        val r = session.transaction(Op.GET_OBJECT_HANDLES, listOf(storage, format.toLong(), parent))
        if (r.code != Rc.OK) return emptyList()
        return r.data?.let { PtpReader(it).u32Array() } ?: emptyList()
    }

    fun objectInfo(handle: Long): CameraObject {
        val r = session.transaction(Op.GET_OBJECT_INFO, listOf(handle)).requireOk("GetObjectInfo")
        return CameraObject.parse(handle, r.data ?: throw PtpException("No object info"))
    }

    fun thumbnail(handle: Long): ByteArray =
        session.transaction(Op.GET_THUMB, listOf(handle)).requireOk("GetThumb").data ?: ByteArray(0)

    /** Copy a file off the card in chunks so multi-GB clips never sit in memory. */
    fun download(obj: CameraObject, out: OutputStream, onProgress: (Long, Long) -> Unit = { _, _ -> }) {
        val total = if (obj.size == 0xFFFFFFFFL) objectSize64(obj.handle) else obj.size
        var offset = 0L
        while (offset < total) {
            val want = minOf(CHUNK, total - offset)
            val r = if (offset + want <= 0xFFFFFFFFL) {
                session.transaction(Op.GET_PARTIAL_OBJECT, listOf(obj.handle, offset, want))
            } else {
                session.transaction(
                    Op.NIKON_GET_PARTIAL_OBJECT_EX,
                    listOf(obj.handle, offset and 0xFFFFFFFFL, offset ushr 32, want),
                )
            }.requireOk("GetPartialObject")
            val bytes = r.data ?: throw PtpException("Camera returned no data at offset $offset")
            if (bytes.isEmpty()) throw PtpException("Camera returned empty chunk at offset $offset")
            out.write(bytes)
            offset += bytes.size
            onProgress(offset, total)
        }
    }

    private fun objectSize64(handle: Long): Long {
        val r = session.transaction(Op.NIKON_GET_OBJECT_SIZE, listOf(handle)).requireOk("GetObjectSize")
        return PtpReader(r.data ?: throw PtpException("No size returned")).u64()
    }

    // ---- diagnostics ------------------------------------------------------------------------

    /** Plain-text dump of device info and every property: attach this to a bug report. */
    fun diagnosticDump(): String = buildString {
        appendLine("Model: ${info.model}  Firmware: ${info.deviceVersion}  Serial: ${info.serial}")
        appendLine("Vendor ext: ${info.vendorExtensionDesc} (0x${info.vendorExtensionId.toString(16)})")
        appendLine("Operations: " + info.operations.joinToString(" ") { "0x" + it.toString(16) })
        appendLine("Properties:")
        for (d in readAllProps()) {
            append("  ${Prop.name(d.code)} type=0x${d.dataType.toString(16)} rw=${d.writable}")
            append(" cur=${d.current.num} def=${d.default.num}")
            when (val f = d.form) {
                is PropForm.Enum -> append(" enum=" + f.values.joinToString(",") { it.num.toString() })
                is PropForm.Range -> append(" range=${f.min.num}..${f.max.num} step ${f.step.num}")
                PropForm.None -> Unit
            }
            appendLine()
        }
    }

    companion object {
        const val ROOT = 0xFFFFFFFFL
        const val CHUNK = 4L * 1024 * 1024

        /** Cut the JPEG out of a live-view payload, which carries a vendor header before it. */
        fun extractJpeg(payload: ByteArray): ByteArray? {
            var start = -1
            for (i in 0 until payload.size - 2) {
                if (payload[i] == 0xFF.toByte() && payload[i + 1] == 0xD8.toByte() &&
                    payload[i + 2] == 0xFF.toByte()
                ) {
                    start = i
                    break
                }
            }
            if (start < 0) return null
            var end = payload.size
            for (i in payload.size - 2 downTo start + 2) {
                if (payload[i] == 0xFF.toByte() && payload[i + 1] == 0xD9.toByte()) {
                    end = i + 2
                    break
                }
            }
            return payload.copyOfRange(start, end)
        }
    }
}
