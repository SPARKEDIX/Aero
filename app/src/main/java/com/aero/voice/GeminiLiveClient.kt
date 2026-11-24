package com.aero.voice

import android.util.Base64
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject

/**
 * Bidirectional WebSocket client for the Gemini Live API (current v1beta protocol).
 *
 * - Sends a JSON `setup` message (model, audio output modality, voice, system prompt).
 * - Streams microphone PCM16 audio (16 kHz) upstream as base64 in `realtimeInput`.
 * - Receives model audio as base64 `inlineData` (24 kHz PCM16) plus text transcriptions.
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
        fun onError(message: String)
        fun onClose(code: Int, reason: String)
    }

    companion object {
        private const val TAG = "GeminiLive"
        private const val WS_URL =
            "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
        private const val MIME_PCM_16K = "audio/pcm"
        private val AUDIO_OUT_PREAMBLE = "audio_output".toByteArray(Charsets.US_ASCII)
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
                    put("parts", org.json.JSONArray().put(JSONObject().put("text", systemInstruction)))
                })
                put("tools", org.json.JSONArray(arrayOf<Any>()))
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
