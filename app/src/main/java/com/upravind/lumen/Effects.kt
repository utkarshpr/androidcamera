package com.upravind.lumen

import android.graphics.*
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/** Extra amounts a preset adds on top of the look. */
data class Extras(val grain: Float = 0f, val glow: Float = 0f, val leak: Int = 0, val dust: Float = 0f)

/** Effects that cannot be a colour matrix (vignette, grain, glow, light leaks, dust, pixel / CRT / glass). Drawn on saved photos. */
object Effects {
    fun apply(bmp: Bitmap, spec: LookSpec, extras: Extras) {
        val w = bmp.width; val h = bmp.height
        val canvas = Canvas(bmp)
        val glow = min(1f, spec.halation + extras.glow)
        if (glow > 0f) glow(bmp, canvas, glow, if (spec.halation > 0f) 0xFFFF4030.toInt() else 0xFFFFFFFF.toInt())
        if (extras.leak > 0) leak(canvas, w, h, extras.leak)
        if (extras.dust > 0f) dust(canvas, w, h, extras.dust)
        if (spec.vignette > 0f) vignette(canvas, w, h, spec.vignette)
        when (spec.fx) {
            1 -> pixelate(bmp, canvas)
            2 -> { blurOver(bmp, canvas, 0.7f); canvas.drawColor(0x10FFFFFF); glow(bmp, canvas, 0.5f, 0xFFFFFFFF.toInt()) }
            3 -> { glow(bmp, canvas, 0.45f, 0xFFFFFFFF.toInt()); scanlines(canvas, w, h) }
            4 -> glow(bmp, canvas, 1f, 0xFF40FF70.toInt())
        }
        val grain = min(1f, spec.grain + extras.grain)
        if (grain > 0f) grain(canvas, w, h, grain)
    }

    private fun blurred(src: Bitmap, divisor: Int): Bitmap {
        val small = Bitmap.createScaledBitmap(src, max(1, src.width / divisor), max(1, src.height / divisor), true)
        val up = Bitmap.createScaledBitmap(small, src.width, src.height, true)
        small.recycle()
        return up
    }

    private fun glow(src: Bitmap, canvas: Canvas, amount: Float, tint: Int) {
        val soft = blurred(src, max(8, src.width / 90))
        val p = Paint().apply {
            blendMode = BlendMode.SCREEN
            alpha = (min(1f, amount * 0.8f) * 255).toInt()
            colorFilter = LightingColorFilter(tint, 0)
        }
        canvas.drawBitmap(soft, 0f, 0f, p)
        soft.recycle()
    }

    private fun blurOver(src: Bitmap, canvas: Canvas, mix: Float) {
        val soft = blurred(src, max(10, src.width / 60))
        canvas.drawBitmap(soft, 0f, 0f, Paint().apply { alpha = (mix * 255).toInt() })
        soft.recycle()
    }

    private fun leak(canvas: Canvas, w: Int, h: Int, kind: Int) {
        fun blob(cx: Float, cy: Float, r: Float, color: Int) {
            val p = Paint().apply {
                blendMode = BlendMode.SCREEN
                shader = RadialGradient(cx * w, cy * h, r * max(w, h), color, color and 0x00FFFFFF, Shader.TileMode.CLAMP)
            }
            canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        }
        when (kind) {
            1 -> { blob(0f, 0.85f, 0.75f, 0xD9FF731F.toInt()); blob(1f, 0.1f, 0.5f, 0x8CFF331A.toInt()) }
            2 -> blob(-0.1f, 0.5f, 0.9f, 0xCCFFC74D.toInt())
            else -> { blob(1f, 0.9f, 0.8f, 0xCCE633B3.toInt()); blob(0f, 0.1f, 0.5f, 0x80664DFF.toInt()) }
        }
    }

    private fun dust(canvas: Canvas, w: Int, h: Int, amount: Float) {
        val rnd = Random(7)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        val big = max(w, h)
        val n = (amount * 260).toInt()
        repeat(n) {
            p.alpha = rnd.nextInt(90, 200)
            canvas.drawCircle(rnd.nextFloat() * w, rnd.nextFloat() * h, big / 1800f * rnd.nextFloat().coerceAtLeast(0.3f) * 2.4f, p)
        }
        p.strokeWidth = big / 2200f; p.style = Paint.Style.STROKE
        repeat((amount * 40).toInt()) {   // a few hairs
            p.alpha = 120
            val x = rnd.nextFloat() * w; val y = rnd.nextFloat() * h
            canvas.drawLine(x, y, x + rnd.nextFloat() * big / 60f - big / 120f, y + rnd.nextFloat() * big / 60f, p)
        }
    }

    private fun vignette(canvas: Canvas, w: Int, h: Int, amount: Float) {
        val a = (min(0.85f, amount * 0.55f) * 255).toInt()
        val p = Paint().apply {
            shader = RadialGradient(w / 2f, h / 2f, max(w, h) * 0.75f, intArrayOf(0, 0, Color.argb(a, 0, 0, 0)), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        }
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
    }

    private fun grain(canvas: Canvas, w: Int, h: Int, amount: Float) {
        val tile = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        val rnd = Random(3)
        val px = IntArray(256 * 256) { val v = rnd.nextInt(256); Color.rgb(v, v, v) }
        tile.setPixels(px, 0, 256, 0, 0, 256, 256)
        val p = Paint().apply {
            shader = BitmapShader(tile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
            blendMode = BlendMode.SOFT_LIGHT
            alpha = (min(1f, amount) * 255).toInt()
        }
        val s = max(1f, w / 2400f)
        canvas.save(); canvas.scale(s, s)
        canvas.drawRect(0f, 0f, w / s, h / s, p)
        canvas.restore()
        tile.recycle()
    }

    private fun pixelate(bmp: Bitmap, canvas: Canvas) {
        val block = max(3, max(bmp.width, bmp.height) / 110)
        val small = Bitmap.createScaledBitmap(bmp, max(1, bmp.width / block), max(1, bmp.height / block), false)
        canvas.drawBitmap(small, null, Rect(0, 0, bmp.width, bmp.height), Paint().apply { isFilterBitmap = false })
        small.recycle()
    }

    private fun scanlines(canvas: Canvas, w: Int, h: Int) {
        val line = max(1.5f, max(w, h) / 320f)
        val p = Paint().apply { color = Color.argb(95, 0, 0, 0) }
        var y = 0f
        while (y < h) { canvas.drawRect(0f, y + line, w.toFloat(), y + line * 2, p); y += line * 2 }
    }

    /** Centre-crops to [ratio] ("1:1", "3:2", "16:9", "4:3"); width:height is given for landscape, so portrait photos use the inverse. */
    fun crop(src: Bitmap, ratio: String?): Bitmap {
        val parts = ratio?.split(":") ?: return src
        val a = parts.getOrNull(0)?.toFloatOrNull() ?: return src
        val b = parts.getOrNull(1)?.toFloatOrNull() ?: return src
        val target = if (src.height >= src.width) b / a else a / b       // width / height
        val cur = src.width.toFloat() / src.height
        if (kotlin.math.abs(cur - target) < 0.01f) return src
        val (cw, ch) = if (cur > target) Pair((src.height * target).toInt(), src.height) else Pair(src.width, (src.width / target).toInt())
        val out = Bitmap.createBitmap(src, (src.width - cw) / 2, (src.height - ch) / 2, cw, ch)
        return out
    }

    /** White instant-film border (extra space at the bottom). */
    fun polaroid(src: Bitmap): Bitmap {
        val side = (src.width * 0.05f).toInt()
        val bottom = (src.width * 0.18f).toInt()
        val out = Bitmap.createBitmap(src.width + side * 2, src.height + side + bottom, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(Color.rgb(248, 246, 240))
        c.drawBitmap(src, side.toFloat(), side.toFloat(), null)
        return out
    }
}
