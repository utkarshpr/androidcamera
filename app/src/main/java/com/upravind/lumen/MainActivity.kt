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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.pow
import kotlin.math.roundToInt

private val Gold = Color(0xFFEBD6AB)
private val Panel = Color(0xFF16161A)

class MainActivity : ComponentActivity() {
    private var controller: CameraController? = null
    private var shutterHook: (() -> Unit)? = null
    private val pendingLook = mutableStateOf<String?>(null)     // lets `adb shell am start --es look NAME` pick a look (used for testing)

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra("look")?.let { pendingLook.value = it }
    }

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        if ((keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP) && event?.repeatCount == 0) {
            shutterHook?.invoke(); return true     // volume buttons work as a shutter
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent.getStringExtra("look")?.let { pendingLook.value = it }
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
        var strength by remember { mutableStateOf(1f) }
        var videoMode by remember { mutableStateOf(false) }
        var showManual by remember { mutableStateOf(false) }
        var flareOn by remember { mutableStateOf(true) }
        var showPresets by remember { mutableStateOf(false) }
        var presetName by remember { mutableStateOf<String?>(null) }
        var grid by remember { mutableStateOf(1) }
        var ratio by remember { mutableStateOf<String?>(null) }
        var previewView by remember { mutableStateOf<PreviewView?>(null) }
        var timer by remember { mutableStateOf(0) }
        var countdown by remember { mutableStateOf<Int?>(null) }
        var pdfPicked by remember { mutableStateOf<List<android.net.Uri>>(emptyList()) }
        val scope = rememberCoroutineScope()
        val pdfPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
            if (uris.isNotEmpty()) pdfPicked = uris.take(64)
        }
        fun fire() {
            if (videoMode) cam.toggleRecording()
            else if (cam.longMode > 0) cam.takeStack(cam.longMode)
            else if (cam.nightMode) cam.takeNight()
            else cam.takePhoto()
        }
        fun shutter() {
            if (cam.busy && countdown == null) return
            if (countdown != null) { countdown = null; return }       // tap again cancels the countdown
            if (timer > 0 && !cam.isRecording) {
                scope.launch {
                    var n = timer
                    while (n > 0) { countdown = n; delay(1000); if (countdown == null) return@launch; n-- }
                    countdown = null
                    fire()
                }
            } else fire()
        }
        DisposableEffect(Unit) { shutterHook = { shutter() }; onDispose { shutterHook = null } }

        // live look on the preview (TextureView-backed preview takes a colour filter)
        LaunchedEffect(pendingLook.value) { pendingLook.value?.let { look = Looks.byName(it); strength = 1f; presetName = null } }
        var grainTick by remember { mutableStateOf(0f) }
        val animated = look.spec.grain > 0f || cam.extras.grain > 0f
        LaunchedEffect(animated) { while (animated) { delay(120); grainTick = (grainTick + 1f) % 97f } }
        LaunchedEffect(look, strength, previewView, cam.extras, grainTick) {
            cam.look = look; cam.strength = strength
            val v = previewView ?: return@LaunchedEffect
            v.post { PreviewFx.apply(v, look, strength, cam.extras, grainTick) }
        }
        LaunchedEffect(flareOn) { cam.flareGuard = flareOn }
        val aspect = ratio?.split(":")?.let { (it[1].toFloat() / it[0].toFloat()) } ?: (3f / 4f)

        Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            // top bar
            Row(Modifier.fillMaxWidth().horizontalScroll(androidx.compose.foundation.rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Chip(if (videoMode) "VIDEO" else "PHOTO", true) { videoMode = !videoMode; if (videoMode && cam.rawMode) cam.setRaw(false); cam.setVideoMode(videoMode) }
                Spacer(Modifier.width(8.dp))
                Chip(if (cam.frontFacing) "SELFIE" else "FLIP", cam.frontFacing) { cam.flipCamera() }
                Spacer(Modifier.width(8.dp))
                Chip("PRESETS", showPresets) { showPresets = !showPresets }
                Spacer(Modifier.width(8.dp))
                Chip(if (cam.nightMode) "NIGHT ${cam.nightSeconds}s" else "NIGHT", cam.nightMode) {
                    if (!cam.nightMode) { if (cam.rawMode) cam.setRaw(false); cam.longMode = 0; cam.nightMode = true }
                    else if (cam.nightSeconds < 10) cam.nightSeconds = if (cam.nightSeconds == 3) 6 else 10
                    else { cam.nightMode = false; cam.nightSeconds = 3 }
                }
                Spacer(Modifier.width(8.dp))
                Chip("RAW", cam.rawMode) { videoMode = false; cam.videoOnly = false; cam.setRaw(!cam.rawMode) }
                Spacer(Modifier.width(8.dp))
                Chip(when (cam.longMode) { 1 -> "LONG ${cam.longSeconds}s"; 2 -> "TRAIL ${cam.longSeconds}s"; else -> "LONG" }, cam.longMode > 0) {
                    if (cam.longMode == 0) { if (cam.rawMode) cam.setRaw(false); cam.nightMode = false; cam.longMode = 1 }
                    else if (cam.longMode == 1) cam.longMode = 2
                    else cam.longMode = 0
                }
                if (cam.longMode > 0) { Spacer(Modifier.width(8.dp)); Chip("TIME", false) { cam.longSeconds = when (cam.longSeconds) { 5 -> 10; 10 -> 20; 20 -> 30; else -> 5 } } }
                Spacer(Modifier.width(8.dp))
                Chip(if (timer == 0) "TIMER" else "TIMER ${timer}s", timer > 0) { timer = when (timer) { 0 -> 3; 3 -> 10; else -> 0 } }
                Spacer(Modifier.width(8.dp))
                Chip("HIST", cam.showHistogram) { cam.showHistogram = !cam.showHistogram }
                Spacer(Modifier.width(8.dp))
                Chip("PDF", false) { pdfPicker.launch("image/*") }
                Spacer(Modifier.width(8.dp))
                Chip("FLARE FIX", flareOn) { flareOn = !flareOn }
                Spacer(Modifier.width(8.dp))
                Chip("MANUAL", showManual) { showManual = !showManual; cam.setManualMode(showManual) }
            }
            if (videoMode) Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip(if (cam.videoUhd) "4K" else "1080p", true) { cam.videoUhd = !cam.videoUhd; cam.videoOptionsChanged() }
                if (cam.supports60) Chip(if (cam.video60) "60 fps" else "30 fps", cam.video60) { cam.video60 = !cam.video60; cam.videoOptionsChanged() }
                Chip("HDR", cam.videoHdr) { cam.videoHdr = !cam.videoHdr; cam.videoOptionsChanged() }
            }
            if (cam.flareWarning) Text("FLARE — wipe the lens or shade it from the light", color = Color(0xFFFFB454), fontSize = 11.sp, modifier = Modifier.padding(horizontal = 16.dp))
            cam.nightProgress?.let { Text(it, color = Color(0xFF9EC5FF), fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp)) }
            presetName?.let { Text("PRESET · $it", color = Gold, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 16.dp)) }

            // viewfinder
            Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.aspectRatio(aspect, matchHeightConstraintsFirst = false).background(Panel, RoundedCornerShape(22.dp))) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            PreviewView(ctx).apply {
                                implementationMode = PreviewView.ImplementationMode.COMPATIBLE   // TextureView: allows the live colour look
                                scaleType = PreviewView.ScaleType.FILL_CENTER
                                setOnTouchListener { v, e ->
                                    if (e.action == android.view.MotionEvent.ACTION_UP) { cam.tapToFocus(this, e.x, e.y); v.performClick() }
                                    true
                                }
                                previewView = this
                                cam.bind(owner, this)
                            }
                        }
                    )
                    GridOverlay(grid)
                    if (cam.showHistogram) androidx.compose.foundation.Canvas(Modifier.align(Alignment.TopEnd).padding(10.dp).size(width = 120.dp, height = 56.dp)
                        .background(Color(0x66000000), RoundedCornerShape(8.dp))) {
                        val bw = size.width / 64f
                        cam.histogram.forEachIndexed { i, v ->
                            drawRect(Color.White.copy(alpha = 0.85f), androidx.compose.ui.geometry.Offset(i * bw, size.height * (1f - v)),
                                androidx.compose.ui.geometry.Size(bw, size.height * v))
                        }
                    }
                    countdown?.let { Text("$it", color = Color.White, fontSize = 96.sp, modifier = Modifier.align(Alignment.Center)) }
                    cam.message?.let { m ->
                        Text(m, color = Color.White, fontSize = 12.sp,
                            modifier = Modifier.align(Alignment.TopCenter).padding(10.dp).background(Color(0xAA000000), RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 6.dp))
                        LaunchedEffect(m) { kotlinx.coroutines.delay(3200); cam.message = null }
                    }
                }
            }

            if (showManual) ManualPanel(cam)

            // lens buttons
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                listOf(0.6f, 1f, 3f, 10f).forEach { z ->
                    val selected = (cam.zoom - z).let { kotlin.math.abs(it) < 0.15f * z }
                    Box(Modifier.padding(horizontal = 5.dp).size(44.dp).background(if (selected) Gold else Panel, CircleShape).clickable { cam.zoomTo(z) },
                        contentAlignment = Alignment.Center) {
                        Text(if (z < 1f) ".6" else z.toInt().toString() + "×", color = if (selected) Color.Black else Color.White, fontSize = 13.sp)
                    }
                }
                Spacer(Modifier.width(10.dp))
                Chip("GRID", grid > 0) { grid = (grid + 1) % 5 }
            }

            // looks
            LazyRow(Modifier.fillMaxWidth().padding(top = 10.dp), contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(Looks.all) { l ->
                    Row(Modifier.background(if (l == look) Gold else Panel, RoundedCornerShape(50)).clickable { look = l; strength = 1f; presetName = null }
                        .padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(9.dp).background(Color(l.spec.swatch), CircleShape))
                        Spacer(Modifier.width(6.dp))
                        Text(l.name, fontSize = 11.sp, color = if (l == look) Color.Black else Color.White.copy(alpha = 0.85f))
                    }
                }
            }
            if (!look.isOriginal) Slider01("LOOK STRENGTH ${(strength * 100).roundToInt()}%", strength) { strength = it }.let { }

            // shutter
            Box(Modifier.fillMaxWidth().padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
                val recording = cam.isRecording
                Box(Modifier.size(82.dp).background(Panel, CircleShape).clickable { shutter() }, contentAlignment = Alignment.Center) {
                    Box(Modifier.size(if (recording) 30.dp else 62.dp)
                        .background(if (videoMode) Color(0xFFE53935) else Gold, if (recording) RoundedCornerShape(8.dp) else CircleShape))
                }
            }
        }

        if (pdfPicked.isNotEmpty()) androidx.compose.material3.AlertDialog(
            onDismissRequest = { pdfPicked = emptyList() },
            title = { Text("Make PDF from ${pdfPicked.size} photo(s)") },
            text = { Text("How many pictures on each page?") },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(1, 2, 4).forEach { n ->
                        androidx.compose.material3.TextButton(onClick = {
                            val picked = pdfPicked; pdfPicked = emptyList()
                            cam.message = "Making PDF…"
                            Thread {
                                val uri = try { PdfExport.create(context, picked, n) } catch (e: Throwable) { null }
                                runOnUiThread { cam.message = if (uri != null) "PDF saved to Documents/Lumen" else "PDF failed" }
                            }.start()
                        }) { Text("$n / page") }
                    }
                }
            },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { pdfPicked = emptyList() }) { Text("Cancel") } }
        )
        if (showPresets) PresetSheet(onClose = { showPresets = false }) { p ->
            look = Looks.byName(p.look); strength = p.strength; grid = p.grid; ratio = p.ratio; presetName = p.name
            val note = cam.applyPreset(p)
            showManual = cam.manual
            val tip = PresetData.tips[p.name] ?: "Preset: ${p.name}"
            cam.message = if (note != null) "$tip\n($note)" else tip
            showPresets = false
        }
        }
    }

    @Composable
    private fun PresetSheet(onClose: () -> Unit, onPick: (Preset) -> Unit) {
        var cat by remember { mutableStateOf(PresetData.categories.first()) }
        Column(Modifier.fillMaxSize().background(Color(0xFF000000)).statusBarsPadding().navigationBarsPadding().padding(top = 8.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("PRESETS", color = Gold, fontSize = 14.sp)
                Spacer(Modifier.weight(1f))
                Chip("CLOSE", false, onClose)
            }
            LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(PresetData.categories) { c -> Chip(c, c == cat) { cat = c } }
            }
            LazyColumn(Modifier.fillMaxSize().padding(top = 8.dp), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(PresetData.all.filter { it.cat == cat }) { p ->
                    val look = Looks.byName(p.look)
                    Row(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(16.dp)).clickable { onPick(p) }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(14.dp).background(Color(look.spec.swatch), CircleShape))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(p.name, color = Color.White, fontSize = 14.sp)
                            Text(look.name + (p.ratio?.let { " · $it" } ?: "") + (if (p.mode != "PHOTO") " · ${p.mode}" else ""), color = Color.White.copy(alpha = 0.5f), fontSize = 10.sp)
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun GridOverlay(style: Int) {
        if (style == 0) return
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            val c = Color.White.copy(alpha = 0.35f); val sw = 1.dp.toPx()
            fun v(x: Float) = drawLine(c, androidx.compose.ui.geometry.Offset(x, 0f), androidx.compose.ui.geometry.Offset(x, size.height), sw)
            fun h(y: Float) = drawLine(c, androidx.compose.ui.geometry.Offset(0f, y), androidx.compose.ui.geometry.Offset(size.width, y), sw)
            when (style) {
                1 -> { v(size.width / 3); v(size.width * 2 / 3); h(size.height / 3); h(size.height * 2 / 3) }
                2 -> { v(size.width / 2); h(size.height / 2) }
                3 -> { v(size.width / 3); v(size.width * 2 / 3) }
                else -> {
                    drawLine(c, androidx.compose.ui.geometry.Offset(0f, 0f), androidx.compose.ui.geometry.Offset(size.width, size.height), sw)
                    drawLine(c, androidx.compose.ui.geometry.Offset(size.width, 0f), androidx.compose.ui.geometry.Offset(0f, size.height), sw)
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
        Text(text, fontSize = 11.sp, maxLines = 1, softWrap = false, color = if (on) Color.Black else Color.White.copy(alpha = 0.85f),
            modifier = Modifier.background(if (on) Gold else Panel, RoundedCornerShape(50)).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 7.dp))
    }
}
