// SPDX-License-Identifier: GPL-3.0-or-later
package com.mamali.ataritv

import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings: CRT scanline toggle + ROM source list with Remove.
 * Removing a source releases its persisted URI permission; the affected
 * shelf slots show "unavailable" until the folder is re-added (saves kept).
 */
class SettingsActivity : ComponentActivity() {

    companion object {
        const val PREFS = "atari_tv_prefs"
        const val KEY_SCANLINES = "scanlines"
    }

    private lateinit var scanSwitch: Switch
    private lateinit var sourcesList: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
            setBackgroundColor(Color.rgb(11, 14, 20))
        }

        val title = TextView(this).apply {
            text = getString(R.string.settings)
            setTextColor(Color.WHITE)
            textSize = 36f
            setPadding(0, 0, 0, 32)
        }
        root.addView(title)

        scanSwitch = Switch(this).apply {
            text = getString(R.string.scanlines)
            setTextColor(Color.WHITE)
            textSize = 22f
            isChecked = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_SCANLINES, false)
            setOnCheckedChangeListener { _, checked ->
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_SCANLINES, checked).apply()
            }
        }
        root.addView(scanSwitch)
        val scanSummary = TextView(this).apply {
            text = getString(R.string.scanlines_summary)
            setTextColor(Color.GRAY)
            textSize = 16f
            setPadding(0, 4, 0, 32)
        }
        root.addView(scanSummary)

        val sourcesTitle = TextView(this).apply {
            text = getString(R.string.sources_title)
            setTextColor(Color.WHITE)
            textSize = 26f
            setPadding(0, 0, 0, 16)
        }
        root.addView(sourcesTitle)

        sourcesList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        // Scrollable sources region: with more than a few sources the rows
        // render off-screen with no D-pad way to reach them (S6).
        val sourcesScroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f,
            )
            isFocusable = true
            addView(sourcesList)
        }
        root.addView(sourcesScroll)

        setContentView(root)
        refreshSources()
    }

    private fun refreshSources() {
        lifecycleScope.launch(Dispatchers.IO) {
            val (sources, roms) = RomLibrary.readLibrary(this@SettingsActivity)
            withContext(Dispatchers.Main) {
                sourcesList.removeAllViews()
                if (sources.isEmpty()) {
                    sourcesList.addView(TextView(this@SettingsActivity).apply {
                        text = "—"
                        setTextColor(Color.GRAY)
                        textSize = 20f
                    })
                }
                for (source in sources) {
                    sourcesList.addView(makeSourceRow(source, roms))
                }
            }
        }
    }

    private fun makeSourceRow(source: RomLibrary.Source, roms: List<RomLibrary.Rom>): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 12, 0, 12)
        }
        val count = roms.count { it.sourceUri == source.uri }
        val label = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            text = "${if (source.type == RomLibrary.Source.FOLDER) "📁" else "📄"} ${source.displayName} ($count)"
            setTextColor(Color.WHITE)
            textSize = 20f
        }
        val remove = Button(this).apply {
            text = getString(R.string.remove)
            textSize = 18f
            setOnClickListener { confirmRemove(source) }
        }
        row.addView(label)
        row.addView(remove)
        return row
    }

    private fun confirmRemove(source: RomLibrary.Source) {
        AlertDialog.Builder(this)
            .setTitle(source.displayName)
            .setMessage(getString(R.string.remove))
            .setPositiveButton(getString(R.string.remove)) { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    RomLibrary.removeSource(this@SettingsActivity, source)
                    withContext(Dispatchers.Main) { refreshSources() }
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }
}
