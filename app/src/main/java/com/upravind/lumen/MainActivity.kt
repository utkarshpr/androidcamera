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
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.foundation.verticalScroll
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
    private val pendingMode = mutableStateOf<String?>(null)      // from the home-screen long-press shortcuts
    private val pendingLook = mutableStateOf<String?>(null)     // lets `adb shell am start --es look NAME` pick a look (used for testing)

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra("look")?.let { pendingLook.value = it }
        intent.getStringExtra("mode")?.let { pendingMode.value = it }
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
        intent.getStringExtra("mode")?.let { pendingMode.value = it }
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
        val prefs = remember { Prefs(context) }
        var flareOn by remember { mutableStateOf(prefs.flareOn) }
        val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
        var showPresets by remember { mutableStateOf(false) }
        var presetName by remember { mutableStateOf<String?>(null) }
        var grid by remember { mutableStateOf(prefs.grid) }
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
            if (cam.timelapseMode) cam.toggleTimelapse()
            else if (cam.intervalMode) cam.toggleInterval()
            else if (cam.hdrMode) cam.takeHdr()
            else if (cam.portraitMode) cam.takePortrait()
            else if (videoMode) cam.toggleRecording()
            else if (cam.longMode > 0) cam.takeStack(cam.longMode)
            else if (cam.nightMode) cam.takeNight()
            else cam.takePhoto()
        }
        fun shutter() {
            haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
            if (cam.tlRunning) { cam.toggleTimelapse(); return }
            if (cam.intervalRunning) { cam.toggleInterval(); return }          // stopping must always work, even mid-capture
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
        LaunchedEffect(look, strength, previewView, cam.extras, grainTick, cam.peaking, cam.zebra) {
            cam.look = look; cam.strength = strength
            val v = previewView ?: return@LaunchedEffect
            v.post { PreviewFx.apply(v, look, strength, cam.extras, grainTick, cam.peaking, cam.zebra) }
        }
        LaunchedEffect(flareOn) { cam.flareGuard = flareOn; prefs.flareOn = flareOn }
        LaunchedEffect(Unit) { cam.flareStrength = prefs.flareStrength }
        LaunchedEffect(grid) { prefs.grid = grid }
        LaunchedEffect(cam.flareStrength) { prefs.flareStrength = cam.flareStrength }
        val aspect = ratio?.split(":")?.let { (it[1].toFloat() / it[0].toFloat()) } ?: (3f / 4f)

        var showTools by remember { mutableStateOf(false) }
        // "My Look": your own recipe, saved
        var myWarm by remember { mutableStateOf(prefs.myWarm) }
        var myContrast by remember { mutableStateOf(prefs.myContrast) }
        var mySat by remember { mutableStateOf(prefs.mySat) }
        var myVig by remember { mutableStateOf(prefs.myVignette) }
        var myGrain by remember { mutableStateOf(prefs.myGrain) }
        val myLook = remember(myWarm, myContrast, mySat, myVig, myGrain) {
            Look("MY LOOK", LookSpec(sat = mySat, contrast = myContrast, kelvin = myWarm, vignette = myVig, grain = myGrain, swatch = 0xFFC9A66B))
        }
        LaunchedEffect(myLook) {
            prefs.myWarm = myWarm; prefs.myContrast = myContrast; prefs.mySat = mySat; prefs.myVignette = myVig; prefs.myGrain = myGrain
            if (look.name == "MY LOOK") look = myLook
        }
        // level / horizon indicator from the gravity sensor
        var roll by remember { mutableStateOf(0f) }
        DisposableEffect(cam.showLevel) {
            val sm = context.getSystemService(android.content.Context.SENSOR_SERVICE) as android.hardware.SensorManager
            val sensor = sm.getDefaultSensor(android.hardware.Sensor.TYPE_ACCELEROMETER)
            val listener = object : android.hardware.SensorEventListener {
                override fun onSensorChanged(e: android.hardware.SensorEvent) {
                    roll = Math.toDegrees(kotlin.math.atan2(e.values[0].toDouble(), e.values[1].toDouble())).toFloat()
                }
                override fun onAccuracyChanged(s: android.hardware.Sensor?, a: Int) {}
            }
            if (cam.showLevel && sensor != null) sm.registerListener(listener, sensor, android.hardware.SensorManager.SENSOR_DELAY_UI)
            onDispose { sm.unregisterListener(listener) }
        }
        // last photo thumbnail (opens the gallery)
        var lastThumb by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
        var lastUri by remember { mutableStateOf<android.net.Uri?>(null) }
        LaunchedEffect(cam.message) {
            if (cam.message == null || cam.message?.contains("saved", ignoreCase = true) == true || lastThumb == null) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    try {
                        context.contentResolver.query(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, arrayOf(android.provider.MediaStore.Images.Media._ID),
                            "${android.provider.MediaStore.Images.Media.RELATIVE_PATH} LIKE ?", arrayOf("%Lumen%"), "${android.provider.MediaStore.Images.Media.DATE_ADDED} DESC")?.use { c ->
                            if (c.moveToFirst()) {
                                val uri = android.content.ContentUris.withAppendedId(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(0))
                                lastUri = uri
                                lastThumb = context.contentResolver.loadThumbnail(uri, android.util.Size(160, 160), null)
                            }
                        }
                    } catch (_: Throwable) {}
                }
            }
        }
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
        var suggestOn by remember { mutableStateOf(prefs.suggestOn) }
        var confirmReset by remember { mutableStateOf(false) }
        var suggestion by remember { mutableStateOf<Suggestion?>(null) }
        val mode = when {
            cam.timelapseMode -> "TIME-LAPSE"; cam.slowMo -> "SLOW-MO"; cam.intervalMode -> "INTERVAL"; cam.hdrMode -> "HDR"; cam.portraitMode -> "PORTRAIT"; videoMode -> "VIDEO"; cam.rawMode -> "RAW"; cam.longMode == 1 -> "LONG"; cam.longMode == 2 -> "TRAIL"; cam.nightMode -> "NIGHT"; else -> "PHOTO"
        }
        LaunchedEffect(thumbSource, suggestOn, look.name, mode) {
            val src = thumbSource
            if (!suggestOn || src == null || videoMode || cam.isRecording) { suggestion = null; return@LaunchedEffect }
            val sg = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { Suggest.from(src) }
            suggestion = if (sg != null && sg.look != look.name && sg.mode != mode) sg else null
        }
        fun resetAll() {
            cam.resetAll()
            look = Looks.all.first(); strength = 1f; videoMode = false; flareOn = true; grid = 1; ratio = null; timer = 0; countdown = null
            presetName = null; showManual = false; suggestOn = true; suggestion = null
            myWarm = 6500f; myContrast = 1f; mySat = 1f; myVig = 0f; myGrain = 0f
            prefs.look = "ORIGINAL"; prefs.flareOn = true; prefs.grid = 1; prefs.flareStrength = 1f; prefs.suggestOn = true
            cam.message = "Everything is back to normal"
        }
        fun setMode(m: String) {
            haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
            if (m == mode) {                                               // tapping the active mode again cycles its time
                if (m == "TIME-LAPSE") cam.tlSeconds = when (cam.tlSeconds) { 1 -> 2; 2 -> 5; 5 -> 10; else -> 1 }
                if (m == "INTERVAL") cam.intervalSeconds = when (cam.intervalSeconds) { 2 -> 5; 5 -> 10; 10 -> 30; else -> 2 }
                if (m == "NIGHT") cam.nightSeconds = when (cam.nightSeconds) { 3 -> 6; 6 -> 10; else -> 3 }
                if (m == "LONG" || m == "TRAIL") cam.longSeconds = when (cam.longSeconds) { 5 -> 10; 10 -> 20; 20 -> 30; else -> 5 }
                return
            }
            if (cam.slowMo && m != "SLOW-MO") { videoMode = false; cam.enterSlowMo(false) }
            if (videoMode && m != "VIDEO") { videoMode = false; cam.setVideoMode(false) }
            if (cam.rawMode && m != "RAW") cam.setRaw(false)
            if (cam.intervalRunning) cam.toggleInterval()
            if (cam.tlRunning) cam.toggleTimelapse()
            cam.timelapseMode = false
            cam.intervalMode = false
            cam.nightMode = false; cam.longMode = 0; cam.hdrMode = false; cam.portraitMode = false
            when (m) {
                "HDR" -> cam.hdrMode = true
                "PORTRAIT" -> cam.portraitMode = true
                "INTERVAL" -> cam.intervalMode = true
                "NIGHT" -> cam.nightMode = true
                "LONG" -> cam.longMode = 1
                "TRAIL" -> cam.longMode = 2
                "RAW" -> { cam.videoOnly = false; cam.setRaw(true) }
                "VIDEO" -> { videoMode = true; cam.setVideoMode(true) }
                "SLOW-MO" -> { videoMode = true; cam.enterSlowMo(true) }
                "TIME-LAPSE" -> cam.timelapseMode = true
            }
        }

        LaunchedEffect(pendingMode.value) {
            val m = pendingMode.value ?: return@LaunchedEffect
            kotlinx.coroutines.delay(1200)          // let the camera finish starting
            if (m == "SELFIE") { if (!cam.frontFacing) cam.flipCamera() } else setMode(m)
            pendingMode.value = null
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
                                val detector = android.view.GestureDetector(ctx, object : android.view.GestureDetector.SimpleOnGestureListener() {
                                    override fun onSingleTapUp(e: android.view.MotionEvent): Boolean { cam.tapToFocus(this@apply, e.x, e.y); return true }
                                    override fun onLongPress(e: android.view.MotionEvent) { cam.lockFocus(this@apply, e.x, e.y) }
                                })
                                val pinch = android.view.ScaleGestureDetector(ctx, object : android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
                                    override fun onScale(d: android.view.ScaleGestureDetector): Boolean { cam.zoomBy(d.scaleFactor); return true }
                                })
                                setOnTouchListener { v, e -> pinch.onTouchEvent(e); if (!pinch.isInProgress) detector.onTouchEvent(e); if (e.action == android.view.MotionEvent.ACTION_UP) v.performClick(); true }
                                previewView = this
                                cam.bind(owner, this)
                            }
                        }
                    )
                    GridOverlay(grid)
                    if (cam.showLevel) {
                        val level = kotlin.math.abs(roll) < 1.2f || kotlin.math.abs(kotlin.math.abs(roll) - 90f) < 1.2f || kotlin.math.abs(kotlin.math.abs(roll) - 180f) < 1.2f
                        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                            val c = if (level) Gold else Color.White.copy(alpha = 0.75f)
                            val cy = size.height / 2; val half = size.width * 0.18f
                            rotate(-roll, androidx.compose.ui.geometry.Offset(size.width / 2, cy)) {
                                drawLine(c, androidx.compose.ui.geometry.Offset(size.width / 2 - half, cy), androidx.compose.ui.geometry.Offset(size.width / 2 + half, cy), 2.dp.toPx())
                            }
                            drawLine(c.copy(alpha = 0.5f), androidx.compose.ui.geometry.Offset(size.width / 2 - half * 1.4f, cy), androidx.compose.ui.geometry.Offset(size.width / 2 - half * 1.15f, cy), 2.dp.toPx())
                            drawLine(c.copy(alpha = 0.5f), androidx.compose.ui.geometry.Offset(size.width / 2 + half * 1.15f, cy), androidx.compose.ui.geometry.Offset(size.width / 2 + half * 1.4f, cy), 2.dp.toPx())
                        }
                    }
                    // top row: tools · status · flip
                    Row(Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        GlassButton("☰") { showTools = true }
                        Spacer(Modifier.weight(1f))
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            if (cam.isRecording) GlassPill("● REC  %02d:%02d".format(cam.recSeconds / 60, cam.recSeconds % 60), Color(0xFFFF5A5A))
                            else cam.nightProgress?.let { GlassPill(it, Color(0xFF9EC5FF)) }
                            if (cam.flareWarning && cam.nightProgress == null && !cam.isRecording) GlassPill("FLARE · wipe the lens or shade it", Color(0xFFFFB454))
                            if (cam.focusLocked) GlassPill("AE/AF LOCK", Gold)
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
                    suggestion?.let { sg ->
                        Text("✦  ${sg.text}", color = Color.Black, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 96.dp).clip(RoundedCornerShape(50))
                                .background(Brush.linearGradient(listOf(GoldLight, Gold))).clickable {
                                    haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                    sg.look?.let { n -> look = Looks.byName(n); strength = 1f; presetName = null; prefs.look = n }
                                    sg.mode?.let { m -> setMode(m) }
                                    suggestion = null
                                }.padding(horizontal = 16.dp, vertical = 9.dp))
                    }
                    // zoom / lens pill
                    if (cam.activeLens.isNotEmpty()) Text("${"%.1f".format(cam.zoom)}×  ·  ${cam.activeLens}", color = Gold, fontSize = 10.sp, letterSpacing = 1.sp,
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 66.dp).clip(RoundedCornerShape(50)).background(Color(0x99000000)).padding(horizontal = 10.dp, vertical = 3.dp))
                    Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp).clip(RoundedCornerShape(50)).background(Color(0x99000000))
                        .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(50)).padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        listOf(0.6f, 1f, 2f, 3f, 5f, 10f).forEach { z ->
                            val selected = (cam.zoom - z).let { kotlin.math.abs(it) < 0.15f * z }
                            Box(Modifier.padding(horizontal = 1.dp).size(if (selected) 40.dp else 32.dp)
                                .background(if (selected) Brush.linearGradient(listOf(GoldLight, Gold)) else Brush.linearGradient(listOf(Color.Transparent, Color.Transparent)), CircleShape)
                                .clickable { haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove); cam.zoomTo(z) }, contentAlignment = Alignment.Center) {
                                Text(if (z < 1f) ".6" else z.toInt().toString() + if (selected) "×" else "", color = if (selected) Color.Black else Color.White.copy(alpha = 0.85f),
                                    fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }

            // ---- looks strip
            if (showLooks) LazyRow(Modifier.fillMaxWidth().padding(top = 4.dp), contentPadding = PaddingValues(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(listOf(Looks.all.first(), myLook) + Looks.all.drop(1)) { l ->
                    val on = l.name == look.name
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { look = l; strength = 1f; presetName = null; prefs.look = l.name }) {
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
            if (look.name == "MY LOOK" && showLooks) Column(Modifier.padding(horizontal = 22.dp)) {
                MiniSlider("WARM", myWarm, 4500f..9000f) { myWarm = it }
                MiniSlider("CONTRAST", myContrast, 0.6f..1.6f) { myContrast = it }
                MiniSlider("COLOUR", mySat, 0f..2f) { mySat = it }
                MiniSlider("VIGNETTE", myVig, 0f..1.5f) { myVig = it }
                MiniSlider("GRAIN", myGrain, 0f..1f) { myGrain = it }
            }
            else if (!look.isOriginal && showLooks) Row(Modifier.padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${(strength * 100).roundToInt()}%", color = Gold, fontSize = 11.sp, modifier = Modifier.width(38.dp))
                Slider(value = strength, onValueChange = { strength = it }, modifier = Modifier.height(24.dp),
                    colors = SliderDefaults.colors(thumbColor = Gold, activeTrackColor = Gold, inactiveTrackColor = Color.White.copy(alpha = 0.15f)))
            }

            // ---- mode selector
            Row(Modifier.fillMaxWidth().horizontalScroll(androidx.compose.foundation.rememberScrollState()).padding(top = 8.dp), horizontalArrangement = Arrangement.Center) {
                Spacer(Modifier.width(24.dp))
                listOf("PHOTO", "PORTRAIT", "NIGHT", "HDR", "RAW", "LONG", "TRAIL", "INTERVAL", "VIDEO", "SLOW-MO", "TIME-LAPSE").filter { it != "SLOW-MO" || cam.slowMoSupported }.forEach { m ->
                    val on = m == mode
                    val label = if (on && m == "NIGHT") "NIGHT ${cam.nightSeconds}s" else if (on && (m == "LONG" || m == "TRAIL")) "$m ${cam.longSeconds}s" else if (on && m == "INTERVAL") "INTERVAL ${cam.intervalSeconds}s" else if (on && m == "SLOW-MO" && cam.slowMoFps > 0) "SLOW-MO ${cam.slowMoFps}" else if (on && m == "TIME-LAPSE") "TIME-LAPSE ${cam.tlSeconds}s" else m
                    Text(label, fontSize = 12.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal, letterSpacing = 1.2.sp,
                        color = if (on) Gold else Color.White.copy(alpha = 0.5f),
                        modifier = Modifier.clickable { setMode(m) }.padding(horizontal = 14.dp, vertical = 6.dp))
                }
                Spacer(Modifier.width(24.dp))
            }

            // ---- bottom bar: presets · shutter · looks toggle
            Row(Modifier.fillMaxWidth().padding(horizontal = 26.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val t = lastThumb
                        Box(Modifier.size(44.dp).clip(CircleShape).background(Panel).border(1.dp, Color.White.copy(alpha = 0.18f), CircleShape).clickable {
                            lastUri?.let { u -> try { startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW).setDataAndType(u, "image/*").addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)) } catch (_: Throwable) {} }
                        }) { if (t != null) androidx.compose.foundation.Image(t.asImageBitmap(), "last photo", Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop) }
                        Spacer(Modifier.width(6.dp))
                        GlassTile("PRESETS", showPresets) { showPresets = !showPresets }
                    }
                }
                val recording = cam.isRecording || cam.intervalRunning || cam.tlRunning
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
            onPdf = { showTools = false; pdfPicker.launch("image/*") }, videoMode = videoMode,
            suggestOn = suggestOn, onSuggest = { suggestOn = !suggestOn; prefs.suggestOn = suggestOn }, onReset = { showTools = false; confirmReset = true }, onClose = { showTools = false })

        if (confirmReset) androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Reset everything?") },
            text = { Text("Mode, look, zoom, timer, manual settings and tools all go back to how the app starts. Your photos are not touched.") },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { confirmReset = false; resetAll() }) { Text("Reset") } },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { confirmReset = false }) { Text("Cancel") } }
        )
        var tour by remember { mutableStateOf(!prefs.tourSeen) }
        if (tour) androidx.compose.material3.AlertDialog(
            onDismissRequest = { tour = false; prefs.tourSeen = true },
            title = { Text("Welcome to Lumen") },
            text = { Text("• Tap the picture to focus; hold to lock focus & exposure\n• Pinch or use the lens buttons to zoom — the label shows the real lens\n• Swipe the mode row: Night, RAW, Long, Trail, Interval, HDR, Portrait, Video\n• Tap the active mode again to change its time\n• Volume buttons take a photo\n• ☰ opens Tools: Flare Fix, Manual, Timer, Peaking, Zebra…") },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { tour = false; prefs.tourSeen = true }) { Text("Got it") } }
        )
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
    private fun MiniSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = Gold, fontSize = 9.sp, letterSpacing = 0.8.sp, modifier = Modifier.width(66.dp))
            Slider(value = value, onValueChange = onChange, valueRange = range, modifier = Modifier.height(20.dp),
                colors = SliderDefaults.colors(thumbColor = Gold, activeTrackColor = Gold, inactiveTrackColor = Color.White.copy(alpha = 0.15f)))
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
        Text(text, color = if (on) Color.Black else Color.White.copy(alpha = 0.85f), fontSize = 11.sp, letterSpacing = 0.6.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false,
            modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(if (on) Brush.linearGradient(listOf(GoldLight, Gold)) else Brush.linearGradient(listOf(Color(0xFF1B1B21), Color(0xFF131317))))
                .border(1.dp, Color.White.copy(alpha = if (on) 0f else 0.10f), RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(horizontal = 11.dp, vertical = 12.dp))
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
                           timer: Int, onTimer: () -> Unit, onPdf: () -> Unit, videoMode: Boolean, suggestOn: Boolean, onSuggest: () -> Unit, onReset: () -> Unit, onClose: () -> Unit) {
        Box(Modifier.fillMaxSize().background(Color(0x99000000)).clickable(onClick = onClose)) {
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                .background(Color(0xFF0F0F13)).border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                .clickable(enabled = false) {}.navigationBarsPadding().verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(18.dp)) {
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
                if (flareOn) Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("STRENGTH ${(cam.flareStrength * 100).roundToInt()}%", color = Gold, fontSize = 10.sp, modifier = Modifier.width(110.dp))
                    Slider(value = cam.flareStrength, onValueChange = { cam.flareStrength = it }, valueRange = 0.2f..1.5f, modifier = Modifier.height(24.dp),
                        colors = SliderDefaults.colors(thumbColor = Gold, activeTrackColor = Gold, inactiveTrackColor = Color.White.copy(alpha = 0.15f)))
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ToolTile("GRID", listOf("off", "thirds", "centre", "columns", "diagonal")[grid], grid > 0, Modifier.weight(1f), onGrid)
                    ToolTile("TIMER", if (timer == 0) "off" else "${timer} seconds", timer > 0, Modifier.weight(1f), onTimer)
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ToolTile("FOCUS PEAKING", if (cam.peaking) "red edges = sharp" else "off", cam.peaking, Modifier.weight(1f)) { cam.peaking = !cam.peaking }
                    ToolTile("ZEBRA", if (cam.zebra) "stripes = overexposed" else "off", cam.zebra, Modifier.weight(1f)) { cam.zebra = !cam.zebra }
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ToolTile("LEVEL", if (cam.showLevel) "horizon line" else "off", cam.showLevel, Modifier.weight(1f)) { cam.showLevel = !cam.showLevel }
                    ToolTile("WHITE BALANCE", listOf("auto", "daylight", "cloudy", "incandescent", "fluorescent", "shade")[cam.wbMode], cam.wbMode > 0, Modifier.weight(1f)) {
                        cam.wbMode = (cam.wbMode + 1) % 6; cam.applyManual() }
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ToolTile("HISTOGRAM", if (cam.showHistogram) "on" else "off", cam.showHistogram, Modifier.weight(1f)) { cam.showHistogram = !cam.showHistogram }
                    ToolTile("BURST", if (cam.burst) "5 shots per press" else "off", cam.burst, Modifier.weight(1f)) { cam.burst = !cam.burst }
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ToolTile("SUGGESTIONS", if (suggestOn) "tap ideas on screen" else "off", suggestOn, Modifier.weight(1f), onSuggest)
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ToolTile("PHOTOS → PDF", "1, 2 or 4 per page", false, Modifier.weight(1f), onPdf)
                }
                if (videoMode) {
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        ToolTile("FRAME RATE", if (cam.videoFps == 24) "24 · cinematic" else "30", cam.videoFps == 24, Modifier.weight(1f)) { cam.videoFps = if (cam.videoFps == 24) 30 else 24; cam.videoOptionsChanged() }
                        ToolTile("STABILISE", if (cam.stabilise) "on" else "off", cam.stabilise, Modifier.weight(1f)) { cam.stabilise = !cam.stabilise; cam.videoOptionsChanged() }
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        ToolTile("RESOLUTION", if (cam.videoUhd) "4K" else "1080p", cam.videoUhd, Modifier.weight(1f)) { cam.videoUhd = !cam.videoUhd; cam.videoOptionsChanged() }
                        ToolTile("HDR", if (cam.videoHdr) "HLG 10-bit" else "off", cam.videoHdr, Modifier.weight(1f)) { cam.videoHdr = !cam.videoHdr; cam.videoOptionsChanged() }
                        if (cam.supports60) ToolTile("FPS", if (cam.video60) "60" else "30", cam.video60, Modifier.weight(1f)) { cam.video60 = !cam.video60; cam.videoOptionsChanged() }
                    }
                }
                if (showManual) { Spacer(Modifier.height(10.dp)); ManualPanel(cam) }
                Spacer(Modifier.height(14.dp))
                Text("RESET EVERYTHING TO NORMAL", color = Color(0xFFFF8A80), fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Color(0xFF1A1214)).border(1.dp, Color(0x55FF8A80), RoundedCornerShape(18.dp)).clickable(onClick = onReset).padding(vertical = 14.dp))
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
