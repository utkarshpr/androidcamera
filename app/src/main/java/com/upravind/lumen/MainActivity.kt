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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.border
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
private val GoldLight = Color(0xFFF7E9C8)

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

        var showTools by remember { mutableStateOf(false) }
        // small square snapshot of the live camera, used to show every look on the real scene
        var thumbSource by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
        LaunchedEffect(previewView) {
            while (true) {
                val v = previewView
                val bmp = if (v != null && v.width > 0) try { v.bitmap } catch (_: Throwable) { null } else null
                if (bmp != null && bmp.width > 8) {
                    val side = minOf(bmp.width, bmp.height)
                    val square = android.graphics.Bitmap.createBitmap(bmp, (bmp.width - side) / 2, (bmp.height - side) / 2, side, side)
                    thumbSource = android.graphics.Bitmap.createScaledBitmap(square, 120, 120, true)
                }
                delay(1200L)
            }
        }
        var showLooks by remember { mutableStateOf(true) }
        val mode = when {
            videoMode -> "VIDEO"; cam.rawMode -> "RAW"; cam.longMode == 1 -> "LONG"; cam.longMode == 2 -> "TRAIL"; cam.nightMode -> "NIGHT"; else -> "PHOTO"
        }
        fun setMode(m: String) {
            if (m == mode) {                                               // tapping the active mode again cycles its time
                if (m == "NIGHT") cam.nightSeconds = when (cam.nightSeconds) { 3 -> 6; 6 -> 10; else -> 3 }
                if (m == "LONG" || m == "TRAIL") cam.longSeconds = when (cam.longSeconds) { 5 -> 10; 10 -> 20; 20 -> 30; else -> 5 }
                return
            }
            if (videoMode && m != "VIDEO") { videoMode = false; cam.setVideoMode(false) }
            if (cam.rawMode && m != "RAW") cam.setRaw(false)
            cam.nightMode = false; cam.longMode = 0
            when (m) {
                "NIGHT" -> cam.nightMode = true
                "LONG" -> cam.longMode = 1
                "TRAIL" -> cam.longMode = 2
                "RAW" -> { cam.videoOnly = false; cam.setRaw(true) }
                "VIDEO" -> { videoMode = true; cam.setVideoMode(true) }
            }
        }

        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF0E0E12), Color(0xFF050507))))) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            // ---- viewfinder with floating glass controls
            Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.aspectRatio(aspect, matchHeightConstraintsFirst = false).clip(RoundedCornerShape(30.dp)).background(Panel)
                    .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(30.dp))) {
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
                    // top row: tools · status · flip
                    Row(Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        GlassButton("☰") { showTools = true }
                        Spacer(Modifier.weight(1f))
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            if (cam.isRecording) GlassPill("● REC", Color(0xFFFF5A5A))
                            else cam.nightProgress?.let { GlassPill(it, Color(0xFF9EC5FF)) }
                            if (cam.flareWarning && cam.nightProgress == null && !cam.isRecording) GlassPill("FLARE · wipe the lens or shade it", Color(0xFFFFB454))
                            presetName?.let { if (cam.nightProgress == null && !cam.isRecording) GlassPill(it, Gold) }
                        }
                        Spacer(Modifier.weight(1f))
                        GlassButton(if (cam.frontFacing) "⟲ S" else "⟲") { cam.flipCamera() }
                    }
                    if (cam.showHistogram) androidx.compose.foundation.Canvas(Modifier.align(Alignment.TopEnd).padding(top = 66.dp, end = 12.dp).size(width = 120.dp, height = 56.dp)
                        .background(Color(0x66000000), RoundedCornerShape(10.dp))) {
                        val bw = size.width / 64f
                        cam.histogram.forEachIndexed { i, v ->
                            drawRect(Color.White.copy(alpha = 0.85f), androidx.compose.ui.geometry.Offset(i * bw, size.height * (1f - v)),
                                androidx.compose.ui.geometry.Size(bw, size.height * v))
                        }
                    }
                    countdown?.let { Text("$it", color = Color.White, fontSize = 96.sp, fontWeight = FontWeight.Light, modifier = Modifier.align(Alignment.Center)) }
                    cam.message?.let { m ->
                        Text(m, color = Color.White, fontSize = 12.sp,
                            modifier = Modifier.align(Alignment.Center).padding(24.dp).background(Color(0xCC000000), RoundedCornerShape(16.dp)).padding(horizontal = 14.dp, vertical = 8.dp))
                        LaunchedEffect(m) { kotlinx.coroutines.delay(3200); cam.message = null }
                    }
                    // zoom / lens pill
                    Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp).clip(RoundedCornerShape(50)).background(Color(0x99000000))
                        .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(50)).padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        listOf(0.6f, 1f, 3f, 10f).forEach { z ->
                            val selected = (cam.zoom - z).let { kotlin.math.abs(it) < 0.15f * z }
                            Box(Modifier.padding(horizontal = 2.dp).size(if (selected) 40.dp else 34.dp)
                                .background(if (selected) Brush.linearGradient(listOf(GoldLight, Gold)) else Brush.linearGradient(listOf(Color.Transparent, Color.Transparent)), CircleShape)
                                .clickable { cam.zoomTo(z) }, contentAlignment = Alignment.Center) {
                                Text(if (z < 1f) ".6" else z.toInt().toString() + if (selected) "×" else "", color = if (selected) Color.Black else Color.White.copy(alpha = 0.85f),
                                    fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }

            // ---- looks strip
            if (showLooks) LazyRow(Modifier.fillMaxWidth().padding(top = 4.dp), contentPadding = PaddingValues(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(Looks.all) { l ->
                    val on = l == look
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { look = l; strength = 1f; presetName = null }) {
                        val src = thumbSource
                        Box(Modifier.size(52.dp).clip(CircleShape).background(Color(l.spec.swatch))
                            .border(if (on) 2.5.dp else 1.dp, if (on) Gold else Color.White.copy(alpha = 0.15f), CircleShape)) {
                            if (src != null) androidx.compose.foundation.Image(
                                bitmap = src.asImageBitmap(), contentDescription = l.name, contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                                colorFilter = androidx.compose.ui.graphics.ColorFilter.colorMatrix(androidx.compose.ui.graphics.ColorMatrix(l.matrix(1f).array)))
                            if (l.spec.vignette > 0f) Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(Color.Transparent, Color.Black.copy(alpha = (l.spec.vignette * 0.4f).coerceAtMost(0.6f))))))
                        }
                        Spacer(Modifier.height(3.dp))
                        Text(l.name.lowercase().replaceFirstChar { it.uppercase() }, fontSize = 9.sp, maxLines = 1,
                            color = if (on) Gold else Color.White.copy(alpha = 0.55f), modifier = Modifier.widthIn(max = 62.dp))
                    }
                }
            }
            if (!look.isOriginal && showLooks) Row(Modifier.padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${(strength * 100).roundToInt()}%", color = Gold, fontSize = 11.sp, modifier = Modifier.width(38.dp))
                Slider(value = strength, onValueChange = { strength = it }, modifier = Modifier.height(24.dp),
                    colors = SliderDefaults.colors(thumbColor = Gold, activeTrackColor = Gold, inactiveTrackColor = Color.White.copy(alpha = 0.15f)))
            }

            // ---- mode selector
            Row(Modifier.fillMaxWidth().horizontalScroll(androidx.compose.foundation.rememberScrollState()).padding(top = 8.dp), horizontalArrangement = Arrangement.Center) {
                Spacer(Modifier.width(24.dp))
                listOf("PHOTO", "NIGHT", "RAW", "LONG", "TRAIL", "VIDEO").forEach { m ->
                    val on = m == mode
                    val label = if (on && m == "NIGHT") "NIGHT ${cam.nightSeconds}s" else if (on && (m == "LONG" || m == "TRAIL")) "$m ${cam.longSeconds}s" else m
                    Text(label, fontSize = 12.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal, letterSpacing = 1.2.sp,
                        color = if (on) Gold else Color.White.copy(alpha = 0.5f),
                        modifier = Modifier.clickable { setMode(m) }.padding(horizontal = 14.dp, vertical = 6.dp))
                }
                Spacer(Modifier.width(24.dp))
            }

            // ---- bottom bar: presets · shutter · looks toggle
            Row(Modifier.fillMaxWidth().padding(horizontal = 26.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    GlassTile("PRESETS", showPresets) { showPresets = !showPresets }
                }
                val recording = cam.isRecording
                Box(Modifier.size(86.dp).border(3.dp, Brush.linearGradient(listOf(GoldLight, Gold)), CircleShape).padding(7.dp)
                    .clip(CircleShape).background(if (videoMode) Color(0xFFE53935) else Color(0xFFF4EBDD)).clickable { shutter() },
                    contentAlignment = Alignment.Center) {
                    if (recording) Box(Modifier.size(26.dp).background(Color.White, RoundedCornerShape(6.dp)))
                }
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                    GlassTile(if (showLooks) "LOOKS ▾" else "LOOKS ▴", false) { showLooks = !showLooks }
                }
            }
        }

        if (showTools) ToolsSheet(
            cam = cam, flareOn = flareOn, onFlare = { flareOn = !flareOn }, showManual = showManual,
            onManual = { showManual = !showManual; cam.setManualMode(showManual) }, grid = grid, onGrid = { grid = (grid + 1) % 5 },
            timer = timer, onTimer = { timer = when (timer) { 0 -> 3; 3 -> 10; else -> 0 } },
            onPdf = { showTools = false; pdfPicker.launch("image/*") }, videoMode = videoMode, onClose = { showTools = false })

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
    private fun GlassButton(text: String, onClick: () -> Unit) {
        Box(Modifier.size(42.dp).clip(CircleShape).background(Color(0x88000000)).border(1.dp, Color.White.copy(alpha = 0.14f), CircleShape).clickable(onClick = onClick),
            contentAlignment = Alignment.Center) { Text(text, color = Color.White, fontSize = 16.sp) }
    }

    @Composable
    private fun GlassPill(text: String, color: Color) {
        Text(text, color = color, fontSize = 11.sp, fontWeight = FontWeight.Medium, maxLines = 1,
            modifier = Modifier.padding(bottom = 4.dp).clip(RoundedCornerShape(50)).background(Color(0x99000000))
                .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 5.dp))
    }

    @Composable
    private fun GlassTile(text: String, on: Boolean, onClick: () -> Unit) {
        Text(text, color = if (on) Color.Black else Color.White.copy(alpha = 0.85f), fontSize = 11.sp, letterSpacing = 1.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(if (on) Brush.linearGradient(listOf(GoldLight, Gold)) else Brush.linearGradient(listOf(Color(0xFF1B1B21), Color(0xFF131317))))
                .border(1.dp, Color.White.copy(alpha = if (on) 0f else 0.10f), RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp))
    }

    @Composable
    private fun ToolTile(title: String, value: String, on: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
        Column(modifier.clip(RoundedCornerShape(18.dp))
            .background(if (on) Brush.linearGradient(listOf(GoldLight, Gold)) else Brush.linearGradient(listOf(Color(0xFF1C1C22), Color(0xFF131317))))
            .border(1.dp, Color.White.copy(alpha = if (on) 0f else 0.10f), RoundedCornerShape(18.dp)).clickable(onClick = onClick).padding(14.dp)) {
            Text(title, color = if (on) Color.Black else Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
            Spacer(Modifier.height(2.dp))
            Text(value, color = if (on) Color.Black.copy(alpha = 0.65f) else Color.White.copy(alpha = 0.5f), fontSize = 10.sp)
        }
    }

    @Composable
    private fun ToolsSheet(cam: CameraController, flareOn: Boolean, onFlare: () -> Unit, showManual: Boolean, onManual: () -> Unit, grid: Int, onGrid: () -> Unit,
                           timer: Int, onTimer: () -> Unit, onPdf: () -> Unit, videoMode: Boolean, onClose: () -> Unit) {
        Box(Modifier.fillMaxSize().background(Color(0x99000000)).clickable(onClick = onClose)) {
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                .background(Color(0xFF0F0F13)).border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                .clickable(enabled = false) {}.navigationBarsPadding().padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("TOOLS", color = Gold, fontSize = 13.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.weight(1f))
                    GlassTile("DONE", false, onClose)
                }
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ToolTile("FLARE FIX", if (flareOn) "on" else "off", flareOn, Modifier.weight(1f), onFlare)
                    ToolTile("MANUAL", if (showManual) "ISO · shutter · focus" else "auto", showManual, Modifier.weight(1f), onManual)
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ToolTile("GRID", listOf("off", "thirds", "centre", "columns", "diagonal")[grid], grid > 0, Modifier.weight(1f), onGrid)
                    ToolTile("TIMER", if (timer == 0) "off" else "${timer} seconds", timer > 0, Modifier.weight(1f), onTimer)
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ToolTile("HISTOGRAM", if (cam.showHistogram) "on" else "off", cam.showHistogram, Modifier.weight(1f)) { cam.showHistogram = !cam.showHistogram }
                    ToolTile("PHOTOS → PDF", "1, 2 or 4 per page", false, Modifier.weight(1f), onPdf)
                }
                if (videoMode) {
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        ToolTile("RESOLUTION", if (cam.videoUhd) "4K" else "1080p", cam.videoUhd, Modifier.weight(1f)) { cam.videoUhd = !cam.videoUhd; cam.videoOptionsChanged() }
                        ToolTile("HDR", if (cam.videoHdr) "HLG 10-bit" else "off", cam.videoHdr, Modifier.weight(1f)) { cam.videoHdr = !cam.videoHdr; cam.videoOptionsChanged() }
                        if (cam.supports60) ToolTile("FPS", if (cam.video60) "60" else "30", cam.video60, Modifier.weight(1f)) { cam.video60 = !cam.video60; cam.videoOptionsChanged() }
                    }
                }
                if (showManual) { Spacer(Modifier.height(10.dp)); ManualPanel(cam) }
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
