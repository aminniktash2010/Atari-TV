// SPDX-License-Identifier: GPL-3.0-or-later
package com.mamali.ataritv

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * ROM library: SAF folder/file intake, persisted index (files/library.json),
 * rescan-on-launch, unavailable-slot handling. Game identity = SHA-256 of the
 * ROM bytes (post zip-extraction).
 *
 * All functions are blocking — callers must use Dispatchers.IO.
 */
object RomLibrary {
    private const val LIBRARY_JSON = "library.json"
    private const val ROMS_DIR = "roms"
    private const val ROM_CACHE_DIR = "romcache"
    private const val CACHE_CAP_BYTES = 64L * 1024 * 1024
    private const val PREFS = "atari_tv_prefs"
    private const val KEY_TEST_ROM_REMOVED = "test_rom_removed"

    val ROM_EXTS = setOf("a26", "bin")
    private val ZIP_EXTS = setOf("zip")

    data class Source(val type: String, val uri: String, val displayName: String) {
        companion object {
            const val FOLDER = "folder"
            const val FILE = "file"
        }
    }

    data class Rom(
        val sha256: String,
        val title: String,
        val displayName: String,
        val kind: String,
        val sourceUri: String?,
        val documentUri: String?,
        val localPath: String?,
        val sizeBytes: Long,
        var available: Boolean,
        var lastSeenMs: Long,
    ) {
        companion object {
            const val BUILTIN = "builtin"
            const val FOLDER = "folder"
            const val FOLDER_ZIP = "folder_zip"
            const val COPIED = "copied"
        }
    }

    // ---------- persistence ----------

    private fun libraryFile(context: Context) = File(context.filesDir, LIBRARY_JSON)

    fun readLibrary(context: Context): Pair<MutableList<Source>, MutableList<Rom>> {
        val sources = mutableListOf<Source>()
        val roms = mutableListOf<Rom>()
        val f = libraryFile(context)
        if (!f.exists()) return sources to roms
        try {
            val root = JSONObject(f.readText())
            val js = root.optJSONArray("sources") ?: JSONArray()
            for (i in 0 until js.length()) {
                val o = js.getJSONObject(i)
                sources.add(Source(o.getString("type"), o.getString("uri"), o.optString("displayName", "?")))
            }
            val jr = root.optJSONArray("roms") ?: JSONArray()
            for (i in 0 until jr.length()) {
                val o = jr.getJSONObject(i)
                roms.add(
                    Rom(
                        sha256 = o.getString("sha256"),
                        title = o.getString("title"),
                        displayName = o.optString("displayName", ""),
                        kind = o.optString("kind", Rom.FOLDER),
                        sourceUri = o.optString("sourceUri", null),
                        documentUri = o.optString("documentUri", null),
                        localPath = o.optString("localPath", null),
                        sizeBytes = o.optLong("sizeBytes", 0),
                        available = o.optBoolean("available", true),
                        lastSeenMs = o.optLong("lastSeenMs", 0),
                    ),
                )
            }
        } catch (_: Exception) {
            // Corrupt index: start clean (saves on disk are untouched).
        }
        return sources to roms
    }

    fun writeLibrary(context: Context, sources: List<Source>, roms: List<Rom>) {
        // Preserve the removal-exclusion list (S3); callers that mutate it use
        // the 4-arg overload directly.
        writeLibrary(context, sources, roms, readExclusions(context))
    }

    fun writeLibrary(context: Context, sources: List<Source>, roms: List<Rom>, excluded: Set<String>) {
        val root = JSONObject()
        val js = JSONArray()
        for (s in sources) {
            js.put(JSONObject().put("type", s.type).put("uri", s.uri).put("displayName", s.displayName))
        }
        val jr = JSONArray()
        for (r in roms) {
            jr.put(
                JSONObject()
                    .put("sha256", r.sha256).put("title", r.title)
                    .put("displayName", r.displayName).put("kind", r.kind)
                    .put("sourceUri", r.sourceUri).put("documentUri", r.documentUri)
                    .put("localPath", r.localPath).put("sizeBytes", r.sizeBytes)
                    .put("available", r.available).put("lastSeenMs", r.lastSeenMs),
            )
        }
        val jx = JSONArray()
        for (sha in excluded) jx.put(sha)
        root.put("sources", js).put("roms", jr).put("excluded", jx)
        libraryFile(context).writeText(root.toString())
    }

    /**
     * SHA-256s of folder-sourced ROMs the user removed via long-press (S3).
     * rescan() consults this so a removed game stays removed instead of being
     * re-discovered from its folder on every launch.
     */
    fun readExclusions(context: Context): MutableSet<String> {
        return try {
            val f = libraryFile(context)
            if (!f.exists()) return mutableSetOf()
            val jx = JSONObject(f.readText()).optJSONArray("excluded") ?: return mutableSetOf()
            (0 until jx.length()).map { jx.getString(it) }.toMutableSet()
        } catch (_: Exception) {
            mutableSetOf()
        }
    }

    // ---------- hashing ----------

    fun sha256(bytes: ByteArray): String {
        val d = MessageDigest.getInstance("SHA-256")
        return d.digest(bytes).joinToString("") { "%02x".format(it) }
    }

    fun titleFor(fileName: String): String =
        fileName.substringBeforeLast('.')
            .replace('_', ' ').replace('-', ' ').trim()
            .ifEmpty { fileName }

    // ---------- built-in test ROM ----------

    fun ensureTestRom(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_TEST_ROM_REMOVED, false)) return
        val (sources, roms) = readLibrary(context)
        val dir = File(context.filesDir, ROMS_DIR).apply { mkdirs() }
        val f = File(dir, "test_cart.bin")
        if (!f.exists()) {
            context.assets.open("test_cart.bin").use { input ->
                f.outputStream().use { input.copyTo(it) }
            }
        }
        val sha = sha256(f.readBytes())
        val existing = roms.find { it.kind == Rom.BUILTIN }
        if (existing == null) {
            roms.add(
                Rom(
                    sha256 = sha,
                    title = context.getString(R.string.test_cart_title),
                    displayName = "test_cart.bin",
                    kind = Rom.BUILTIN,
                    sourceUri = null, documentUri = null,
                    localPath = f.absolutePath, sizeBytes = f.length(),
                    available = true, lastSeenMs = System.currentTimeMillis(),
                ),
            )
        } else if (existing.sha256 != sha) {
            roms.remove(existing)
            roms.add(existing.copy(sha256 = sha, localPath = f.absolutePath, available = true))
        }
        writeLibrary(context, sources, roms)
    }

    fun markTestRomRemoved(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_TEST_ROM_REMOVED, true).apply()
    }

    // ---------- intake ----------

    /** Persistable folder pick. Returns false if the permission could not be taken. */
    fun addFolderSource(context: Context, treeUri: Uri): Boolean {
        return try {
            context.contentResolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
            val (sources, roms) = readLibrary(context)
            val uriStr = treeUri.toString()
            if (sources.none { it.uri == uriStr }) {
                val name = DocumentFile.fromTreeUri(context, treeUri)?.name ?: uriStr
                sources.add(Source(Source.FOLDER, uriStr, name))
                writeLibrary(context, sources, roms)
            }
            true
        } catch (e: SecurityException) {
            false
        }
    }

    /** Cloud/file pick: copy into app-private storage, hashing content. */
    fun addFileSources(context: Context, uris: List<Uri>) {
        val (sources, roms) = readLibrary(context)
        val excluded = readExclusions(context)
        val dir = File(context.filesDir, ROMS_DIR).apply { mkdirs() }
        var changed = false
        for (uri in uris) {
            try {
                val name = queryDisplayName(context, uri) ?: "rom.bin"
                val ext = name.substringAfterLast('.', "bin").lowercase()
                if (ext in ZIP_EXTS) {
                    val extracted = extractFirstRom(context, uri) ?: continue
                    val sha = sha256(extracted)
                    File(dir, "$sha.bin").writeBytes(extracted)
                    excluded.remove(sha) // explicit re-add clears a removal exclusion (S3)
                    upsert(roms, Rom(sha, titleFor(name), name, Rom.COPIED, uri.toString(), null,
                        File(dir, "$sha.bin").absolutePath, extracted.size.toLong(),
                        true, System.currentTimeMillis()))
                } else if (ext in ROM_EXTS) {
                    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: continue
                    val sha = sha256(bytes)
                    File(dir, "$sha.$ext").writeBytes(bytes)
                    excluded.remove(sha) // explicit re-add clears a removal exclusion (S3)
                    upsert(roms, Rom(sha, titleFor(name), name, Rom.COPIED, uri.toString(), null,
                        File(dir, "$sha.$ext").absolutePath, bytes.size.toLong(),
                        true, System.currentTimeMillis()))
                }
                // Record the picked file as a source for the settings screen.
                if (sources.none { it.type == Source.FILE && it.uri == uri.toString() }) {
                    sources.add(Source(Source.FILE, uri.toString(), name))
                }
                changed = true
            } catch (_: Exception) {
            }
        }
        if (changed) writeLibrary(context, sources, roms, excluded)
    }

    // ---------- rescan ----------

    fun rescan(context: Context) {
        ensureTestRom(context)
        val (sources, roms) = readLibrary(context)
        val excluded = readExclusions(context)
        val now = System.currentTimeMillis()
        var changed = false
        for (source in sources.filter { it.type == Source.FOLDER }) {
            try {
                val treeDoc = DocumentFile.fromTreeUri(context, Uri.parse(source.uri))
                if (treeDoc == null || !treeDoc.exists()) {
                    if (markSourceUnavailable(roms, source.uri)) changed = true
                    continue
                }
                val seen = mutableSetOf<String>()
                for (doc in treeDoc.listFiles()) {
                    if (doc.isDirectory) continue
                    val name = doc.name ?: continue
                    val ext = name.substringAfterLast('.', "").lowercase()
                    if (ext !in ROM_EXTS && ext !in ZIP_EXTS) continue
                    seen.add(doc.uri.toString())
                    try {
                        if (ext in ZIP_EXTS) {
                            val extracted = extractFirstRom(context, doc.uri)
                            if (extracted != null) {
                                val sha = sha256(extracted)
                                cacheRomBytes(context, sha, extracted)
                                if (sha !in excluded) {
                                    upsert(roms, Rom(sha, titleFor(name), name, Rom.FOLDER_ZIP,
                                        source.uri, doc.uri.toString(),
                                        null, extracted.size.toLong(), true, now))
                                    changed = true
                                }
                            }
                        } else {
                            val bytes = context.contentResolver.openInputStream(doc.uri)
                                ?.use { it.readBytes() } ?: continue
                            val sha = sha256(bytes)
                            if (sha !in excluded) {
                                upsert(roms, Rom(sha, titleFor(name), name, Rom.FOLDER,
                                    source.uri, doc.uri.toString(),
                                    null, bytes.size.toLong(), true, now))
                                changed = true
                            }
                        }
                    } catch (_: Exception) {
                    }
                }
                for (rom in roms.filter { it.sourceUri == source.uri }) {
                    val vis = rom.documentUri in seen
                    if (rom.available != vis) { rom.available = vis; changed = true }
                    if (vis) rom.lastSeenMs = now
                }
            } catch (e: SecurityException) {
                if (markSourceUnavailable(roms, source.uri)) changed = true
            } catch (_: Exception) {
            }
        }
        // Copied/builtin: verify local files still exist.
        for (rom in roms.filter { it.kind == Rom.COPIED || it.kind == Rom.BUILTIN }) {
            val ok = rom.localPath?.let { File(it).exists() } == true
            if (rom.available != ok) { rom.available = ok; changed = true }
        }
        pruneRomCache(context)
        if (changed) writeLibrary(context, sources, roms)
    }

    private fun markSourceUnavailable(roms: MutableList<Rom>, sourceUri: String): Boolean {
        var changed = false
        for (rom in roms.filter { it.sourceUri == sourceUri && it.available }) {
            rom.available = false
            changed = true
        }
        return changed
    }

    private fun upsert(roms: MutableList<Rom>, rom: Rom) {
        val i = roms.indexOfFirst { it.sha256 == rom.sha256 }
        if (i >= 0) roms[i] = rom else roms.add(rom)
    }

    // ---------- reading ----------

    /** ROM bytes for emulation. Null when the source is gone. */
    fun openBytes(context: Context, rom: Rom): ByteArray? {
        return try {
            when (rom.kind) {
                Rom.BUILTIN, Rom.COPIED -> rom.localPath?.let { File(it).takeIf { f -> f.exists() }?.readBytes() }
                Rom.FOLDER -> rom.documentUri?.let {
                    context.contentResolver.openInputStream(Uri.parse(it))?.use { s -> s.readBytes() }
                }
                Rom.FOLDER_ZIP -> {
                    val cached = File(File(context.cacheDir, ROM_CACHE_DIR), "${rom.sha256}.bin")
                    if (cached.exists()) cached.readBytes()
                    else rom.documentUri?.let { extractFirstRom(context, Uri.parse(it)) }
                }
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    // ---------- removal ----------

    fun removeSource(context: Context, source: Source) {
        val (sources, roms) = readLibrary(context)
        if (source.type == Source.FOLDER) {
            try {
                context.contentResolver.releasePersistableUriPermission(
                    Uri.parse(source.uri), Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            } catch (_: Exception) {
            }
        }
        sources.removeAll { it.uri == source.uri && it.type == source.type }
        roms.removeAll { it.sourceUri == source.uri }
        writeLibrary(context, sources, roms)
    }

    /** Remove a game from the shelf (and its saves). */
    fun deleteRom(context: Context, rom: Rom) {
        val (sources, roms) = readLibrary(context)
        roms.removeAll { it.sha256 == rom.sha256 }
        if (rom.kind == Rom.COPIED) {
            rom.localPath?.let { try { File(it).delete() } catch (_: Exception) { } }
        }
        if (rom.kind == Rom.BUILTIN) {
            rom.localPath?.let { try { File(it).delete() } catch (_: Exception) { } }
            markTestRomRemoved(context)
        }
        // Folder-sourced ROMs still live in the user's folder: remember the
        // removal so the next rescan doesn't re-discover them (S3).
        val excluded = readExclusions(context)
        if (rom.kind == Rom.FOLDER || rom.kind == Rom.FOLDER_ZIP) {
            excluded.add(rom.sha256)
        }
        writeLibrary(context, sources, roms, excluded)
        SaveManager.deleteGame(context, rom.sha256)
    }

    // ---------- helpers ----------

    private fun queryDisplayName(context: Context, uri: Uri): String? {
        return try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        } catch (_: Exception) {
            null
        }
    }

    /** First zip entry with a ROM extension, as bytes. */
    private fun extractFirstRom(context: Context, zipUri: Uri): ByteArray? {
        return try {
            context.contentResolver.openInputStream(zipUri)?.use { raw ->
                ZipInputStream(raw).use { zin ->
                    var e = zin.nextEntry
                    while (e != null) {
                        val ext = e.name.substringAfterLast('.', "").lowercase()
                        if (!e.isDirectory && ext in ROM_EXTS) return zin.readBytes()
                        e = zin.nextEntry
                    }
                    null
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun cacheRomBytes(context: Context, sha: String, bytes: ByteArray) {
        try {
            val dir = File(context.cacheDir, ROM_CACHE_DIR).apply { mkdirs() }
            val f = File(dir, "$sha.bin")
            // Skip the rewrite when the entry already exists: unconditional
            // rewrites refresh lastModified and defeat the LRU in
            // pruneRomCache (S8).
            if (f.exists()) return
            f.writeBytes(bytes)
        } catch (_: Exception) {
        }
    }

    private fun pruneRomCache(context: Context) {
        try {
            val dir = File(context.cacheDir, ROM_CACHE_DIR)
            if (!dir.exists()) return
            val files = dir.listFiles()?.sortedBy { it.lastModified() } ?: return
            var total = files.sumOf { it.length() }
            for (f in files) {
                if (total <= CACHE_CAP_BYTES) break
                total -= f.length()
                f.delete()
            }
        } catch (_: Exception) {
        }
    }
}
