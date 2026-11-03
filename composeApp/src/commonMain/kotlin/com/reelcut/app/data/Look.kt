package com.reelcut.app.data

import kotlinx.serialization.Serializable

/**
 * A clip's colour treatment. Each value runs from -1 to 1 and 0 leaves the picture alone.
 * Everything that draws or exports a look (the preview, the Android export, the Windows export)
 * starts from [LookMath.affine], so they all agree.
 */
@Serializable
data class Look(
    val brightness: Float = 0f,
    val contrast: Float = 0f,
    val saturation: Float = 0f,
    val warmth: Float = 0f,
) {
    val isNeutral: Boolean get() = brightness == 0f && contrast == 0f && saturation == 0f && warmth == 0f

    fun coerced(): Look = Look(
        brightness.coerceIn(-1f, 1f), contrast.coerceIn(-1f, 1f), saturation.coerceIn(-1f, 1f), warmth.coerceIn(-1f, 1f),
    )
}

/** A named starting point shown as a chip; the sliders can adjust it afterwards. */
data class LookPreset(val id: String, val label: String, val look: Look)

object LookPresets {
    val all = listOf(
        LookPreset("vivid", "Vivid", Look(contrast = 0.15f, saturation = 0.35f)),
        LookPreset("warm", "Warm", Look(warmth = 0.4f, saturation = 0.1f)),
        LookPreset("cool", "Cool", Look(warmth = -0.4f, contrast = 0.05f)),
        LookPreset("sunset", "Sunset", Look(warmth = 0.6f, saturation = 0.2f, contrast = 0.1f, brightness = -0.03f)),
        LookPreset("fade", "Fade", Look(brightness = 0.08f, contrast = -0.2f, saturation = -0.25f)),
        LookPreset("mono", "Mono", Look(saturation = -1f, contrast = 0.1f)),
        LookPreset("noir", "Noir", Look(saturation = -1f, contrast = 0.35f, brightness = -0.05f)),
    )

    fun byId(id: String?): LookPreset? = all.firstOrNull { it.id == id }
}

/** out = [m] x in + [o] for each colour channel in 0..1; the result is clamped to 0..1 by whoever applies it. */
class ColorAffine(
    /** Row-major 3x3: row 0 makes red, row 1 green, row 2 blue. */
    val m: FloatArray,
    val o: FloatArray,
)

object LookMath {
    private const val LUM_R = 0.2126f
    private const val LUM_G = 0.7152f
    private const val LUM_B = 0.0722f
    private const val WARMTH_GAIN = 0.15f
    private const val BRIGHTNESS_SHIFT = 0.3f

    /**
     * Saturation (blend towards grey), then warmth (more red and less blue), then contrast
     * (stretch around mid-grey), then brightness (shift), folded into one matrix and offset.
     */
    fun affine(look: Look): ColorAffine {
        val l = look.coerced()
        val f = 1f + l.saturation
        val lum = floatArrayOf(LUM_R, LUM_G, LUM_B)
        val gain = floatArrayOf(1f + WARMTH_GAIN * l.warmth, 1f, 1f - WARMTH_GAIN * l.warmth)
        val k = 1f + l.contrast
        val m = FloatArray(9)
        for (row in 0..2) for (col in 0..2) {
            val sat = lum[col] * (1f - f) + if (row == col) f else 0f
            m[row * 3 + col] = k * gain[row] * sat
        }
        val offset = 0.5f * (1f - k) + BRIGHTNESS_SHIFT * l.brightness
        return ColorAffine(m, floatArrayOf(offset, offset, offset))
    }

    /** The look applied to one colour (each channel 0..1), clamped. For tests and sampling. */
    fun apply(a: ColorAffine, r: Float, g: Float, b: Float): FloatArray = FloatArray(3) { row ->
        (a.m[row * 3] * r + a.m[row * 3 + 1] * g + a.m[row * 3 + 2] * b + a.o[row]).coerceIn(0f, 1f)
    }

    /** Compose / Android ColorMatrix: 4x5, row-major, offsets on a 0..255 scale, alpha untouched. */
    fun colorMatrix(a: ColorAffine): FloatArray = floatArrayOf(
        a.m[0], a.m[1], a.m[2], 0f, a.o[0] * 255f,
        a.m[3], a.m[4], a.m[5], 0f, a.o[1] * 255f,
        a.m[6], a.m[7], a.m[8], 0f, a.o[2] * 255f,
        0f, 0f, 0f, 1f, 0f,
    )

    /**
     * Media3 RgbMatrix: a 4x4 matrix in column-major order that is multiplied with (r, g, b, a).
     * Video is opaque (a = 1), so the fourth column carries the offsets.
     */
    fun rgbMatrix(a: ColorAffine): FloatArray {
        val out = FloatArray(16)
        for (row in 0..2) {
            for (col in 0..2) out[col * 4 + row] = a.m[row * 3 + col]
            out[12 + row] = a.o[row]
        }
        out[15] = 1f
        return out
    }

    /**
     * A 3D colour lookup table in the .cube format (red changes fastest), which ffmpeg's lut3d filter
     * applies. Unlike chaining ffmpeg's own colour filters, it clamps once at the end, like the others.
     */
    fun cube(look: Look, size: Int = 33): String {
        val a = affine(look)
        val sb = StringBuilder(size * size * size * 24)
        sb.append("LUT_3D_SIZE ").append(size).append('\n')
        val step = 1f / (size - 1)
        for (b in 0 until size) for (g in 0 until size) for (r in 0 until size) {
            val c = apply(a, r * step, g * step, b * step)
            sb.append(fmt(c[0])).append(' ').append(fmt(c[1])).append(' ').append(fmt(c[2])).append('\n')
        }
        return sb.toString()
    }

    private fun fmt(v: Float): String {
        val n = kotlin.math.round(v * 1_000_000f).toInt()
        val whole = n / 1_000_000
        val frac = (n % 1_000_000).toString().padStart(6, '0')
        return "$whole.$frac"
    }
}
