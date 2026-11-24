package com.niriun.app.obs

import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject

data class ObsConfig(val host: String, val port: Int, val password: String, val source: String) {
    val isConfigured: Boolean get() = host.isNotBlank()
}

enum class ObsConnection { OFF, CONNECTING, CONNECTED, ERROR }

data class ObsState(
    val connection: ObsConnection = ObsConnection.OFF,
    val tally: Tally = Tally.OFF,
    /** Video-capture style inputs in OBS, offered as choices for the source name. */
    val inputs: List<String> = emptyList(),
    val message: String? = null,
    /** True while "create source in OBS" is running. */
    val creating: Boolean = false,
    val createResult: CreateResult? = null,
)

sealed interface CreateResult {
    /** [deviceSet]: the phone's camera device was found and selected automatically. */
    data class Created(val name: String, val deviceSet: Boolean) : CreateResult
    data class Failed(val message: String) : CreateResult
}

/**
 * obs-websocket v5 client that watches which scene is in Program/Preview and reports whether [ObsConfig.source]
 * is visible there. Reconnects by itself. [onState] is called from background threads.
 */
class ObsClient(
    private val http: OkHttpClient = OkHttpClient.Builder().pingInterval(15, TimeUnit.SECONDS).readTimeout(0, TimeUnit.SECONDS).build(),
    private val onState: (ObsState) -> Unit,
) {
    private var config = ObsConfig("", 4455, "", "")
    private val running = AtomicBoolean(false)
    private val generation = AtomicInteger()
    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { Thread(it, "niriun-obs-retry") }
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "niriun-obs-worker") }
    private val pending = ConcurrentHashMap<String, CompletableFuture<JSONObject>>()
    private val refreshQueued = AtomicBoolean(false)
    private val requestIds = AtomicInteger()

    @Volatile private var socket: WebSocket? = null
    @Volatile private var state = ObsState()

    fun start(newConfig: ObsConfig) {
        stopSocket()
        config = newConfig
        if (!newConfig.isConfigured) {
            running.set(false)
            publish(ObsState())
            return
        }
        running.set(true)
        connect(generation.incrementAndGet())
    }

    fun stop() {
        running.set(false)
        generation.incrementAndGet()
        stopSocket()
        publish(ObsState())
    }

    /** Changes the watched source without reconnecting. */
    fun setSource(source: String) {
        config = config.copy(source = source)
        refresh()
    }

    /**
     * Adds a video-capture source to OBS (in the Preview scene when studio mode is on, so nothing goes live) and
     * tries to point it at the Iriun virtual camera. [onCreated] gets the final source name.
     */
    fun createCameraSource(baseName: String, onCreated: (String) -> Unit) {
        if (state.connection != ObsConnection.CONNECTED || state.creating) return
        publish(state.copy(creating = true, createResult = null))
        worker.execute {
            val result = try {
                doCreate(baseName)
            } catch (e: Exception) {
                CreateResult.Failed(e.message ?: "Could not create the source")
            }
            if (result is CreateResult.Created) onCreated(result.name)
            publish(state.copy(creating = false, createResult = result))
            loadInputs()
        }
    }

    private fun doCreate(baseName: String): CreateResult {
        val platform = request("GetVersion").optJSONObject("responseData")?.optString("platform").orEmpty()
        val kind = when {
            platform.startsWith("win") -> "dshow_input"
            platform.startsWith("mac") -> "av_capture_input"
            else -> "v4l2_input"
        }
        val studio = request("GetStudioModeEnabled").optJSONObject("responseData")?.optBoolean("studioModeEnabled") == true
        val scene = if (studio) {
            request("GetCurrentPreviewScene").optJSONObject("responseData")?.optString("currentPreviewSceneName")
        } else {
            request("GetCurrentProgramScene").optJSONObject("responseData")?.optString("currentProgramSceneName")
        }
        if (scene.isNullOrEmpty()) return CreateResult.Failed("No scene available")

        val existing = request("GetInputList").optJSONObject("responseData")?.optJSONArray("inputs")
            ?.let { a -> (0 until a.length()).map { a.getJSONObject(it).optString("inputName") }.toSet() }.orEmpty()
        var name = baseName
        var n = 2
        while (name in existing) name = "$baseName ${n++}"

        val created = request(
            "CreateInput",
            JSONObject().put("sceneName", scene).put("inputName", name).put("inputKind", kind).put("sceneItemEnabled", true),
        )
        if (created.optBoolean("failed")) return CreateResult.Failed(created.optString("comment").ifBlank { "OBS refused to create the source" })

        var deviceSet = false
        if (kind == "dshow_input") {
            val items = request(
                "GetInputPropertiesListPropertyItems",
                JSONObject().put("inputName", name).put("propertyName", "video_device_id"),
            ).optJSONObject("responseData")?.optJSONArray("propertyItems")
            val match = items?.let { a ->
                (0 until a.length()).map { a.getJSONObject(it) }.firstOrNull { it.optString("itemName").contains("iriun", ignoreCase = true) }
            }
            if (match != null) {
                val set = request(
                    "SetInputSettings",
                    JSONObject().put("inputName", name).put("overlay", true)
                        .put("inputSettings", JSONObject().put("video_device_id", match.optString("itemValue"))),
                )
                deviceSet = !set.optBoolean("failed")
            }
        }
        return CreateResult.Created(name, deviceSet)
    }

    fun release() {
        stop()
        scheduler.shutdownNow()
        worker.shutdownNow()
    }

    private fun stopSocket() {
        socket?.close(1000, null)
        socket = null
        failPending()
    }

    private fun connect(gen: Int) {
        if (!running.get() || gen != generation.get()) return
        publish(ObsState(ObsConnection.CONNECTING))
        val request = try {
            Request.Builder().url("ws://${config.host.trim()}:${config.port}").build()
        } catch (e: IllegalArgumentException) {
            running.set(false)
            publish(ObsState(ObsConnection.ERROR, message = "Invalid address"))
            return
        }
        socket = http.newWebSocket(request, Listener(gen))
    }

    private fun retryLater(gen: Int, message: String?, delaySeconds: Long = 3) {
        if (!running.get() || gen != generation.get()) return
        publish(ObsState(ObsConnection.ERROR, message = message))
        runCatching { scheduler.schedule({ connect(gen) }, delaySeconds, TimeUnit.SECONDS) }
    }

    private fun publish(s: ObsState) {
        state = s
        onState(s)
    }

    private fun send(ws: WebSocket, op: Int, data: JSONObject) {
        ws.send(JSONObject().put("op", op).put("d", data).toString())
    }

    private fun request(type: String, data: JSONObject? = null): JSONObject {
        val ws = socket ?: throw IllegalStateException("not connected")
        val id = "n${requestIds.incrementAndGet()}"
        val future = CompletableFuture<JSONObject>()
        pending[id] = future
        val d = JSONObject().put("requestType", type).put("requestId", id)
        if (data != null) d.put("requestData", data)
        send(ws, OP_REQUEST, d)
        try {
            val response = future.get(3, TimeUnit.SECONDS)
            return response
        } finally {
            pending.remove(id)
        }
    }

    private fun failPending() {
        pending.values.forEach { it.completeExceptionally(IllegalStateException("closed")) }
        pending.clear()
    }

    /** Coalesces bursts of events into one scene-graph read. */
    private fun refresh() {
        if (refreshQueued.getAndSet(true)) return
        runCatching {
            worker.execute {
                refreshQueued.set(false)
                try {
                    doRefresh()
                } catch (_: Exception) {
                    // Timeout or closed socket: the next event or reconnect refreshes again.
                }
            }
        }
    }

    private fun doRefresh() {
        val program = request("GetCurrentProgramScene").optJSONObject("responseData")?.optString("currentProgramSceneName")
        val studio = request("GetStudioModeEnabled").optJSONObject("responseData")?.optBoolean("studioModeEnabled") == true
        val preview = if (studio) {
            request("GetCurrentPreviewScene").optJSONObject("responseData")?.optString("currentPreviewSceneName")
        } else {
            null
        }
        val cache = HashMap<String, List<SceneItem>?>()
        val tally = TallyLogic.compute(program, preview, studio, config.source) { name, group ->
            cache.getOrPut((if (group) "g:" else "s:") + name) { fetchItems(name, group) }
        }
        if (state.connection == ObsConnection.CONNECTED && state.tally != tally) publish(state.copy(tally = tally))
    }

    private fun fetchItems(name: String, group: Boolean): List<SceneItem>? {
        val type = if (group) "GetGroupSceneItemList" else "GetSceneItemList"
        val response = request(type, JSONObject().put("sceneName", name))
        val array = response.optJSONObject("responseData")?.optJSONArray("sceneItems") ?: return null
        return List(array.length()) { i ->
            val o = array.getJSONObject(i)
            SceneItem(
                name = o.optString("sourceName"),
                enabled = o.optBoolean("sceneItemEnabled", true),
                isScene = o.optString("sourceType") == "OBS_SOURCE_TYPE_SCENE" && !o.optBoolean("isGroup"),
                isGroup = o.optBoolean("isGroup"),
            )
        }
    }

    private fun loadInputs() {
        worker.execute {
            try {
                val array = request("GetInputList").optJSONObject("responseData")?.optJSONArray("inputs") ?: return@execute
                val names = (0 until array.length()).map { array.getJSONObject(it) }
                    .filter { it.optString("inputKind") in SOURCE_KINDS }
                    .map { it.optString("inputName") }
                if (state.connection == ObsConnection.CONNECTED) publish(state.copy(inputs = names))
            } catch (_: Exception) {
            }
        }
    }

    private inner class Listener(private val gen: Int) : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            if (gen != generation.get()) return
            val message = runCatching { JSONObject(text) }.getOrNull() ?: return
            val d = message.optJSONObject("d") ?: JSONObject()
            when (message.optInt("op", -1)) {
                OP_HELLO -> identify(webSocket, d)
                OP_IDENTIFIED -> {
                    publish(ObsState(ObsConnection.CONNECTED))
                    refresh()
                    loadInputs()
                }
                OP_EVENT -> when (d.optString("eventType")) {
                    in REFRESH_EVENTS -> refresh()
                    "InputCreated", "InputRemoved", "InputNameChanged" -> {
                        loadInputs()
                        refresh()
                    }
                }
                OP_RESPONSE -> pending[d.optString("requestId")]?.let { future ->
                    val status = d.optJSONObject("requestStatus")
                    if (status?.optBoolean("result") == false) {
                        // A failed request (e.g. no preview scene outside studio mode) still answers the waiter.
                        future.complete(JSONObject().put("failed", true).put("comment", status.optString("comment")))
                    } else {
                        future.complete(d)
                    }
                }
            }
        }

        private fun identify(ws: WebSocket, hello: JSONObject) {
            val identify = JSONObject().put("rpcVersion", 1).put("eventSubscriptions", SUBSCRIPTIONS)
            // Servers with authentication disabled send no challenge; nothing is added then.
            hello.optJSONObject("authentication")?.let { auth ->
                if (config.password.isEmpty()) {
                    retryLater(gen, "OBS requires a password (leave it empty only if authentication is off)", 10)
                    ws.close(1000, null)
                    return
                }
                identify.put("authentication", ObsAuth.response(config.password, auth.optString("salt"), auth.optString("challenge")))
            }
            send(ws, OP_IDENTIFY, identify)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (gen != generation.get()) return
            failPending()
            if (code == CLOSE_AUTH_FAILED) retryLater(gen, "Wrong OBS password", 10)
            else retryLater(gen, "OBS closed the connection ($code ${reason.ifBlank { "no reason" }})")
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (gen != generation.get()) return
            failPending()
            retryLater(gen, t.message ?: "Cannot reach OBS")
        }
    }

    private companion object {
        const val OP_HELLO = 0
        const val OP_IDENTIFY = 1
        const val OP_IDENTIFIED = 2
        const val OP_EVENT = 5
        const val OP_REQUEST = 6
        const val OP_RESPONSE = 7
        const val CLOSE_AUTH_FAILED = 4009

        // Scenes (4) + Inputs (8) + SceneItems (128) + Ui (1024: studio mode toggles).
        const val SUBSCRIPTIONS = 4 or 8 or 128 or 1024

        val REFRESH_EVENTS = setOf(
            "CurrentProgramSceneChanged",
            "CurrentPreviewSceneChanged",
            "StudioModeStateChanged",
            "SceneItemEnableStateChanged",
            "SceneItemCreated",
            "SceneItemRemoved",
            "SceneCreated",
            "SceneRemoved",
        )
        val SOURCE_KINDS = setOf("dshow_input", "av_capture_input", "v4l2_input", "ndi_source")
    }
}
