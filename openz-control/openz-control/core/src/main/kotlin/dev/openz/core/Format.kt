package dev.openz.core

import java.util.Locale

/** Human-readable labels for raw property values. */
object Format {
    private fun trim(v: Double): String =
        String.format(Locale.ROOT, "%.1f", v).removeSuffix(".0")

    /**
     * Shutter speed. Standard PTP is 0.1 ms units (125 = 1/80 s). Nikon bodies often pack it as
     * numerator in the high 16 bits and denominator in the low 16 (0x0001003C = 1/60 s). Packed
     * values are always above 0xFFFF, so the two forms cannot be confused.
     */
    fun shutter(raw: Long): String {
        if (raw == 0xFFFFFFFFL) return "Bulb"
        if (raw == 0xFFFFFFFEL) return "Time"
        if (raw > 0xFFFFL) {
            val num = raw shr 16
            val den = raw and 0xFFFF
            if (den == 0L) return "?"
            if (den == 1L) return "$num\""
            if (num == 1L) return "1/$den"
            val secs = num.toDouble() / den
            return if (secs >= 1.0) trim(secs) + "\"" else "$num/$den"
        }
        if (raw == 0L) return "?"
        val secs = raw / 10000.0
        return if (secs >= 1.0) trim(secs) + "\"" else "1/" + Math.round(1.0 / secs)
    }

    /** F-number is stored in hundredths (280 = f/2.8). */
    fun aperture(raw: Long): String = "f/" + trim(raw / 100.0)

    fun iso(raw: Long): String = raw.toString()

    /** Exposure compensation is stored in thousandths of an EV. */
    fun exposureBias(raw: Long): String {
        val ev = raw / 1000.0
        val s = String.format(Locale.ROOT, "%.1f", ev)
        return if (ev > 0) "+$s" else s
    }

    fun whiteBalance(raw: Long): String = when (raw.toInt()) {
        0x0002 -> "Auto"
        0x0004 -> "Daylight"
        0x0005 -> "Fluorescent"
        0x0006 -> "Incandescent"
        0x0007 -> "Flash"
        0x8010 -> "Cloudy"
        0x8011 -> "Shade"
        0x8012 -> "Preset"
        else -> "WB 0x" + raw.toString(16)
    }

    fun exposureProgram(raw: Long): String = when (raw.toInt()) {
        1 -> "M"
        2 -> "P"
        3 -> "A"
        4 -> "S"
        else -> "Mode $raw"
    }

    fun battery(raw: Long): String = "$raw%"

    /** Label for a value of the property with the given [code]. */
    fun forProp(code: Int, raw: Long, map: PropertyMap = PropertyMap()): String = when (code) {
        map.iso -> iso(raw)
        map.shutter -> shutter(raw)
        map.aperture -> aperture(raw)
        map.whiteBalance -> whiteBalance(raw)
        map.exposureProgram -> exposureProgram(raw)
        map.exposureBias -> exposureBias(raw)
        map.battery -> battery(raw)
        else -> raw.toString()
    }
}
