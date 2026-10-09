// SPDX-License-Identifier: GPL-3.0-or-later
package com.mamali.ataritv

import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.View
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.swordfish.libretrodroid.GLRetroView
import com.swordfish.libretrodroid.GLRetroViewData
import com.swordfish.libretrodroid.LibretroDroid
import com.swordfish.libretrodroid.ShaderConfig
import com.swordfish.libretrodroid.ViewportAlignment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.coroutines.resume

/**
 * Hosts the LibretroDroid GL view, the sticky input router, the pause menu
 * overlay and the unified save system (3 manual + 1 auto slot per game).
 */
class GameActivity : ComponentActivity() {

    companion object {
        const val EXTRA_SHA = "rom_sha256"
        const val EXTRA_CONTINUE = "continue"
        private const val PREFS = "atari_tv_prefs"
        private const val KEY_SCANLINES = "scanlines"
    }

    private lateinit var container: FrameLayout
    private lateinit var pauseMenu: PauseMenuView
    private lateinit var loading: ProgressBar
    private lateinit var router: InputRouter

    private var retroView: GLRetroView? = null
    private var romSha: String = ""
    private var continueRequested = false
    private var gameLoaded = false
    private var menuOpen = false
    private var continued = false
    private var sessionStartMs = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_game)
        container = findViewById(R.id.game_container)
        pauseMenu = findViewById(R.id.pause_menu)
        loading = findViewById(R.id.loading)

        romSha = intent.getStringExtra(EXTRA_SHA) ?: ""
        continueRequested = intent.getBooleanExtra(EXTRA_CONTINUE, false)
        if (romSha.isEmpty()) {
            toast(getString(R.string.load_error)); finish(); return
        }

        router = InputRouter(
            sendKey = { action, keyCode, port -> retroView?.sendKeyEvent(action, keyCode, port) },
            sendMotion = { source, x, y, port -> retroView?.sendMotionEvent(source, x, y, port) },
            onPauseRequested = { if (!menuOpen) openPauseMenu() },
        )

        lifecycleScope.launch {
            val rom = withContext(Dispatchers.IO) {
                RomLibrary.readLibrary(this@GameActivity).second.find { it.sha256 == romSha }
            }
            if (rom == null) {
                toast(getString(R.string.load_error)); finish(); return@launch
            }
            val bytes = withContext(Dispatchers.IO) { RomLibrary.openBytes(this@GameActivity, rom) }
            if (bytes == null) {
                toast(getString(R.string.load_error)); finish(); return@launch
            }
            startEmulation(bytes)
        }
    }

    // ---------- emulation setup ----------

    private fun startEmulation(romBytes: ByteArray) {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val data = GLRetroViewData(this).apply {
            coreFilePath = "stella_libretro.so" // resolved from app jniLibs/<abi>/
            gameFileBytes = romBytes
            systemDirectory = filesDir.absolutePath
            savesDirectory = filesDir.absolutePath
            shader = if (prefs.getBoolean(KEY_SCANLINES, false)) ShaderConfig.CRT else ShaderConfig.Sharp
            viewportAlignment = ViewportAlignment.CENTER
            rumbleEventsEnabled = false // 2600 has no rumble
            preferLowLatencyAudio = true
        }
        val view = GLRetroView(this, data)
        retroView = view
        container.addView(view, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        lifecycle.addObserver(view)
        gameLoaded = true
        markResumed()

        lifecycleScope.launch {
            view.getGLRetroErrors().collect { code ->
                if (code == GLRetroView.ERROR_LOAD_GAME) {
                    toast(getString(R.string.load_error))
                    finish()
                }
            }
        }
        // First rendered frame: hide the spinner; apply Continue if requested.
        lifecycleScope.launch {
            view.getGLRetroEvents().collect { event ->
                if (event is GLRetroView.GLRetroEvents.FrameRendered) {
                    loading.visibility = View.GONE
                    if (continueRequested && !continued) {
                        continued = true
                        doContinue()
                    }
                }
            }
        }
    }

    // ---------- input ----------

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                if (menuOpen) closePauseMenu() else openPauseMenu()
            }
            return true
        }
        if (menuOpen) {
            // Gamepad A activates the focused menu button (remotes send DPAD_CENTER natively).
            if (event.action == KeyEvent.ACTION_DOWN && router.isGamepad(event)) {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_BUTTON_A -> return pauseMenu.clickFocused()
                    KeyEvent.KEYCODE_BUTTON_START -> { closePauseMenu(); return true }
                }
            }
            return super.dispatchKeyEvent(event)
        }
        val rv = retroView
        if (rv == null || !gameLoaded) return super.dispatchKeyEvent(event)
        return when (event.action) {
            KeyEvent.ACTION_DOWN -> router.onKeyDown(event.keyCode, event)
            KeyEvent.ACTION_UP -> router.onKeyUp(event.keyCode, event)
            else -> super.dispatchKeyEvent(event)
        }
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (!menuOpen && gameLoaded && retroView != null && router.onMotionEvent(event)) return true
        return super.dispatchGenericMotionEvent(event)
    }

    // ---------- pause menu ----------

    private fun openPauseMenu() {
        if (menuOpen || !gameLoaded) return
        menuOpen = true
        // Release any latched inputs BEFORE pausing: the menu swallows key-ups
        // and motion events, so without this a held direction/stick stays
        // pressed in the core and the game resumes moving (B1).
        router.releaseAll()
        LibretroDroid.pause()
        lifecycleScope.launch { recordPlayTime() }
        pauseMenu.setItems(
            listOf(
                PauseMenuView.Item(getString(R.string.resume)) { closePauseMenu() },
                PauseMenuView.Item(getString(R.string.save_state)) {
                    SlotPickerDialog.show(this, romSha, forSave = true) { slot -> saveToSlot(slot) }
                },
                PauseMenuView.Item(getString(R.string.load_state)) {
                    SlotPickerDialog.show(this, romSha, forSave = false) { slot -> loadFromSlot(slot) }
                },
                PauseMenuView.Item(getString(R.string.reset_game)) { resetGame() },
                // Console switches: synthetic momentary presses on port 0.
                PauseMenuView.Item(getString(R.string.console_select)) {
                    closePauseMenu(); momentaryPress(KeyEvent.KEYCODE_BUTTON_SELECT)
                },
                PauseMenuView.Item(getString(R.string.console_reset)) {
                    closePauseMenu(); momentaryPress(KeyEvent.KEYCODE_BUTTON_START)
                },
                PauseMenuView.Item(getString(R.string.difficulty_l)) {
                    closePauseMenu(); momentaryPress(KeyEvent.KEYCODE_BUTTON_L1)
                },
                PauseMenuView.Item(getString(R.string.difficulty_r)) {
                    closePauseMenu(); momentaryPress(KeyEvent.KEYCODE_BUTTON_R1)
                },
                PauseMenuView.Item(getString(R.string.back_to_shelf)) { backToShelf() },
            ),
        )
        pauseMenu.show()
    }

    private fun closePauseMenu() {
        if (!menuOpen) return
        menuOpen = false
        pauseMenu.hide()
        LibretroDroid.resume()
        markResumed()
    }

    private fun momentaryPress(keyCode: Int) {
        val rv = retroView ?: return
        rv.sendKeyEvent(KeyEvent.ACTION_DOWN, keyCode, 0)
        rv.sendKeyEvent(KeyEvent.ACTION_UP, keyCode, 0)
    }

    // ---------- saves ----------

    private fun saveToSlot(slot: Int) {
        lifecycleScope.launch {
            val thumb = captureThumbnail()
            var ok = true
            withContext(Dispatchers.IO) {
                LibretroDroid.pause()
                try {
                    val state = retroView?.serializeState()
                    if (state == null) {
                        ok = false
                    } else {
                        SaveManager.save(this@GameActivity, romSha, slot, state, thumb)
                    }
                } catch (_: Exception) {
                    // serializeState wraps native failures in RetroException (a
                    // RuntimeException) — catch everything like loadFromSlot (B2).
                    ok = false
                } finally {
                    if (menuOpen) LibretroDroid.pause() else LibretroDroid.resume()
                }
            }
            toast(getString(if (ok) R.string.saved else R.string.save_failed))
            if (menuOpen) openPauseMenuRefresh() else openPauseMenu()
        }
    }

    /** Re-show the menu (it was hidden while the slot picker was up). */
    private fun openPauseMenuRefresh() {
        menuOpen = false
        openPauseMenu()
    }

    private fun loadFromSlot(slot: Int) {
        lifecycleScope.launch(Dispatchers.IO) {
            val bytes = SaveManager.load(this@GameActivity, romSha, slot)
            if (bytes == null) {
                withContext(Dispatchers.Main) { toast(getString(R.string.load_failed)) }
                return@launch
            }
            warnOnCoreMismatch()
            LibretroDroid.pause()
            val ok = try {
                retroView?.unserializeState(bytes) == true
            } catch (_: Exception) {
                false
            } finally {
                if (menuOpen) LibretroDroid.pause() else LibretroDroid.resume()
            }
            withContext(Dispatchers.Main) {
                toast(getString(if (ok) R.string.loaded else R.string.load_failed))
                if (ok) closePauseMenu()
            }
        }
    }

    private suspend fun doContinue() {
        val bytes = withContext(Dispatchers.IO) {
            SaveManager.load(this@GameActivity, romSha, SaveManager.SLOT_AUTO)
        } ?: return
        warnOnCoreMismatch()
        withContext(Dispatchers.IO) {
            LibretroDroid.pause()
            try {
                retroView?.unserializeState(bytes)
            } finally {
                LibretroDroid.resume()
            }
        }
    }

    private suspend fun warnOnCoreMismatch() {
        val saved = withContext(Dispatchers.IO) { SaveManager.meta(this@GameActivity, romSha).coreSha }
        val current = SaveManager.coreSha(this@GameActivity)
        if (saved != "unknown" && saved != current) {
            withContext(Dispatchers.Main) { toast(getString(R.string.core_mismatch)) }
        }
    }

    private fun resetGame() {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { doAutoSave() } // save position before reset
            retroView?.reset()
            closePauseMenu()
            toast(getString(R.string.reset_game))
        }
    }

    private fun backToShelf() {
        lifecycleScope.launch {
            recordPlayTime()
            withContext(Dispatchers.IO) { doAutoSave() }
            finish()
        }
    }

    /** Auto slot write: thumbnail (best-effort) + serialize while paused. */
    private suspend fun doAutoSave() {
        val rv = retroView ?: return
        if (!gameLoaded) return
        val thumb = captureThumbnail()
        LibretroDroid.pause()
        try {
            val state = try {
                rv.serializeState()
            } catch (_: Exception) {
                // Best-effort auto-save: a failed serialize must never crash (B2).
                null
            } ?: return
            withContext(Dispatchers.IO) {
                try {
                    SaveManager.save(this@GameActivity, romSha, SaveManager.SLOT_AUTO, state, thumb)
                } catch (_: IOException) {
                }
            }
        } finally {
            // Leave paused; the caller (menu/activity) owns resume.
        }
    }

    /**
     * Auto-save for the onPause path: pause the core FIRST (idempotent), then
     * serialize without the emulation-thread latch. The GL render thread may
     * already be torn down by super.onPause(), so the latch in
     * runOnEmulationThread(true) could block an IO thread forever (S1).
     */
    private suspend fun doOnPauseAutoSave() {
        val rv = retroView ?: return
        val thumb = captureThumbnail()
        LibretroDroid.pause() // idempotent; the core is quiescent now
        val state = try {
            rv.serializeState(useEmulationThread = false)
        } catch (_: Exception) {
            null
        } ?: return
        withContext(Dispatchers.IO) {
            try {
                SaveManager.save(this@GameActivity, romSha, SaveManager.SLOT_AUTO, state, thumb)
            } catch (_: IOException) {
            }
        }
    }

    private suspend fun captureThumbnail(): Bitmap? =
        suspendCancellableCoroutine { cont ->
            val rv = retroView
            if (rv == null || rv.width <= 0 || rv.height <= 0) {
                cont.resume(null)
                return@suspendCancellableCoroutine
            }
            // PixelCopy must be issued on the main thread.
            if (Looper.myLooper() == Looper.getMainLooper()) {
                requestCopy(rv, cont)
            } else {
                Handler(Looper.getMainLooper()).post { requestCopy(rv, cont) }
            }
        }

    private fun requestCopy(
        rv: GLRetroView,
        cont: kotlinx.coroutines.CancellableContinuation<Bitmap?>,
    ) {
        try {
            val bmp = Bitmap.createBitmap(rv.width, rv.height, Bitmap.Config.ARGB_8888)
            PixelCopy.request(rv, bmp, { result ->
                cont.resume(if (result == PixelCopy.SUCCESS) bmp else null)
            }, Handler(Looper.getMainLooper()))
        } catch (_: Exception) {
            cont.resume(null)
        }
    }

    // ---------- play time ----------

    private fun markResumed() {
        if (gameLoaded) sessionStartMs = SystemClock.elapsedRealtime()
    }

    private suspend fun recordPlayTime() {
        if (sessionStartMs == 0L) return
        val ms = SystemClock.elapsedRealtime() - sessionStartMs
        sessionStartMs = 0L
        withContext(Dispatchers.IO) { SaveManager.addPlayTime(this@GameActivity, romSha, ms) }
    }

    // ---------- lifecycle ----------

    override fun onPause() {
        // Best-effort auto-save while the surface is still alive. The onPause
        // path pauses the core first and serializes WITHOUT the emulation
        // thread (S1: no latch to hang on after GL teardown).
        if (gameLoaded && !isFinishing) {
            lifecycleScope.launch {
                recordPlayTime()
                doOnPauseAutoSave()
            }
        } else if (gameLoaded) {
            lifecycleScope.launch { recordPlayTime() }
        }
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (gameLoaded && !menuOpen) markResumed()
    }

    override fun onDestroy() {
        router.reset()
        super.onDestroy()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
