package dev.openz.core

/*
 * Operation, response and property codes.
 *
 * Standard PTP codes (ISO 15740) are reliable. The Nikon vendor codes (0x90xx / 0x92xx / 0xDxxx)
 * come from public reverse-engineering work (libgphoto2) and have NOT been verified against a Z8 by
 * this project. Anything marked "believed" is the first thing to check if a feature misbehaves.
 */

object Op {
    const val GET_DEVICE_INFO = 0x1001
    const val OPEN_SESSION = 0x1002
    const val CLOSE_SESSION = 0x1003
    const val GET_STORAGE_IDS = 0x1004
    const val GET_OBJECT_HANDLES = 0x1007
    const val GET_OBJECT_INFO = 0x1008
    const val GET_OBJECT = 0x1009
    const val GET_THUMB = 0x100A
    const val DELETE_OBJECT = 0x100B
    const val GET_PARTIAL_OBJECT = 0x101B
    const val GET_DEVICE_PROP_DESC = 0x1014
    const val GET_DEVICE_PROP_VALUE = 0x1015
    const val SET_DEVICE_PROP_VALUE = 0x1016

    // Nikon vendor operations (believed)
    const val NIKON_DEVICE_READY = 0x90C8
    const val NIKON_START_LIVE_VIEW = 0x9201
    const val NIKON_END_LIVE_VIEW = 0x9202
    const val NIKON_GET_LIVE_VIEW_IMG = 0x9203
    const val NIKON_START_MOVIE_REC = 0x920A
    const val NIKON_END_MOVIE_REC = 0x920B
    const val NIKON_GET_OBJECT_SIZE = 0x9421 // 64-bit size for files over 4 GiB (believed)
    const val NIKON_GET_PARTIAL_OBJECT_EX = 0x95C1 // 64-bit offset (believed)
}

object Rc {
    const val OK = 0x2001
    const val GENERAL_ERROR = 0x2002
    const val SESSION_NOT_OPEN = 0x2003
    const val OPERATION_NOT_SUPPORTED = 0x2005
    const val DEVICE_BUSY = 0x2019
    const val SESSION_ALREADY_OPEN = 0x201E
    const val NIKON_NOT_LIVE_VIEW = 0xA00B // believed

    fun name(code: Int): String = when (code) {
        OK -> "OK"
        GENERAL_ERROR -> "General error"
        SESSION_NOT_OPEN -> "Session not open"
        OPERATION_NOT_SUPPORTED -> "Operation not supported"
        0x2006 -> "Parameter not supported"
        0x200F -> "Access denied"
        DEVICE_BUSY -> "Device busy"
        SESSION_ALREADY_OPEN -> "Session already open"
        NIKON_NOT_LIVE_VIEW -> "Not in live view"
        else -> "0x" + code.toString(16).uppercase()
    }
}

object ContainerType {
    const val COMMAND = 1
    const val DATA = 2
    const val RESPONSE = 3
    const val EVENT = 4
}

object DataType {
    const val INT8 = 0x0001
    const val UINT8 = 0x0002
    const val INT16 = 0x0003
    const val UINT16 = 0x0004
    const val INT32 = 0x0005
    const val UINT32 = 0x0006
    const val INT64 = 0x0007
    const val UINT64 = 0x0008
    const val STRING = 0xFFFF

    /** Encode [v] as the given integer type, ready for SetDevicePropValue. */
    fun encode(type: Int, v: Long): ByteArray {
        val w = PtpWriter()
        when (type) {
            INT8, UINT8 -> w.u8(v.toInt())
            INT16, UINT16 -> w.u16(v.toInt())
            INT32, UINT32 -> w.u32(v)
            INT64, UINT64 -> w.u64(v)
            else -> throw PtpException("Cannot encode data type 0x${type.toString(16)}")
        }
        return w.toByteArray()
    }
}

object Prop {
    // Standard PTP device properties
    const val BATTERY_LEVEL = 0x5001
    const val WHITE_BALANCE = 0x5005
    const val F_NUMBER = 0x5007
    const val FOCAL_LENGTH = 0x5008
    const val EXPOSURE_TIME = 0x500D
    const val EXPOSURE_PROGRAM = 0x500E
    const val EXPOSURE_INDEX = 0x500F // ISO
    const val EXPOSURE_BIAS = 0x5010

    // Nikon (believed)
    const val NIKON_LIVE_VIEW_STATUS = 0xD1A2
    const val NIKON_LIVE_VIEW_SELECTOR = 0xD1A6 // 0 = photo, 1 = movie

    fun name(code: Int): String = when (code) {
        BATTERY_LEVEL -> "BatteryLevel"
        WHITE_BALANCE -> "WhiteBalance"
        F_NUMBER -> "FNumber"
        FOCAL_LENGTH -> "FocalLength"
        EXPOSURE_TIME -> "ExposureTime"
        EXPOSURE_PROGRAM -> "ExposureProgramMode"
        EXPOSURE_INDEX -> "ExposureIndex(ISO)"
        EXPOSURE_BIAS -> "ExposureBiasCompensation"
        NIKON_LIVE_VIEW_STATUS -> "Nikon.LiveViewStatus"
        NIKON_LIVE_VIEW_SELECTOR -> "Nikon.LiveViewSelector"
        else -> "0x" + code.toString(16).uppercase().padStart(4, '0')
    }
}

/**
 * Which device property controls which setting. Defaults are the standard PTP properties used for
 * stills. Video mode on newer bodies may use different (Nikon-specific) properties; if so, supply a
 * custom map once the real codes are known (the app's Debug tab dumps every property the camera
 * advertises so they can be found).
 */
data class PropertyMap(
    val iso: Int = Prop.EXPOSURE_INDEX,
    val shutter: Int = Prop.EXPOSURE_TIME,
    val aperture: Int = Prop.F_NUMBER,
    val whiteBalance: Int = Prop.WHITE_BALANCE,
    val exposureProgram: Int = Prop.EXPOSURE_PROGRAM,
    val exposureBias: Int = Prop.EXPOSURE_BIAS,
    val battery: Int = Prop.BATTERY_LEVEL,
    val liveViewStatus: Int = Prop.NIKON_LIVE_VIEW_STATUS,
    val liveViewSelector: Int = Prop.NIKON_LIVE_VIEW_SELECTOR,
)
