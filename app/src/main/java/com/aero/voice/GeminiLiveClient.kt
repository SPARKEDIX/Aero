package com.aero.voice

import android.util.Base64
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject

/** A function call requested by the model. */
data class ToolCall(val id: String, val name: String, val args: JSONObject)

/** The JSON payload sent back to the model for a [ToolCall]. */
data class ToolResponse(val id: String, val name: String, val result: JSONObject)

/**
 * Bidirectional WebSocket client for the Gemini Live API (current v1beta protocol).
 *
 * - Sends a JSON `setup` message (model, audio output modality, voice, system prompt).
 * - Streams microphone PCM16 audio (16 kHz) upstream as base64 in `realtimeInput`.
 * - Receives model audio as base64 `inlineData` (24 kHz PCM16) plus text transcriptions.
 * - Exposes the `web_search` tool: the model calls it, the app answers with live JSON.
 */
class GeminiLiveClient(
    private val apiKey: String,
    private val modelId: String,
    private val voice: String,
    private val systemInstruction: String,
    private val listener: Listener
) {

    interface Listener {
        fun onOpen()
        fun onSetupComplete()
        fun onUserText(text: String)
        fun onAssistantText(text: String)
        fun onAudio(bytes: ByteArray)
        fun onTurnComplete()
        fun onToolCall(calls: List<ToolCall>) {}
        fun onToolCallCancellation(ids: List<String>) {}
        fun onError(message: String)
        fun onClose(code: Int, reason: String)
    }

    companion object {
        private const val TAG = "GeminiLive"
        private const val WS_URL =
            "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
        private const val MIME_PCM_16K = "audio/pcm"
        private val AUDIO_OUT_PREAMBLE = "audio_output".toByteArray(Charsets.US_ASCII)

        private val INTERNET_INSTRUCTIONS = """
            You can access the live internet with the `web_search` tool.
            Use it whenever the user asks about news, current events, weather, prices, sports,
            or any fact that may have changed recently. Call the tool before answering such
            questions and never claim you cannot access the internet.
            The tool returns JSON from several websites. Synthesize a concise spoken answer,
            mention the source when useful, and reply in the user's language (Hinglish if they
            speak Hinglish).
        """.trimIndent()
    }

    private val client = OkHttpClient.Builder().build()
    private var ws: WebSocket? = null

    fun connect() {
        val request = Request.Builder().url("$WS_URL?key=$apiKey").build()
        ws = client.newWebSocket(request, object : WebSocketListener() {

            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket open")
                sendSetup()
                listener.onOpen()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d(TAG, "in(text) ${text.take(300)}")
                handleJson(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                Log.d(TAG, "in(bin) ${bytes.size} bytes")
                handleBinary(bytes)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket failure", t)
                listener.onError(t.message ?: "Connection failed")
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "closing code=$code reason=$reason")
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "closed code=$code reason=$reason")
                listener.onClose(code, reason)
            }
        })
    }

    private fun sendSetup() {
        // Mirrors Google's current official example payload (2026).
        val payload = JSONObject().apply {
            put("setup", JSONObject().apply {
                put("model", "models/$modelId")
                put("generationConfig", JSONObject().apply {
                    put("responseModalities", org.json.JSONArray(arrayOf("AUDIO")))
                    put("temperature", 1.0)
                    put("speechConfig", JSONObject().apply {
                        put("voiceConfig", JSONObject().apply {
                            put("prebuiltVoiceConfig", JSONObject().apply {
                                put("voiceName", voice)
                            })
                        })
                    })
                })
                put("systemInstruction", JSONObject().apply {
                    put("parts", JSONArray().put(JSONObject().put("text", "$systemInstruction\n\n$INTERNET_INSTRUCTIONS")))
                })
                put("tools", buildTools())
                put("realtimeInputConfig", JSONObject().apply {
                    put("automaticActivityDetection", JSONObject().apply {
                        put("disabled", false)
                        put("silenceDurationMs", 2000)
                        put("prefixPaddingMs", 500)
                        put("endOfSpeechSensitivity", "END_SENSITIVITY_UNSPECIFIED")
                        put("startOfSpeechSensitivity", "START_SENSITIVITY_UNSPECIFIED")
                    })
                    put("activityHandling", "ACTIVITY_HANDLING_UNSPECIFIED")
                    put("turnCoverage", "TURN_INCLUDES_ONLY_ACTIVITY")
                })
            })
        }
        Log.d(TAG, "setup sent: $payload")
        ws?.send(payload.toString())
    }

    private fun buildTools(): JSONArray = JSONArray().put(JSONObject().apply {
        put("functionDeclarations", JSONArray().put(JSONObject().apply {
            put("name", "web_search")
            put(
                "description",
                "Search the live internet for current information from multiple websites. " +
                    "Use for news, weather, prices, sports, recent events, or anything that " +
                    "may have changed after your training data."
            )
            put("parameters", JSONObject().apply {
                put("type", "OBJECT")
                put("properties", JSONObject().apply {
                    put("query", JSONObject().apply {
                        put("type", "STRING")
                        put("description", "The search query, e.g. 'latest AI news' or 'weather in Mumbai'.")
                    })
                })
                put("required", JSONArray().put("query"))
            })
        }))
    })

    /** Send one or more function responses. Gemini continues the turn after this. */
    fun sendToolResponses(responses: List<ToolResponse>) {
        if (responses.isEmpty()) return
        val arr = JSONArray()
        responses.forEach { r ->
            arr.put(JSONObject().apply {
                put("id", r.id)
                put("name", r.name)
                put("response", r.result)
            })
        }
        val msg = JSONObject().put("toolResponse", JSONObject().put("functionResponses", arr))
        Log.d(TAG, "toolResponse sent (${responses.size}): ${msg.toString().take(400)}")
        ws?.send(msg.toString())
    }

    private var sentChunks = 0

    fun sendAudio(data: ByteArray) {
        val b64 = Base64.encodeToString(data, Base64.NO_WRAP)
        val msg = JSONObject().apply {
            put("realtimeInput", JSONObject().apply {
                put("audio", JSONObject().apply {
                    put("data", b64)
                    put("mimeType", MIME_PCM_16K)
                })
            })
        }
        ws?.send(msg.toString())
        sentChunks++
        if (sentChunks % 20 == 1) Log.d(TAG, "sent audio chunks: $sentChunks")
    }

    private fun handleJson(text: String) {
        try {
            val json = JSONObject(text)

            if (json.has("setupComplete")) {
                Log.d(TAG, "setupComplete")
                listener.onSetupComplete()
                return
            }

            if (json.has("toolCall")) {
                val tc = json.optJSONObject("toolCall")
                val arr = tc?.optJSONArray("functionCalls")
                if (arr != null) {
                    val calls = ArrayList<ToolCall>()
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        calls.add(
                            ToolCall(
                                o.optString("id"),
                                o.optString("name"),
                                o.optJSONObject("args") ?: JSONObject()
                            )
                        )
                    }
                    if (calls.isNotEmpty()) {
                        Log.d(TAG, "toolCall: ${calls.joinToString { it.name }}")
                        listener.onToolCall(calls)
                    }
                }
                return
            }

            if (json.has("toolCallCancellation")) {
                val idsArr = json.optJSONObject("toolCallCancellation")?.optJSONArray("ids")
                val ids = ArrayList<String>()
                if (idsArr != null) {
                    for (i in 0 until idsArr.length()) ids.add(idsArr.optString(i))
                }
                if (ids.isNotEmpty()) {
                    Log.d(TAG, "toolCallCancellation: $ids")
                    listener.onToolCallCancellation(ids)
                }
                return
            }

            if (json.has("serverContent")) {
                val sc = json.getJSONObject("serverContent")

                // Model audio output (base64 PCM16 24kHz)
                if (sc.has("modelTurn")) {
                    val mt = sc.getJSONObject("modelTurn")
                    if (mt.has("parts")) {
                        val parts = mt.getJSONArray("parts")
                        for (i in 0 until parts.length()) {
                            val part = parts.getJSONObject(i)
                            if (part.has("inlineData")) {
                                val b64 = part.getJSONObject("inlineData").optString("data")
                                if (b64.isNotEmpty()) {
                                    Log.d(TAG, "audio out chunk ${b64.length} chars")
                                    listener.onAudio(Base64.decode(b64, Base64.DEFAULT))
                                }
                            }
                        }
                    }
                }

                // Live transcriptions
                if (sc.has("inputTranscription")) {
                    val t = sc.getJSONObject("inputTranscription").optString("text")
                    if (t.isNotEmpty()) {
                        Log.d(TAG, "user text: $t")
                        listener.onUserText(t)
                    }
                }
                if (sc.has("outputTranscription")) {
                    val t = sc.getJSONObject("outputTranscription").optString("text")
                    if (t.isNotEmpty()) {
                        Log.d(TAG, "assistant text: $t")
                        listener.onAssistantText(t)
                    }
                }

                if (sc.optBoolean("turnComplete")) {
                    Log.d(TAG, "turnComplete (in serverContent)")
                    listener.onTurnComplete()
                }
            } else if (json.has("turnComplete")) {
                Log.d(TAG, "turnComplete")
                listener.onTurnComplete()
            }

            if (json.has("error")) {
                val err = json.getJSONObject("error")
                val msg = err.optString("message", "Unknown API error")
                Log.e(TAG, "Server error: $msg")
                listener.onError(msg)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Unparseable message: $text")
        }
    }

    /**
     * Binary frames can carry either:
     * 1. Raw PCM audio prefixed with `audio_output` (older protocol), or
     * 2. The regular JSON protocol payloads delivered as a binary frame.
     */
    private fun handleBinary(bytes: ByteString) {
        val data = bytes.toByteArray()

        if (data.size >= AUDIO_OUT_PREAMBLE.size &&
            data.copyOfRange(0, AUDIO_OUT_PREAMBLE.size).contentEquals(AUDIO_OUT_PREAMBLE)
        ) {
            val audio = data.copyOfRange(AUDIO_OUT_PREAMBLE.size, data.size)
            Log.d(TAG, "legacy audio frame ${audio.size} bytes")
            listener.onAudio(audio)
            return
        }

        val utf8 = String(data, Charsets.UTF_8)
        if (utf8.trimStart().startsWith("{")) {
            Log.d(TAG, "binary-JSON: ${utf8.take(300)}")
            try {
                handleJson(utf8)
                return
            } catch (_: Exception) {
            }
        }
        Log.d(TAG, "unhandled binary: $utf8")
    }

    fun close() {
        try {
            ws?.close(1000, "bye")
        } catch (_: Exception) {
        }
        ws = null
    }
}
