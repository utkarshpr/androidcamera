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
    var zoom by mutableStateOf(1f)
    var minZoom by mutableStateOf(0.6f)
    var maxZoom by mutableStateOf(10f)

    // ---- settings ----
    var look: Look = Looks.all.first()
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

    var frontFacing = false

    fun bind(owner: LifecycleOwner, view: PreviewView) {
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
            val preview = previewBuilder.build().also { it.setSurfaceProvider(view.surfaceProvider) }
            val capture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .build()
            val recorder = Recorder.Builder().setQualitySelector(QualitySelector.from(Quality.UHD, androidx.camera.video.FallbackStrategy.lowerQualityOrHigherThan(Quality.FHD))).build()
            val video = VideoCapture.withOutput(recorder)
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .also { androidx.camera.camera2.interop.Camera2Interop.Extender(it).setSessionCaptureCallback(aeWatcher) }
                .build()
            var frame = 0
            analysis.setAnalyzer(analysisExecutor) { img: ImageProxy ->
                if (++frame % 10 == 0) {
                    val flaring = FlareGuard.measure(img).flaring
                    mainExecutor.execute { if (flareWarning != flaring) flareWarning = flaring }
                }
                img.close()
            }
            val selector = if (frontFacing) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
            try {
                p.unbindAll()
                camera = p.bindToLifecycle(owner, selector, preview, capture, video, analysis)
                imageCapture = capture
                videoCapture = video
                camera?.cameraInfo?.zoomState?.observe(owner) { z ->
                    zoom = z.zoomRatio; minZoom = z.minZoomRatio; maxZoom = z.maxZoomRatio
                }
                zoomTo(1f)
                applyManual()
            } catch (e: Exception) {
                message = "Camera error: ${e.message}"
            }
        }, mainExecutor)
    }

    /** Lens buttons: 0.6× ultra-wide, 1× main, 3× tele, 10× periscope on the S22 Ultra (the phone switches the real lens). */
    fun zoomTo(ratio: Float) {
        val r = ratio.coerceIn(minZoom, maxZoom)
        camera?.cameraControl?.setZoomRatio(r)
    }

    fun tapToFocus(view: PreviewView, x: Float, y: Float) {
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
        if (focusManual) {
            builder.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            builder.setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, focusDiopters)
        } else {
            builder.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        }
        Camera2CameraControl.from(cam.cameraControl).setCaptureRequestOptions(builder.build())
        if (!manual) camera?.cameraControl?.setExposureCompensationIndex(exposureIndex)
    }

    // ------------------------------------------------------------------ photo

    fun takePhoto() {
        val capture = imageCapture ?: return
        if (busy) return
        busy = true
        val needProcessing = look.name != "ORIGINAL" || flareGuard
        if (!needProcessing) {
            // Nothing to change: save the camera's own JPEG untouched.
            val options = ImageCapture.OutputFileOptions.Builder(
                context.contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, photoValues()).build()
            capture.takePicture(options, mainExecutor, object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(result: ImageCapture.OutputFileResults) { busy = false; message = "Photo saved" }
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
                    val matrices = mutableListOf<android.graphics.ColorMatrix>()
                    if (flareGuard) matrices.add(FlareGuard.matrix(FlareGuard.measure(bitmap), flareStrength))
                    matrices.add(look.matrix)
                    val out = FlareGuard.render(bitmap, *matrices.toTypedArray())
                    bitmap.recycle()
                    val uri = save(out)
                    out.recycle()
                    mainExecutor.execute { busy = false; message = if (uri != null) "Photo saved" else "Could not save photo" }
                } catch (e: Exception) {
                    image.close()
                    mainExecutor.execute { busy = false; message = "Photo failed: ${e.message}" }
                }
            }
            override fun onError(e: ImageCaptureException) {
                mainExecutor.execute { busy = false; message = "Photo failed: ${e.message}" }
            }
        })
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
        recording?.stop()
        analysisExecutor.shutdown()
        processExecutor.shutdown()
    }
}
