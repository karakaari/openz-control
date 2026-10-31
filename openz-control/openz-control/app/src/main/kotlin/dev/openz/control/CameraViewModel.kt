package dev.openz.control

import android.app.Application
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.BitmapFactory
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.openz.core.CameraObject
import dev.openz.core.Format
import dev.openz.core.NikonCamera
import dev.openz.core.PropertyMap
import dev.openz.core.PtpException
import dev.openz.core.PtpSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import java.io.IOException
import java.util.concurrent.Executors

enum class Tab { Monitor, Gallery, Debug }

/** One adjustable camera setting as shown in the UI. */
data class Control(
    val code: Int,
    val name: String,
    val current: Long,
    val choices: List<Long>,
    val writable: Boolean,
)

data class GalleryItem(val obj: CameraObject, val thumb: ImageBitmap?)

data class UiState(
    val connected: Boolean = false,
    val busy: Boolean = false,
    val message: String = "Plug your Nikon into the phone with a USB-C data cable.\n" +
        "In the camera menu, set USB connection to MTP/PTP.",
    val notice: String? = null,
    val model: String = "",
    val tab: Tab = Tab.Monitor,
    val frame: ImageBitmap? = null,
    val controls: List<Control> = emptyList(),
    val recording: Boolean = false,
    val battery: String? = null,
    val gallery: List<GalleryItem> = emptyList(),
    val downloadName: String? = null,
    val downloadProgress: Float? = null,
    val diagnostics: String? = null,
)

class CameraViewModel(app: Application) : AndroidViewModel(app) {

    val propMap = PropertyMap()

    private val usb = app.getSystemService(Context.USB_SERVICE) as UsbManager

    // The camera speaks one command at a time, so every camera call runs on this single thread.
    private val io = Executors.newSingleThreadExecutor { Thread(it, "camera-io") }.asCoroutineDispatcher()

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    @Volatile private var camera: NikonCamera? = null
    @Volatile private var transport: UsbPtpTransport? = null
    private var liveJob: Job? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    val device = intent.usbDevice()
                    if (device != null && intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        open(device)
                    } else {
                        abort("USB permission was denied.")
                    }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> abort("Camera unplugged.")
            }
        }
    }

    init {
        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(app, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    // ---- connection -------------------------------------------------------------------------

    fun connect() {
        val s = _state.value
        if (s.connected || s.busy) return
        val device = usb.deviceList.values.firstOrNull { it.vendorId == NIKON_VENDOR_ID }
            ?: return abort("No Nikon camera found. Check the cable and that the camera is switched on.")
        if (usb.hasPermission(device)) {
            open(device)
        } else {
            _state.update { it.copy(busy = true, message = "Waiting for USB permission…") }
            val app = getApplication<Application>()
            val intent = Intent(ACTION_USB_PERMISSION).setPackage(app.packageName)
            val pending = PendingIntent.getBroadcast(app, 0, intent, PendingIntent.FLAG_MUTABLE)
            usb.requestPermission(device, pending)
        }
    }

    private fun open(device: UsbDevice) {
        _state.update { it.copy(busy = true, message = "Connecting…") }
        viewModelScope.launch(io) {
            var opened: UsbPtpTransport? = null
            try {
                opened = UsbPtpTransport.open(usb, device)
                val cam = NikonCamera(PtpSession(opened), propMap)
                val info = cam.connect()
                transport = opened
                camera = cam
                _state.update { it.copy(connected = true, busy = false, model = info.model, message = "") }
                refreshControls(cam)
                startMonitor(cam)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                runCatching { opened?.close() }
                abort(e.message ?: e.toString())
            }
        }
    }

    /** User-initiated, graceful disconnect. */
    fun disconnect() {
        val cam = camera ?: return
        viewModelScope.launch(io) {
            pauseMonitor()
            runCatching { cam.disconnect() }
            reset("Disconnected.")
        }
    }

    /** Abrupt teardown (cable pulled, I/O error). Closes USB immediately so a blocked read returns. */
    private fun abort(message: String) {
        liveJob?.cancel()
        runCatching { transport?.close() }
        reset(message)
    }

    private fun reset(message: String) {
        camera = null
        transport = null
        liveJob = null
        _state.value = UiState(message = message)
    }

    // ---- monitor ----------------------------------------------------------------------------

    private fun startMonitor(cam: NikonCamera) {
        liveJob?.cancel()
        liveJob = viewModelScope.launch(io) {
            try {
                cam.startLiveView(movieMode = true)
                var lastRefresh = System.nanoTime()
                while (isActive) {
                    val jpeg = cam.liveViewFrame()
                    if (jpeg != null) {
                        val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
                        if (bitmap != null) _state.update { it.copy(frame = bitmap.asImageBitmap()) }
                    } else {
                        delay(20)
                    }
                    if (System.nanoTime() - lastRefresh > REFRESH_NANOS) {
                        refreshControls(cam)
                        lastRefresh = System.nanoTime()
                    }
                    yield() // lets queued commands (set ISO, record, ...) run between frames
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: PtpException) {
                notice(e.message ?: "Live view failed")
            } catch (e: IOException) {
                abort("Lost connection to the camera: ${e.message}")
            }
        }
    }

    private suspend fun pauseMonitor() {
        liveJob?.cancelAndJoin()
        liveJob = null
        camera?.let { if (it.liveViewRunning) runCatching { it.stopLiveView() } }
    }

    fun selectTab(tab: Tab) {
        val previous = _state.value.tab
        if (tab == previous) return
        _state.update { it.copy(tab = tab) }
        val cam = camera ?: return
        viewModelScope.launch(io) {
            try {
                if (tab == Tab.Monitor) {
                    startMonitor(cam)
                } else if (previous == Tab.Monitor) {
                    pauseMonitor() // the card and settings are easier to reach with live view off
                    if (tab == Tab.Gallery) loadGallery(cam)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: PtpException) {
                notice(e.message ?: "Camera error")
            } catch (e: IOException) {
                abort("Lost connection to the camera: ${e.message}")
            }
        }
    }

    // ---- controls ---------------------------------------------------------------------------

    private fun refreshControls(cam: NikonCamera) {
        val wanted = listOf(
            propMap.exposureProgram to "Mode",
            propMap.iso to "ISO",
            propMap.shutter to "Shutter",
            propMap.aperture to "Aperture",
            propMap.exposureBias to "EV",
            propMap.whiteBalance to "WB",
        )
        val controls = wanted.mapNotNull { (code, name) ->
            runCatching { cam.getProp(code) }.getOrNull()
                ?.let { Control(code, name, it.current.num, it.choices, it.writable) }
        }
        val battery = runCatching { Format.battery(cam.getProp(propMap.battery).current.num) }.getOrNull()
        _state.update { it.copy(controls = controls, battery = battery) }
    }

    fun setControl(code: Int, value: Long) = launchCamera { cam ->
        cam.setProp(cam.getProp(code), value)
        refreshControls(cam)
    }

    fun toggleRecording() = launchCamera { cam ->
        if (cam.recording) cam.stopRecording() else cam.startRecording()
        _state.update { it.copy(recording = cam.recording) }
    }

    // ---- gallery ----------------------------------------------------------------------------

    private suspend fun loadGallery(cam: NikonCamera) {
        _state.update { it.copy(busy = true, gallery = emptyList()) }
        try {
            val newest = cam.listHandles().takeLast(GALLERY_LIMIT).reversed()
            for ((_, handle) in newest) {
                val obj = cam.objectInfo(handle)
                val thumb = runCatching { cam.thumbnail(handle) }.getOrNull()
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }
                _state.update { it.copy(gallery = it.gallery + GalleryItem(obj, thumb)) }
                yield()
            }
        } finally {
            _state.update { it.copy(busy = false) }
        }
    }

    fun download(item: GalleryItem) = launchCamera { cam ->
        val obj = item.obj
        val app = getApplication<Application>()
        val (collection, mime, folder) = destinationFor(obj.name)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, obj.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, folder)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = app.contentResolver
        val uri = resolver.insert(collection, values) ?: throw PtpException("Could not create a file on the phone")
        _state.update { it.copy(downloadName = obj.name, downloadProgress = 0f) }
        try {
            val out = resolver.openOutputStream(uri) ?: throw PtpException("Could not open the file for writing")
            out.use {
                cam.download(obj, it) { done, total ->
                    _state.update { s -> s.copy(downloadProgress = done.toFloat() / total.coerceAtLeast(1)) }
                }
            }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            notice("Saved ${obj.name} to $folder")
        } catch (e: Exception) {
            resolver.delete(uri, null, null) // don't leave a half-copied file behind
            throw e
        } finally {
            _state.update { it.copy(downloadName = null, downloadProgress = null) }
        }
    }

    private fun destinationFor(name: String): Triple<Uri, String, String> =
        when (val ext = name.substringAfterLast('.', "").lowercase()) {
            "mov", "mp4" -> Triple(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                if (ext == "mp4") "video/mp4" else "video/quicktime",
                "Movies/OpenZControl",
            )
            "jpg", "jpeg" -> Triple(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "image/jpeg", "Pictures/OpenZControl",
            )
            else -> Triple(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI, "application/octet-stream", "Download/OpenZControl",
            )
        }

    // ---- diagnostics ------------------------------------------------------------------------

    fun runDiagnostics() = launchCamera { cam ->
        _state.update { it.copy(busy = true) }
        try {
            val text = cam.diagnosticDump()
            _state.update { it.copy(diagnostics = text) }
        } finally {
            _state.update { it.copy(busy = false) }
        }
    }

    // ---- plumbing ---------------------------------------------------------------------------

    private fun launchCamera(block: suspend CoroutineScope.(NikonCamera) -> Unit) {
        val cam = camera ?: return
        viewModelScope.launch(io) {
            try {
                block(cam)
            } catch (e: CancellationException) {
                throw e
            } catch (e: PtpException) {
                notice(e.message ?: "Camera error")
            } catch (e: IOException) {
                abort("Lost connection to the camera: ${e.message}")
            }
        }
    }

    private fun notice(text: String) = _state.update { it.copy(notice = text) }

    fun clearNotice() = _state.update { it.copy(notice = null) }

    override fun onCleared() {
        runCatching { getApplication<Application>().unregisterReceiver(receiver) }
        liveJob?.cancel()
        runCatching { transport?.close() }
        io.close()
    }

    private fun Intent.usbDevice(): UsbDevice? =
        if (Build.VERSION.SDK_INT >= 33) {
            getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }

    private companion object {
        const val ACTION_USB_PERMISSION = "dev.openz.control.USB_PERMISSION"
        const val NIKON_VENDOR_ID = 0x04B0
        const val REFRESH_NANOS = 1_500_000_000L
        const val GALLERY_LIMIT = 60
    }
}
