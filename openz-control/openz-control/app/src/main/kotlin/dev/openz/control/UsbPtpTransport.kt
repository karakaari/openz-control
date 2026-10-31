package dev.openz.control

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import dev.openz.core.PtpTransport
import java.io.IOException

/** PTP over a USB still-image-class interface: bulk OUT for commands, bulk IN for data/responses. */
class UsbPtpTransport private constructor(
    private val connection: UsbDeviceConnection,
    private val iface: UsbInterface,
    private val bulkIn: UsbEndpoint,
    private val bulkOut: UsbEndpoint,
) : PtpTransport {

    private val readBuffer = ByteArray(CHUNK)

    @Volatile
    private var closed = false

    override fun write(data: ByteArray) {
        var offset = 0
        while (offset < data.size) {
            val n = minOf(CHUNK, data.size - offset)
            val sent = connection.bulkTransfer(bulkOut, data, offset, n, WRITE_TIMEOUT_MS)
            if (sent < 0) throw IOException("USB write failed")
            offset += sent
        }
        // A transfer that ends exactly on a packet boundary needs a zero-length packet to finish it.
        if (data.isNotEmpty() && data.size % bulkOut.maxPacketSize == 0) {
            connection.bulkTransfer(bulkOut, ByteArray(0), 0, 0, WRITE_TIMEOUT_MS)
        }
    }

    override fun read(maxLength: Int): ByteArray {
        val n = connection.bulkTransfer(bulkIn, readBuffer, minOf(maxLength, CHUNK), READ_TIMEOUT_MS)
        if (n < 0) throw IOException("USB read failed or timed out")
        return readBuffer.copyOf(n)
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { connection.releaseInterface(iface) }
        runCatching { connection.close() }
    }

    companion object {
        // Older Android versions cap a single bulk transfer at 16 KiB, so never ask for more.
        private const val CHUNK = 16 * 1024
        private const val WRITE_TIMEOUT_MS = 5_000
        private const val READ_TIMEOUT_MS = 8_000 // live view can stall briefly while the camera is busy

        /** Open the first still-image (PTP) interface on [device]. The app must already hold permission. */
        fun open(manager: UsbManager, device: UsbDevice): UsbPtpTransport {
            val iface = (0 until device.interfaceCount)
                .map { device.getInterface(it) }
                .firstOrNull { it.interfaceClass == UsbConstants.USB_CLASS_STILL_IMAGE }
                ?: throw IOException(
                    "The camera has no PTP interface. Set USB connection to MTP/PTP in the camera menu.",
                )

            val bulk = (0 until iface.endpointCount)
                .map { iface.getEndpoint(it) }
                .filter { it.type == UsbConstants.USB_ENDPOINT_XFER_BULK }
            val bulkIn = bulk.firstOrNull { it.direction == UsbConstants.USB_DIR_IN }
                ?: throw IOException("PTP interface is missing a bulk IN endpoint")
            val bulkOut = bulk.firstOrNull { it.direction == UsbConstants.USB_DIR_OUT }
                ?: throw IOException("PTP interface is missing a bulk OUT endpoint")

            val connection = manager.openDevice(device) ?: throw IOException("Could not open the USB device")
            if (!connection.claimInterface(iface, true)) {
                connection.close()
                throw IOException("Could not claim the camera's USB interface (another app using it?)")
            }
            return UsbPtpTransport(connection, iface, bulkIn, bulkOut)
        }
    }
}
