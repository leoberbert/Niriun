package com.niriun.app.stream

import android.view.Surface
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

enum class Phase { STOPPED, WAITING, CONNECTED, STREAMING }

data class UiState(
    val phase: Phase = Phase.STOPPED,
    val deviceName: String = "",
    val peer: String? = null,
    val cameras: List<String> = emptyList(),
    val selectedCamera: Int = 0,
    val streamInfo: String? = null,
    val audioActive: Boolean = false,
    val microphoneAvailable: Boolean = false,
    val torch: Boolean = false,
    val torchAvailable: Boolean = false,
    val mirror: Boolean = false,
    val fps: Float = 0f,
    val kbps: Int = 0,
    val error: String? = null,
    /** Tally reported by the PC over the control channel (message 0x30); OFF whenever no PC is connected. */
    val tally: com.niriun.app.protocol.Tally = com.niriun.app.protocol.Tally.OFF,
    val tallySource: String = "",
    /** PC report (message 0x1A): is an application reading the virtual camera? null until the PC says. */
    val pcCameraInUse: Boolean? = null,
    /** Width/height of the camera picture as the preview draws it; 0 while no camera is open. */
    val previewAspect: Float = 0f,
)

/** Process-wide bridge between the foreground service and the UI. */
object NiriunRuntime {
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    @Volatile var service: StreamService? = null

    /** Last surface offered by the UI; re-applied when the service (re)starts. */
    @Volatile var previewSurface: Surface? = null
        private set

    fun update(transform: (UiState) -> UiState) = _state.update(transform)

    /** Called by the UI; the surface is remembered even while the service is not running. */
    fun setPreviewSurface(surface: Surface?) {
        previewSurface = surface
        service?.setPreviewSurface(surface)
    }

    fun selectCamera(index: Int) {
        service?.selectCamera(index)
    }

    fun setTorch(enabled: Boolean) {
        service?.setTorch(enabled)
    }

    fun setMirror(enabled: Boolean) {
        service?.setMirror(enabled)
    }

    fun setLook(brightness: Float, contrast: Float, saturation: Float) {
        service?.setLook(brightness, contrast, saturation)
    }

    fun setZoom(percent: Int) {
        service?.setZoom(percent)
    }

    /** Turns discovery/serving on or off while the camera preview stays available. */
    fun setBroadcasting(enabled: Boolean) {
        service?.setBroadcasting(enabled)
    }
}
