package com.aero.voice

import android.content.Context

object Prefs {
    private const val FILE = "voice_prefs"
    private const val KEY_API = "api_key"
    private const val KEY_MODEL = "model_id"
    private const val KEY_VOICE = "voice"
    private const val KEY_PROMPT = "system_prompt"

    const val DEFAULT_MODEL = "gemini-3.8-live"
    const val DEFAULT_VOICE = "Kore"
    const val DEFAULT_PROMPT = "You are a helpful voice assistant. Answer concisely and naturally, in the language the user speaks."

    fun apiKey(ctx: Context): String? =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY_API, null)

    fun modelId(ctx: Context): String =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY_MODEL, DEFAULT_MODEL) ?: DEFAULT_MODEL

    fun voice(ctx: Context): String =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY_VOICE, DEFAULT_VOICE) ?: DEFAULT_VOICE

    fun systemPrompt(ctx: Context): String =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY_PROMPT, DEFAULT_PROMPT) ?: DEFAULT_PROMPT

    fun save(ctx: Context, apiKey: String, model: String, voice: String, prompt: String) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString(KEY_API, apiKey)
            .putString(KEY_MODEL, model)
            .putString(KEY_VOICE, voice)
            .putString(KEY_PROMPT, prompt)
            .apply()
    }
}
