package com.niriun.app.ui

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.net.toUri
import com.niriun.app.R
import com.niriun.app.stream.ControlsMode
import com.niriun.app.stream.FramingGrid
import com.niriun.app.stream.NiriunRuntime
import com.niriun.app.stream.Preferences
import com.niriun.app.stream.ScreenPower
import com.niriun.app.stream.StopScreen
import com.niriun.app.stream.TallyAlert
import com.niriun.app.stream.UiState
import kotlinx.coroutines.launch

private enum class Section { CAMERA, IMAGE, AUDIO, SCREEN, TALLY, ABOUT }

private const val PLUGIN_URL = "https://github.com/leoberbert/nirium-obs-plugin"
private const val PLUGIN_RELEASES_URL = "https://github.com/leoberbert/nirium-obs-plugin/releases/latest"
private const val APP_URL = "https://github.com/leoberbert/Niriun"
private const val OBS_URL = "https://obsproject.com/download"

/**
 * Settings as a sheet over the live preview, same structure as DroidNDI: title row with help and expand buttons,
 * collapsible section cards with a summary, a scroll thumb and a footer. Everything applies and is saved at once, so
 * the footer only has "Close". [onGuarded] holds back changes that alter the picture while this phone is ON AIR.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsSheet(
    state: UiState,
    prefs: Preferences,
    onPrefs: (Preferences) -> Unit,
    onGuarded: (() -> Unit) -> Unit,
    onSupport: () -> Unit,
    onDismiss: () -> Unit,
) {
    var open by rememberSaveable { mutableStateOf(setOf(Section.CAMERA.name)) }
    var help by rememberSaveable { mutableStateOf(false) }
    // Half height keeps the live preview visible; full height for reading.
    var tall by rememberSaveable { mutableStateOf(false) }
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val scroll = rememberScrollState()
    val helpScroll = rememberScrollState()
    val activeScroll = if (help) helpScroll else scroll
    val scope = rememberCoroutineScope()

    fun toggle(section: Section) {
        open = if (section.name in open) open - section.name else open + section.name
    }

    Dialog(
        onDismissRequest = { if (help) help = false else onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        // Portrait: bottom sheet with the camera above. Landscape: side panel.
        Box(Modifier.fillMaxSize().padding(12.dp), contentAlignment = if (landscape) Alignment.CenterEnd else Alignment.BottomCenter) {
            val sheet = if (landscape) {
                Modifier.fillMaxHeight().fillMaxWidth(0.55f).widthIn(max = 560.dp)
            } else {
                Modifier.widthIn(max = 840.dp).fillMaxWidth().fillMaxHeight(if (tall) 0.94f else 0.58f)
            }
            Surface(sheet.safeDrawingPadding(), shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainer) {
                Column(
                    Modifier.fillMaxSize().padding(if (landscape) 16.dp else 20.dp),
                    verticalArrangement = Arrangement.spacedBy(if (landscape) 8.dp else 12.dp),
                ) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(if (help) R.string.settings_title_help else R.string.settings),
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.titleLarge,
                        )
                        if (!help) {
                            IconButton(onClick = { help = true }) {
                                Icon(painterResource(R.drawable.ic_help_outline), contentDescription = stringResource(R.string.settings_help))
                            }
                        }
                        if (!landscape) {
                            IconButton(onClick = { tall = !tall }) {
                                Icon(
                                    painterResource(if (tall) R.drawable.ic_close_fullscreen else R.drawable.ic_open_in_full),
                                    contentDescription = stringResource(if (tall) R.string.settings_shrink else R.string.settings_expand_panel),
                                )
                            }
                        }
                    }
                    HorizontalDivider()
                    Box(Modifier.weight(1f, fill = true).fillMaxWidth()) {
                        Column(
                            Modifier.fillMaxWidth().verticalScroll(activeScroll).padding(end = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(if (landscape) 8.dp else 12.dp),
                        ) {
                            if (help) {
                                HelpContent()
                            } else {
                                val context = LocalContext.current
                                val locale = LocalLocale.current.platformLocale
                                val on = stringResource(R.string.settings_on)
                                val off = stringResource(R.string.settings_off)
                                val camera = state.cameras.getOrNull(state.selectedCamera) ?: state.deviceName

                                SectionCard(
                                    stringResource(R.string.camera_title),
                                    "$camera · ${stringResource(R.string.mirror)}: ${if (state.mirror) on else off}",
                                    Section.CAMERA.name in open,
                                    { toggle(Section.CAMERA) },
                                ) {
                                    if (state.cameras.isEmpty()) {
                                        Hint(stringResource(R.string.settings_no_cameras))
                                    } else {
                                        ChoiceRow(state.cameras, state.selectedCamera) { index -> onGuarded { NiriunRuntime.selectCamera(index) } }
                                    }
                                    if (state.torchAvailable) SwitchRow(stringResource(R.string.torch), state.torch) { NiriunRuntime.setTorch(it) }
                                    SwitchRow(stringResource(R.string.mirror), state.mirror) { enabled -> onGuarded { NiriunRuntime.setMirror(enabled) } }
                                    Hint(stringResource(R.string.settings_camera_hint))
                                }

                                val gridLabels = FramingGrid.entries.map { stringResource(gridTitle(it)) }
                                SectionCard(
                                    stringResource(R.string.settings_section_image),
                                    stringResource(
                                        R.string.settings_image_summary,
                                        String.format(locale, "%+.0f", prefs.brightness * 100),
                                        String.format(locale, "%.0f", prefs.contrast * 100),
                                        String.format(locale, "%.0f", prefs.saturation * 100),
                                        gridLabels[prefs.framingGrid.ordinal].lowercase(locale),
                                    ),
                                    Section.IMAGE.name in open,
                                    { toggle(Section.IMAGE) },
                                ) {
                                    Text(stringResource(R.string.settings_brightness, String.format(locale, "%+.0f", prefs.brightness * 100)))
                                    Slider(prefs.brightness, { onPrefs(prefs.copy(brightness = it)) }, valueRange = -1f..1f)
                                    Text(stringResource(R.string.settings_contrast, String.format(locale, "%.0f", prefs.contrast * 100)))
                                    Slider(prefs.contrast, { onPrefs(prefs.copy(contrast = it)) }, valueRange = 0.5f..1.5f)
                                    Text(stringResource(R.string.settings_saturation, String.format(locale, "%.0f", prefs.saturation * 100)))
                                    Slider(prefs.saturation, { onPrefs(prefs.copy(saturation = it)) }, valueRange = 0f..2f)
                                    TextButton(onClick = { onPrefs(prefs.copy(brightness = 0f, contrast = 1f, saturation = 1f)) }) {
                                        Text(stringResource(R.string.settings_reset_image))
                                    }
                                    Hint(stringResource(R.string.settings_image_hint))
                                    Text(stringResource(R.string.settings_grid_title), style = MaterialTheme.typography.titleSmall)
                                    ChoiceRow(gridLabels, prefs.framingGrid.ordinal) { onPrefs(prefs.copy(framingGrid = FramingGrid.entries[it])) }
                                    Hint(stringResource(R.string.settings_grid_hint))
                                }

                                SectionCard(
                                    stringResource(R.string.settings_section_audio),
                                    stringResource(if (prefs.audio) R.string.settings_audio_on else R.string.settings_audio_off),
                                    Section.AUDIO.name in open,
                                    { toggle(Section.AUDIO) },
                                ) {
                                    SwitchRow(stringResource(R.string.settings_audio_switch), prefs.audio) { onPrefs(prefs.copy(audio = it)) }
                                    Hint(stringResource(R.string.settings_audio_hint))
                                }

                                val powerLabels = ScreenPower.entries.map { stringResource(powerTitle(it)) }
                                val controlsLabels = ControlsMode.entries.map { stringResource(controlsTitle(it)) }
                                val stopLabels = StopScreen.entries.map { stringResource(stopTitle(it)) }
                                SectionCard(
                                    stringResource(R.string.settings_section_screen),
                                    stringResource(
                                        R.string.settings_screen_summary,
                                        powerLabels[prefs.screenPower.ordinal],
                                        controlsLabels[prefs.controls.ordinal],
                                        stopLabels[prefs.stopScreen.ordinal],
                                    ),
                                    Section.SCREEN.name in open,
                                    { toggle(Section.SCREEN) },
                                ) {
                                    Text(stringResource(R.string.settings_power_title), style = MaterialTheme.typography.titleSmall)
                                    ChoiceRow(powerLabels, prefs.screenPower.ordinal) { onPrefs(prefs.copy(screenPower = ScreenPower.entries[it])) }
                                    Hint(stringResource(R.string.settings_power_hint))
                                    Text(stringResource(R.string.settings_controls_title), style = MaterialTheme.typography.titleSmall)
                                    ChoiceRow(controlsLabels, prefs.controls.ordinal) { onPrefs(prefs.copy(controls = ControlsMode.entries[it])) }
                                    Hint(
                                        stringResource(
                                            when (prefs.controls) {
                                                ControlsMode.AUTO -> R.string.settings_controls_hint_auto
                                                ControlsMode.COMPACT -> R.string.settings_controls_hint_compact
                                                ControlsMode.ALWAYS -> R.string.settings_controls_hint_always
                                            },
                                        ),
                                    )
                                    Text(stringResource(R.string.settings_stop_title), style = MaterialTheme.typography.titleSmall)
                                    ChoiceRow(stopLabels, prefs.stopScreen.ordinal) { onPrefs(prefs.copy(stopScreen = StopScreen.entries[it])) }
                                    Hint(stringResource(R.string.settings_stop_hint))
                                }

                                val alertLabels = TallyAlert.entries.map { stringResource(alertTitle(it)) }
                                SectionCard(
                                    stringResource(R.string.settings_section_tally),
                                    alertLabels[prefs.tallyAlert.ordinal],
                                    Section.TALLY.name in open,
                                    { toggle(Section.TALLY) },
                                ) {
                                    Text(stringResource(R.string.settings_tally_title), style = MaterialTheme.typography.titleSmall)
                                    ChoiceRow(alertLabels, prefs.tallyAlert.ordinal) { onPrefs(prefs.copy(tallyAlert = TallyAlert.entries[it])) }
                                    Hint(stringResource(R.string.tally_info))
                                    Hint(stringResource(R.string.settings_tally_hint))
                                }

                                SectionCard(
                                    stringResource(R.string.settings_section_about),
                                    stringResource(R.string.settings_about_summary),
                                    Section.ABOUT.name in open,
                                    { toggle(Section.ABOUT) },
                                ) {
                                    Text(stringResource(R.string.settings_about_text), style = MaterialTheme.typography.bodyMedium)
                                    OutlinedButton(onClick = { openUrl(context, PLUGIN_URL) }, modifier = Modifier.fillMaxWidth()) {
                                        Text(stringResource(R.string.settings_about_plugin))
                                    }
                                    TextButton(onClick = { openUrl(context, APP_URL) }, modifier = Modifier.fillMaxWidth()) {
                                        Text(stringResource(R.string.settings_about_source))
                                    }
                                    TextButton(onClick = onSupport, modifier = Modifier.fillMaxWidth()) {
                                        Text(stringResource(R.string.settings_support_button))
                                    }
                                }
                            }
                        }
                        if (activeScroll.maxValue > 0) {
                            val track = MaterialTheme.colorScheme.outlineVariant
                            val thumb = MaterialTheme.colorScheme.primary
                            Canvas(Modifier.matchParentSize()) {
                                val x = size.width - 3.dp.toPx()
                                val thumbHeight = (size.height * activeScroll.viewportSize / (activeScroll.viewportSize + activeScroll.maxValue).toFloat())
                                    .coerceIn(24.dp.toPx().coerceAtMost(size.height), size.height)
                                val top = (size.height - thumbHeight) * activeScroll.value / activeScroll.maxValue.toFloat()
                                drawLine(track, Offset(x, 0f), Offset(x, size.height), 3.dp.toPx(), StrokeCap.Round)
                                drawLine(thumb, Offset(x, top), Offset(x, top + thumbHeight), 4.dp.toPx(), StrokeCap.Round)
                            }
                        }
                    }
                    HorizontalDivider()
                    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (activeScroll.canScrollForward || activeScroll.canScrollBackward) {
                            TextButton(onClick = {
                                scope.launch {
                                    if (activeScroll.canScrollForward) {
                                        activeScroll.animateScrollTo((activeScroll.value + (activeScroll.viewportSize * 0.8f).toInt()).coerceAtMost(activeScroll.maxValue))
                                    } else {
                                        activeScroll.animateScrollTo(0)
                                    }
                                }
                            }) { Text(stringResource(if (activeScroll.canScrollForward) R.string.settings_more_options else R.string.settings_back_to_top)) }
                        } else {
                            Box(Modifier)
                        }
                        if (help) {
                            TextButton(onClick = { help = false }) { Text(stringResource(R.string.settings_back)) }
                        } else {
                            Button(onClick = onDismiss) { Text(stringResource(R.string.settings_close)) }
                        }
                    }
                }
            }
        }
    }
}

/** What has to be done in OBS, with links for the two downloads. */
@Composable
private fun HelpContent() {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(stringResource(R.string.help_intro), style = MaterialTheme.typography.bodyMedium)
        HelpStep("1", stringResource(R.string.help_step1_title), stringResource(R.string.help_step1_detail), stringResource(R.string.help_step1_action)) {
            openUrl(context, OBS_URL)
        }
        HelpStep("2", stringResource(R.string.help_step2_title), stringResource(R.string.help_step2_detail), stringResource(R.string.help_step2_action)) {
            openUrl(context, PLUGIN_RELEASES_URL)
        }
        HelpStep("3", stringResource(R.string.help_step3_title), stringResource(R.string.help_step3_detail), null, null)
        HelpStep("4", stringResource(R.string.help_step4_title), stringResource(R.string.help_step4_detail), null, null)
        Text(
            stringResource(R.string.help_footer),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun HelpStep(number: String, title: String, detail: String, action: String?, onAction: (() -> Unit)?) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.large) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("$number. $title", style = MaterialTheme.typography.titleMedium)
            Text(detail, style = MaterialTheme.typography.bodySmall)
            if (action != null && onAction != null) TextButton(onClick = onAction) { Text(action) }
        }
    }
}

private fun gridTitle(g: FramingGrid) = when (g) {
    FramingGrid.OFF -> R.string.settings_grid_off
    FramingGrid.THIRDS -> R.string.settings_grid_thirds
    FramingGrid.CROSS -> R.string.settings_grid_cross
}

private fun powerTitle(p: ScreenPower) = when (p) {
    ScreenPower.NORMAL -> R.string.settings_power_normal
    ScreenPower.DIM -> R.string.settings_power_dim
    ScreenPower.OFF -> R.string.settings_power_off
}

private fun controlsTitle(c: ControlsMode) = when (c) {
    ControlsMode.AUTO -> R.string.settings_controls_auto
    ControlsMode.COMPACT -> R.string.settings_controls_compact
    ControlsMode.ALWAYS -> R.string.settings_controls_always
}

private fun stopTitle(s: StopScreen) = when (s) {
    StopScreen.BLACK -> R.string.settings_stop_black
    StopScreen.OFFLINE -> R.string.settings_stop_offline
}

private fun alertTitle(a: TallyAlert) = when (a) {
    TallyAlert.OFF -> R.string.settings_tally_off
    TallyAlert.PROGRAM -> R.string.settings_tally_program
    TallyAlert.PROGRAM_AND_PREVIEW -> R.string.settings_tally_program_preview
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChoiceRow(labels: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        labels.forEachIndexed { index, text ->
            val checked = index == selected
            FilterChip(
                selected = checked,
                onClick = { onSelect(index) },
                label = { Text(text) },
                leadingIcon = if (checked) {
                    { Icon(painterResource(R.drawable.ic_check), contentDescription = null, modifier = Modifier.size(18.dp)) }
                } else null,
            )
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** Collapsible group; the summary shows the current values so closed sections still inform. */
@Composable
private fun SectionCard(title: String, summary: String, expanded: Boolean, onToggle: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.large) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth()
                    .clickable(
                        onClickLabel = stringResource(if (expanded) R.string.settings_collapse else R.string.settings_expand),
                        onClick = onToggle,
                    )
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    if (!expanded) {
                        Text(
                            summary,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Icon(painterResource(if (expanded) R.drawable.ic_expand_less else R.drawable.ic_expand_more), contentDescription = null)
            }
            if (expanded) {
                Column(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    content = content,
                )
            }
        }
    }
}

private fun openUrl(context: Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) }
}
