package com.aero.voice

import android.content.res.ColorStateList
import android.graphics.Color
import android.widget.ImageButton

/**
 * Single source of truth for every button's visual theme.
 *
 * All screens (main activity, floating orb, future screens) must apply
 * colours/states through this object — never by writing inline tint code
 * again. Changing a value here changes it everywhere.
 */
object AeroUi {

    // ---- Theme palette (mirrors res/values/colors.xml) ----
    val C_BG_CIRCLE = Color.WHITE             // white circle (default app action button)
    val C_TEXT_DARK = Color.parseColor("#111318") // default icon on white
    val C_RED = Color.parseColor("#EF4444")   // danger / off / muted
    val C_PURPLE = Color.parseColor("#5B5BF0") // active / on (accent)
    val C_TRANSPARENT = Color.TRANSPARENT     // no tint (overlay default)

    // ---- Common sizes ----
    const val ACTION_BTN_SIZE_DP = 48
    const val MIC_BTN_SIZE_DP = 64
    const val OVERLAY_BTN_SIZE_DP = 52

    /** Visual state of any button. */
    enum class State {
        NORMAL,   // default look
        DANGER,   // red — muted / share off / destructive
        ACTIVE    // accent — recording / share on / highlighted
    }

    /** Colours for one state in one context (app vs overlay). */
    private data class Style(val background: Int, val icon: Int)

    // App buttons: white circular background, dark icon by default.
    private val APP_STYLES = mapOf(
        State.NORMAL to Style(C_BG_CIRCLE, C_TEXT_DARK),
        State.DANGER to Style(C_RED, Color.WHITE),
        State.ACTIVE to Style(C_PURPLE, Color.WHITE)
    )

    // Overlay buttons: transparent background, white icon by default.
    private val OVERLAY_STYLES = mapOf(
        State.NORMAL to Style(C_TRANSPARENT, Color.WHITE),
        State.DANGER to Style(C_RED, Color.WHITE),
        State.ACTIVE to Style(C_PURPLE, Color.WHITE)
    )

    /** Applies a theme state to a main-app action button. */
    fun applyAppButton(button: ImageButton, state: State) {
        apply(button, APP_STYLES.getValue(state))
    }

    /** Applies a theme state to a floating-orb overlay button. */
    fun applyOverlayButton(button: ImageButton, state: State) {
        apply(button, OVERLAY_STYLES.getValue(state))
    }

    private fun apply(button: ImageButton, style: Style) {
        button.backgroundTintList = ColorStateList.valueOf(style.background)
        button.imageTintList = ColorStateList.valueOf(style.icon)
    }
}
