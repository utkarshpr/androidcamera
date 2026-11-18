package com.upravind.lumen

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** A tappable idea: apply [look] or switch to [mode]. */
data class Suggestion(val text: String, val look: String? = null, val mode: String? = null)

/** Looks at a small snapshot of the live scene (colour mix and brightness) and suggests a look or mode. */
object Suggest {
    fun from(bmp: Bitmap): Suggestion? {
        val w = bmp.width; val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        var sumL = 0f
        var blueTop = 0; var green = 0; var skin = 0; var warm = 0; var food = 0; var sunset = 0
        for (y in 0 until h) for (x in 0 until w) {
            val c = px[y * w + x]
            val r = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val b = c and 0xFF
            val l = (0.299f * r + 0.587f * g + 0.114f * b) / 255f
            sumL += l
            val mx = max(r, max(g, b)); val mn = min(r, min(g, b))
            val sat = if (mx == 0) 0f else (mx - mn).toFloat() / mx
            val top = y < h / 2
            if (top && b > r + 25 && b > g + 10 && l > 0.3f) blueTop++
            if (g > r + 15 && g > b + 15) green++
            if (r > 95 && g > 40 && b > 20 && r > g && r > b && abs(r - g) > 15 && mx - mn > 15 && r - b > 30) skin++
            if (r > b + 40) warm++
            if (r > g + 30 && g > b + 20 && sat > 0.45f && l in 0.25f..0.8f) food++
            if (top && r > g + 40 && r > b + 60 && l > 0.35f) sunset++
        }
        val n = (w * h).toFloat()
        val avg = sumL / n
        return when {
            avg < 0.02f -> null                                   // pure black = the camera is still starting or covered
            avg < 0.12f -> Suggestion("Dark scene — try Night mode", mode = "NIGHT")
            sunset / n > 0.12f -> Suggestion("Sunset colours — try SUNSET", look = "SUNSET")
            blueTop / n > 0.20f && avg > 0.45f -> Suggestion("Blue sky — try VIVID SKY", look = "VIVID SKY")
            green / n > 0.35f -> Suggestion("Lots of green — try FOREST", look = "FOREST")
            skin / n > 0.14f -> Suggestion("Person in view — try Portrait", mode = "PORTRAIT")
            food / n > 0.35f -> Suggestion("Warm food colours — try FOOD FRESH", look = "FOOD FRESH")
            warm / n > 0.5f && avg < 0.5f -> Suggestion("Orange bulb light — try INDOOR TRUE", look = "INDOOR TRUE")
            else -> null
        }
    }
}
