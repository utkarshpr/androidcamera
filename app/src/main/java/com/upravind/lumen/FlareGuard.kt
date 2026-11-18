package com.upravind.lumen

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import androidx.camera.core.ImageProxy

/**
 * Software mitigation for lens flare / veiling glare (the milky haze and washed-out blacks you get when a bright
 * light hits the lens, typical on phones with glass-heavy lens stacks such as the S22 Ultra).
 *
 * It cannot remove a physical ghost, but it does remove the haze: glare lifts the black level, so we measure how far
 * the darkest part of the picture has been lifted and subtract that "veil", then restore contrast.
 * The strength follows the amount of bright light in the frame, so clean pictures are left alone.
 */
object FlareGuard {
    class Stats(val veil: Float, val brightFraction: Float) {
        /** Show the "flare" warning when blacks are lifted AND a strong light source is in view. */
        val flaring get() = veil > 0.10f && brightFraction > 0.003f
    }

    /** Measure from a luma (Y) plane. Cheap: samples every 8th pixel. */
    fun measure(image: ImageProxy): Stats {
        val plane = image.planes[0]
        val buf = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val hist = IntArray(256)
        var bright = 0
        var n = 0
        var y = 0
        while (y < image.height) {
            var x = 0
            while (x < image.width) {
                val v = buf.get(y * rowStride + x * pixelStride).toInt() and 0xFF
                hist[v]++
                if (v >= 250) bright++
                n++
                x += 8
            }
            y += 8
        }
        return Stats(percentile(hist, n, 0.01f) / 255f, bright.toFloat() / n)
    }

    fun measure(bitmap: Bitmap): Stats {
        val w = 256
        val h = (bitmap.height * w / bitmap.width).coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(bitmap, w, h, true)
        val px = IntArray(w * h)
        small.getPixels(px, 0, w, 0, 0, w, h)
        small.recycle()
        val hist = IntArray(256)
        var bright = 0
        for (c in px) {
            val l = ((0.299f * ((c shr 16) and 0xFF)) + 0.587f * ((c shr 8) and 0xFF) + 0.114f * (c and 0xFF)).toInt()
            hist[l]++
            if (l >= 250) bright++
        }
        return Stats(percentile(hist, px.size, 0.01f) / 255f, bright.toFloat() / px.size)
    }

    private fun percentile(hist: IntArray, n: Int, p: Float): Float {
        val target = (n * p).toInt()
        var sum = 0
        for (i in hist.indices) { sum += hist[i]; if (sum >= target) return i.toFloat() }
        return 0f
    }

    /** Colour matrix that removes the veil. [amount] 0..1 scales it. */
    fun matrix(stats: Stats, amount: Float = 1f): ColorMatrix {
        val bias = if (stats.brightFraction > 0.001f) 0.85f else 0.35f   // a light source in frame → stronger correction
        val veil = (stats.veil * bias * amount).coerceIn(0f, 0.35f)
        val scale = 1f / (1f - veil)
        val t = -255f * veil * scale
        // a little extra contrast brings back the punch that glare took away
        val c = 1f + veil * 0.6f
        val tt = t + (1f - c) * 128f
        return ColorMatrix(floatArrayOf(
            scale * c, 0f, 0f, 0f, tt,
            0f, scale * c, 0f, 0f, tt,
            0f, 0f, scale * c, 0f, tt,
            0f, 0f, 0f, 1f, 0f))
    }

    /** Applies [matrices] in order to [src] and returns a new bitmap. */
    fun render(src: Bitmap, vararg matrices: ColorMatrix): Bitmap {
        val total = ColorMatrix()
        for (m in matrices) total.postConcat(m)
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = ColorMatrixColorFilter(total) }
        Canvas(out).drawBitmap(src, 0f, 0f, paint)
        return out
    }
}
