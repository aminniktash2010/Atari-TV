// SPDX-License-Identifier: GPL-3.0-or-later
package com.mamali.ataritv

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Unified app-level save system: 3 manual slots + 1 auto slot per game,
 * namespaced by ROM SHA-256. Thumbnails are 320px-wide JPEGs captured via
 * PixelCopy before pausing. meta.json records timestamps, play time and the
 * core commit SHA (states are core-version-specific).
 *
 * All functions are blocking — callers must use Dispatchers.IO except where noted.
 */
object SaveManager {
    const val SLOT_AUTO = 0
    const val MANUAL_SLOTS = 3
    private const val THUMB_WIDTH = 320
    private const val THUMB_QUALITY = 80
    private const val QUOTA_BYTES = 200L * 1024 * 1024

    data class GameMeta(
        val coreSha: String,
        val slotTimestamps: Map<Int, Long>,
        val autoTimestamp: Long?,
        val totalPlayMs: Long,
        val lastPlayedMs: Long,
    )

    fun dirFor(context: Context, sha256: String): File =
        File(context.filesDir, "saves/$sha256").apply { mkdirs() }

    private fun metaFile(context: Context, sha256: String) = File(dirFor(context, sha256), "meta.json")

    fun stateFile(context: Context, sha256: String, slot: Int): File {
        val name = if (slot == SLOT_AUTO) "auto.state" else "slot$slot.state"
        return File(dirFor(context, sha256), name)
    }

    fun thumbFile(context: Context, sha256: String, slot: Int): File {
        val name = if (slot == SLOT_AUTO) "auto.jpg" else "slot$slot.jpg"
        return File(dirFor(context, sha256), name)
    }

    fun coreSha(context: Context): String {
        return try {
            context.assets.open("core_version.txt").use { it.readBytes().toString(Charsets.UTF_8).trim() }
        } catch (_: Exception) {
            "unknown"
        }
    }

    /** Write a slot (call with the core already paused). */
    fun save(context: Context, sha256: String, slot: Int, state: ByteArray, thumb: Bitmap?) {
        val dir = dirFor(context, sha256)
        try {
            stateFile(context, sha256, slot).writeBytes(state)
        } catch (e: java.io.IOException) {
            throw e
        }
        if (thumb != null) {
            try {
                val scaled = Bitmap.createScaledBitmap(
                    thumb,
                    THUMB_WIDTH,
                    (thumb.height * THUMB_WIDTH / thumb.width.toFloat()).toInt().coerceAtLeast(1),
                    true,
                )
                ByteArrayOutputStream().use { out ->
                    scaled.compress(Bitmap.CompressFormat.JPEG, THUMB_QUALITY, out)
                    thumbFile(context, sha256, slot).writeBytes(out.toByteArray())
                }
                if (scaled !== thumb) scaled.recycle()
            } catch (_: Exception) {
                // Thumbnail is best-effort; the state itself is what matters.
            }
        }
        val meta = readMeta(context, sha256).toMutableMap()
        val now = System.currentTimeMillis()
        if (slot == SLOT_AUTO) meta["autoTs"] = now else meta["slot$slot"] = now
        meta["lastPlayedMs"] = now
        writeMeta(context, sha256, meta)
        if (enforceQuota(context)) {
            // User-visible warning: the 200 MB saves quota triggered eviction
            // of the oldest auto slots (S9). Toast on the main thread —
            // save() is documented blocking and runs on Dispatchers.IO.
            val appCtx = context.applicationContext
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(appCtx, R.string.saves_quota_warn, Toast.LENGTH_LONG).show()
            }
        }
    }

    fun load(context: Context, sha256: String, slot: Int): ByteArray? {
        val f = stateFile(context, sha256, slot)
        return if (f.exists()) try { f.readBytes() } catch (_: Exception) { null } else null
    }

    fun hasSave(context: Context, sha256: String, slot: Int): Boolean =
        stateFile(context, sha256, slot).exists()

    fun hasAutoSave(context: Context, sha256: String): Boolean = hasSave(context, sha256, SLOT_AUTO)

    fun saveCount(context: Context, sha256: String): Int =
        (1..MANUAL_SLOTS).count { hasSave(context, sha256, it) }

    fun thumbnail(context: Context, sha256: String, slot: Int): Bitmap? {
        val f = thumbFile(context, sha256, slot)
        return if (f.exists()) try {
            BitmapFactory.decodeFile(f.absolutePath)
        } catch (_: Exception) { null } else null
    }

    fun slotTimestamp(context: Context, sha256: String, slot: Int): Long? {
        val m = readMeta(context, sha256)
        return if (slot == SLOT_AUTO) m["autoTs"] as? Long else m["slot$slot"] as? Long
    }

    fun addPlayTime(context: Context, sha256: String, ms: Long) {
        if (ms <= 0) return
        val meta = readMeta(context, sha256).toMutableMap()
        meta["totalPlayMs"] = (meta["totalPlayMs"] as? Long ?: 0L) + ms
        meta["lastPlayedMs"] = System.currentTimeMillis()
        writeMeta(context, sha256, meta)
    }

    fun meta(context: Context, sha256: String): GameMeta {
        val m = readMeta(context, sha256)
        val slots = (1..MANUAL_SLOTS).mapNotNull { s ->
            (m["slot$s"] as? Long)?.let { s to it }
        }.toMap()
        return GameMeta(
            coreSha = m["coreSha"] as? String ?: "unknown",
            slotTimestamps = slots,
            autoTimestamp = m["autoTs"] as? Long,
            totalPlayMs = m["totalPlayMs"] as? Long ?: 0L,
            lastPlayedMs = m["lastPlayedMs"] as? Long ?: 0L,
        )
    }

    fun deleteGame(context: Context, sha256: String) {
        try {
            File(context.filesDir, "saves/$sha256").deleteRecursively()
        } catch (_: Exception) {
        }
    }

    // ---------- internals ----------

    private fun readMeta(context: Context, sha256: String): Map<String, Any> {
        val f = metaFile(context, sha256)
        if (!f.exists()) return mapOf("coreSha" to coreSha(context))
        return try {
            val o = JSONObject(f.readText())
            buildMap {
                put("coreSha", o.optString("coreSha", "unknown"))
                for (s in 1..MANUAL_SLOTS) {
                    if (o.has("slot$s")) put("slot$s", o.getLong("slot$s"))
                }
                if (o.has("autoTs")) put("autoTs", o.getLong("autoTs"))
                put("totalPlayMs", o.optLong("totalPlayMs", 0))
                put("lastPlayedMs", o.optLong("lastPlayedMs", 0))
            }
        } catch (_: Exception) {
            mapOf("coreSha" to coreSha(context))
        }
    }

    private fun writeMeta(context: Context, sha256: String, meta: Map<String, Any>) {
        try {
            val o = JSONObject()
            o.put("coreSha", meta["coreSha"] as? String ?: coreSha(context))
            for (s in 1..MANUAL_SLOTS) {
                (meta["slot$s"] as? Long)?.let { o.put("slot$s", it) }
            }
            (meta["autoTs"] as? Long)?.let { o.put("autoTs", it) }
            o.put("totalPlayMs", meta["totalPlayMs"] as? Long ?: 0L)
            o.put("lastPlayedMs", meta["lastPlayedMs"] as? Long ?: 0L)
            metaFile(context, sha256).writeText(o.toString())
        } catch (_: Exception) {
        }
    }

    /**
     * Warn-level quota: evict oldest auto slots first; manual slots are never
     * auto-deleted. Returns true when anything was evicted (caller warns the user).
     */
    private fun enforceQuota(context: Context): Boolean {
        try {
            val root = File(context.filesDir, "saves")
            if (!root.exists()) return false
            var total = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            if (total <= QUOTA_BYTES) return false
            val autos = root.walkTopDown()
                .filter { it.isFile && it.name == "auto.state" }
                .sortedBy { it.lastModified() }
            var evicted = false
            for (f in autos) {
                if (total <= QUOTA_BYTES) break
                total -= f.length()
                f.delete()
                File(f.parent, "auto.jpg").delete()
                evicted = true
            }
            return evicted
        } catch (_: Exception) {
            return false
        }
    }
}
