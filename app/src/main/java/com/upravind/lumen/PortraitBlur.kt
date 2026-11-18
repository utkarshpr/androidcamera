package com.upravind.lumen

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
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
        val buf = mask.buffer
        val px = IntArray(mw * mh)
        var person = 0
        for (i in px.indices) {
            val p = buf.get()
            val t = ((p - 0.35f) / 0.4f).coerceIn(0f, 1f)           // soft edge
            val a = (t * t * (3f - 2f * t) * 255f).toInt()
            if (a > 200) person++
            px[i] = a shl 24
        }
        segmenter.close()
        if (person < mw * mh * 0.01f) return Pair(src, false)       // nobody found
        val maskSmall = Bitmap.createBitmap(mw, mh, Bitmap.Config.ARGB_8888)
        maskSmall.setPixels(px, 0, mw, 0, 0, mw, mh)
        val maskFull = Bitmap.createScaledBitmap(maskSmall, src.width, src.height, true)
        maskSmall.recycle()

        // blurred background: shrink a lot, then enlarge with filtering
        val tiny = Bitmap.createScaledBitmap(src, max(1, src.width / 28), max(1, src.height / 28), true)
        val bg = Bitmap.createScaledBitmap(tiny, src.width, src.height, true)
        tiny.recycle()
        // sharp person: the original, kept only where the mask is opaque
        val fg = src.copy(Bitmap.Config.ARGB_8888, true)
        Canvas(fg).drawBitmap(maskFull, 0f, 0f, Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) })
        maskFull.recycle()
        Canvas(bg).drawBitmap(fg, 0f, 0f, null)
        fg.recycle()
        return Pair(bg, true)
    }
}
