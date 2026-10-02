/*
 * Attendance AI — offline-first face-recognition attendance app.
 * Copyright (C) 2026 The Attendance AI Authors. GPL-3.0-or-later.
 */
package org.attendanceai.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Shared programmatic styling for the simple secondary screens (Reports,
 * Settings, Recovery phrase). Keeps the classic-view look consistent with
 * [AttendanceActivity]'s inline helpers without introducing XML layouts or a
 * design system — visual polish is a later theme stage.
 */
object NavChrome {

    /** Screen colours shared with AttendanceActivity and the lock screen. */
    const val INK = 0xFF1D2942.toInt()
    const val MUTED = 0xFF66738D.toInt()
    const val PRIMARY = 0xFF6D5DF5.toInt()
    const val SECONDARY = 0xFF28B8A6.toInt()
    const val TERTIARY = 0xFF9A79D9.toInt()
    const val ERROR = 0xFFC23B5A.toInt()

    /** A padded vertical page with the app's pastel-gradient background. */
    @JvmStatic
    fun page(context: Context): LinearLayout {
        val layout = LinearLayout(context)
        layout.orientation = LinearLayout.VERTICAL
        layout.setPadding(dp(context, 20), dp(context, 24), dp(context, 20), dp(context, 20))
        layout.background = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(0xFFE8E5FF.toInt(), 0xFFF4F7FC.toInt(), 0xFFE2F7F2.toInt()),
        )
        return layout
    }

    /** Page heading. */
    @JvmStatic
    fun title(context: Context, text: String): TextView {
        val view = TextView(context)
        view.text = text
        view.textSize = 26f
        view.typeface = Typeface.DEFAULT_BOLD
        view.setTextColor(INK)
        view.setPadding(0, 0, 0, dp(context, 6))
        return view
    }

    /** A rounded translucent card holding body text. */
    @JvmStatic
    fun cardText(context: Context, size: Float, color: Int): TextView {
        val view = TextView(context)
        view.textSize = size
        view.setTextColor(color)
        view.setPadding(dp(context, 16), dp(context, 14), dp(context, 16), dp(context, 14))
        view.background = roundBackground(context, 0xDFFFFFFF.toInt(), 18)
        return view
    }

    /** A tappable card row, used for settings-style entries. */
    @JvmStatic
    fun rowCard(context: Context, label: String): TextView {
        val view = cardText(context, 16f, INK)
        view.text = label
        view.gravity = Gravity.CENTER_VERTICAL
        val lp = ViewGroup.MarginLayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        lp.topMargin = dp(context, 10)
        view.layoutParams = lp
        return view
    }

    /** Full-width action button in the app's palette. */
    @JvmStatic
    fun actionButton(context: Context, label: String, color: Int): Button {
        val button = Button(context)
        button.text = label
        button.textSize = 15f
        button.setTextColor(Color.WHITE)
        button.isAllCaps = false
        button.typeface = Typeface.DEFAULT_BOLD
        button.minHeight = dp(context, 54)
        button.setPadding(dp(context, 18), dp(context, 8), dp(context, 18), dp(context, 8))
        button.background = roundBackground(context, color, 18)
        val lp = ViewGroup.MarginLayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        lp.topMargin = dp(context, 10)
        button.layoutParams = lp
        return button
    }

    /** Rounded solid background. */
    @JvmStatic
    fun roundBackground(context: Context, color: Int, radiusDp: Int): GradientDrawable {
        val drawable = GradientDrawable()
        drawable.setColor(color)
        drawable.cornerRadius = radiusDp * context.resources.displayMetrics.density
        return drawable
    }

    /** Density-independent pixels, matching AttendanceActivity's helper. */
    @JvmStatic
    fun dp(context: Context, value: Int): Int =
        Math.round(value * context.resources.displayMetrics.density)
}