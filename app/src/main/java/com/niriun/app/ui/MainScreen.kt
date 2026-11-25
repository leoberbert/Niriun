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
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.niriun.app.R
import com.niriun.app.protocol.Tally
import com.niriun.app.protocol.Wire
import com.niriun.app.stream.NiriunRuntime
import com.niriun.app.stream.Phase
import com.niriun.app.stream.Settings
import com.niriun.app.stream.StreamService
import com.niriun.app.stream.UiState
import java.net.Inet4Address
import java.net.NetworkInterface
import kotlinx.coroutines.delay

private enum class Tool { NONE, ZOOM, DETAILS, SETTINGS }

/** Runtime permission introduced with Android 17 (API 37) for local-network access. */
private const val LOCAL_NETWORK_PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"
private const val LOCAL_NETWORK_API = 37

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
    var confirmStopAgain by remember { mutableStateOf(false) }
    // Actions that change the picture are held back for confirmation while this phone is ON AIR in OBS.
    var guarded by remember { mutableStateOf<(() -> Unit)?>(null) }
    var zoom by remember(state.selectedCamera) { mutableFloatStateOf(0f) }

    // Controls fade out while streaming so the picture is clean; tapping brings them back.
    val showControls = expanded || !streaming
    LaunchedEffect(streaming, expanded, tool, interaction) {
        if (streaming && expanded && tool == Tool.NONE) {
            delay(5000)
            expanded = false
        }
    }
    LaunchedEffect(streaming) { expanded = true }
    BackHandler(enabled = !showControls) { expanded = true }
    BackHandler(enabled = showControls && tool != Tool.NONE) { tool = Tool.NONE }

    val view = LocalView.current
    val activity = LocalActivity.current
    DisposableEffect(view, running) {
        view.keepScreenOn = running
        onDispose { view.keepScreenOn = false }
    }

    // Tally from the PC (OBS plugin): haptic pulse and a running clock when this phone's source goes to Program.
    val tally = state.tally
    var nowMs by remember { mutableLongStateOf(0L) }
    var onAirSince by remember { mutableLongStateOf(0L) }
    LaunchedEffect(tally) {
        if (tally == Tally.PROGRAM) {
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
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
    DisposableEffect(activity, view, showControls) {
        val controller = activity?.let { WindowCompat.getInsetsController(it.window, view) }
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (showControls) controller?.show(WindowInsetsCompat.Type.systemBars()) else controller?.hide(WindowInsetsCompat.Type.systemBars())
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
                                expanded = !expanded || !streaming
                                interaction = android.os.SystemClock.uptimeMillis()
                            }
                        }
                        .pointerInput(Unit) {
                            detectTransformGestures { _, _, scale, _ ->
                                if (scale != 1f) {
                                    zoom = (zoom + (scale - 1f) * 100f).coerceIn(0f, 100f)
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

            if (tally != Tally.OFF) {
                val tallyColor = if (tally == Tally.PROGRAM) StatusColors.program else StatusColors.preview
                TallyEdgeOverlay(
                    Modifier.fillMaxSize().safeDrawingPadding(),
                    if (tally == Tally.PROGRAM) StatusColors.programEdge else StatusColors.previewEdge,
                    tallyFlash,
                )
                if (!showControls) {
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
                            tallyLabel(tally, state.tallySource),
                            color = tallyColor,
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
            }

            if (showControls) {
                Header(
                    state = state,
                    tally = tally,
                    onAir = onAirText,
                    modifier = Modifier.align(Alignment.TopCenter).safeDrawingPadding().padding(8.dp).fillMaxWidth(),
                    onDetails = { tool = if (tool == Tool.DETAILS) Tool.NONE else Tool.DETAILS },
                    onHide = if (streaming) ({ expanded = false }) else null,
                )
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
                            Tool.SETTINGS -> SettingsPanel(state) { action ->
                                if (tally == Tally.PROGRAM) guarded = action else action()
                            }
                            Tool.NONE -> Unit
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            ToolButton(R.drawable.ic_zoom_in, stringResource(R.string.zoom_value, zoom.toInt()), tool == Tool.ZOOM) {
                                tool = if (tool == Tool.ZOOM) Tool.NONE else Tool.ZOOM
                            }
                            ToolButton(R.drawable.ic_info, stringResource(R.string.details), tool == Tool.DETAILS) {
                                tool = if (tool == Tool.DETAILS) Tool.NONE else Tool.DETAILS
                            }
                            ToolButton(R.drawable.ic_settings, stringResource(R.string.settings), tool == Tool.SETTINGS) {
                                tool = if (tool == Tool.SETTINGS) Tool.NONE else Tool.SETTINGS
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

/** "ON AIR" / "PREVIEW", followed by the OBS source name when the plugin reported one. */
@Composable
private fun tallyLabel(tally: Tally, source: String): String =
    if (source.isBlank()) stringResource(if (tally == Tally.PROGRAM) R.string.tally_program else R.string.tally_preview)
    else stringResource(if (tally == Tally.PROGRAM) R.string.tally_program_named else R.string.tally_preview_named, source)

private enum class IndicatorShape { FILLED, RING, LINK }

@Composable
private fun Header(state: UiState, tally: Tally, onAir: String?, modifier: Modifier, onDetails: () -> Unit, onHide: (() -> Unit)?) {
    val (label, color, shape) = when {
        tally == Tally.PROGRAM -> Triple(tallyLabel(tally, state.tallySource), StatusColors.program, IndicatorShape.FILLED)
        tally == Tally.PREVIEW -> Triple(tallyLabel(tally, state.tallySource), StatusColors.preview, IndicatorShape.RING)
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
    val subtitle = listOfNotNull(onAir?.let { stringResource(R.string.tally_on_air_for, it) }, base.ifEmpty { null }).joinToString(" · ")
    Surface(modifier, shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(
            Modifier.padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                Modifier.weight(1f),
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
        addresses.forEach {
            Text(stringResource(R.string.connect_port, it, Wire.TCP_PORT), fontWeight = FontWeight.SemiBold)
        }
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

@Composable
private fun SettingsPanel(state: UiState, onGuarded: (() -> Unit) -> Unit) {
    if (state.cameras.isNotEmpty()) {
        Text(stringResource(R.string.camera_title), style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            state.cameras.forEachIndexed { index, name ->
                FilterChip(selected = index == state.selectedCamera, onClick = { onGuarded { NiriunRuntime.selectCamera(index) } }, label = { Text(name) })
            }
        }
        if (state.torchAvailable) SwitchRow(stringResource(R.string.torch), state.torch) { NiriunRuntime.setTorch(it) }
        SwitchRow(stringResource(R.string.mirror), state.mirror) { enabled -> onGuarded { NiriunRuntime.setMirror(enabled) } }
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
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun ErrorText(text: String) {
    Text(text, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
}

private fun localAddresses(): List<String> = runCatching {
    NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { it.inetAddresses.toList() }
        .filterIsInstance<Inet4Address>()
        .mapNotNull { it.hostAddress }
}.getOrDefault(emptyList())

private fun startBroadcast(context: Context) {
    if (NiriunRuntime.service == null) StreamService.start(context, broadcast = true) else NiriunRuntime.setBroadcasting(true)
}
