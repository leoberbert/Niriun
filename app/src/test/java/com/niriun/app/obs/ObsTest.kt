package com.niriun.app.obs

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ObsAuthTest {
    @Test
    fun matchesTheExampleFromTheProtocolDocumentation() {
        assertEquals(
            "1Ct943GAT+6YQUUX47Ia/ncufilbe6+oD6lY+5kaCu4=",
            ObsAuth.response(
                "supersecretpassword",
                "lM1GncleQOaCu9lT1yeUZhFYnqhsLLP1G5lAGo3ixaI=",
                "+IxH4CnCiqpX1rM9scsNynZzbOe4KhDeYcTNS3PDaeY=",
            ),
        )
    }
}

class TallyLogicTest {
    private fun item(name: String, enabled: Boolean = true, scene: Boolean = false, group: Boolean = false) =
        SceneItem(name, enabled, scene, group)

    private val graph = mapOf(
        "s:Live" to listOf(item("Cam"), item("Logo")),
        "s:Hidden" to listOf(item("Cam", enabled = false)),
        "s:Nested" to listOf(item("Live", scene = true)),
        "s:Grouped" to listOf(item("Overlay", group = true)),
        "g:Overlay" to listOf(item("Cam")),
        "s:Loop" to listOf(item("Loop", scene = true)),
        "s:Empty" to emptyList(),
    )
    private val items = { name: String, group: Boolean -> graph[(if (group) "g:" else "s:") + name] }

    private fun tally(program: String?, preview: String?, studio: Boolean = true) =
        TallyLogic.compute(program, preview, studio, "Cam", items)

    @Test
    fun programWinsOverPreview() = assertEquals(Tally.PROGRAM, tally("Live", "Live"))

    @Test
    fun previewOnlyCountsInStudioMode() {
        assertEquals(Tally.PREVIEW, tally("Empty", "Live"))
        assertEquals(Tally.OFF, tally("Empty", "Live", studio = false))
    }

    @Test
    fun disabledItemsDoNotCount() = assertEquals(Tally.OFF, tally("Hidden", null))

    @Test
    fun nestedScenesAndGroupsAreSearched() {
        assertEquals(Tally.PROGRAM, tally("Nested", null))
        assertEquals(Tally.PROGRAM, tally("Grouped", null))
    }

    @Test
    fun recursiveScenesTerminate() = assertEquals(Tally.OFF, tally("Loop", null))

    @Test
    fun blankSourceIsAlwaysOff() = assertEquals(Tally.OFF, TallyLogic.compute("Live", "Live", true, "", items))
}

/** Drives [ObsClient] against a scripted obs-websocket server. */
class ObsClientTest {
    @Test
    fun authenticatesThenFollowsProgramAndPreviewChanges() {
        var program = "Empty"
        val preview = "Live"
        val scenes = mapOf(
            "Empty" to JSONArray(),
            "Live" to JSONArray().put(
                JSONObject().put("sourceName", "Cam").put("sceneItemEnabled", true)
                    .put("sourceType", "OBS_SOURCE_TYPE_INPUT").put("isGroup", false),
            ),
        )
        var authenticated = false
        val server = MockWebServer()
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    val hello = JSONObject().put("obsWebSocketVersion", "5.5.0").put("rpcVersion", 1)
                        .put("authentication", JSONObject().put("challenge", "ch").put("salt", "sa"))
                    webSocket.send(JSONObject().put("op", 0).put("d", hello).toString())
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(1000, null)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    val m = JSONObject(text)
                    val d = m.getJSONObject("d")
                    when (m.getInt("op")) {
                        1 -> {
                            authenticated = d.optString("authentication") == ObsAuth.response("secret", "sa", "ch")
                            webSocket.send(JSONObject().put("op", 2).put("d", JSONObject().put("negotiatedRpcVersion", 1)).toString())
                        }
                        6 -> {
                            val type = d.getString("requestType")
                            val data = JSONObject()
                            when (type) {
                                "GetCurrentProgramScene" -> data.put("currentProgramSceneName", program)
                                "GetStudioModeEnabled" -> data.put("studioModeEnabled", true)
                                "GetCurrentPreviewScene" -> data.put("currentPreviewSceneName", preview)
                                "GetSceneItemList" -> data.put("sceneItems", scenes[d.getJSONObject("requestData").getString("sceneName")])
                                "GetInputList" -> data.put(
                                    "inputs",
                                    JSONArray().put(JSONObject().put("inputName", "Cam").put("inputKind", "dshow_input"))
                                        .put(JSONObject().put("inputName", "Logo").put("inputKind", "image_source")),
                                )
                            }
                            val response = JSONObject().put("requestType", type).put("requestId", d.getString("requestId"))
                                .put("requestStatus", JSONObject().put("result", true).put("code", 100)).put("responseData", data)
                            webSocket.send(JSONObject().put("op", 7).put("d", response).toString())
                            if (type == "GetInputList") {
                                // The operator takes the camera live.
                                program = "Live"
                                val event = JSONObject().put("eventType", "CurrentProgramSceneChanged")
                                    .put("eventData", JSONObject().put("sceneName", "Live"))
                                webSocket.send(JSONObject().put("op", 5).put("d", event).toString())
                            }
                        }
                    }
                }
            }),
        )
        server.start()
        val states = LinkedBlockingQueue<ObsState>()
        val client = ObsClient { states.add(it) }
        try {
            client.start(ObsConfig(server.hostName, server.port, "secret", "Cam"))
            val seen = mutableListOf<Tally>()
            var inputs = emptyList<String>()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
            while ((Tally.PROGRAM !in seen || inputs.isEmpty()) && System.nanoTime() < deadline) {
                val s = states.poll(500, TimeUnit.MILLISECONDS) ?: continue
                if (s.connection == ObsConnection.CONNECTED) {
                    seen += s.tally
                    if (s.inputs.isNotEmpty()) inputs = s.inputs
                }
            }
            assertTrue("password challenge must be answered", authenticated)
            assertTrue("should end on program, saw $seen", Tally.PROGRAM in seen)
            assertEquals("only video-capture inputs are offered", listOf("Cam"), inputs)
        } finally {
            client.release()
            server.shutdown()
        }
    }
}

class ObsCreateSourceTest {
    @Test
    fun createsACameraSourceInThePreviewSceneAndSelectsTheIriunDevice() {
        val calls = java.util.concurrent.CopyOnWriteArrayList<JSONObject>()
        val server = MockWebServer()
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send(JSONObject().put("op", 0).put("d", JSONObject().put("rpcVersion", 1)).toString())
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(1000, null)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    val m = JSONObject(text)
                    val d = m.getJSONObject("d")
                    if (m.getInt("op") == 1) {
                        webSocket.send(JSONObject().put("op", 2).put("d", JSONObject()).toString())
                        return
                    }
                    val type = d.getString("requestType")
                    calls += JSONObject().put("type", type).put("data", d.optJSONObject("requestData") ?: JSONObject())
                    val data = JSONObject()
                    when (type) {
                        "GetVersion" -> data.put("platform", "windows")
                        "GetStudioModeEnabled" -> data.put("studioModeEnabled", true)
                        "GetCurrentPreviewScene" -> data.put("currentPreviewSceneName", "Preview scene")
                        "GetCurrentProgramScene" -> data.put("currentProgramSceneName", "Program scene")
                        "GetInputList" -> data.put("inputs", JSONArray().put(JSONObject().put("inputName", "Niriun").put("inputKind", "dshow_input")))
                        "GetInputPropertiesListPropertyItems" -> data.put(
                            "propertyItems",
                            JSONArray().put(JSONObject().put("itemName", "OBS Virtual Camera").put("itemValue", "obs"))
                                .put(JSONObject().put("itemName", "Iriun Webcam").put("itemValue", "Iriun Webcam:id")),
                        )
                        "GetSceneItemList" -> data.put("sceneItems", JSONArray())
                    }
                    val response = JSONObject().put("requestType", type).put("requestId", d.getString("requestId"))
                        .put("requestStatus", JSONObject().put("result", true).put("code", 100)).put("responseData", data)
                    webSocket.send(JSONObject().put("op", 7).put("d", response).toString())
                }
            }),
        )
        server.start()
        val states = LinkedBlockingQueue<ObsState>()
        val created = LinkedBlockingQueue<String>()
        val client = ObsClient { states.add(it) }
        try {
            client.start(ObsConfig(server.hostName, server.port, "", ""))
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (System.nanoTime() < deadline) {
                if (states.poll(300, TimeUnit.MILLISECONDS)?.connection == ObsConnection.CONNECTED) break
            }
            client.createCameraSource("Niriun") { created.add(it) }
            assertEquals("a taken name gets a suffix", "Niriun 2", created.poll(5, TimeUnit.SECONDS))

            val create = calls.first { it.getString("type") == "CreateInput" }.getJSONObject("data")
            assertEquals("Preview scene", create.getString("sceneName"))
            assertEquals("dshow_input", create.getString("inputKind"))
            val settings = calls.first { it.getString("type") == "SetInputSettings" }.getJSONObject("data")
            assertEquals("Iriun Webcam:id", settings.getJSONObject("inputSettings").getString("video_device_id"))
        } finally {
            client.release()
            server.shutdown()
        }
    }
}
