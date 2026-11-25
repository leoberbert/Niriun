package com.niriun.app.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.niriun.app.R
import com.niriun.app.protocol.Tally
import com.niriun.app.protocol.Wire
import com.niriun.app.stream.NiriunRuntime
import com.niriun.app.stream.Phase
import com.niriun.app.stream.ControlsMode
import com.niriun.app.stream.FramingGrid
import com.niriun.app.stream.Preferences
import com.niriun.app.stream.ScreenPower
import com.niriun.app.stream.Settings
import com.niriun.app.stream.TallyAlert
import com.niriun.app.stream.StreamService
import com.niriun.app.stream.UiState
import java.net.Inet4Address
import java.net.NetworkInterface
import kotlinx.coroutines.delay

private enum class Tool { NONE, ZOOM, DETAILS }

/** Runtime permission introduced with Android 17 (API 37) for local-network access. */
private const val LOCAL_NETWORK_PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"
private const val LOCAL_NETWORK_API = 37

/** Zoom ratio that the 0-100 % pinch scale spans (1x to 10x). */
private const val PINCH_RANGE = 10.0

@Composable
fun MainScreen() {
    val context = LocalContext.current
    val settings = remember { Settings(context) }

    fun granted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    // Camera is required; on Android 17+ so is the local-network permission, otherwise PCs on the LAN cannot reach us.
    fun essentialGranted() = granted(Manifest.permission.CAMERA) &&
        (Build.VERSION.SDK_INT < LOCAL_NETWORK_API || granted(LOCAL_NETWORK_PERMISSION))

    var ready by remember { mutableStateOf(essentialGranted()) }
    var welcomeSeen by remember { mutableStateOf(settings.welcomeSeen || essentialGranted()) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        ready = essentialGranted()
        if (ready) StreamService.start(context)
    }
    val requestPermissions = {
        val wanted = buildList {
            add(Manifest.permission.CAMERA)
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= LOCAL_NETWORK_API) add(LOCAL_NETWORK_PERMISSION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissionLauncher.launch(wanted.toTypedArray())
    }

    // Opening the app makes the phone discoverable, like the reference.
    LaunchedEffect(ready) {
        if (ready && NiriunRuntime.service == null) StreamService.start(context)
    }

    if (!welcomeSeen) {
        WelcomeScreen(onStart = {
            settings.welcomeSeen = true
            welcomeSeen = true
            requestPermissions()
        })
    } else {
        LiveScreen(permissionsReady = ready, onRequestPermissions = requestPermissions)
    }
}

@Composable
private fun LiveScreen(permissionsReady: Boolean, onRequestPermissions: () -> Unit) {
    val context = LocalContext.current
    val state by NiriunRuntime.state.collectAsStateWithLifecycle()
    val phase = state.phase
    val streaming = phase == Phase.STREAMING
    val running = phase != Phase.STOPPED

    var tool by remember { mutableStateOf(Tool.NONE) }
    var expanded by remember { mutableStateOf(true) }
    var interaction by remember { mutableLongStateOf(0L) }
    var confirmStop by remember { mutableStateOf(false) }
    var supportOpen by remember { mutableStateOf(false) }
    var settingsOpen by remember { mutableStateOf(false) }
    var confirmStopAgain by remember { mutableStateOf(false) }
    // Actions that change the picture are held back for confirmation while this phone is ON AIR in OBS.
    var guarded by remember { mutableStateOf<(() -> Unit)?>(null) }
    var zoom by remember(state.selectedCamera) { mutableFloatStateOf(0f) }

    // Settings: saved as soon as they change; the look is applied to the preview and to the stream.
    val appSettings = remember { Settings(context) }
    var prefs by remember { mutableStateOf(appSettings.preferences) }
    val updatePrefs: (Preferences) -> Unit = {
        prefs = it
        appSettings.preferences = it
    }
    LaunchedEffect(prefs.brightness, prefs.contrast, prefs.saturation, running) {
        NiriunRuntime.setLook(prefs.brightness, prefs.contrast, prefs.saturation)
    }
    // The service stops itself 3 s after the screen goes away (app switch, screen lock): coming back starts it again.
    LifecycleEventEffect(Lifecycle.Event.ON_START) {
        if (permissionsReady && NiriunRuntime.service == null) StreamService.start(context)
    }

    // Controls fade out while streaming so the picture is clean; tapping brings them back.
    val mode = prefs.controls
    val showControls = mode == ControlsMode.ALWAYS || expanded || !permissionsReady
    val showHeader = showControls || (streaming && mode == ControlsMode.COMPACT)
    LaunchedEffect(streaming, expanded, tool, interaction, settingsOpen, mode) {
        if (streaming && mode == ControlsMode.AUTO && expanded && tool == Tool.NONE && !settingsOpen) {
            delay(5000)
            expanded = false
        }
    }
    LaunchedEffect(streaming, mode) { expanded = !streaming || mode != ControlsMode.COMPACT }
    BackHandler(enabled = !showControls) { expanded = true }
    BackHandler(enabled = showControls && tool != Tool.NONE) { tool = Tool.NONE }

    val view = LocalView.current
    val activity = LocalActivity.current
    DisposableEffect(view, running) {
        view.keepScreenOn = running
        onDispose { view.keepScreenOn = false }
    }

    // Time since the picture started flowing, shown in the top bar like DroidNDI's live summary.
    var liveSince by remember { mutableLongStateOf(0L) }
    var liveNow by remember { mutableLongStateOf(0L) }
    LaunchedEffect(streaming) {
        if (streaming) {
            liveSince = SystemClock.elapsedRealtime()
            while (true) {
                liveNow = SystemClock.elapsedRealtime()
                delay(1000)
            }
        }
    }
    val liveText = if (streaming) {
        val s = ((liveNow - liveSince) / 1000).coerceAtLeast(0)
        if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%02d:%02d".format(s / 60, s % 60)
    } else {
        null
    }

    // Tally from the PC (OBS plugin): haptic pulse and a running clock when this phone's source goes to Program.
    val tally = state.tally
    var nowMs by remember { mutableLongStateOf(0L) }
    var onAirSince by remember { mutableLongStateOf(0L) }
    LaunchedEffect(tally) {
        if (tally == Tally.PROGRAM) {
            if (prefs.tallyAlert != TallyAlert.OFF) view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            onAirSince = SystemClock.elapsedRealtime()
            while (true) {
                nowMs = SystemClock.elapsedRealtime()
                delay(1000)
            }
        }
    }
    var tallyFlash by remember { mutableStateOf(false) }
    LaunchedEffect(tally) {
        tallyFlash = tally != Tally.OFF
        if (tallyFlash) {
            delay(1200)
            tallyFlash = false
        }
    }
    val onAirText = if (tally == Tally.PROGRAM) {
        val s = ((nowMs - onAirSince) / 1000).coerceAtLeast(0)
        "%02d:%02d".format(s / 60, s % 60)
    } else {
        null
    }
    val alertOn = when (prefs.tallyAlert) {
        TallyAlert.OFF -> false
        TallyAlert.PROGRAM -> tally == Tally.PROGRAM
        TallyAlert.PROGRAM_AND_PREVIEW -> tally != Tally.OFF
    }
    LaunchedEffect(tally, prefs.tallyAlert) {
        if (tally == Tally.PREVIEW && prefs.tallyAlert == TallyAlert.PROGRAM_AND_PREVIEW) view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    // Controls hidden while streaming: dim the screen to save battery and heat (tap to bring it back).
    val immersive = !showHeader && streaming && !settingsOpen && !confirmStop && !confirmStopAgain && guarded == null
    val powerSave = immersive && prefs.screenPower != ScreenPower.NORMAL
    DisposableEffect(activity, powerSave, prefs.screenPower) {
        val window = activity?.window
        if (window != null) {
            val attrs = window.attributes
            attrs.screenBrightness = when {
                !powerSave -> WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                prefs.screenPower == ScreenPower.DIM -> 0.05f
                else -> 0.01f
            }
            window.attributes = attrs
        }
        onDispose {
            val restore = activity?.window ?: return@onDispose
            val attrs = restore.attributes
            attrs.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            restore.attributes = attrs
        }
    }
    DisposableEffect(activity, view, showHeader) {
        val controller = activity?.let { WindowCompat.getInsetsController(it.window, view) }
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (showHeader) controller?.show(WindowInsetsCompat.Type.systemBars()) else controller?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
    }

    Surface(Modifier.fillMaxSize(), color = Color.Black, contentColor = MaterialTheme.colorScheme.onSurface) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val landscape = maxWidth > maxHeight

            if (permissionsReady) {
                CameraSurface(Modifier.fillMaxSize())
                // Gesture layer: tap toggles the controls, pinch zooms.
                Box(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectTapGestures {
                                expanded = true
                                interaction = android.os.SystemClock.uptimeMillis()
                            }
                        }
                        .pointerInput(Unit) {
                            detectTransformGestures { _, _, scale, _ ->
                                if (scale != 1f) {
                                    // Multiplicative like DroidNDI: the same finger spread gives the same zoom ratio at any level.
                                    // The 0-100 % scale is logarithmic between 1x and 10x.
                                    val factor = Math.pow(PINCH_RANGE, zoom / 100.0) * scale
                                    zoom = (Math.log(factor) / Math.log(PINCH_RANGE) * 100.0).toFloat().coerceIn(0f, 100f)
                                    NiriunRuntime.setZoom(zoom.toInt())
                                    expanded = true
                                    tool = Tool.ZOOM
                                    interaction = android.os.SystemClock.uptimeMillis()
                                }
                            }
                        },
                )
            } else {
                PermissionPrompt(Modifier.align(Alignment.Center), onRequestPermissions)
            }

            if (permissionsReady) FramingGridOverlay(Modifier.fillMaxSize(), state.previewAspect, prefs.framingGrid)
            if (powerSave && prefs.screenPower == ScreenPower.OFF) Box(Modifier.fillMaxSize().background(Color.Black))
            if (tally != Tally.OFF) {
                val tallyColor = if (tally == Tally.PROGRAM) StatusColors.program else StatusColors.preview
                if (alertOn) TallyEdgeOverlay(
                    Modifier.fillMaxSize().safeDrawingPadding(),
                    if (tally == Tally.PROGRAM) StatusColors.programEdge else StatusColors.previewEdge,
                    tallyFlash,
                )
                if (!showHeader) {
                    Row(
                        Modifier.align(Alignment.TopStart).safeDrawingPadding().padding(start = 16.dp, top = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Box(
                            if (tally == Tally.PROGRAM) Modifier.size(12.dp).background(tallyColor, CircleShape)
                            else Modifier.size(12.dp).border(2.5.dp, tallyColor, CircleShape),
                        )
                        Text(
                            tallyLabel(tally),
                            color = tallyColor,
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
            }

            if (showHeader) {
                Header(
                    state = state,
                    tally = tally,
                    onAir = onAirText,
                    live = liveText,
                    modifier = Modifier.align(Alignment.TopCenter).safeDrawingPadding().padding(8.dp).fillMaxWidth(),
                    onDetails = { tool = if (tool == Tool.DETAILS) Tool.NONE else Tool.DETAILS },
                    // Low-key support entry, like DroidNDI: only while no picture is being sent.
                    onSupport = if (!streaming) ({ supportOpen = true }) else null,
                    onHide = if (permissionsReady && mode != ControlsMode.ALWAYS) ({ expanded = false }) else null,
                )
            }
            if (showControls) {
                Surface(
                    Modifier
                        .align(if (landscape) Alignment.BottomEnd else Alignment.BottomCenter)
                        .safeDrawingPadding()
                        .padding(8.dp)
                        .then(if (landscape) Modifier.width(340.dp) else Modifier.fillMaxWidth()),
                    shape = MaterialTheme.shapes.extraLarge,
                    color = MaterialTheme.colorScheme.surfaceContainer,
                ) {
                    Column(
                        Modifier
                            .heightIn(max = if (landscape) (maxHeight - 96.dp).coerceAtLeast(120.dp) else maxHeight * 0.62f)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        state.error?.let { ErrorText(it) }
                        when (tool) {
                            Tool.ZOOM -> ZoomPanel(zoom) {
                                zoom = it
                                NiriunRuntime.setZoom(it.toInt())
                                interaction = android.os.SystemClock.uptimeMillis()
                            }
                            Tool.DETAILS -> DetailsPanel(state)
                            Tool.NONE -> Unit
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            ToolButton(R.drawable.ic_zoom_in, stringResource(R.string.zoom_value, zoom.toInt()), tool == Tool.ZOOM) {
                                tool = if (tool == Tool.ZOOM) Tool.NONE else Tool.ZOOM
                            }
                            ToolButton(R.drawable.ic_info, stringResource(R.string.details), tool == Tool.DETAILS) {
                                tool = if (tool == Tool.DETAILS) Tool.NONE else Tool.DETAILS
                            }
                            ToolButton(R.drawable.ic_settings, stringResource(R.string.settings), settingsOpen) {
                                settingsOpen = true
                            }
                            Spacer(Modifier.weight(1f))
                            BroadcastButton(running, enabled = permissionsReady) {
                                when {
                                    !running -> startBroadcast(context)
                                    streaming || tally == Tally.PROGRAM -> confirmStop = true
                                    else -> NiriunRuntime.setBroadcasting(false)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (confirmStop) {
        val live = tally == Tally.PROGRAM
        AlertDialog(
            onDismissRequest = { confirmStop = false },
            title = { Text(stringResource(if (live) R.string.live_title else R.string.stop_title)) },
            text = { Text(stringResource(if (live) R.string.live_stop_text else R.string.stop_text)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmStop = false
                    if (live) confirmStopAgain = true else NiriunRuntime.setBroadcasting(false)
                }) { Text(stringResource(if (live) R.string.live_continue else R.string.stop), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmStop = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (confirmStopAgain) {
        AlertDialog(
            onDismissRequest = { confirmStopAgain = false },
            title = { Text(stringResource(R.string.live_sure_title)) },
            text = { Text(stringResource(R.string.live_sure_text)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmStopAgain = false
                    NiriunRuntime.setBroadcasting(false)
                }) { Text(stringResource(R.string.live_stop_anyway), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmStopAgain = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    guarded?.let { action ->
        AlertDialog(
            onDismissRequest = { guarded = null },
            title = { Text(stringResource(R.string.live_title)) },
            text = { Text(stringResource(R.string.live_action_text)) },
            confirmButton = {
                TextButton(onClick = {
                    guarded = null
                    action()
                }) { Text(stringResource(R.string.live_continue)) }
            },
            dismissButton = { TextButton(onClick = { guarded = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (settingsOpen) {
        SettingsSheet(
            state = state,
            prefs = prefs,
            onPrefs = updatePrefs,
            onGuarded = { action -> if (tally == Tally.PROGRAM) guarded = action else action() },
            onSupport = { settingsOpen = false; supportOpen = true },
            onDismiss = { settingsOpen = false },
        )
    }
    if (supportOpen) {
        AlertDialog(
            onDismissRequest = { supportOpen = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            text = { Column(Modifier.verticalScroll(rememberScrollState())) { ContributionContent() } },
            confirmButton = { TextButton(onClick = { supportOpen = false }) { Text(stringResource(R.string.main_close)) } },
        )
    }
}

@Composable
private fun CameraSurface(modifier: Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            SurfaceView(ctx).apply {
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) = NiriunRuntime.setPreviewSurface(holder.surface)
                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
                    override fun surfaceDestroyed(holder: SurfaceHolder) = NiriunRuntime.setPreviewSurface(null)
                })
            }
        },
        onRelease = { NiriunRuntime.setPreviewSurface(null) },
    )
}

@Composable
private fun PermissionPrompt(modifier: Modifier, onRequest: () -> Unit) {
    val context = LocalContext.current
    Column(modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.permission_title), style = MaterialTheme.typography.titleLarge)
        Text(
            stringResource(R.string.permission_body),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
        Button(onClick = onRequest, colors = ButtonDefaults.buttonColors(containerColor = BrandPurple, contentColor = Color.White)) {
            Text(stringResource(R.string.permission_grant))
        }
        TextButton(onClick = {
            context.startActivity(
                Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")),
            )
        }) { Text(stringResource(R.string.permission_open_settings)) }
    }
}

/** "ON AIR" / "PREVIEW". */
@Composable
private fun tallyLabel(tally: Tally): String =
    stringResource(if (tally == Tally.PROGRAM) R.string.tally_program else R.string.tally_preview)

private enum class IndicatorShape { FILLED, RING, LINK }

@Composable
private fun Header(
    state: UiState,
    tally: Tally,
    onAir: String?,
    live: String?,
    modifier: Modifier,
    onDetails: () -> Unit,
    onSupport: (() -> Unit)?,
    onHide: (() -> Unit)?,
) {
    val (label, color, shape) = when {
        tally == Tally.PROGRAM -> Triple(tallyLabel(tally), StatusColors.program, IndicatorShape.FILLED)
        tally == Tally.PREVIEW -> Triple(tallyLabel(tally), StatusColors.preview, IndicatorShape.RING)
        else -> when (state.phase) {
            Phase.STOPPED -> Triple(stringResource(R.string.status_stopped), StatusColors.stopped, IndicatorShape.FILLED)
            Phase.WAITING -> Triple(stringResource(R.string.status_waiting), StatusColors.waiting, IndicatorShape.FILLED)
            Phase.CONNECTED -> Triple(stringResource(R.string.status_connected), StatusColors.connected, IndicatorShape.LINK)
            Phase.STREAMING -> Triple(stringResource(R.string.status_streaming), StatusColors.streaming, IndicatorShape.RING)
        }
    }
    val base = when (state.phase) {
        Phase.STREAMING -> listOfNotNull(state.streamInfo, stringResource(R.string.stream_stats, state.fps, state.kbps)).joinToString(" · ")
        Phase.CONNECTED -> state.peer.orEmpty()
        else -> state.deviceName
    }
    val subtitle = listOfNotNull(
        onAir?.let { stringResource(R.string.tally_on_air_for, it) },
        live.takeIf { onAir == null },  // the on-air clock already says how long this has been running
        base.ifEmpty { null },
    ).joinToString(" · ")
    Surface(modifier, shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(
            Modifier.padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                Modifier.weight(1f).clickable(onClickLabel = stringResource(R.string.details), onClick = onDetails),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                when (shape) {
                    IndicatorShape.FILLED -> Box(Modifier.size(12.dp).background(color, CircleShape))
                    IndicatorShape.RING -> Box(Modifier.size(12.dp).border(2.5.dp, color, CircleShape))
                    IndicatorShape.LINK -> Icon(painterResource(R.drawable.ic_link), null, Modifier.size(16.dp), tint = color)
                }
                Column {
                    Text(label, color = color, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (subtitle.isNotEmpty()) {
                        Text(
                            subtitle,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            if (onSupport != null) {
                IconButton(onClick = onSupport) {
                    Icon(
                        painterResource(R.drawable.ic_favorite_border),
                        contentDescription = stringResource(R.string.main_support),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (onHide != null) {
                IconButton(onClick = onHide) {
                    Icon(painterResource(R.drawable.ic_fullscreen), contentDescription = stringResource(R.string.fullscreen))
                }
            }
        }
    }
}

@Composable
private fun ToolButton(icon: Int, label: String, selected: Boolean, onClick: () -> Unit) {
    FilledTonalIconToggleButton(
        checked = selected,
        onCheckedChange = { onClick() },
        colors = IconButtonDefaults.filledTonalIconToggleButtonColors(
            containerColor = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onSurface,
            checkedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            checkedContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    ) {
        Icon(painterResource(icon), contentDescription = label)
    }
}

/** Start is the brand purple; stop is red, so the two never look alike. */
@Composable
private fun BroadcastButton(running: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.heightIn(min = 48.dp),
        colors = if (running) ButtonDefaults.buttonColors(containerColor = StatusColors.stop, contentColor = StatusColors.onStop)
        else ButtonDefaults.buttonColors(containerColor = BrandPurple, contentColor = Color.White),
    ) {
        Icon(painterResource(if (running) R.drawable.ic_stop else R.drawable.ic_fiber_manual_record), null, Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(stringResource(if (running) R.string.stop else R.string.start), maxLines = 1, softWrap = false)
    }
}

@Composable
private fun ZoomPanel(zoom: Float, onChange: (Float) -> Unit) {
    Text(stringResource(R.string.zoom_value, zoom.toInt()), style = MaterialTheme.typography.titleSmall)
    Slider(value = zoom, onValueChange = onChange, valueRange = 0f..100f)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(R.string.zoom_hint),
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = { onChange(0f) }) { Text(stringResource(R.string.zoom_reset)) }
    }
}

@Composable
private fun DetailsPanel(state: UiState) {
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    val addresses = remember(state.phase) { localAddresses() }
    val firstAddress = addresses.firstOrNull()

    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.details_device), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(state.deviceName, style = MaterialTheme.typography.titleSmall)
        }
    }
    Text(stringResource(R.string.details_addresses), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (addresses.isEmpty()) {
        Text(stringResource(R.string.no_network), style = MaterialTheme.typography.bodyMedium)
    } else {
        Text(stringResource(R.string.connect_port, firstAddress.orEmpty(), Wire.TCP_PORT), fontWeight = FontWeight.SemiBold)
        TextButton(onClick = {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Niriun", firstAddress))
            copied = true
        }) {
            Icon(painterResource(if (copied) R.drawable.ic_check else R.drawable.ic_content_copy), null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(if (copied) R.string.copied else R.string.copy))
        }
    }
    state.pcCameraInUse?.let { inUse ->
        Text(
            stringResource(if (inUse) R.string.pc_camera_in_use else R.string.pc_camera_idle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Text(
        stringResource(R.string.tally_info),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (state.phase == Phase.STREAMING) {
        Text(
            stringResource(
                when {
                    state.audioActive -> R.string.audio_on
                    else -> R.string.audio_unavailable
                },
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Tally frame drawn inside the area left by the status/navigation bars (the caller applies the insets), so the whole
 * rectangle stays visible. When the bars are hidden the insets are zero and it hugs the screen edge.
 * Thickness and pulse follow DroidNDI: 3 dp at 55 %, pulsing to 5 dp at 95 % when the tally changes.
 */
@Composable
private fun TallyEdgeOverlay(modifier: Modifier, color: Color, flash: Boolean) {
    val alpha by animateFloatAsState(targetValue = if (flash) 0.95f else 0.55f, label = "tallyEdge")
    val stroke by animateFloatAsState(targetValue = if (flash) 5f else 3f, label = "tallyStroke")
    Canvas(modifier) {
        val w = stroke.dp.toPx()
        drawRect(
            color = color.copy(alpha = alpha),
            topLeft = androidx.compose.ui.geometry.Offset(w / 2, w / 2),
            size = androidx.compose.ui.geometry.Size(size.width - w, size.height - w),
            style = Stroke(width = w),
        )
    }
}

@Composable
private fun ErrorText(text: String) {
    Text(text, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
}

/**
 * The address a computer on the same network would use, best first: Wi-Fi, hotspot and Ethernet before anything else.
 * Cellular interfaces (rmnet*, ccmni*) and the 192.0.0.0/29 range used by the carrier's IPv4 translation (CLAT, e.g.
 * 192.0.0.8) are not reachable from a computer, so they are left out.
 */
private fun localAddresses(): List<String> = runCatching {
    fun rank(name: String) = when {
        name.startsWith("wlan") || name.startsWith("swlan") || name.startsWith("ap") -> 0
        name.startsWith("eth") || name.startsWith("rndis") || name.startsWith("usb") -> 1
        else -> 2
    }
    NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback && !it.name.startsWith("rmnet") && !it.name.startsWith("ccmni") && !it.name.startsWith("v4-") }
        .sortedBy { rank(it.name) }
        .flatMap { it.inetAddresses.toList() }
        .filterIsInstance<Inet4Address>()
        .mapNotNull { it.hostAddress }
        .filterNot { it.startsWith("192.0.0.") }
}.getOrDefault(emptyList())

private fun startBroadcast(context: Context) {
    if (NiriunRuntime.service == null) StreamService.start(context, broadcast = true) else NiriunRuntime.setBroadcasting(true)
}

/**
 * Thirds or a cross over the picture, to frame the shot. [aspect] is the picture's width/height, so the lines fit the
 * letterboxed image rather than the whole screen. Only drawn on this screen, never sent to OBS.
 */
@Composable
private fun FramingGridOverlay(modifier: Modifier, aspect: Float, grid: FramingGrid) {
    if (grid == FramingGrid.OFF || aspect <= 0f) return
    Canvas(modifier) {
        val w: Float
        val h: Float
        if (size.width / size.height > aspect) {
            h = size.height
            w = size.height * aspect
        } else {
            w = size.width
            h = size.width / aspect
        }
        val left = (size.width - w) / 2f
        val top = (size.height - h) / 2f
        val color = Color.White.copy(alpha = 0.45f)
        val stroke = 1.dp.toPx()
        when (grid) {
            FramingGrid.THIRDS -> for (i in 1..2) {
                val x = left + w * i / 3f
                val y = top + h * i / 3f
                drawLine(color, Offset(x, top), Offset(x, top + h), stroke)
                drawLine(color, Offset(left, y), Offset(left + w, y), stroke)
            }
            FramingGrid.CROSS -> {
                drawLine(color, Offset(left + w / 2f, top), Offset(left + w / 2f, top + h), stroke)
                drawLine(color, Offset(left, top + h / 2f), Offset(left + w, top + h / 2f), stroke)
            }
            FramingGrid.OFF -> Unit
        }
    }
}
