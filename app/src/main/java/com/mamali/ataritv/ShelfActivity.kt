// SPDX-License-Identifier: GPL-3.0-or-later
package com.mamali.ataritv

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * TV game shelf: scrollable D-pad-navigable grid of all indexed games,
 * "Add game" card, rescan-on-launch, unavailable-slot UX.
 */
class ShelfActivity : ComponentActivity() {

    private lateinit var grid: RecyclerView
    private lateinit var emptyState: TextView
    private lateinit var scanProgress: ProgressBar
    private lateinit var adapter: ShelfAdapter

    /** SHA of the last game opened: focus is restored to it after rescans (S7). */
    private var restoreSha: String? = null

    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) {
            lifecycleScope.launch(Dispatchers.IO) {
                val ok = RomLibrary.addFolderSource(this@ShelfActivity, uri)
                RomLibrary.rescan(this@ShelfActivity)
                withContext(Dispatchers.Main) {
                    if (!ok) toast(getString(R.string.folder_unavailable_msg))
                    refreshShelf()
                }
            }
        }
    }

    private val pickFiles = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            lifecycleScope.launch(Dispatchers.IO) {
                RomLibrary.addFileSources(this@ShelfActivity, uris)
                withContext(Dispatchers.Main) { refreshShelf() }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_shelf)
        grid = findViewById(R.id.shelf_grid)
        emptyState = findViewById(R.id.empty_state)
        scanProgress = findViewById(R.id.scan_progress)

        adapter = ShelfAdapter(
            this,
            onGameClick = { rom -> onGameClick(rom) },
            onGameLongPress = { rom -> onGameLongPress(rom) },
            onAddClick = { showAddDialog() },
        )
        grid.layoutManager = GridLayoutManager(this, 4)
        grid.adapter = adapter

        findViewById<View>(R.id.btn_add).setOnClickListener { showAddDialog() }
        findViewById<View>(R.id.btn_settings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    override fun onStart() {
        super.onStart()
        refreshShelf()
    }

    private fun refreshShelf() {
        scanProgress.visibility = View.VISIBLE
        lifecycleScope.launch(Dispatchers.IO) {
            RomLibrary.rescan(this@ShelfActivity)
            val (_, roms) = RomLibrary.readLibrary(this@ShelfActivity)
            val meta = roms.associate { it.sha256 to SaveManager.meta(this@ShelfActivity, it.sha256) }
            val continueShas = roms.filter { SaveManager.hasAutoSave(this@ShelfActivity, it.sha256) }
                .map { it.sha256 }.toSet()
            withContext(Dispatchers.Main) {
                scanProgress.visibility = View.GONE
                // Test cart first, then alphabetical.
                val sorted = roms.sortedWith(
                    compareBy({ it.kind != RomLibrary.Rom.BUILTIN }, { it.title.lowercase() }),
                )
                adapter.submit(sorted, meta, continueShas)
                val showEmpty = sorted.isEmpty()
                emptyState.visibility = if (showEmpty) View.VISIBLE else View.GONE
                grid.visibility = if (showEmpty) View.GONE else View.VISIBLE
                // Restore D-pad focus: notifyDataSetChanged() drops it (S7).
                // Prefer the game that was last opened; otherwise the first card.
                if (!showEmpty) {
                    val target = sorted.indexOfFirst { it.sha256 == restoreSha }
                        .takeIf { it >= 0 } ?: 0
                    grid.post {
                        grid.layoutManager?.findViewByPosition(target)?.requestFocus()
                            ?: grid.requestFocus()
                    }
                }
            }
        }
    }

    private fun onGameClick(rom: RomLibrary.Rom) {
        if (!rom.available) {
            toast(getString(R.string.folder_unavailable_msg))
            return
        }
        restoreSha = rom.sha256
        val intent = Intent(this, GameActivity::class.java).apply {
            putExtra(GameActivity.EXTRA_SHA, rom.sha256)
            // Tapping a game with an auto save resumes it (Continue badge).
            putExtra(GameActivity.EXTRA_CONTINUE, SaveManager.hasAutoSave(this@ShelfActivity, rom.sha256))
        }
        startActivity(intent)
    }

    private fun onGameLongPress(rom: RomLibrary.Rom) {
        AlertDialog.Builder(this)
            .setTitle(rom.title)
            .setMessage(getString(R.string.remove_game_confirm))
            .setPositiveButton(getString(R.string.remove)) { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    RomLibrary.deleteRom(this@ShelfActivity, rom)
                    withContext(Dispatchers.Main) { refreshShelf() }
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun showAddDialog() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.add_game))
            .setItems(arrayOf(getString(R.string.add_folder), getString(R.string.add_files))) { _, which ->
                if (which == 0) pickFolder.launch(null) else pickFiles.launch(arrayOf("*/*"))
            }
            .show()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
