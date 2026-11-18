package com.upravind.lumen

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraConstrainedHighSpeedCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.util.Range
import android.util.Size
import android.view.Surface
import java.io.File
import java.nio.ByteBuffer

/**
 * True slow motion through the phone's high-speed Camera2 session (1080p at 120 fps on the S22 Ultra).
 * CameraX cannot do this on this phone, so while the SLOW-MO mode is open this class owns the camera.
 * The raw file is recorded at the real high frame rate, then re-timed 4x slower (no re-encode) so it plays as slow motion.
 */
class SlowMoCamera2(private val context: Context, private val handler: Handler) {
    private val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val cameraId = "0"
    private var device: CameraDevice? = null
    private var session: CameraConstrainedHighSpeedCaptureSession? = null
    private var recorder: MediaRecorder? = null
    private var previewSurface: Surface? = null
    private var rawFile: File? = null
    private var recording = false

    var size = Size(1920, 1080); private set
    var fps = 120; private set
    private var range = Range(120, 120)

    fun supported(): Boolean = try {
        manager.getCameraCharacteristics(cameraId).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)?.highSpeedVideoSizes?.isNotEmpty() == true
    } catch (e: Throwable) { false }

    private fun pickConfig() {
        val map = manager.getCameraCharacteristics(cameraId).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
        val sizes = map.highSpeedVideoSizes.toList()
        size = sizes.firstOrNull { it.width == 1920 && it.height == 1080 } ?: sizes.maxByOrNull { it.width * it.height }!!
        val ranges = map.getHighSpeedVideoFpsRangesFor(size).toList()
        range = ranges.filter { it.lower == it.upper }.maxByOrNull { it.upper } ?: ranges.maxByOrNull { it.upper }!!
        fps = range.upper
    }

    /** Opens the camera and shows the live preview on [texture]. */
    @SuppressLint("MissingPermission")
    fun start(texture: SurfaceTexture, onPreview: () -> Unit, onError: (String) -> Unit) {
        try {
            pickConfig()
            texture.setDefaultBufferSize(size.width, size.height)
            previewSurface = Surface(texture)
            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) { device = camera; newSession(onPreview, onError) }
                override fun onDisconnected(camera: CameraDevice) { camera.close(); device = null }
                override fun onError(camera: CameraDevice, error: Int) { camera.close(); device = null; onError("camera error $error") }
            }, handler)
        } catch (e: Throwable) { onError(e.message ?: "slow motion failed") }
    }

    private fun prepareRecorder() {
        val f = File(context.cacheDir, "slow_raw.mp4"); f.delete(); rawFile = f
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
        r.setVideoSource(MediaRecorder.VideoSource.SURFACE)
        r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        r.setOutputFile(f.path)
        r.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
        r.setVideoEncodingBitRate(30_000_000)
        r.setVideoFrameRate(fps)
        r.setVideoSize(size.width, size.height)
        r.setOrientationHint(90)
        r.prepare()
        recorder = r
    }

    private fun newSession(onPreview: () -> Unit, onError: (String) -> Unit) {
        val dev = device ?: return
        try {
            prepareRecorder()
            val rs = recorder!!.surface
            dev.createConstrainedHighSpeedCaptureSession(listOf(previewSurface!!, rs), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    val hs = s as CameraConstrainedHighSpeedCaptureSession
                    session = hs
                    try {
                        val b = dev.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
                        b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, range)
                        b.addTarget(previewSurface!!); b.addTarget(rs)
                        hs.setRepeatingBurst(hs.createHighSpeedRequestList(b.build()), null, handler)
                        onPreview()
                    } catch (e: Throwable) { onError(e.message ?: "slow motion failed") }
                }
                override fun onConfigureFailed(s: CameraCaptureSession) { onError("The camera refused the high-speed session") }
            }, handler)
        } catch (e: Throwable) { onError(e.message ?: "slow motion failed") }
    }

    fun startRecording(): Boolean = try { recorder!!.start(); recording = true; true } catch (e: Throwable) { false }

    /** Stops, re-times the clip 4x slower and returns the finished file (or null). Then re-opens the preview session. */
    fun stopRecording(onPreview: () -> Unit, onError: (String) -> Unit): File? {
        var out: File? = null
        try {
            if (recording) { recorder?.stop(); recording = false }
            session?.stopRepeating(); session?.close(); session = null
            recorder?.release(); recorder = null
            val raw = rawFile
            if (raw != null && raw.length() > 0) out = retime(raw, fps / 30f)
        } catch (e: Throwable) { onError(e.message ?: "could not finish the clip") }
        newSession(onPreview, onError)
        return out
    }

    /** Copies the video samples with timestamps stretched by [factor], so 120 fps footage plays at 30 fps (4x slower). */
    private fun retime(raw: File, factor: Float): File {
        val out = File(context.cacheDir, "slow_out.mp4"); out.delete()
        val ex = MediaExtractor(); ex.setDataSource(raw.path)
        var track = -1
        for (i in 0 until ex.trackCount) if (ex.getTrackFormat(i).getString(android.media.MediaFormat.KEY_MIME)?.startsWith("video/") == true) { track = i; break }
        if (track < 0) { ex.release(); return raw }
        ex.selectTrack(track)
        val muxer = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        muxer.setOrientationHint(90)
        val mt = muxer.addTrack(ex.getTrackFormat(track))
        muxer.start()
        val buf = ByteBuffer.allocate(4 * 1024 * 1024)
        val info = MediaCodec.BufferInfo()
        while (true) {
            buf.clear()
            val n = ex.readSampleData(buf, 0)
            if (n < 0) break
            info.set(0, n, (ex.sampleTime * factor).toLong(), if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
            muxer.writeSampleData(mt, buf, info)
            ex.advance()
        }
        muxer.stop(); muxer.release(); ex.release()
        return out
    }

    fun close() {
        try { if (recording) recorder?.stop() } catch (_: Throwable) {}
        recording = false
        try { session?.stopRepeating() } catch (_: Throwable) {}
        try { session?.close() } catch (_: Throwable) {}
        session = null
        try { recorder?.release() } catch (_: Throwable) {}
        recorder = null
        try { device?.close() } catch (_: Throwable) {}
        device = null
        try { previewSurface?.release() } catch (_: Throwable) {}
        previewSurface = null
    }
}
