package com.upravind.lumen

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.min

/** Aligns frames to the first one (translation only) and keeps a running per-pixel sum. */
class NightStacker {
    private var w = 0
    private var h = 0
    private var acc: IntArray? = null
    private var refCoarse: IntArray? = null     // luma at 1/16
    private var refFine: IntArray? = null       // luma at 1/4
    var frames = 0
        private set

    private fun luma(px: IntArray, w: Int, h: Int, step: Int): IntArray {
        val sw = w / step; val sh = h / step
        val out = IntArray(sw * sh)
        for (y in 0 until sh) for (x in 0 until sw) {
            val c = px[(y * step) * w + x * step]
            out[y * sw + x] = (((c shr 16) and 0xFF) * 77 + ((c shr 8) and 0xFF) * 150 + (c and 0xFF) * 29) shr 8
        }
        return out
    }

    /** Best (dx, dy) in [-range, range] around (cx, cy) so that cur(x+dx, y+dy) matches ref(x, y). */
    private fun search(ref: IntArray, cur: IntArray, sw: Int, sh: Int, cx: Int, cy: Int, range: Int): Pair<Int, Int> {
        var best = Long.MAX_VALUE
        var bx = cx; var by = cy
        val margin = range + 2 + abs(cx).coerceAtLeast(abs(cy))
        val x0 = min(margin, sw / 4); val x1 = sw - x0
        val y0 = min(margin, sh / 4); val y1 = sh - y0
        for (dy in cy - range..cy + range) for (dx in cx - range..cx + range) {
            var sum = 0L
            var y = y0
            while (y < y1) {
                var x = x0
                while (x < x1) {
                    val sx = x + dx; val sy = y + dy
                    if (sx in 0 until sw && sy in 0 until sh) sum += abs(ref[y * sw + x] - cur[sy * sw + sx])
                    else sum += 255
                    x += 2
                }
                y += 2
            }
            if (sum < best) { best = sum; bx = dx; by = dy }
        }
        return Pair(bx, by)
    }

    fun add(bmp: Bitmap) {
        val bw = bmp.width; val bh = bmp.height
        val px = IntArray(bw * bh)
        bmp.getPixels(px, 0, bw, 0, 0, bw, bh)
        var dx = 0; var dy = 0
        if (acc == null) {
            w = bw; h = bh
            acc = IntArray(w * h * 3)
            refCoarse = luma(px, w, h, 16)
            refFine = luma(px, w, h, 4)
        } else {
            val coarse = luma(px, w, h, 16)
            val (cx, cy) = search(refCoarse!!, coarse, w / 16, h / 16, 0, 0, 6)
            val fine = luma(px, w, h, 4)
            val (fx, fy) = search(refFine!!, fine, w / 4, h / 4, cx * 4, cy * 4, 4)
            dx = fx * 4; dy = fy * 4
        }
        val a = acc!!
        for (y in 0 until h) {
            val sy = (y + dy).coerceIn(0, h - 1)
            for (x in 0 until w) {
                val sx = (x + dx).coerceIn(0, w - 1)
                val c = px[sy * w + sx]
                val i = (y * w + x) * 3
                a[i] += (c shr 16) and 0xFF
                a[i + 1] += (c shr 8) and 0xFF
                a[i + 2] += c and 0xFF
            }
        }
        frames++
    }

    fun result(): Bitmap {
        val a = acc ?: throw IllegalStateException("no frames")
        val n = frames
        val out = IntArray(w * h)
        for (i in 0 until w * h) {
            val j = i * 3
            out[i] = (0xFF shl 24) or ((a[j] / n) shl 16) or ((a[j + 1] / n) shl 8) or (a[j + 2] / n)
        }
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.setPixels(out, 0, w, 0, 0, w, h)
        acc = null
        return bmp
    }
}
