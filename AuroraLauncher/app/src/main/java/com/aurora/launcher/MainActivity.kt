package com.aurora.launcher

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.HapticFeedbackConstants
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.drop
import android.app.ActivityOptions
import android.app.WallpaperManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.pager.*
import androidx.compose.foundation.shape.*
import androidx.compose.foundation.text.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.input.nestedscroll.*
import androidx.compose.ui.layout.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.*
import kotlinx.coroutines.*
import kotlin.math.*

data class App(val label: String, val pkg: String, val cls: String, val icon: ImageBitmap)

class MainActivity : ComponentActivity() {
    private val homeTick = mutableIntStateOf(0)
    private val resumeTick = mutableIntStateOf(0)
    private val tiltX = mutableFloatStateOf(0f)
    private val tiltY = mutableFloatStateOf(0f)
    private val sm by lazy { getSystemService(SENSOR_SERVICE) as SensorManager }
    private val tiltListener = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) { // low-passed tilt -> depth parallax
            val x = (-e.values[0] / 9.8f).coerceIn(-1f, 1f)
            val y = ((e.values[1] - 6.5f) / 9.8f).coerceIn(-1f, 1f)
            tiltX.floatValue += (x - tiltX.floatValue) * .08f
            tiltY.floatValue += (y - tiltY.floatValue) * .08f
        }
        override fun onAccuracyChanged(s: Sensor?, a: Int) {}
    }
    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val t = android.graphics.Color.TRANSPARENT
        enableEdgeToEdge(SystemBarStyle.dark(t), SystemBarStyle.dark(t))
        // ask for the highest refresh rate at the current resolution (OEMs often cap launchers at 60Hz)
        val d = windowManager.defaultDisplay; val cur = d.mode
        d.supportedModes.filter { it.physicalWidth == cur.physicalWidth && it.physicalHeight == cur.physicalHeight }
            .maxByOrNull { it.refreshRate }?.let { m -> window.attributes = window.attributes.also { it.preferredDisplayModeId = m.modeId } }
        setContent { Launcher(homeTick, resumeTick, tiltX, tiltY) }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == Intent.ACTION_MAIN) homeTick.intValue++
    }
    override fun onResume() {
        super.onResume(); resumeTick.intValue++
        sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { sm.registerListener(tiltListener, it, SensorManager.SENSOR_DELAY_GAME) }
    }
    override fun onPause() { super.onPause(); sm.unregisterListener(tiltListener) }
}

// ---------- data ----------
private val iconCache = HashMap<String, ImageBitmap>()
private var cachedApps: List<App> = emptyList() // survives resume: no re-query on every return

private fun Drawable.toIcon(size: Int): ImageBitmap {
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(bmp)
    val a = if (Build.VERSION.SDK_INT >= 26) this as? AdaptiveIconDrawable else null
    if (a != null) { // full-bleed layers -> we apply our own squircle mask
        val o = (size * 0.25f).toInt()
        listOf(a.background, a.foreground).forEach { d -> d?.setBounds(-o, -o, size + o, size + o); d?.draw(c) }
    } else { setBounds(0, 0, size, size); draw(c) }
    return bmp.asImageBitmap()
}

private fun loadApps(ctx: Context): List<App> {
    val pm = ctx.packageManager
    val q = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return pm.queryIntentActivities(q, 0).filter { it.activityInfo.packageName != ctx.packageName }.map { ri ->
        val key = ri.activityInfo.packageName + "/" + ri.activityInfo.name
        val icon = synchronized(iconCache) { iconCache.getOrPut(key) { ri.loadIcon(pm).toIcon(160) } }
        App(ri.loadLabel(pm).toString(), ri.activityInfo.packageName, ri.activityInfo.name, icon)
    }.sortedBy { it.label.lowercase() }
}

private fun List<App>.pickDock(): List<App> {
    val d = listOf("dialer", "messag", "camera", "chrome").mapNotNull { k -> firstOrNull { it.pkg.contains(k) } }
    return (d + this).distinct().take(4)
}

// ---------- visuals ----------
private fun Modifier.glass(shape: Shape, tilt: () -> Float = { 0f }) = this.clip(shape)
    .background(Brush.verticalGradient(listOf(Color.White.copy(.30f), Color.White.copy(.12f))))
    .drawWithContent {
        drawContent()
        val t = tilt()
        drawOutline(
            shape.createOutline(size, layoutDirection, this),
            Brush.linearGradient(
                colors = listOf(Color.White.copy(.85f), Color.White.copy(.05f), Color.White.copy(.4f)),
                start = Offset(size.width * (-.2f + t), 0f), end = Offset(size.width * (1.2f + t), size.height)),
            style = Stroke(2.dp.toPx()))
    }

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AppIcon(
    app: App, edit: Boolean, jig: State<Float>, sign: Float, shift: () -> Float, labels: Boolean,
    onOpen: (App, LayoutCoordinates?) -> Unit, onEdit: () -> Unit, onRemove: (App) -> Unit
) {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    // iOS-style squish + springy overshoot on release
    val s by animateFloatAsState(if (pressed) .84f else 1f, spring(0.38f, 500f), label = "press")
    val co = remember { arrayOfNulls<LayoutCoordinates>(1) }
    Column(
        Modifier.width(72.dp)
            .graphicsLayer { translationX = shift(); scaleX = s; scaleY = s; rotationZ = if (edit) jig.value * sign else 0f }
            .combinedClickable(src, null, onClick = { if (!edit) onOpen(app, co[0]) }, onLongClick = onEdit),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box {
            Image(app.icon, app.label, Modifier.size(60.dp).onGloballyPositioned { co[0] = it }.clip(RoundedCornerShape(14.dp)))
            if (edit) Box(
                Modifier.align(Alignment.TopStart).offset((-6).dp, (-6).dp).size(22.dp).clip(CircleShape)
                    .background(Color(0xCC3A3A3C)).clickable { onRemove(app) },
                contentAlignment = Alignment.Center
            ) { BasicText("−", style = TextStyle(color = Color.White, fontSize = 16.sp)) }
        }
        if (labels) BasicText(
            app.label, Modifier.padding(top = 5.dp), maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = TextStyle(color = Color.White, fontSize = 11.sp, textAlign = TextAlign.Center,
                shadow = Shadow(Color.Black.copy(.5f), Offset(0f, 1f), 4f))
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Launcher(homeTick: State<Int>, resumeTick: State<Int>, tiltX: State<Float>, tiltY: State<Float>) {
    val ctx = LocalContext.current as Activity
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val cfg = LocalConfiguration.current
    val hPx = with(density) { cfg.screenHeightDp.dp.toPx() }
    val wPx = with(density) { cfg.screenWidthDp.dp.toPx() }

    var apps by remember { mutableStateOf(cachedApps) }
    var pkgTick by remember { mutableIntStateOf(0) }
    var edit by remember { mutableStateOf(false) }
    var launching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val progress = remember { Animatable(0f) } // 0 = home, 1 = app drawer
    val drawerOpen by remember { derivedStateOf { progress.value > 0f } }
    val dock = remember(apps) { apps.pickDock() }
    val pages = remember(apps) { (apps - dock.toSet()).chunked(20) }
    val pager = rememberPagerState { pages.size.coerceAtLeast(1) }
    val shown = remember(apps, query) { if (query.isBlank()) apps else apps.filter { it.label.contains(query, true) } }
    val noJig = remember { mutableFloatStateOf(0f) }

    val jig: State<Float> = if (edit)
        rememberInfiniteTransition(label = "jig").animateFloat(-2.2f, 2.2f, infiniteRepeatable(tween(120, easing = LinearEasing), RepeatMode.Reverse), label = "j")
    else noJig

    // Velocity-aware settle: quick flick snaps, slow drag follows the finger (ColorOS 17 "intent-aware")
    val drag: (Float) -> Unit = { dy -> scope.launch { progress.snapTo((progress.value - dy / (hPx * .6f)).coerceIn(0f, 1f)) } }
    fun settle(vy: Float) {
        scope.launch {
            val t = when { vy < -900f -> 1f; vy > 900f -> 0f; else -> if (progress.value > .5f) 1f else 0f }
            progress.animateTo(t, spring(0.85f, 320f), -vy / hPx)
        }
    }
    val conn = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (available.y < 0f && progress.value < 1f) { drag(available.y); return Offset(0f, available.y) }
                return Offset.Zero
            }
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (available.y > 0f && progress.value > 0f) { drag(available.y); return Offset(0f, available.y) }
                return Offset.Zero
            }
            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                if (progress.value in 0.001f..0.999f) { settle(available.y); return available }
                return Velocity.Zero
            }
        }
    }

    val open: (App, LayoutCoordinates?) -> Unit = { a, co ->
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setClassName(a.pkg, a.cls)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        val r = co?.boundsInWindow()
        val o = if (r != null) ActivityOptions.makeScaleUpAnimation(view, r.left.toInt(), r.top.toInt(), r.width.toInt(), r.height.toInt())
        else ActivityOptions.makeBasic()
        launching = true
        try { ctx.startActivity(i, o.toBundle()) } catch (e: Exception) { launching = false }
    }
    val remove: (App) -> Unit = { ctx.startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:${it.pkg}"))) }
    val startEdit: () -> Unit = { view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS); edit = true }

    // reload the app list only when something is installed / removed / updated
    DisposableEffect(Unit) {
        val r = object : BroadcastReceiver() { override fun onReceive(c: Context?, i: Intent?) { pkgTick++ } }
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED); addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED); addAction(Intent.ACTION_PACKAGE_REPLACED); addDataScheme("package")
        }
        ContextCompat.registerReceiver(ctx, r, f, ContextCompat.RECEIVER_EXPORTED)
        onDispose { ctx.unregisterReceiver(r) }
    }
    LaunchedEffect(pkgTick) {
        val n = withContext(Dispatchers.Default) { loadApps(ctx) }
        if (n != apps) { apps = n; cachedApps = n }
    }
    LaunchedEffect(resumeTick.value) { // returning from an app: the drawer slides away instead of vanishing
        launching = false; query = ""
        if (progress.value > 0f) progress.animateTo(0f, spring(0.85f, 320f))
    }
    LaunchedEffect(launching) { if (launching) { delay(2500); launching = false } } // self-recovery if a launch fails
    LaunchedEffect(Unit) { snapshotFlow { pager.settledPage }.drop(1).collect { view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) } }
    LaunchedEffect(homeTick.value) {
        if (homeTick.value > 0) { edit = false; launch { progress.animateTo(0f, spring(0.85f, 320f)) }; pager.animateScrollToPage(0) }
    }
    // iOS-style wallpaper depth: blur grows with the drawer; wallpaper parallax follows the pages
    LaunchedEffect(Unit) {
        snapshotFlow { progress.value }.collect { if (Build.VERSION.SDK_INT >= 31) ctx.window.setBackgroundBlurRadius((it * 90).toInt()) }
    }
    LaunchedEffect(pages.size) {
        val wm = WallpaperManager.getInstance(ctx); val n = max(1, pages.size - 1)
        wm.setWallpaperOffsetSteps(1f / n, 1f)
        snapshotFlow { pager.currentPage + pager.currentPageOffsetFraction }
            .collect { wm.setWallpaperOffsets(view.windowToken, (it / n).coerceIn(0f, 1f), 0.5f) }
    }
    BackHandler(drawerOpen || edit) { if (edit) edit = false else settle(2000f) }

    // iOS-style: home zooms toward you while the app grows out of its icon
    val lScale by animateFloatAsState(if (launching) 1.12f else 1f, spring(0.8f, 260f), label = "ls")
    val lAlpha by animateFloatAsState(if (launching) 0f else 1f, spring(1f, 300f), label = "la")
    val none = remember { MutableInteractionSource() }

    Box(
        Modifier.fillMaxSize().draggable(
            rememberDraggableState { drag(it) }, Orientation.Vertical, enabled = !edit && !launching, onDragStopped = { settle(it) })
    ) {
        // ----- HOME -----
        Box(
            Modifier.fillMaxSize()
                .graphicsLayer {
                    val p = progress.value
                    scaleX = (1f - .08f * p) * lScale; scaleY = scaleX
                    alpha = (1f - p * 1.5f).coerceIn(0f, 1f) * lAlpha
                }
                .clickable(none, null) { edit = false }
        ) {
            HorizontalPager(pager, Modifier.fillMaxSize(),
                flingBehavior = PagerDefaults.flingBehavior(pager, snapAnimationSpec = spring(0.8f, 380f))) { p ->
                val o = { (pager.currentPage - p) + pager.currentPageOffsetFraction }
                val sw = { pager.currentPage + pager.currentPageOffsetFraction - pager.settledPage }
                // ColorOS-17-style stretch: trailing columns lag behind the swipe, then spring back
                val shift: (Int) -> Float = { c ->
                    val k = abs(o()) * wPx * .10f
                    if (sw() >= 0f) k * c / 3f else -k * (3 - c) / 3f
                }
                Column(
                    Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars)
                        .padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 150.dp)
                        .graphicsLayer {
                            val a = abs(o()); alpha = 1f - a * .7f; scaleX = 1f - a * .12f; scaleY = scaleX
                            translationX = tiltX.value * 6.dp.toPx(); translationY = tiltY.value * 5.dp.toPx() // depth layer 1
                        },
                    verticalArrangement = Arrangement.SpaceEvenly
                ) {
                    pages.getOrNull(p).orEmpty().chunked(4).forEachIndexed { r, row ->
                        Row(Modifier.fillMaxWidth()) {
                            row.forEachIndexed { c, app ->
                                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                                    AppIcon(app, edit, jig, if ((r + c) % 2 == 0) 1f else -1f, { shift(c) }, true, open, startEdit, remove)
                                }
                            }
                            repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
            // fluid "worm" page indicator
            Canvas(
                Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(bottom = 128.dp).size((pages.size.coerceAtLeast(1) * 16).dp, 8.dp)
            ) {
                val g = 16.dp.toPx(); val r = 3.dp.toPx(); val y = size.height / 2
                repeat(pages.size) { drawCircle(Color.White.copy(.4f), r, Offset(it * g + g / 2, y)) }
                val pos = pager.currentPage + pager.currentPageOffsetFraction; val b = floor(pos); val f = pos - b
                val l = b * g + max(0f, 2 * f - 1) * g; val rt = b * g + min(1f, 2 * f) * g
                drawRoundRect(Color.White, Offset(l + g / 2 - r, y - r), Size(rt - l + 2 * r, 2 * r), CornerRadius(r))
            }
            // floating-island glass dock
            Row(
                Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(start = 16.dp, end = 16.dp, bottom = 14.dp).fillMaxWidth()
                    .graphicsLayer { translationX = -tiltX.value * 4.dp.toPx() } // depth layer 2 (opposite)
                    .glass(RoundedCornerShape(34.dp)) { tiltX.value }.padding(vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) { dock.forEach { AppIcon(it, edit, jig, 1f, { 0f }, false, open, startEdit, remove) } }
        }

        // ----- APP DRAWER -----
        if (drawerOpen) Column(
            Modifier.fillMaxSize()
                .graphicsLayer { val p = progress.value; alpha = p * lAlpha; translationY = (1 - p) * hPx * .25f; scaleX = (.94f + .06f * p) * lScale; scaleY = scaleX }
                .background(Color.Black.copy(.25f))
                .clickable(none, null) {}
                .windowInsetsPadding(WindowInsets.systemBars).padding(horizontal = 16.dp)
        ) {
            Spacer(Modifier.height(10.dp))
            BasicTextField(
                query, { query = it }, Modifier.fillMaxWidth().glass(RoundedCornerShape(22.dp)).padding(horizontal = 18.dp, vertical = 13.dp),
                singleLine = true, textStyle = TextStyle(color = Color.White, fontSize = 16.sp), cursorBrush = SolidColor(Color.White),
                decorationBox = { inner ->
                    Box { if (query.isEmpty()) BasicText("Search apps", style = TextStyle(color = Color.White.copy(.6f), fontSize = 16.sp)); inner() }
                }
            )
            LazyVerticalGrid(
                GridCells.Fixed(4), Modifier.nestedScroll(conn).weight(1f),
                contentPadding = PaddingValues(top = 18.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                items(shown, key = { it.pkg + it.cls }) { a ->
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        AppIcon(a, false, noJig, 1f, { 0f }, true, open, {}, {})
                    }
                }
            }
        }
        if (launching) Box(Modifier.fillMaxSize().clickable(none, null) {}) // swallow touches while an app opens
    }
}
