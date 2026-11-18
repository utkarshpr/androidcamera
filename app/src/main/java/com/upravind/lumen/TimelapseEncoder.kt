package com.upravind.lumen

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File

/** Turns a list of still pictures into an H.264 MP4 (each picture becomes one video frame at [fps]). */
object TimelapseEncoder {
    fun encode(frames: List<File>, width: Int, height: Int, fps: Int, out: File) {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, 14_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        val muxer = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var track = -1
        var started = false
        val info = MediaCodec.BufferInfo()

        fun drain(untilEnd: Boolean): Boolean {
            while (true) {
                val idx = codec.dequeueOutputBuffer(info, if (untilEnd) 10_000 else 0)
                when {
                    idx == MediaCodec.INFO_TRY_AGAIN_LATER -> { if (!untilEnd) return false }
                    idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { track = muxer.addTrack(codec.outputFormat); muxer.start(); started = true }
                    idx >= 0 -> {
                        val buf = codec.getOutputBuffer(idx)!!
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                        if (info.size > 0 && started) {
                            buf.position(info.offset); buf.limit(info.offset + info.size)
                            muxer.writeSampleData(track, buf, info)
                        }
                        codec.releaseOutputBuffer(idx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return true
                    }
                }
            }
        }

        try {
            val px = IntArray(width * height)
            frames.forEachIndexed { i, file ->
                val bmp = BitmapFactory.decodeFile(file.path) ?: return@forEachIndexed
                val scaled = if (bmp.width != width || bmp.height != height) Bitmap.createScaledBitmap(bmp, width, height, true).also { if (it !== bmp) bmp.recycle() } else bmp
                scaled.getPixels(px, 0, width, 0, 0, width, height)
                scaled.recycle()
                var inIdx = codec.dequeueInputBuffer(10_000)
                while (inIdx < 0) { drain(false); inIdx = codec.dequeueInputBuffer(10_000) }
                val image = codec.getInputImage(inIdx)!!
                fill(image, px, width, height)
                codec.queueInputBuffer(inIdx, 0, width * height * 3 / 2, i * 1_000_000L / fps, 0)
                drain(false)
            }
            var inIdx = codec.dequeueInputBuffer(10_000)
            while (inIdx < 0) { drain(false); inIdx = codec.dequeueInputBuffer(10_000) }
            codec.queueInputBuffer(inIdx, 0, 0, frames.size * 1_000_000L / fps, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            drain(true)
        } finally {
            try { codec.stop() } catch (_: Exception) {}
            codec.release()
            try { if (started) muxer.stop() } catch (_: Exception) {}
            muxer.release()
        }
    }

    /** ARGB pixels → the encoder's YUV420 image planes. */
    private fun fill(image: android.media.Image, px: IntArray, w: Int, h: Int) {
        val yP = image.planes[0]; val uP = image.planes[1]; val vP = image.planes[2]
        val yb = yP.buffer; val ub = uP.buffer; val vb = vP.buffer
        for (y in 0 until h) {
            for (x in 0 until w) {
                val c = px[y * w + x]
                val r = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val b = c and 0xFF
                yb.put(y * yP.rowStride + x * yP.pixelStride, (((66 * r + 129 * g + 25 * b + 128) shr 8) + 16).coerceIn(0, 255).toByte())
                if (y % 2 == 0 && x % 2 == 0) {
                    val u = (((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128).coerceIn(0, 255).toByte()
                    val v = (((112 * r - 94 * g - 18 * b + 128) shr 8) + 128).coerceIn(0, 255).toByte()
                    ub.put((y / 2) * uP.rowStride + (x / 2) * uP.pixelStride, u)
                    vb.put((y / 2) * vP.rowStride + (x / 2) * vP.pixelStride, v)
                }
            }
        }
    }
}
