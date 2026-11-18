package com.upravind.lumen

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.provider.MediaStore
import kotlin.math.ceil
import kotlin.math.min

/** Photos → PDF (A4 pages). [perPage] is 1, 2 or 4 pictures on each page. Saved to Documents/Lumen. */
object PdfExport {
    fun create(context: Context, uris: List<Uri>, perPage: Int): Uri? {
        val pageW = 595; val pageH = 842          // A4 at 72 dpi
        val doc = PdfDocument()
        val cols = if (perPage == 4) 2 else 1
        val rows = if (perPage == 1) 1 else 2
        val margin = 24f; val gap = 12f
        val cellW = (pageW - margin * 2 - gap * (cols - 1)) / cols
        val cellH = (pageH - margin * 2 - gap * (rows - 1)) / rows
        val pages = ceil(uris.size / perPage.toFloat()).toInt()
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        for (p in 0 until pages) {
            val page = doc.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, p + 1).create())
            val c: Canvas = page.canvas
            for (k in 0 until perPage) {
                val idx = p * perPage + k
                if (idx >= uris.size) break
                val bmp = decode(context, uris[idx], 1600) ?: continue
                val col = k % cols; val row = k / cols
                val x0 = margin + col * (cellW + gap); val y0 = margin + row * (cellH + gap)
                val scale = min(cellW / bmp.width, cellH / bmp.height)
                val w = bmp.width * scale; val h = bmp.height * scale
                val dst = RectF(x0 + (cellW - w) / 2, y0 + (cellH - h) / 2, x0 + (cellW + w) / 2, y0 + (cellH + h) / 2)
                c.drawBitmap(bmp, null, dst, paint)
                bmp.recycle()
            }
            doc.finishPage(page)
        }
        val values = ContentValues().apply {
            put(MediaStore.Files.FileColumns.DISPLAY_NAME, "Lumen_${System.currentTimeMillis()}.pdf")
            put(MediaStore.Files.FileColumns.MIME_TYPE, "application/pdf")
            put(MediaStore.Files.FileColumns.RELATIVE_PATH, "Documents/Lumen")
        }
        val out = context.contentResolver.insert(MediaStore.Files.getContentUri("external"), values)
        if (out != null) context.contentResolver.openOutputStream(out)?.use { doc.writeTo(it) }
        doc.close()
        return out
    }

    private fun decode(context: Context, uri: Uri, maxSide: Int): Bitmap? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        var sample = 1
        while (opts.outWidth / sample > maxSide * 2 || opts.outHeight / sample > maxSide * 2) sample *= 2
        val real = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, real) } ?: return null
        return bmp
    }
}
