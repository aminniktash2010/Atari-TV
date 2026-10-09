// SPDX-License-Identifier: GPL-3.0-or-later
package com.mamali.ataritv

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Shelf grid: one card per indexed ROM plus a trailing "Add game" card.
 * Card art is procedurally drawn from the ROM hash (original, no artwork).
 */
class ShelfAdapter(
    private val context: Context,
    private val onGameClick: (RomLibrary.Rom) -> Unit,
    private val onGameLongPress: (RomLibrary.Rom) -> Unit,
    private val onAddClick: () -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var roms: List<RomLibrary.Rom> = emptyList()
    private var meta: Map<String, SaveManager.GameMeta> = emptyMap()
    private var continueShas: Set<String> = emptySet()

    /**
     * Per-SHA cache of the procedurally generated card art: proceduralArt()
     * allocates a 480x270 bitmap per bind, which churns the GC during scroll
     * on TV (N7). Access-ordered, capped so it can't grow without bound.
     */
    private val artCache = object : LinkedHashMap<String, Bitmap>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>): Boolean =
            size > 32
    }
    private val addArtBmp: Bitmap by lazy { addArt() }

    fun submit(roms: List<RomLibrary.Rom>, meta: Map<String, SaveManager.GameMeta>, continueShas: Set<String>) {
        this.roms = roms
        this.meta = meta
        this.continueShas = continueShas
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = roms.size + 1 // + Add card

    override fun getItemViewType(position: Int): Int =
        if (position < roms.size) TYPE_GAME else TYPE_ADD

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_game, parent, false)
        return if (viewType == TYPE_GAME) GameHolder(v) else AddHolder(v)
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        if (holder is GameHolder) holder.bind(roms[position])
        else (holder as AddHolder).bind()
    }

    inner class GameHolder(v: View) : RecyclerView.ViewHolder(v) {
        private val art: ImageView = v.findViewById(R.id.card_art)
        private val title: TextView = v.findViewById(R.id.card_title)
        private val badge: TextView = v.findViewById(R.id.card_badge)
        private val sub: TextView = v.findViewById(R.id.card_sub)

        fun bind(rom: RomLibrary.Rom) {
            title.text = rom.title
            art.setImageBitmap(artCache.getOrPut(rom.sha256) { proceduralArt(rom.sha256) })
            val m = meta[rom.sha256]
            if (!rom.available) {
                badge.visibility = View.VISIBLE
                badge.text = context.getString(R.string.unavailable)
                badge.setTextColor(Color.GRAY)
                art.alpha = 0.4f
                sub.text = ""
            } else {
                art.alpha = 1f
                if (rom.sha256 in continueShas) {
                    badge.visibility = View.VISIBLE
                    badge.text = context.getString(R.string.continue_badge)
                    badge.setTextColor(context.getColor(R.color.accent))
                } else {
                    badge.visibility = View.GONE
                }
                val saves = (m?.slotTimestamps?.size ?: 0) +
                    if (m?.autoTimestamp != null) 1 else 0 // all slots incl. auto (N8)
                val last = m?.lastPlayedMs ?: 0L
                sub.text = buildString {
                    if (saves > 0) append("$saves save${if (saves > 1) "s" else ""}")
                    if (last > 0) {
                        if (isNotEmpty()) append(" · ")
                        append(SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(last)))
                    }
                }
            }
            itemView.setOnClickListener { onGameClick(rom) }
            itemView.setOnLongClickListener { onGameLongPress(rom); true }
        }
    }

    inner class AddHolder(v: View) : RecyclerView.ViewHolder(v) {
        private val art: ImageView = v.findViewById(R.id.card_art)
        private val title: TextView = v.findViewById(R.id.card_title)
        private val sub: TextView = v.findViewById(R.id.card_sub)

        fun bind() {
            title.text = context.getString(R.string.add_game)
            sub.text = ""
            itemView.findViewById<TextView>(R.id.card_badge).visibility = View.GONE
            art.setImageBitmap(addArtBmp)
            itemView.setOnClickListener { onAddClick() }
            itemView.setOnLongClickListener(null)
        }
    }

    /** Deterministic abstract art from the ROM hash: gradient + bands + square. */
    private fun proceduralArt(sha: String): Bitmap {
        val w = 480
        val h = 270
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint()
        val seed = sha.take(8).toLongOrNull(16) ?: 0L
        val rnd = java.util.Random(seed)
        val hue = rnd.nextFloat() * 360f
        p.shader = LinearGradient(
            0f, 0f, w.toFloat(), h.toFloat(),
            Color.HSVToColor(floatArrayOf(hue, 0.7f, 0.35f)),
            Color.HSVToColor(floatArrayOf((hue + 60) % 360, 0.7f, 0.15f)),
            Shader.TileMode.CLAMP,
        )
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        // Bands.
        p.shader = null
        for (i in 0 until 6) {
            p.color = Color.HSVToColor(floatArrayOf((hue + i * 40) % 360, 0.55f, 0.5f))
            c.drawRect(0f, i * h / 6f, w.toFloat(), (i + 1) * h / 6f, p)
        }
        // Square.
        p.color = Color.WHITE
        val s = 56f
        c.drawRect(w / 2f - s / 2, h / 2f - s / 2, w / 2f + s / 2, h / 2f + s / 2, p)
        return bmp
    }

    private fun addArt(): Bitmap {
        val w = 480
        val h = 270
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint().apply { color = Color.argb(255, 36, 48, 74) }
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        p.color = Color.WHITE
        p.strokeWidth = 14f
        // Plus sign.
        c.drawLine(w / 2f, h / 2f - 60, w / 2f, h / 2f + 60, p)
        c.drawLine(w / 2f - 60, h / 2f, w / 2f + 60, h / 2f, p)
        return bmp
    }

    companion object {
        private const val TYPE_GAME = 0
        private const val TYPE_ADD = 1
    }
}
