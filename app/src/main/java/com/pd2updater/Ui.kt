package com.pd2updater

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Colours and small builders for the app's one screen. */
object Palette {
    val background = Color.parseColor("#120D0B")
    val card = Color.parseColor("#1E1714")
    val stroke = Color.parseColor("#3A2A20")
    val gold = Color.parseColor("#D4A24C")
    val goldDim = Color.parseColor("#6E5226")
    val text = Color.parseColor("#EDE3D3")
    val muted = Color.parseColor("#A89884")
    val ok = Color.parseColor("#7FBF6A")
    val error = Color.parseColor("#E0644E")
    val onGold = Color.parseColor("#1A120C")
}

fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

fun Context.rounded(fill: Int, stroke: Int? = null, radius: Int = 14): GradientDrawable =
    GradientDrawable().apply {
        cornerRadius = dp(radius).toFloat()
        setColor(fill)
        if (stroke != null) setStroke(dp(1), stroke)
    }

fun Context.card(): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    background = rounded(Palette.card, Palette.stroke, 16)
    setPadding(dp(16), dp(14), dp(16), dp(16))
    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        .apply { bottomMargin = dp(12) }
}

fun Context.sectionTitle(text: String): TextView = TextView(this).apply {
    this.text = text.uppercase()
    setTextColor(Palette.gold)
    textSize = 12f
    letterSpacing = 0.12f
    typeface = Typeface.DEFAULT_BOLD
    setPadding(0, 0, 0, dp(8))
}

fun Context.body(text: String = "", muted: Boolean = false, size: Float = 14f): TextView = TextView(this).apply {
    this.text = text
    setTextColor(if (muted) Palette.muted else Palette.text)
    textSize = size
}

/** A filled gold button ([primary]) or an outlined one. */
fun Context.actionButton(label: String, primary: Boolean, onClick: () -> Unit): Button = Button(this).apply {
    text = label
    isAllCaps = false
    textSize = 15f
    typeface = Typeface.DEFAULT_BOLD
    stateListAnimator = null
    minHeight = dp(48)
    val enabledText = if (primary) Palette.onGold else Palette.gold
    setTextColor(
        ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_enabled), intArrayOf()),
            intArrayOf(enabledText, Palette.muted),
        ),
    )
    val shape = if (primary) rounded(Palette.gold, radius = 12) else rounded(Palette.card, Palette.goldDim, 12)
    background = RippleDrawable(ColorStateList.valueOf(Color.argb(60, 255, 255, 255)), shape, null)
    setOnClickListener { onClick() }
    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        .apply { topMargin = dp(8) }
}

/** Two buttons side by side. */
fun Context.row(vararg views: View): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    views.forEachIndexed { index, view ->
        addView(view, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            topMargin = dp(8)
            if (index > 0) marginStart = dp(8)
        })
    }
}

/** The view's enabled look: dim when disabled. */
fun View.enable(on: Boolean) {
    isEnabled = on
    alpha = if (on) 1f else 0.45f
}
