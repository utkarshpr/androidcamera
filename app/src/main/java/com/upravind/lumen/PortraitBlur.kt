package com.upravind.lumen

import android.graphics.Bitmap
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import kotlin.math.max

/** Portrait mode: finds the person (on-device ML Kit) and blurs everything else. Returns [src] unchanged if nobody is found. */
object PortraitBlur {
    fun apply(src: Bitmap): Pair<Bitmap, Boolean> {
        val segmenter = Segmentation.getClient(SelfieSegmenterOptions.Builder().setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE).build())
        val sw = 512; val sh = max(1, 512 * src.height / src.width)
        val small = Bitmap.createScaledBitmap(src, sw, sh, true)
        val mask = Tasks.await(segmenter.process(InputImage.fromBitmap(small, 0)))
        small.recycle()
        val mw = mask.width; val mh = mask.height
        val buf = mask.buffer.asFloatBuffer()      // the mask is one float (0..1 person confidence) per pixel
        val m = FloatArray(mw * mh)
        var person = 0
        for (i in m.indices) {
            val p = buf.get()
            val t = ((p - 0.35f) / 0.4f).coerceIn(0f, 1f)           // soft edge
            m[i] = t * t * (3f - 2f * t)
            if (m[i] > 0.8f) person++
        }
        segmenter.close()
        if (person < mw * mh * 0.01f) return Pair(src, false)       // nobody found

        // blurred background: shrink a lot, then enlarge with filtering
        var step = Bitmap.createScaledBitmap(src, max(1, src.width / 40), max(1, src.height / 40), true)
        while (step.width < src.width) {                           // enlarge in several small steps so the blur is smooth, not blocky
            val nw = minOf(src.width, step.width * 2); val nh = minOf(src.height, step.height * 2)
            val next = Bitmap.createScaledBitmap(step, nw, nh, true)
            step.recycle(); step = next
        }
        val bg = if (step.height != src.height) Bitmap.createScaledBitmap(step, src.width, src.height, true) else step

        // blend pixel by pixel: sharp where the mask says "person", blurred elsewhere (in strips, to save memory)
        val w = src.width; val h = src.height
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val strip = 128
        val a = IntArray(w * strip); val bgPx = IntArray(w * strip); val dst = IntArray(w * strip)
        var y0 = 0
        while (y0 < h) {
            val rows = minOf(strip, h - y0)
            src.getPixels(a, 0, w, 0, y0, w, rows)
            bg.getPixels(bgPx, 0, w, 0, y0, w, rows)
            for (ry in 0 until rows) {
                val fy = ((y0 + ry) + 0.5f) * mh / h - 0.5f
                val iy = fy.toInt().coerceIn(0, mh - 1); val iy2 = (iy + 1).coerceAtMost(mh - 1); val ty = (fy - iy).coerceIn(0f, 1f)
                for (x in 0 until w) {
                    val fx = (x + 0.5f) * mw / w - 0.5f
                    val ix = fx.toInt().coerceIn(0, mw - 1); val ix2 = (ix + 1).coerceAtMost(mw - 1); val tx = (fx - ix).coerceIn(0f, 1f)
                    val mv = (m[iy * mw + ix] * (1 - tx) + m[iy * mw + ix2] * tx) * (1 - ty) + (m[iy2 * mw + ix] * (1 - tx) + m[iy2 * mw + ix2] * tx) * ty
                    val i = ry * w + x
                    val c = a[i]; val d = bgPx[i]
                    val r = (((c shr 16) and 0xFF) * mv + ((d shr 16) and 0xFF) * (1 - mv)).toInt()
                    val g = (((c shr 8) and 0xFF) * mv + ((d shr 8) and 0xFF) * (1 - mv)).toInt()
                    val b = ((c and 0xFF) * mv + (d and 0xFF) * (1 - mv)).toInt()
                    dst[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
            out.setPixels(dst, 0, w, 0, y0, w, rows)
            y0 += rows
        }
        bg.recycle()
        return Pair(out, true)
    }
}
