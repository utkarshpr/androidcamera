package com.upravind.lumen

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.hardware.camera2.CaptureRequest
import android.net.Uri
import android.provider.MediaStore
import android.util.Range
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.content.PermissionChecker
import androidx.lifecycle.LifecycleOwner
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/** Owns the CameraX session: preview, photo, video, zoom/lens, manual controls and the live flare detector. */
@OptIn(ExperimentalCamera2Interop::class)
class CameraController(private val context: Context) {
    private val mainExecutor = ContextCompat.getMainExecutor(context)
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val processExecutor = Executors.newSingleThreadExecutor()

    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null

    // ---- observable state for the UI ----
    var message by mutableStateOf<String?>(null)
    var isRecording by mutableStateOf(false)
    var busy by mutableStateOf(false)
    var flareWarning by mutableStateOf(false)
    var showHistogram by mutableStateOf(false)
    var peaking by mutableStateOf(false)
    var zebra by mutableStateOf(false)
    var showLevel by mutableStateOf(false)
    /** 0 auto, then daylight, cloudy, incandescent, fluorescent, shade */
    var wbMode by mutableStateOf(0)
    var histogram by mutableStateOf(FloatArray(64))
    var videoUhd by mutableStateOf(true)
    var video60 by mutableStateOf(false)
    var videoHdr by mutableStateOf(false)
    var fpsRanges by mutableStateOf("")
    /** The S22 Ultra only offers up to 30 fps to other apps, so the 60 fps chip is hidden unless the camera reports a 60 fps range. */
    val supports60 get() = fpsRanges.split(", ").any { (it.substringAfter("-").toIntOrNull() ?: 0) >= 60 }

    /** Applies the video options by rebuilding the camera session. */
    fun videoOptionsChanged() { val o = boundOwner; val v = boundView; if (o != null && v != null) bind(o, v) }
    var zoom by mutableStateOf(1f)
    var minZoom by mutableStateOf(0.6f)
    var maxZoom by mutableStateOf(10f)

    // ---- settings ----
    var look: Look = Looks.all.first()
    var strength = 1f
    var extras by mutableStateOf(Extras())
    var ratio: String? = null          // "1:1", "3:2", "16:9"… null = camera default (4:3)
    var frame = false
    var burst by mutableStateOf(false)
    var steady = false
    var flareGuard = true
    var flareStrength = 1f

    // manual
    var manual by mutableStateOf(false)
    var iso by mutableStateOf(100)
    var shutterSeconds by mutableStateOf(1.0 / 120)
    var focusManual by mutableStateOf(false)
    var focusDiopters by mutableStateOf(0f)
    var exposureIndex by mutableStateOf(0)

    @Volatile private var autoIso = 100
    @Volatile private var autoShutter = 1.0 / 120

    /** Turns manual on/off. Turning it on starts from whatever the auto exposure had just chosen. */
    fun setManualMode(on: Boolean) {
        if (on) { iso = autoIso; shutterSeconds = autoShutter.coerceIn(1.0 / 8000, 1.0) }
        manual = on
        applyManual()
    }

    var frontFacing by mutableStateOf(false)

    /** Switches between the back camera and the selfie camera. */
    fun flipCamera() {
        frontFacing = !frontFacing
        rawMode = false
        zoom = 1f
        videoOptionsChanged()      // rebinds the session with the other camera
    }

    private var boundOwner: LifecycleOwner? = null
    private var boundView: PreviewView? = null
    /** In video mode only preview + video are bound: the phone only offers 60 fps / HDR with fewer streams. */
    var videoOnly by mutableStateOf(false)
    fun setVideoMode(on: Boolean) { videoOnly = on; if (on && rawMode) rawMode = false; videoOptionsChanged() }
    var rawMode by mutableStateOf(false)
    var rawSupported by mutableStateOf(false)

    /** RAW (DNG + JPEG). Uses only preview + photo streams: the phone cannot run RAW together with video / analysis streams. */
    fun setRaw(on: Boolean) {
        if (on && !rawSupported) { message = "RAW isn't available on this camera"; return }
        rawMode = on
        if (on) { nightMode = false; flareWatch = false } else flareWatch = true
        val o = boundOwner; val v = boundView
        if (o != null && v != null) bind(o, v)
    }
    private var flareWatch = true

    fun bind(owner: LifecycleOwner, view: PreviewView) {
        boundOwner = owner; boundView = view
        ProcessCameraProvider.getInstance(context).addListener({
            val p = provider ?: ProcessCameraProvider.getInstance(context).get().also { provider = it }
            val previewBuilder = Preview.Builder()
            val aeWatcher = object : android.hardware.camera2.CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(session: android.hardware.camera2.CameraCaptureSession, request: CaptureRequest, result: android.hardware.camera2.TotalCaptureResult) {
                    result.get(android.hardware.camera2.CaptureResult.SENSOR_SENSITIVITY)?.let { autoIso = it }
                    result.get(android.hardware.camera2.CaptureResult.SENSOR_EXPOSURE_TIME)?.let { autoShutter = it / 1e9 }
                }
            }
            // Remember what auto exposure is doing, so MANUAL starts from a correctly exposed picture instead of a black one.
            androidx.camera.camera2.interop.Camera2Interop.Extender(previewBuilder).setSessionCaptureCallback(aeWatcher)
            if (videoHdr && !rawMode) previewBuilder.setDynamicRange(androidx.camera.core.DynamicRange.HLG_10_BIT)
            val preview = previewBuilder.build().also { it.setSurfaceProvider(view.surfaceProvider) }
            val captureBuilder = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            if (rawMode) captureBuilder.setOutputFormat(ImageCapture.OUTPUT_FORMAT_RAW_JPEG)
            val capture = captureBuilder.build()
            val wanted = if (videoUhd) Quality.UHD else Quality.FHD
            val recorder = Recorder.Builder().setQualitySelector(QualitySelector.from(wanted, androidx.camera.video.FallbackStrategy.lowerQualityOrHigherThan(Quality.HD))).build()
            val videoBuilder = VideoCapture.Builder(recorder)
            if (video60) videoBuilder.setTargetFrameRate(Range(60, 60))
            if (videoHdr) videoBuilder.setDynamicRange(androidx.camera.core.DynamicRange.HLG_10_BIT)
            val video = videoBuilder.build()
            var frame = 0
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .also { androidx.camera.camera2.interop.Camera2Interop.Extender(it).setSessionCaptureCallback(aeWatcher) }
                .build()
            analysis.setAnalyzer(analysisExecutor) { img: ImageProxy ->
                frame++
                if (flareWatch && frame % 10 == 0) {
                    val flaring = FlareGuard.measure(img).flaring
                    mainExecutor.execute { if (flareWarning != flaring) flareWarning = flaring }
                }
                if (showHistogram && frame % 4 == 0) {
                    val h = FlareGuard.histogram(img)
                    mainExecutor.execute { histogram = h }
                }
                img.close()
            }
            val selector = if (frontFacing) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
            try {
                p.unbindAll()
                camera = if (rawMode) p.bindToLifecycle(owner, selector, preview, capture)
                         else if (videoOnly) p.bindToLifecycle(owner, selector, preview, video)
                         else p.bindToLifecycle(owner, selector, preview, capture, video, analysis)
                fpsRanges = camera!!.cameraInfo.supportedFrameRateRanges.joinToString { "${it.lower}-${it.upper}" }
                rawSupported = ImageCapture.getImageCaptureCapabilities(camera!!.cameraInfo).supportedOutputFormats
                    .contains(ImageCapture.OUTPUT_FORMAT_RAW_JPEG)
                imageCapture = capture
                videoCapture = video
                camera?.cameraInfo?.zoomState?.observe(owner) { z ->
                    zoom = z.zoomRatio; minZoom = z.minZoomRatio; maxZoom = z.maxZoomRatio
                }
                zoomTo(1f)
                applyManual()
            } catch (e: Exception) {
                message = "Camera error: ${e.message}"
                if (rawMode) { rawMode = false; message = "RAW can't run on this camera setup (${e.message})"; bind(owner, view) }
                else if (videoHdr || video60) { videoHdr = false; video60 = false; message = "This HDR / 60 fps setup isn't supported with photo — back to standard"; bind(owner, view) }
            }
        }, mainExecutor)
    }

    /** Lens buttons: 0.6× ultra-wide, 1× main, 3× tele, 10× periscope on the S22 Ultra (the phone switches the real lens). */
    fun zoomTo(ratio: Float) {
        val r = ratio.coerceIn(minZoom, maxZoom)
        camera?.cameraControl?.setZoomRatio(r)
    }

    var focusLocked by mutableStateOf(false)

    /** Long press: lock focus and exposure on that spot until the next tap. */
    fun lockFocus(view: PreviewView, x: Float, y: Float) {
        val point = view.meteringPointFactory.createPoint(x, y)
        camera?.cameraControl?.startFocusAndMetering(androidx.camera.core.FocusMeteringAction.Builder(point).disableAutoCancel().build())
        focusLocked = true
        message = "Focus & exposure locked — tap to unlock"
    }

    fun tapToFocus(view: PreviewView, x: Float, y: Float) {
        if (focusLocked) { camera?.cameraControl?.cancelFocusAndMetering(); focusLocked = false; message = "Unlocked" }
        val point = view.meteringPointFactory.createPoint(x, y)
        val action = androidx.camera.core.FocusMeteringAction.Builder(point).build()
        camera?.cameraControl?.startFocusAndMetering(action)
    }

    // ------------------------------------------------------------------ manual controls

    /** Pushes manual ISO / shutter / focus / EV to the camera through Camera2 interop. */
    fun applyManual() {
        val cam = camera ?: return
        val builder = CaptureRequestOptions.Builder()
        if (manual) {
            val info = Camera2CameraInfo.from(cam.cameraInfo)
            val isoRange = info.getCameraCharacteristic(android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
                ?: Range(50, 3200)
            val expRange = info.getCameraCharacteristic(android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
                ?: Range(100_000L, 1_000_000_000L)
            builder.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            builder.setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, iso.coerceIn(isoRange.lower, isoRange.upper))
            val ns = (shutterSeconds * 1e9).toLong().coerceIn(expRange.lower, expRange.upper)
            builder.setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, ns)
        } else {
            builder.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
        }
        val awb = when (wbMode) { 1 -> CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT; 2 -> CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT
            3 -> CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT; 4 -> CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT; 5 -> CaptureRequest.CONTROL_AWB_MODE_SHADE
            else -> CaptureRequest.CONTROL_AWB_MODE_AUTO }
        builder.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, awb)
        if (focusManual) {
            builder.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            builder.setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, focusDiopters)
        } else {
            builder.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        }
        Camera2CameraControl.from(cam.cameraControl).setCaptureRequestOptions(builder.build())
        if (!manual) camera?.cameraControl?.setExposureCompensationIndex(exposureIndex)
    }

    // ------------------------------------------------------------------ presets

    /** Applies everything a preset sets that Android supports; returns a note about what it had to skip. */
    fun applyPreset(p: Preset): String? {
        look = Looks.byName(p.look); strength = p.strength
        extras = Extras(p.grain, p.glow, p.leak, p.dust)
        ratio = p.ratio; frame = p.frame; burst = p.burst; steady = p.steady
        if (p.manualIso != null && p.manualShutter != null) {
            iso = p.manualIso; shutterSeconds = p.manualShutter; manual = true
        } else manual = false
        val step = camera?.cameraInfo?.exposureState?.exposureCompensationStep?.toFloat() ?: 0f
        exposureIndex = if (step > 0f) (p.bias / step).roundToInt() else 0
        applyManual()
        nightMode = p.mode == "NIGHT"
        return if (p.mode != "PHOTO" && p.mode != "NIGHT") "${p.mode} mode isn't on Android yet — using a normal photo" else null
    }

    // ------------------------------------------------------------------ photo

    fun takePhoto() {
        if (busy) return
        busy = true
        val shots = if (burst) 5 else 1
        // "steady" presets wait a moment so hand shake settles after the tap
        val delay = if (steady) 350L else 0L
        mainExecutor.execute { Thread { Thread.sleep(delay); mainExecutor.execute { shoot(shots) } }.start() }
    }

    private fun shoot(remaining: Int) {
        val capture = imageCapture
        if (capture == null || remaining <= 0) { busy = false; return }
        val next = { if (remaining > 1) shoot(remaining - 1) else { busy = false } }
        if (rawMode) {
            val dng = ImageCapture.OutputFileOptions.Builder(context.contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, "Lumen_${stamp()}")
                    put(MediaStore.Images.Media.MIME_TYPE, "image/x-adobe-dng")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Lumen")
                }).build()
            val jpg = ImageCapture.OutputFileOptions.Builder(context.contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, photoValues()).build()
            var saved = 0
            capture.takePicture(dng, jpg, mainExecutor, object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                    if (++saved >= 2) { message = "RAW (DNG) + JPEG saved"; next() }
                }
                override fun onError(e: ImageCaptureException) { busy = false; message = "RAW failed: ${e.message}" }
            })
            return
        }
        val needProcessing = !look.isOriginal || flareGuard || extras != Extras() || ratio != null || frame
        if (!needProcessing) {
            // Nothing to change: save the camera's own JPEG untouched.
            val options = ImageCapture.OutputFileOptions.Builder(
                context.contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, photoValues()).build()
            capture.takePicture(options, mainExecutor, object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(result: ImageCapture.OutputFileResults) { message = "Photo saved"; next() }
                override fun onError(e: ImageCaptureException) { busy = false; message = "Photo failed: ${e.message}" }
            })
            return
        }
        capture.takePicture(processExecutor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                try {
                    val rotation = image.imageInfo.rotationDegrees
                    var bitmap = image.toBitmap()
                    image.close()
                    if (rotation != 0) {
                        val m = Matrix().apply { postRotate(rotation.toFloat()) }
                        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true)
                        bitmap.recycle(); bitmap = rotated
                    }
                    val out = finish(bitmap, null)
                    val uri = save(out)
                    out.recycle()
                    mainExecutor.execute { message = if (uri != null) "Photo saved" else "Could not save photo"; next() }
                } catch (e: Throwable) {
                    try { image.close() } catch (_: Exception) {}
                    mainExecutor.execute { busy = false; message = "Photo failed: ${e.message}" }
                }
            }
            override fun onError(e: ImageCaptureException) {
                mainExecutor.execute { busy = false; message = "Photo failed: ${e.message}" }
            }
        })
    }

    /** Look, flare fix, effects, crop and frame, in that order, on an upright bitmap. Recycles [src]. */
    private fun finish(src: Bitmap, tone: android.graphics.ColorMatrix?): Bitmap {
        val matrices = mutableListOf<android.graphics.ColorMatrix>()
        if (flareGuard) matrices.add(FlareGuard.matrix(FlareGuard.measure(src), flareStrength))
        if (tone != null) matrices.add(tone)
        matrices.add(look.matrix(strength))
        var out = FlareGuard.render(src, *matrices.toTypedArray())
        src.recycle()
        Effects.apply(out, look.spec, extras)
        val cropped = Effects.crop(out, ratio)
        if (cropped !== out) { out.recycle(); out = cropped }
        if (frame) { val framed = Effects.polaroid(out); out.recycle(); out = framed }
        return out
    }

    // ------------------------------------------------------------------ night mode

    var nightMode by mutableStateOf(false)
    var nightSeconds by mutableStateOf(3)
    var nightProgress by mutableStateOf<String?>(null)

    /**
     * Night mode: shoots as many frames as fit in [nightSeconds], lines them up (hand shake) and averages them.
     * Averaging N frames cuts noise by about sqrt(N), so the picture can be brightened without grain.
     */
    fun takeNight() = takeStack(0)

    var longMode by mutableStateOf(0)        // 0 off, 1 smooth (average), 2 light trails (lighten)
    var longSeconds by mutableStateOf(10)

    /** kind 0 = night (aligned average), 1 = long exposure average (tripod), 2 = light trails (tripod, lighten). */
    fun takeStack(kind: Int) {
        val capture = imageCapture ?: return
        if (busy) return
        busy = true
        val cam = camera
        val ev = cam?.cameraInfo?.exposureState
        val oldIndex = exposureIndex
        if (kind == 0 && !manual && ev != null && ev.isExposureCompensationSupported) {
            val step = ev.exposureCompensationStep.toFloat().coerceAtLeast(0.01f)
            cam.cameraControl.setExposureCompensationIndex((1.0f / step).roundToInt().coerceIn(ev.exposureCompensationRange.lower, ev.exposureCompensationRange.upper))
        }
        val startTime = System.currentTimeMillis()
        val maxFrames = if (kind == 0) 16 else 40
        val seconds = if (kind == 0) nightSeconds else longSeconds
        val label = when (kind) { 0 -> "NIGHT"; 1 -> "LONG EXPOSURE"; else -> "LIGHT TRAIL" }
        val stacker = NightStacker(align = kind == 0, lighten = kind == 2)
        var rotation = 0

        fun finishStack() {
            processExecutor.execute {
                try {
                    var result = stacker.result()
                    if (rotation != 0) {
                        val m = Matrix().apply { postRotate(rotation.toFloat()) }
                        val rotated = Bitmap.createBitmap(result, 0, 0, result.width, result.height, m, true)
                        result.recycle(); result = rotated
                    }
                    // night tone: lift the shadows a little and keep colour
                    val tone = if (kind != 0) null else android.graphics.ColorMatrix().apply {
                        setSaturation(1.1f)
                        postConcat(android.graphics.ColorMatrix(floatArrayOf(
                            1.12f, 0f, 0f, 0f, 14f, 0f, 1.12f, 0f, 0f, 14f, 0f, 0f, 1.12f, 0f, 14f, 0f, 0f, 0f, 1f, 0f)))
                    }
                    val out = finish(result, tone)
                    val uri = save(out)
                    out.recycle()
                    mainExecutor.execute { nightProgress = null; busy = false; message = if (uri != null) "$label photo saved (${stacker.frames} frames)" else "Could not save photo" }
                } catch (e: Throwable) {
                    mainExecutor.execute { nightProgress = null; busy = false; message = "Night failed: ${e.message}" }
                } finally {
                    mainExecutor.execute { if (!manual) camera?.cameraControl?.setExposureCompensationIndex(oldIndex) }
                }
            }
        }

        fun next() {
            capture.takePicture(processExecutor, object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    try {
                        if (stacker.frames == 0) rotation = image.imageInfo.rotationDegrees
                        val bmp = image.toBitmap()
                        image.close()
                        stacker.add(bmp)
                        bmp.recycle()
                        mainExecutor.execute { nightProgress = "$label · ${if (kind == 0) "hold still" else "keep on a tripod"} · ${stacker.frames} frames" }
                    } catch (e: Throwable) {
                        try { image.close() } catch (_: Exception) {}
                        mainExecutor.execute { nightProgress = null; busy = false; message = "$label failed: ${e.message}" }
                        return
                    }
                    val elapsed = System.currentTimeMillis() - startTime
                    if (elapsed < seconds * 1000L && stacker.frames < maxFrames) next() else finishStack()
                }
                override fun onError(e: ImageCaptureException) {
                    if (stacker.frames >= 2) finishStack()
                    else mainExecutor.execute { nightProgress = null; busy = false; message = "Night failed: ${e.message}" }
                }
            })
        }
        nightProgress = "$label · starting"
        next()
    }

    // ------------------------------------------------------------------ interval (time-lapse of stills)

    var intervalMode by mutableStateOf(false)
    var intervalSeconds by mutableStateOf(5)
    var intervalRunning by mutableStateOf(false)
    var intervalCount by mutableStateOf(0)
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    fun toggleInterval() {
        if (intervalRunning) {
            intervalRunning = false; handler.removeCallbacksAndMessages(null)
            message = "Interval stopped · $intervalCount photos"; nightProgress = null
            return
        }
        intervalRunning = true; intervalCount = 0
        intervalTick()
    }

    private fun intervalTick() {
        if (!intervalRunning) return
        if (!busy) { takePhoto(); intervalCount++; nightProgress = "INTERVAL · $intervalCount photos · every ${intervalSeconds}s" }
        handler.postDelayed({ intervalTick() }, intervalSeconds * 1000L)
    }

    private fun photoValues() = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, "Lumen_${stamp()}")
        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Lumen")
    }

    private fun save(bitmap: Bitmap): Uri? {
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, photoValues()) ?: return null
        resolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.JPEG, 96, it) }
        return uri
    }

    // ------------------------------------------------------------------ video

    fun toggleRecording() {
        val video = videoCapture ?: return
        recording?.let { it.stop(); recording = null; return }
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "Lumen_${stamp()}")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Lumen")
        }
        val output = MediaStoreOutputOptions.Builder(context.contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(values).build()
        var pending = video.output.prepareRecording(context, output)
        if (PermissionChecker.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) == PermissionChecker.PERMISSION_GRANTED) {
            pending = pending.withAudioEnabled()
        }
        recording = pending.start(mainExecutor) { event ->
            when (event) {
                is VideoRecordEvent.Start -> isRecording = true
                is VideoRecordEvent.Finalize -> {
                    isRecording = false
                    message = if (event.hasError()) "Video error ${event.error}" else "Video saved"
                }
                else -> {}
            }
        }
    }

    private fun stamp() = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    fun release() {
        handler.removeCallbacksAndMessages(null)
        recording?.stop()
        analysisExecutor.shutdown()
        processExecutor.shutdown()
    }
}
