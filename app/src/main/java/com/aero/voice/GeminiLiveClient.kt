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

        private val INTERNET_INSTRUCTIONS = AeroIdentity.TEXT + """

            You can access the live internet with the `web_search` tool.
            You also receive live camera and screen-share images as video frames
            whenever those features are ACTIVE. Only describe what you actually
            see in recent frames. If you have NOT received frames recently,
            say clearly that you cannot see the screen and suggest enabling
            screen sharing. Never pretend to see the screen when sharing is off.
            Use internet search whenever the user asks about news, current events, weather, prices, sports,
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

    private fun buildTools(): JSONArray {
        val arr = JSONArray()

        // web_search
        arr.put(JSONObject().apply {
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

        // open_app
        arr.put(JSONObject().apply {
            put("functionDeclarations", JSONArray().put(JSONObject().apply {
                put("name", "open_app")
                put(
                    "description",
                    "Open an app on the user's phone. Use the app's display name, e.g. 'WhatsApp', 'Chrome', 'YouTube'."
                )
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject().apply {
                        put("app_name", JSONObject().apply {
                            put("type", "STRING")
                            put("description", "Display name of the app to open.")
                        })
                    })
                    put("required", JSONArray().put("app_name"))
                })
            }))
        })

        // close_app
        arr.put(JSONObject().apply {
            put("functionDeclarations", JSONArray().put(JSONObject().apply {
                put("name", "close_app")
                put(
                    "description",
                    "Close or minimize an app on the user's phone. Use the app's display name."
                )
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject().apply {
                        put("app_name", JSONObject().apply {
                            put("type", "STRING")
                            put("description", "Display name of the app to close or minimize.")
                        })
                    })
                    put("required", JSONArray().put("app_name"))
                })
            }))
        })

        // clear_recents
        arr.put(JSONObject().apply {
            put("functionDeclarations", JSONArray().put(JSONObject().apply {
                put("name", "clear_recents")
                put(
                    "description",
                    "Open the recent apps screen and clear all recent apps."
                )
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject())
                    put("required", JSONArray())
                })
            }))
        })

        // make_phone_call
        arr.put(JSONObject().apply {
            put("functionDeclarations", JSONArray().put(JSONObject().apply {
                put("name", "make_phone_call")
                put(
                    "description",
                    "Place a phone call to a contact name or a phone number."
                )
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject().apply {
                        put("target", JSONObject().apply {
                            put("type", "STRING")
                            put("description", "Contact name or phone number to call.")
                        })
                    })
                    put("required", JSONArray().put("target"))
                })
            }))
        })

        // end_call
        arr.put(JSONObject().apply {
            put("functionDeclarations", JSONArray().put(JSONObject().apply {
                put("name", "end_call")
                put(
                    "description",
                    "End / hang up the currently active phone or WhatsApp call."
                )
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject())
                    put("required", JSONArray())
                })
            }))
        })

        // send_whatsapp_message
        arr.put(JSONObject().apply {
            put("functionDeclarations", JSONArray().put(JSONObject().apply {
                put("name", "send_whatsapp_message")
                put(
                    "description",
                    "Open WhatsApp and send a text message to a contact name or phone number."
                )
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject().apply {
                        put("target", JSONObject().apply {
                            put("type", "STRING")
                            put("description", "Contact name or phone number.")
                        })
                        put("message", JSONObject().apply {
                            put("type", "STRING")
                            put("description", "The message text to send.")
                        })
                    })
                    put("required", JSONArray().put("target").put("message"))
                })
            }))
        })

        // whatsapp_call
        arr.put(JSONObject().apply {
            put("functionDeclarations", JSONArray().put(JSONObject().apply {
                put("name", "whatsapp_call")
                put(
                    "description",
                    "Start a WhatsApp voice or video call with a contact name or phone number."
                )
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject().apply {
                        put("target", JSONObject().apply {
                            put("type", "STRING")
                            put("description", "Contact name or phone number.")
                        })
                        put("video", JSONObject().apply {
                            put("type", "BOOLEAN")
                            put("description", "true for video call, false for voice call.")
                        })
                    })
                    put("required", JSONArray().put("target").put("video"))
                })
            }))
        })

        // app_click
        arr.put(JSONObject().apply {
            put("functionDeclarations", JSONArray().put(JSONObject().apply {
                put("name", "app_click")
                put(
                    "description",
                    "Tap a button/link/element on the current screen by its visible text. " +
                        "Use for navigating inside any app (e.g. tap 'Like', tap 'Search', tap 'Next')."
                )
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject().apply {
                        put("text", JSONObject().apply {
                            put("type", "STRING")
                            put("description", "Visible text of the element to tap.")
                        })
                    })
                    put("required", JSONArray().put("text"))
                })
            }))
        })

        // app_type
        arr.put(JSONObject().apply {
            put("functionDeclarations", JSONArray().put(JSONObject().apply {
                put("name", "app_type")
                put(
                    "description",
                    "Type text into the currently focused input field on screen."
                )
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject().apply {
                        put("text", JSONObject().apply {
                            put("type", "STRING")
                            put("description", "Text to type.")
                        })
                    })
                    put("required", JSONArray().put("text"))
                })
            }))
        })

        // go_home
        arr.put(JSONObject().apply {
            put("functionDeclarations", JSONArray().put(JSONObject().apply {
                put("name", "go_home")
                put("description", "Go to the home screen.")
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject())
                    put("required", JSONArray())
                })
            }))
        })

        // go_back
        arr.put(JSONObject().apply {
            put("functionDeclarations", JSONArray().put(JSONObject().apply {
                put("name", "go_back")
                put("description", "Navigate back one screen.")
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject())
                    put("required", JSONArray())
                })
            }))
        })

        // camera_live — let the model look through the camera itself
        arr.put(JSONObject().apply {
            put("functionDeclarations", JSONArray().put(JSONObject().apply {
                put("name", "camera_live")
                put(
                    "description",
                    "Turn on the live camera so you can SEE the real world around the user. " +
                        "Choose which lens to use: 'back' (what's in front of the phone) or 'front' (the user's face). " +
                        "Use this when the user asks what you see, or wants you to look at something / someone."
                )
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject().apply {
                        put("lens", JSONObject().apply {
                            put("type", "STRING")
                            put("description", "'back' or 'front'.")
                        })
                    })
                    put("required", JSONArray().put("lens"))
                })
            }))
        })

        // camera_stop
        arr.put(JSONObject().apply {
            put("functionDeclarations", JSONArray().put(JSONObject().apply {
                put("name", "camera_stop")
                put("description", "Turn off the live camera.")
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject())
                    put("required", JSONArray())
                })
            }))
        })

        // set_volume
        arr.put(JSONObject().apply {
            put("functionDeclarations", JSONArray().put(JSONObject().apply {
                put("name", "set_volume")
                put("description", "Set media volume. Use 0-100 as percentage.")
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject().apply {
                        put("percent", JSONObject().apply {
                            put("type", "INTEGER")
                            put("description", "Volume percentage 0-100.")
                        })
                    })
                    put("required", JSONArray().put("percent"))
                })
            }))
        })

        // Read notifications
        arr.put(JSONObject().apply {
            put("functionDeclarations", JSONArray().put(JSONObject().apply {
                put("name", "read_notifications")
                put("description", "Read the user's recent notifications.")
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject())
                    put("required", JSONArray())
                })
            }))
        })

        // Clear notifications
        arr.put(JSONObject().apply {
            put("functionDeclarations", JSONArray().put(JSONObject().apply {
                put("name", "clear_notifications")
                put("description", "Clear / dismiss the user's notifications.")
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject())
                    put("required", JSONArray())
                })
            }))
        })

        // scroll_down / scroll_up
        arr.put(JSONObject().apply {
            put("functionDeclarations", JSONArray().put(JSONObject().apply {
                put("name", "scroll_down")
                put("description", "Scroll the current screen down.")
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject())
                    put("required", JSONArray())
                })
            }))
        })
        arr.put(JSONObject().apply {
            put("functionDeclarations", JSONArray().put(JSONObject().apply {
                put("name", "scroll_up")
                put("description", "Scroll the current screen up.")
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject())
                    put("required", JSONArray())
                })
            }))
        })

        // torch
        arr.put(JSONObject().apply {
            put("functionDeclarations", JSONArray().put(JSONObject().apply {
                put("name", "torch_set")
                put("description", "Turn the flashlight/torch on or off.")
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject().apply {
                        put("on", JSONObject().apply {
                            put("type", "BOOLEAN")
                            put("description", "true to turn on, false to turn off.")
                        })
                    })
                    put("required", JSONArray().put("on"))
                })
            }))
        })

        // bluetooth
        arr.put(JSONObject().apply {
            put("functionDeclarations", JSONArray().put(JSONObject().apply {
                put("name", "bluetooth_set")
                put("description", "Turn bluetooth on or off.")
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject().apply {
                        put("on", JSONObject().apply {
                            put("type", "BOOLEAN")
                            put("description", "true to turn on, false to turn off.")
                        })
                    })
                    put("required", JSONArray().put("on"))
                })
            }))
        })

        // brightness
        arr.put(JSONObject().apply {
            put("functionDeclarations", JSONArray().put(JSONObject().apply {
                put("name", "set_brightness")
                put("description", "Set screen brightness. Use 0-100 as percentage.")
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject().apply {
                        put("percent", JSONObject().apply {
                            put("type", "INTEGER")
                            put("description", "Brightness percentage 0-100.")
                        })
                    })
                    put("required", JSONArray().put("percent"))
                })
            }))
        })

        // screenshot
        arr.put(JSONObject().apply {
            put("functionDeclarations", JSONArray().put(JSONObject().apply {
                put("name", "take_screenshot")
                put("description", "Take a screenshot of the screen and save it to Pictures/Aero.")
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject())
                    put("required", JSONArray())
                })
            }))
        })

        // wifi / data settings
        arr.put(JSONObject().apply {
            put("functionDeclarations", JSONArray().put(JSONObject().apply {
                put("name", "open_wifi_settings")
                put("description", "Open the WiFi settings screen.")
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject())
                    put("required", JSONArray())
                })
            }))
        })
        arr.put(JSONObject().apply {
            put("functionDeclarations", JSONArray().put(JSONObject().apply {
                put("name", "open_data_settings")
                put("description", "Open the mobile data settings screen.")
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject())
                    put("required", JSONArray())
                })
            }))
        })

        arr.put(JSONObject().apply {
            put("functionDeclarations", JSONArray().put(JSONObject().apply {
                put("name", "get_current_time")
                put(
                    "description",
                    "Return the current date, time and timezone from the user's phone. " +
                        "Use whenever the user asks what time/date it is, or needs a precise " +
                        "time (e.g. for a reminder). Timezone is the phone's own."
                )
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject())
                    put("required", JSONArray())
                })
            }))
        })

        // ---- long-term memory ----
        arr.put(JSONObject().apply {
            put("functionDeclarations", JSONArray().put(JSONObject().apply {
                put("name", "remember_fact")
                put(
                    "description",
                    "Save a long-term fact about the user (name, city, birthday, preferences, " +
                        "important dates, relationships). Call this whenever the user says " +
                        "'yaad rakho', 'remember that', 'note karo' or shares a personal detail. " +
                        "The fact is stored on the phone and injected into every future session."
                )
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject().apply {
                        put("fact", JSONObject().apply {
                            put("type", "STRING")
                            put("description", "One clear sentence to remember, e.g. 'User's name is Kartik'.")
                        })
                    })
                    put("required", JSONArray().put("fact"))
                })
            }))
        })
        arr.put(JSONObject().apply {
            put("functionDeclarations", JSONArray().put(JSONObject().apply {
                put("name", "forget_fact")
                put(
                    "description",
                    "Delete saved memories that match a keyword. Use when the user says 'bhool jao' or 'forget'."
                )
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject().apply {
                        put("query", JSONObject().apply {
                            put("type", "STRING")
                            put("description", "Keyword or sentence to forget, e.g. 'Delhi' or 'old office'.")
                        })
                    })
                    put("required", JSONArray().put("query"))
                })
            }))
        })
        arr.put(JSONObject().apply {
            put("functionDeclarations", JSONArray().put(JSONObject().apply {
                put("name", "recall_memory")
                put("description", "Return everything saved in long-term memory. Use when the user asks what you remember.")
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject())
                    put("required", JSONArray())
                })
            }))
        })

        // ---- reminders (call-style ring) ----
        arr.put(JSONObject().apply {
            put("functionDeclarations", JSONArray().put(JSONObject().apply {
                put("name", "set_reminder")
                put(
                    "description",
                    "Schedule an on-device reminder. At that time the phone rings like a real " +
                        "incoming call and the user can talk to Aero. Never say reminders are " +
                        "unsupported — always call this tool."
                )
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject().apply {
                        put("when", JSONObject().apply {
                            put("type", "STRING")
                            put(
                                "description",
                                "Time phrase exactly as the user said it, e.g. 'kal shaam 5 baje', " +
                                    "'aaj 8:30 pm', '10 minute baad', 'in 2 hours'. Also accepts ISO " +
                                    "like '2026-11-26 17:00'."
                            )
                        })
                        put("message", JSONObject().apply {
                            put("type", "STRING")
                            put("description", "Short reminder text, e.g. 'gym jana hai'.")
                        })
                    })
                    put("required", JSONArray().put("when").put("message"))
                })
            }))
        })
        arr.put(JSONObject().apply {
            put("functionDeclarations", JSONArray().put(JSONObject().apply {
                put("name", "call_me")
                put(
                    "description",
                    "Ring the user's phone with an in-app Aero call. Use for 'mujhe call karo', " +
                        "'call me', or 'call me in 10 minutes'."
                )
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject().apply {
                        put("delay_minutes", JSONObject().apply {
                            put("type", "INTEGER")
                            put("description", "0 = ring now. Otherwise call after this many minutes.")
                        })
                        put("message", JSONObject().apply {
                            put("type", "STRING")
                            put("description", "What the call should say, e.g. 'Aero reminder: meeting at 5'.")
                        })
                    })
                    put("required", JSONArray().put("delay_minutes"))
                })
            }))
        })

        arr.put(JSONObject().apply {
            put("functionDeclarations", JSONArray().put(JSONObject().apply {
                put("name", "manage_reminders")
                put(
                    "description",
                    "Manage the user's existing reminders: list them, cancel, delay " +
                        "(push later), make earlier, or fire now. Use when the user says " +
                        "things like 'reminders dikhao', 'gym wala band karo', '10 minute delay " +
                        "karo', 'jldi karo', 'abhi karo'."
                )
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject().apply {
                        put("action", JSONObject().apply {
                            put("type", "STRING")
                            put(
                                "description",
                                "list | cancel | delay | earlier | fire_now | clear_all"
                            )
                        })
                        put("query", JSONObject().apply {
                            put("type", "STRING")
                            put("description", "Reminder text to match, e.g. 'gym'. Empty for all.")
                        })
                        put("minutes", JSONObject().apply {
                            put("type", "INTEGER")
                            put("description", "Minutes for delay/earlier.")
                        })
                    })
                    put("required", JSONArray().put("action"))
                })
            }))
        })

        return arr
    }
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

    /** Sends a JPEG frame straight into the live conversation (Gemini vision). */
    fun sendImage(jpeg: ByteArray) {
        if (jpeg.isEmpty()) return
        val b64 = Base64.encodeToString(jpeg, Base64.NO_WRAP)
        val msg = JSONObject().apply {
            put("realtimeInput", JSONObject().apply {
                put("video", JSONObject().apply {
                    put("data", b64)
                    put("mimeType", "image/jpeg")
                })
            })
        }
        ws?.send(msg.toString())
        Log.d(TAG, "video frame sent: ${jpeg.size} bytes")
    }

    /** Sends OCR / document text into the live conversation as a user turn. */
    fun sendContextText(text: String) {
        if (text.isBlank()) return
        val msg = JSONObject().apply {
            put("realtimeInput", JSONObject().put("text", text))
        }
        Log.d(TAG, "context text sent: ${text.take(160)}")
        ws?.send(msg.toString())
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
