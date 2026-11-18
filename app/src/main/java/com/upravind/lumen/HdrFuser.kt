package com.upravind.lumen

import android.graphics.Bitmap
import kotlin.math.exp

/**
 * Exposure fusion: every pixel is a weighted mix of the dark, normal and bright frames, where the weight is highest
 * for the frame in which that pixel is well exposed (neither crushed nor blown). Pixel-wise, so it needs a steady phone.
 */
object HdrFuser {
    fun fuse(frames: List<Bitmap>): Bitmap {
        val w = frames[0].width; val h = frames[0].height
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val strip = 128
        val bufs = frames.map { IntArray(w * strip) }
        val dst = IntArray(w * strip)
        var y = 0
        while (y < h) {
            val rows = minOf(strip, h - y)
            frames.forEachIndexed { i, f -> f.getPixels(bufs[i], 0, w, 0, y, w, rows) }
            for (p in 0 until w * rows) {
                var sr = 0f; var sg = 0f; var sb = 0f; var sw = 0f
                for (b in bufs) {
                    val c = b[p]
                    val r = ((c shr 16) and 0xFF) / 255f; val g = ((c shr 8) and 0xFF) / 255f; val bl = (c and 0xFF) / 255f
                    val lum = 0.299f * r + 0.587f * g + 0.114f * bl
                    val d = lum - 0.5f
                    val wt = exp(-(d * d) / 0.08f) + 0.002f
                    sr += wt * r; sg += wt * g; sb += wt * bl; sw += wt
                }
                val r = (sr / sw * 255f).toInt().coerceIn(0, 255); val g = (sg / sw * 255f).toInt().coerceIn(0, 255); val b = (sb / sw * 255f).toInt().coerceIn(0, 255)
                dst[p] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
            out.setPixels(dst, 0, w, 0, y, w, rows)
            y += rows
        }
        return out
    }
}
