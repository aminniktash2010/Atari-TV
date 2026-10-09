// SPDX-License-Identifier: GPL-3.0-or-later
package com.mamali.ataritv

import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyEvent
import android.view.MotionEvent

/**
 * Sticky deviceId -> libretro port router, ported from River Ride's GameView
 * (isGamepad / assign). Maps Android input onto LibretroDroid's
 * sendKeyEvent(action, keyCode, port) / sendMotionEvent(source, x, y, port).
 *
 * Mapping (architecture section 5):
 * - TV remote D-pad -> RetroPad dpad, port 0 (joystick 1)
 * - Remote OK (DPAD_CENTER/ENTER) -> KEYCODE_BUTTON_B -> RetroPad B -> Fire (P1)
 * - Gamepad #1/#2 (sticky) -> ports 0/1; A<->B / X<->Y swap applied (same swap
 *   GLRetroView's own onKeyDown applies via GamepadsManager)
 * - Gamepad left stick -> sendMotionEvent(MOTION_SOURCE_DPAD=0, x, y, port),
 *   0.25 deadzone, quantized to -1/0/1 (the core polls RETRO_DEVICE_JOYPAD only)
 * - Gamepad START / remote BACK -> pause menu (intercepted, never forwarded:
 *   the core maps RetroPad START to ConsoleReset)
 * - Gamepad SELECT -> forwarded (core maps RetroPad SELECT to ConsoleSelect)
 * - Extras beyond 2 gamepads are dropped.
 */
class InputRouter(
    private val sendKey: (action: Int, keyCode: Int, port: Int) -> Unit,
    private val sendMotion: (source: Int, x: Float, y: Float, port: Int) -> Unit,
    private val onPauseRequested: () -> Unit,
) {
    private val deviceToPort = mutableMapOf<Int, Int>()

    /** (port, keyCode) pairs forwarded as ACTION_DOWN and not yet released. */
    private val pressedKeys = mutableSetOf<Pair<Int, Int>>()

    /** Ports that received a stick motion event since the last releaseAll(). */
    private val stickPorts = mutableSetOf<Int>()

    fun isGamepad(event: InputEvent): Boolean =
        (event.source and (InputDevice.SOURCE_GAMEPAD or InputDevice.SOURCE_JOYSTICK)) != 0

    /** Sticky assignment: first gamepad -> port 0, second -> port 1, else -1 (drop). */
    private fun portFor(deviceId: Int): Int {
        deviceToPort[deviceId]?.let { return it }
        val port = when {
            !deviceToPort.containsValue(0) -> 0
            !deviceToPort.containsValue(1) -> 1
            else -> -1
        }
        if (port >= 0) deviceToPort[deviceId] = port
        return port
    }

    /** Android gamepad layout -> RetroPad layout (mirrors GamepadsManager). */
    private fun swapForGamepad(keyCode: Int): Int = when (keyCode) {
        KeyEvent.KEYCODE_BUTTON_A -> KeyEvent.KEYCODE_BUTTON_B
        KeyEvent.KEYCODE_BUTTON_B -> KeyEvent.KEYCODE_BUTTON_A
        KeyEvent.KEYCODE_BUTTON_X -> KeyEvent.KEYCODE_BUTTON_Y
        KeyEvent.KEYCODE_BUTTON_Y -> KeyEvent.KEYCODE_BUTTON_X
        else -> keyCode
    }

    /**
     * Key actually forwarded for a gamepad event: a gamepad's DPAD_CENTER (the
     * D-pad press) is mapped to KEYCODE_BUTTON_B (fire), mirroring the remote
     * branch — it has no case in the native key map and would otherwise be
     * silently dropped. (Not routed through swapForGamepad: BUTTON_B is the
     * RetroPad target already.)
     */
    private fun gamepadKeyToSend(keyCode: Int): Int =
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER) KeyEvent.KEYCODE_BUTTON_B
        else swapForGamepad(keyCode)

    /** True if the event was consumed (do not propagate further). */
    fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            onPauseRequested()
            return true
        }
        if (isGamepad(event)) {
            // START is the pause button; never forward it (core: ConsoleReset).
            if (keyCode == KeyEvent.KEYCODE_BUTTON_START) {
                onPauseRequested()
                return true
            }
            val port = portFor(event.deviceId)
            if (port < 0) return true // no free player slot: swallow
            val sent = gamepadKeyToSend(keyCode)
            sendKey(KeyEvent.ACTION_DOWN, sent, port)
            pressedKeys.add(port to sent)
            return true
        }
        // TV remote.
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            -> {
                sendKey(KeyEvent.ACTION_DOWN, keyCode, 0)
                pressedKeys.add(0 to keyCode)
                true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_BUTTON_A,
            -> {
                // Remote OK = Fire on port 0.
                sendKey(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_B, 0)
                pressedKeys.add(0 to KeyEvent.KEYCODE_BUTTON_B)
                true
            }
            else -> false
        }
    }

    fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) return true
        if (isGamepad(event)) {
            if (keyCode == KeyEvent.KEYCODE_BUTTON_START) return true
            val port = portFor(event.deviceId)
            if (port < 0) return true
            val sent = gamepadKeyToSend(keyCode)
            sendKey(KeyEvent.ACTION_UP, sent, port)
            pressedKeys.remove(port to sent)
            return true
        }
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            -> {
                sendKey(KeyEvent.ACTION_UP, keyCode, 0)
                pressedKeys.remove(0 to keyCode)
                true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_BUTTON_A,
            -> {
                sendKey(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_B, 0)
                pressedKeys.remove(0 to KeyEvent.KEYCODE_BUTTON_B)
                true
            }
            else -> false
        }
    }

    fun onMotionEvent(event: MotionEvent): Boolean {
        if (isGamepad(event) && event.action == MotionEvent.ACTION_MOVE) {
            val port = portFor(event.deviceId)
            if (port < 0) return true
            stickPorts.add(port)
            val x = quantizedAxis(event, MotionEvent.AXIS_X, MotionEvent.AXIS_HAT_X)
            val y = quantizedAxis(event, MotionEvent.AXIS_Y, MotionEvent.AXIS_HAT_Y)
            // MOTION_SOURCE_DPAD = 0 in LibretroDroid's native input.cpp; the
            // stella core only polls RETRO_DEVICE_JOYPAD, so the stick must ride
            // the dpad path (native rounds to -1/0/1 and ORs with buttons).
            sendMotion(0, x, y, port)
            return true
        }
        return false
    }

    private fun quantizedAxis(event: MotionEvent, axis: Int, hatAxis: Int): Float {
        var v = event.getAxisValue(axis)
        if (v == 0f) v = event.getAxisValue(hatAxis)
        return when {
            v > 0.25f -> 1f
            v < -0.25f -> -1f
            else -> 0f
        }
    }

    /** Forget assignments (e.g. when leaving the game). */
    fun reset() {
        deviceToPort.clear()
        pressedKeys.clear()
        stickPorts.clear()
    }

    /**
     * Release every key still marked pressed (ACTION_UP for each) and recenter
     * any deflected stick for the affected ports. Call BEFORE the core is
     * paused (e.g. when opening the pause menu): key-ups and motion events are
     * swallowed while the menu is open, so without this the native side keeps
     * the last direction/stick position latched and the game resumes moving.
     */
    fun releaseAll() {
        for ((port, keyCode) in pressedKeys) {
            sendKey(KeyEvent.ACTION_UP, keyCode, port)
        }
        for (port in pressedKeys.map { it.first } + stickPorts) {
            sendMotion(0, 0f, 0f, port)
        }
        pressedKeys.clear()
        stickPorts.clear()
    }
}
