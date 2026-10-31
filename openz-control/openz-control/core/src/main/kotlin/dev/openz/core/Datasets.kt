package dev.openz.core

data class DeviceInfo(
    val standardVersion: Int,
    val vendorExtensionId: Long,
    val vendorExtensionVersion: Int,
    val vendorExtensionDesc: String,
    val functionalMode: Int,
    val operations: List<Int>,
    val events: List<Int>,
    val deviceProps: List<Int>,
    val captureFormats: List<Int>,
    val imageFormats: List<Int>,
    val manufacturer: String,
    val model: String,
    val deviceVersion: String,
    val serial: String,
) {
    companion object {
        fun parse(data: ByteArray): DeviceInfo {
            val r = PtpReader(data)
            return DeviceInfo(
                standardVersion = r.u16(),
                vendorExtensionId = r.u32(),
                vendorExtensionVersion = r.u16(),
                vendorExtensionDesc = r.string(),
                functionalMode = r.u16(),
                operations = r.u16Array(),
                events = r.u16Array(),
                deviceProps = r.u16Array(),
                captureFormats = r.u16Array(),
                imageFormats = r.u16Array(),
                manufacturer = r.string(),
                model = r.string(),
                deviceVersion = r.string(),
                serial = r.string(),
            )
        }
    }
}

sealed class PropForm {
    object None : PropForm()
    data class Range(val min: PropValue, val max: PropValue, val step: PropValue) : PropForm()
    data class Enum(val values: List<PropValue>) : PropForm()
}

/** Device property descriptor: current value plus what values the camera will accept. */
data class PropDesc(
    val code: Int,
    val dataType: Int,
    val writable: Boolean,
    val default: PropValue,
    val current: PropValue,
    val form: PropForm,
) {
    /** Values the camera will accept: its explicit list, or a range expanded step by step. */
    val choices: List<Long>
        get() = when (val f = form) {
            is PropForm.Enum -> f.values.map { it.num }
            is PropForm.Range -> {
                val step = f.step.num
                if (step > 0 && (f.max.num - f.min.num) / step <= 400) {
                    generateSequence(f.min.num) { it + step }.takeWhile { it <= f.max.num }.toList()
                } else {
                    emptyList()
                }
            }
            PropForm.None -> emptyList()
        }

    companion object {
        fun parse(data: ByteArray): PropDesc {
            val r = PtpReader(data)
            val code = r.u16()
            val type = r.u16()
            val getSet = r.u8()
            val default = r.value(type)
            val current = r.value(type)
            val form = when (r.u8()) {
                1 -> PropForm.Range(r.value(type), r.value(type), r.value(type))
                2 -> {
                    val n = r.u16()
                    PropForm.Enum(List(n) { r.value(type) })
                }
                else -> PropForm.None
            }
            return PropDesc(code, type, getSet == 1, default, current, form)
        }
    }
}

data class CameraObject(
    val handle: Long,
    val storageId: Long,
    val format: Int,
    val size: Long,
    val name: String,
    val captureDate: String,
    val width: Long,
    val height: Long,
) {
    val isVideo: Boolean
        get() = format == FORMAT_MOV || format == FORMAT_MP4 || name.uppercase().let {
            it.endsWith(".MOV") || it.endsWith(".MP4") || it.endsWith(".MXF")
        }

    companion object {
        const val FORMAT_ASSOCIATION = 0x3001
        const val FORMAT_MOV = 0x300D
        const val FORMAT_MP4 = 0xB982

        fun parse(handle: Long, data: ByteArray): CameraObject {
            val r = PtpReader(data)
            val storage = r.u32()
            val format = r.u16()
            r.u16() // protection status
            val size = r.u32()
            r.u16() // thumb format
            r.u32() // thumb size
            r.u32() // thumb width
            r.u32() // thumb height
            val width = r.u32()
            val height = r.u32()
            r.u32() // bit depth
            r.u32() // parent
            r.u16() // association type
            r.u32() // association desc
            r.u32() // sequence number
            val name = r.string()
            val date = r.string()
            return CameraObject(handle, storage, format, size, name, date, width, height)
        }
    }
}
