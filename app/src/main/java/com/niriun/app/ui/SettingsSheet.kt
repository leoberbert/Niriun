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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.net.toUri
import com.niriun.app.R
import com.niriun.app.stream.NiriunRuntime
import com.niriun.app.stream.UiState
import kotlinx.coroutines.launch

private enum class Section { CAMERA, TALLY, ABOUT }

private const val PLUGIN_URL = "https://github.com/leoberbert/nirium-obs-plugin"
private const val APP_URL = "https://github.com/leoberbert/Niriun"

/**
 * Settings as a sheet over the live preview, same structure as DroidNDI: title row with expand toggle, collapsible
 * section cards with a summary, a scroll thumb and a footer. Changes apply immediately (camera, torch, mirror), so the
 * footer only has "Close". [onGuarded] holds back changes that alter the picture while this phone is ON AIR.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsSheet(state: UiState, onGuarded: (() -> Unit) -> Unit, onSupport: () -> Unit, onDismiss: () -> Unit) {
    var open by rememberSaveable { mutableStateOf(setOf(Section.CAMERA.name)) }
    // Half height keeps the live preview visible; full height for reading.
    var tall by rememberSaveable { mutableStateOf(false) }
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()

    fun toggle(section: Section) {
        open = if (section.name in open) open - section.name else open + section.name
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
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
                        Text(stringResource(R.string.settings), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
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
                            Modifier.fillMaxWidth().verticalScroll(scroll).padding(end = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(if (landscape) 8.dp else 12.dp),
                        ) {
                            val mirrorOn = stringResource(R.string.settings_on)
                            val mirrorOff = stringResource(R.string.settings_off)
                            val camera = state.cameras.getOrNull(state.selectedCamera) ?: state.deviceName
                            SectionCard(
                                stringResource(R.string.camera_title),
                                "$camera · ${stringResource(R.string.mirror)}: ${if (state.mirror) mirrorOn else mirrorOff}",
                                Section.CAMERA.name in open,
                                { toggle(Section.CAMERA) },
                            ) {
                                if (state.cameras.isEmpty()) {
                                    Hint(stringResource(R.string.settings_no_cameras))
                                } else {
                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        state.cameras.forEachIndexed { index, name ->
                                            val checked = index == state.selectedCamera
                                            FilterChip(
                                                selected = checked,
                                                onClick = { onGuarded { NiriunRuntime.selectCamera(index) } },
                                                label = { Text(name) },
                                                leadingIcon = if (checked) {
                                                    { Icon(painterResource(R.drawable.ic_check), contentDescription = null, modifier = Modifier.size(18.dp)) }
                                                } else null,
                                            )
                                        }
                                    }
                                }
                                if (state.torchAvailable) SwitchRow(stringResource(R.string.torch), state.torch) { NiriunRuntime.setTorch(it) }
                                SwitchRow(stringResource(R.string.mirror), state.mirror) { enabled -> onGuarded { NiriunRuntime.setMirror(enabled) } }
                                Hint(stringResource(R.string.settings_camera_hint))
                            }
                            SectionCard(
                                stringResource(R.string.settings_section_tally),
                                stringResource(R.string.settings_tally_summary),
                                Section.TALLY.name in open,
                                { toggle(Section.TALLY) },
                            ) {
                                Hint(stringResource(R.string.tally_info))
                                Hint(stringResource(R.string.settings_tally_hint))
                            }
                            SectionCard(
                                stringResource(R.string.settings_section_about),
                                stringResource(R.string.settings_about_summary),
                                Section.ABOUT.name in open,
                                { toggle(Section.ABOUT) },
                            ) {
                                val context = LocalContext.current
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
                        if (scroll.maxValue > 0) {
                            val track = MaterialTheme.colorScheme.outlineVariant
                            val thumb = MaterialTheme.colorScheme.primary
                            Canvas(Modifier.matchParentSize()) {
                                val x = size.width - 3.dp.toPx()
                                val thumbHeight = (size.height * scroll.viewportSize / (scroll.viewportSize + scroll.maxValue).toFloat())
                                    .coerceIn(24.dp.toPx().coerceAtMost(size.height), size.height)
                                val top = (size.height - thumbHeight) * scroll.value / scroll.maxValue.toFloat()
                                drawLine(track, Offset(x, 0f), Offset(x, size.height), 3.dp.toPx(), StrokeCap.Round)
                                drawLine(thumb, Offset(x, top), Offset(x, top + thumbHeight), 4.dp.toPx(), StrokeCap.Round)
                            }
                        }
                    }
                    HorizontalDivider()
                    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (scroll.canScrollForward || scroll.canScrollBackward) {
                            TextButton(onClick = {
                                scope.launch {
                                    if (scroll.canScrollForward) {
                                        scroll.animateScrollTo((scroll.value + (scroll.viewportSize * 0.8f).toInt()).coerceAtMost(scroll.maxValue))
                                    } else {
                                        scroll.animateScrollTo(0)
                                    }
                                }
                            }) { Text(stringResource(if (scroll.canScrollForward) R.string.settings_more_options else R.string.settings_back_to_top)) }
                        } else {
                            Box(Modifier)
                        }
                        Button(onClick = onDismiss) { Text(stringResource(R.string.settings_close)) }
                    }
                }
            }
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
