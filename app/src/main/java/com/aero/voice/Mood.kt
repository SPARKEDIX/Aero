package com.aero.voice

/**
 * Mood presets. Each mood drives:
 *  - the orb's top / bottom / smoke colours (via AGSL uniforms),
 *  - the system instruction given to Gemini.
 *
 * PROFESSIONAL deliberately uses the exact previous colours, so the default
 * orb remains the same blue/white ball the app already had.
 */
data class MoodPalette(
    val key: String,
    val label: String,
    val useCase: String,
    val top: Int,
    val bottom: Int,
    val smoke: Int,
    val instruction: String
) {
    companion object {

        const val PROFESSIONAL = "professional"
        const val CREATIVE = "creative"
        const val FRIENDLY = "friendly"
        const val CALM = "calm"
        const val ECO = "eco"

        val ALL = listOf(
            PROFESSIONAL, CREATIVE, FRIENDLY, CALM, ECO
        )

        private val PALETTES = listOf(
            MoodPalette(
                key = PROFESSIONAL,
                label = "Professional & Analytical",
                useCase = "Electric Blue & Dark Slate — coding, research, office work",
                top = 0xFF5858F0.toInt(),
                bottom = 0xFF9099FF.toInt(),
                smoke = 0xFFB3C2FF.toInt(),
                instruction = AeroIdentity.TEXT + " " +
                    "You are a professional, analytical voice assistant. Give precise, " +
                    "structured, factual answers. Best for coding, research and office work."
            ),
            MoodPalette(
                key = CREATIVE,
                label = "Creative & Brainstorming",
                useCase = "Neon Purple & Violet — content creation, art, brainstorming",
                top = 0xFFB026FF.toInt(),
                bottom = 0xFF6A0DAD.toInt(),
                smoke = 0xFFE3B8FF.toInt(),
                instruction = AeroIdentity.TEXT + " " +
                    "You are a creative brainstorming partner. Be imaginative, playful " +
                    "and full of fresh ideas. Best for content creation, art and brainstorming."
            ),
            MoodPalette(
                key = FRIENDLY,
                label = "Friendly & Empathetic",
                useCase = "Warm Orange & Gold — voice companion, motivation, casual chat",
                top = 0xFFFF8A3D.toInt(),
                bottom = 0xFFFFC83D.toInt(),
                smoke = 0xFFFFE0A3.toInt(),
                instruction = AeroIdentity.TEXT + " " +
                    "You are a warm, friendly and empathetic voice companion. Be " +
                    "encouraging, caring and conversational. Best for motivation and casual chat."
            ),
            MoodPalette(
                key = CALM,
                label = "Calm & Mindful",
                useCase = "Soft White, Silver & Pastel — wellness, night mode, minimal reading",
                top = 0xFFE8ECF5.toInt(),
                bottom = 0xFFC9D6E8.toInt(),
                smoke = 0xFF9FB2CC.toInt(),
                instruction = AeroIdentity.TEXT + " " +
                    "You are a calm, mindful and gentle voice assistant. Speak softly and " +
                    "keep answers short and soothing. Best for wellness, night mode and " +
                    "quiet moments."
            ),
            MoodPalette(
                key = ECO,
                label = "Eco-Smart & Helpful",
                useCase = "Emerald Green & Teal — smart home, daily productivity, health",
                top = 0xFF10B981.toInt(),
                bottom = 0xFF0F766E.toInt(),
                smoke = 0xFFA7F3D0.toInt(),
                instruction = AeroIdentity.TEXT + " " +
                    "You are an eco-smart, practical helper for smart home, daily " +
                    "productivity and health. Give efficient, actionable tips."
            )
        )

        fun forKey(key: String): MoodPalette =
            PALETTES.firstOrNull { it.key == key } ?: PALETTES[0]
    }
}
