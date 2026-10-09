// SPDX-License-Identifier: GPL-3.0-or-later
package com.mamali.ataritv

import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * In-game pause overlay: dim background + D-pad-focusable menu.
 * The core is paused via LibretroDroid.pause() by the host activity while
 * this is visible; no second GL surface involved.
 */
class PauseMenuView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    data class Item(val label: String, val action: () -> Unit)

    private val menuBox: LinearLayout
    private var items: List<Item> = emptyList()

    init {
        setBackgroundColor(Color.argb(160, 0, 0, 0))
        isFocusable = true
        isFocusableInTouchMode = true

        val center = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        val lp = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER)
        menuBox = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 32)
            setBackgroundColor(Color.argb(230, 22, 29, 41))
        }
        val title = TextView(context).apply {
            text = context.getString(R.string.app_name)
            setTextColor(Color.WHITE)
            textSize = 30f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 24)
        }
        menuBox.addView(title)
        center.addView(menuBox, lp)
        addView(center, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    fun setItems(newItems: List<Item>) {
        items = newItems
        // Keep the title (child 0); rebuild the buttons.
        while (menuBox.childCount > 1) menuBox.removeViewAt(1)
        for (item in items) {
            val b = Button(context).apply {
                text = item.label
                textSize = 22f
                isFocusable = true
                setOnClickListener { item.action() }
                val p = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
                p.topMargin = 8
                layoutParams = p
            }
            menuBox.addView(b)
        }
    }

    fun show() {
        visibility = View.VISIBLE
        // Focus the first button for D-pad navigation.
        post { (menuBox.getChildAt(1) as? View)?.requestFocus() }
    }

    fun hide() {
        visibility = View.GONE
    }

    val isShowing: Boolean get() = visibility == View.VISIBLE

    /** Gamepad A should activate the focused menu button (TV remotes send DPAD_CENTER natively). */
    fun clickFocused(): Boolean {
        val v = focusedChild ?: findFocus()
        if (v is Button && isShowing) {
            v.performClick()
            return true
        }
        // Fallback: walk the menu box for the focused button.
        for (i in 0 until menuBox.childCount) {
            val c = menuBox.getChildAt(i)
            if (c is Button && c.isFocused) {
                c.performClick()
                return true
            }
        }
        return false
    }
}
