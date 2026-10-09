// SPDX-License-Identifier: GPL-3.0-or-later
package com.mamali.ataritv

import android.app.AlertDialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Save/load slot picker: 3 manual slots (+ auto slot in LOAD mode),
 * each with its 320px thumbnail and timestamp. No text entry on TV —
 * manual slots are auto-named "Slot N · <date>".
 */
object SlotPickerDialog {
    /** Shows the dialog; the picked slot (0 = auto) is delivered to onPick. */
    fun show(context: Context, sha256: String, forSave: Boolean, onPick: (slot: Int) -> Unit) {
        val list = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 24, 32, 24)
        }
        val slots = if (forSave) {
            (1..SaveManager.MANUAL_SLOTS).toList()
        } else {
            (1..SaveManager.MANUAL_SLOTS).toList() + SaveManager.SLOT_AUTO
        }
        val dialog = AlertDialog.Builder(context)
            .setTitle(if (forSave) context.getString(R.string.save_state) else context.getString(R.string.load_state))
            .setView(list)
            .setNegativeButton(context.getString(R.string.cancel), null)
            .create()
        var first: View? = null
        for (slot in slots) {
            val row = makeRow(context, sha256, slot, forSave, dialog, onPick)
            if (first == null && row.isEnabled) first = row
            list.addView(row)
        }
        dialog.show()
        (first ?: list).requestFocus()
    }

    private fun makeRow(
        context: Context,
        sha256: String,
        slot: Int,
        forSave: Boolean,
        dialog: AlertDialog,
        onPick: (slot: Int) -> Unit,
    ): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(16, 16, 16, 16)
            isFocusable = true
            isClickable = true
            setBackgroundResource(R.drawable.card_bg)
        }
        val thumb: Bitmap? = SaveManager.thumbnail(context, sha256, slot)
        val iv = ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(240, 140)
            scaleType = ImageView.ScaleType.CENTER_CROP
            if (thumb != null) setImageBitmap(thumb)
            else setBackgroundColor(Color.DKGRAY)
        }
        val label = TextView(context).apply {
            val name = if (slot == SaveManager.SLOT_AUTO) "Auto" else "Slot $slot"
            val ts = SaveManager.slotTimestamp(context, sha256, slot)
            text = if (ts != null) {
                "$name · ${SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(Date(ts))}"
            } else {
                "$name · empty"
            }
            setTextColor(Color.WHITE)
            textSize = 20f
            setPadding(24, 0, 0, 0)
        }
        row.addView(iv)
        row.addView(label)
        val enabled = forSave || SaveManager.hasSave(context, sha256, slot)
        row.isEnabled = enabled
        row.alpha = if (enabled) 1f else 0.4f
        if (enabled) {
            row.setOnClickListener {
                dialog.dismiss()
                onPick(slot)
            }
        }
        return row
    }
}
