package com.spaimic.nbttool.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView

/** A simple circular "+" button, bottom-right — built manually (no Material
 * Components dependency) since the rest of this app avoids extra libraries. */
fun createAddFab(context: Context, onClick: () -> Unit): TextView {
    val sizePx = 160
    val bg = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(Color.parseColor("#2196F3"))
    }
    return TextView(context).apply {
        text = "+"
        textSize = 28f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        background = bg
        elevation = 12f
        layoutParams = FrameLayout.LayoutParams(sizePx, sizePx).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            marginEnd = 48
            bottomMargin = 48
        }
        setOnClickListener { onClick() }
    }
}
