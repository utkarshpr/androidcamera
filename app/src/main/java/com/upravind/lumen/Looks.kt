package com.upravind.lumen

import android.graphics.ColorMatrix

/** Colour recipe, ported 1:1 from the iPhone app (see tools/gen_from_ios.py). */
data class LookSpec(
    val sat: Float = 1f, val contrast: Float = 1f, val brightness: Float = 0f,
    val kelvin: Float = 6500f,
    val gain: FloatArray = floatArrayOf(1f, 1f, 1f), val bias: FloatArray = floatArrayOf(0f, 0f, 0f),
    val grain: Float = 0f, val vignette: Float = 0f, val halation: Float = 0f, val sepia: Float = 0f,
    /** 1 pixel, 2 frosted glass, 3 CRT, 4 neon-green glow */
    val fx: Int = 0,
    val swatch: Long = 0xFF888888
)

/** A named look: colour matrix (live preview + photo) plus the extra effects drawn onto saved photos. */
class Look(val name: String, val spec: LookSpec) {
    val isOriginal get() = name == "ORIGINAL"

    private val full: ColorMatrix by lazy { build(spec) }

    /** The look's matrix blended with "no change" by [strength] (an exact cross-fade, since matrices are affine). */
    fun matrix(strength: Float = 1f): ColorMatrix {
        if (strength >= 0.99f) return ColorMatrix(full)
        val a = ColorMatrix().array
        val b = full.array
        val out = FloatArray(20) { a[it] + (b[it] - a[it]) * strength.coerceIn(0f, 1f) }
        return ColorMatrix(out)
    }

    private fun build(s: LookSpec): ColorMatrix {
        val m = ColorMatrix()
        // 1. white-balance shift from the Kelvin target (below 6500 = warmer)
        if (s.kelvin != 6500f) {
            val w = (6500f - s.kelvin) / 6500f
            m.postConcat(channelGain(1f + w * 0.55f, 1f + w * 0.05f, 1f - w * 0.55f))
        }
        // 2. saturation, contrast around mid-grey, brightness
        m.postConcat(ColorMatrix().apply { setSaturation(s.sat) })
        val t = (1f - s.contrast) * 128f + s.brightness * 255f
        m.postConcat(ColorMatrix(floatArrayOf(
            s.contrast, 0f, 0f, 0f, t,
            0f, s.contrast, 0f, 0f, t,
            0f, 0f, s.contrast, 0f, t,
            0f, 0f, 0f, 1f, 0f)))
        // 3. per-channel gain and bias (lifted / tinted shadows)
        m.postConcat(ColorMatrix(floatArrayOf(
            s.gain[0], 0f, 0f, 0f, s.bias[0] * 255f,
            0f, s.gain[1], 0f, 0f, s.bias[1] * 255f,
            0f, 0f, s.gain[2], 0f, s.bias[2] * 255f,
            0f, 0f, 0f, 1f, 0f)))
        // 4. sepia
        if (s.sepia > 0f) {
            val sepia = ColorMatrix(floatArrayOf(
                0.393f, 0.769f, 0.189f, 0f, 0f,
                0.349f, 0.686f, 0.168f, 0f, 0f,
                0.272f, 0.534f, 0.131f, 0f, 0f,
                0f, 0f, 0f, 1f, 0f))
            val id = ColorMatrix().array
            val sp = sepia.array
            m.postConcat(ColorMatrix(FloatArray(20) { id[it] + (sp[it] - id[it]) * s.sepia }))
        }
        return m
    }

    private fun channelGain(r: Float, g: Float, b: Float) = ColorMatrix(floatArrayOf(
        r, 0f, 0f, 0f, 0f, 0f, g, 0f, 0f, 0f, 0f, 0f, b, 0f, 0f, 0f, 0f, 0f, 1f, 0f))
}

object Looks {
    val all: List<Look> = LookData.specs.map { (name, spec) -> Look(name, spec) }
    fun byName(name: String): Look = all.firstOrNull { it.name == name } ?: all.first()
}

/** One iPhone-app preset: a look plus the camera settings that go with it. */
data class Preset(
    val cat: String, val name: String, val look: String, val strength: Float,
    val mode: String = "PHOTO", val grid: Int = 1, val frame: Boolean = false, val burst: Boolean = false,
    val manualIso: Int? = null, val manualShutter: Double? = null,
    val ratio: String? = null, val sky: Boolean? = null, val dark: Int? = null,
    val grain: Float = 0f, val glow: Float = 0f, val leak: Int = 0, val dust: Float = 0f,
    val steady: Boolean = false, val bias: Float = 0f
)
