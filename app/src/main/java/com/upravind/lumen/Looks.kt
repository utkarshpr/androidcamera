package com.upravind.lumen

import android.graphics.ColorMatrix

/** A colour look = one 4x5 colour matrix, used on the live preview and on saved photos. */
data class Look(val name: String, val matrix: ColorMatrix)

object Looks {
    private fun sat(s: Float) = ColorMatrix().apply { setSaturation(s) }

    /** contrast around mid-grey plus brightness offset, per-channel gain (r,g,b). */
    private fun tone(contrast: Float = 1f, brightness: Float = 0f, r: Float = 1f, g: Float = 1f, b: Float = 1f): ColorMatrix {
        val t = (1f - contrast) * 128f + brightness * 255f
        return ColorMatrix(floatArrayOf(
            contrast * r, 0f, 0f, 0f, t,
            0f, contrast * g, 0f, 0f, t,
            0f, 0f, contrast * b, 0f, t,
            0f, 0f, 0f, 1f, 0f))
    }

    private fun look(name: String, saturation: Float = 1f, contrast: Float = 1f, brightness: Float = 0f,
                     r: Float = 1f, g: Float = 1f, b: Float = 1f): Look {
        val m = ColorMatrix(sat(saturation))
        m.postConcat(tone(contrast, brightness, r, g, b))
        return Look(name, m)
    }

    val all: List<Look> = listOf(
        Look("ORIGINAL", ColorMatrix()),
        look("VIVID", saturation = 1.3f, contrast = 1.1f),
        look("WARM", saturation = 1.1f, r = 1.07f, b = 0.9f),
        look("COOL", saturation = 1.05f, r = 0.92f, b = 1.08f),
        look("MONO", saturation = 0f, contrast = 1.3f),
        look("VINTAGE", saturation = 0.8f, contrast = 0.95f, brightness = 0.03f, r = 1.08f, g = 1.0f, b = 0.82f),
        look("FOOD", saturation = 1.25f, contrast = 1.08f, brightness = 0.02f, r = 1.05f, b = 0.95f),
        look("SUNSET", saturation = 1.3f, contrast = 1.1f, r = 1.1f, g = 0.97f, b = 0.85f),
        look("TEAL ORANGE", saturation = 1.2f, contrast = 1.15f, r = 1.08f, g = 0.98f, b = 1.02f),
        look("HIGH KEY", saturation = 0.95f, contrast = 0.9f, brightness = 0.1f),
        // Indoor: bulbs make everything orange — pull red down / blue up, then lift the dim room.
        look("INDOOR TRUE", saturation = 1.05f, contrast = 1.06f, brightness = 0.05f, r = 0.88f, g = 1.0f, b = 1.12f),
        look("INDOOR COZY", saturation = 1.08f, contrast = 1.04f, brightness = 0.06f, r = 0.96f, g = 1.0f, b = 1.04f),
        look("MATTE", saturation = 0.9f, contrast = 0.88f, brightness = 0.05f),
        look("DRAMA", saturation = 0.7f, contrast = 1.4f, brightness = -0.04f)
    )
}
