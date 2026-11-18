package com.upravind.lumen

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Paint
import android.os.Bundle
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import android.graphics.ColorMatrixColorFilter
import kotlin.math.pow
import kotlin.math.roundToInt

private val Gold = Color(0xFFEBD6AB)
private val Panel = Color(0xFF16161A)

class MainActivity : ComponentActivity() {
    private var controller: CameraController? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme(colorScheme = darkColorScheme(primary = Gold)) { Root() } }
    }

    override fun onDestroy() { controller?.release(); super.onDestroy() }

    @Composable
    private fun Root() {
        val context = LocalContext.current
        var granted by remember {
            mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
        }
        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            granted = it[Manifest.permission.CAMERA] == true
        }
        LaunchedEffect(Unit) {
            if (!granted) launcher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
        }
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            if (granted) CameraScreen() else Text("Camera permission is needed", color = Color.White, modifier = Modifier.align(Alignment.Center))
        }
    }

    @Composable
    private fun CameraScreen() {
        val context = LocalContext.current
        val owner = LocalLifecycleOwner.current
        val cam = remember { CameraController(context).also { controller = it } }
        var look by remember { mutableStateOf(Looks.all.first()) }
        var videoMode by remember { mutableStateOf(false) }
        var showManual by remember { mutableStateOf(false) }
        var flareOn by remember { mutableStateOf(true) }
        var previewView by remember { mutableStateOf<PreviewView?>(null) }

        // live look on the preview (TextureView-backed preview takes a colour filter)
        LaunchedEffect(look, previewView) {
            cam.look = look
            val v = previewView ?: return@LaunchedEffect
            val paint = Paint().apply { colorFilter = ColorMatrixColorFilter(look.matrix) }
            v.setLayerType(View.LAYER_TYPE_HARDWARE, paint)
        }
        LaunchedEffect(flareOn) { cam.flareGuard = flareOn }

        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            // top bar
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Chip(if (videoMode) "VIDEO" else "PHOTO", true) { videoMode = !videoMode }
                Spacer(Modifier.weight(1f))
                if (cam.flareWarning) {
                    Text("FLARE — wipe lens or shade it", color = Color(0xFFFFB454), fontSize = 11.sp)
                    Spacer(Modifier.width(8.dp))
                }
                Chip("FLARE FIX", flareOn) { flareOn = !flareOn }
                Spacer(Modifier.width(8.dp))
                Chip("MANUAL", showManual) { showManual = !showManual; cam.manual = showManual; cam.applyManual() }
            }

            // viewfinder
            Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp).background(Panel, RoundedCornerShape(22.dp))) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        PreviewView(ctx).apply {
                            implementationMode = PreviewView.ImplementationMode.COMPATIBLE   // TextureView: allows the live colour look
                            scaleType = PreviewView.ScaleType.FIT_CENTER
                            setOnTouchListener { v, e ->
                                if (e.action == android.view.MotionEvent.ACTION_UP) { cam.tapToFocus(this, e.x, e.y); v.performClick() }
                                true
                            }
                            previewView = this
                            cam.bind(owner, this)
                        }
                    }
                )
                cam.message?.let { m ->
                    Text(m, color = Color.White, fontSize = 12.sp,
                        modifier = Modifier.align(Alignment.TopCenter).padding(10.dp).background(Color(0xAA000000), RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 6.dp))
                    LaunchedEffect(m) { kotlinx.coroutines.delay(2200); cam.message = null }
                }
            }

            if (showManual) ManualPanel(cam)

            // lens buttons
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.Center) {
                listOf(0.6f, 1f, 3f, 10f).forEach { z ->
                    val selected = (cam.zoom - z).let { kotlin.math.abs(it) < 0.15f * z }
                    Box(Modifier.padding(horizontal = 5.dp).size(44.dp).background(if (selected) Gold else Panel, CircleShape).clickable { cam.zoomTo(z) },
                        contentAlignment = Alignment.Center) {
                        Text(if (z < 1f) ".6" else z.toInt().toString() + "×", color = if (selected) Color.Black else Color.White, fontSize = 13.sp)
                    }
                }
            }

            // looks
            LazyRow(Modifier.fillMaxWidth().padding(top = 10.dp), contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(Looks.all) { l -> Chip(l.name, l == look) { look = l } }
            }

            // shutter
            Box(Modifier.fillMaxWidth().padding(vertical = 14.dp), contentAlignment = Alignment.Center) {
                val recording = cam.isRecording
                Box(Modifier.size(82.dp).background(Panel, CircleShape).clickable(enabled = !cam.busy) {
                    if (videoMode) cam.toggleRecording() else cam.takePhoto()
                }, contentAlignment = Alignment.Center) {
                    Box(Modifier.size(if (recording) 30.dp else 62.dp)
                        .background(if (videoMode) Color(0xFFE53935) else Gold, if (recording) RoundedCornerShape(8.dp) else CircleShape))
                }
            }
        }
    }

    @Composable
    private fun ManualPanel(cam: CameraController) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
            // ISO 50..3200 and shutter 1/8000..1s on log scales
            Slider01("ISO ${cam.iso}", (kotlin.math.ln(cam.iso / 50f) / kotlin.math.ln(64f))) {
                cam.iso = (50f * 64f.pow(it)).roundToInt(); cam.applyManual()
            }
            val shutterLabel = if (cam.shutterSeconds >= 0.5) "${"%.1f".format(cam.shutterSeconds)}s" else "1/${(1 / cam.shutterSeconds).roundToInt()}"
            Slider01("SHUTTER $shutterLabel", (kotlin.math.ln(cam.shutterSeconds / (1.0 / 8000)) / kotlin.math.ln(8000.0)).toFloat()) {
                cam.shutterSeconds = (1.0 / 8000) * 8000.0.pow(it.toDouble()); cam.applyManual()
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Chip(if (cam.focusManual) "FOCUS MANUAL" else "FOCUS AUTO", cam.focusManual) { cam.focusManual = !cam.focusManual; cam.applyManual() }
            }
            if (cam.focusManual) Slider01("FOCUS", (cam.focusDiopters / 10f).coerceIn(0f, 1f)) { cam.focusDiopters = it * 10f; cam.applyManual() }
            if (!cam.manual) Slider01("EXPOSURE ${cam.exposureIndex}", (cam.exposureIndex + 6) / 12f) {
                cam.exposureIndex = (it * 12).roundToInt() - 6; cam.applyManual()
            }
        }
    }

    @Composable
    private fun Slider01(label: String, value: Float, onChange: (Float) -> Unit) {
        Column {
            Text(label, color = Color.White.copy(alpha = 0.6f), fontSize = 10.sp)
            Slider(value = value.coerceIn(0f, 1f), onValueChange = onChange, modifier = Modifier.height(28.dp))
        }
    }

    @Composable
    private fun Chip(text: String, on: Boolean, onClick: () -> Unit) {
        Text(text, fontSize = 11.sp, color = if (on) Color.Black else Color.White.copy(alpha = 0.85f),
            modifier = Modifier.background(if (on) Gold else Panel, RoundedCornerShape(50)).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 7.dp))
    }
}
