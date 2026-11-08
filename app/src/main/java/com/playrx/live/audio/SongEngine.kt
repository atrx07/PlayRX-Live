package com.playrx.live.audio

/**
 * SONG timeline (2026-11-08) — the arranger from the Remixlive
 * Tutorial Tour, as pure Kotlin with no Android APIs.
 *
 * A Song is a timeline, NOT a grid: numbered bars across the top,
 * the 8 instrument columns as tracks down the left, and clips
 * placed on it. A clip references a pad:
 * - a loop pad (Pad/FX column, or an imported LOOP sample) plays
 *   for the clip's bars, then stops;
 * - a one-shot pad fires once at the clip's start bar.
 * The timeline loops with the transport (Stop resets to bar 1).
 *
 * Operations are the ones Remixlive shows on a clip: place, move,
 * shrink/grow, duplicate, cut (split at a bar), remove. Copy is
 * duplicate + move. Zoom is UI state only (bars visible), not
 * stored here.
 *
 * JSON is hand-rolled like the sequencer's, so the Projects slice
 * can persist the whole song in one file.
 */
data class SongClip(
    val id: String,
    /** Track = instrument column 0..7. */
    val track: Int,
    /** 1-based bar the clip starts on. */
    val startBar: Int,
    /** Length in bars, >= 1. */
    val lengthBars: Int,
    /** Pad whose sound the clip plays. */
    val pad: Int,
    val name: String,
) {
    val endBar: Int get() = startBar + lengthBars - 1
    fun covers(bar: Int): Boolean = bar in startBar..endBar
    fun sanitized(lengthBarsMax: Int): SongClip = copy(
        track = track.coerceIn(0, SampleBank.COLUMNS - 1),
        startBar = startBar.coerceAtLeast(1),
        lengthBars = lengthBars.coerceIn(1, lengthBarsMax),
        pad = pad.coerceIn(0, SampleBank.PAD_COUNT - 1),
    )
}

class SongTimeline(
    /** Total bars in the arrangement. */
    var lengthBars: Int = 16,
) {
    private val clips = mutableListOf<SongClip>()
    private var nextNumber = 1

    fun list(): List<SongClip> = clips.sortedWith(compareBy({ it.track }, { it.startBar }))

    fun clipsAtBar(bar: Int): List<SongClip> = clips.filter { it.covers(bar) }

    fun clipAt(track: Int, bar: Int): SongClip? =
        clips.firstOrNull { it.track == track && it.covers(bar) }

    /** Place a clip. Returns null when the cell is already occupied
     * by a clip on the same track (clips on one track may not
     * overlap — Remixlive cuts the old clip instead; here placing
     * on an occupied cell is refused and the UI selects the old
     * clip, which is the honest, non-destructive read). */
    fun addClip(track: Int, startBar: Int, lengthBars: Int, pad: Int, name: String): SongClip? {
        if (track !in 0 until SampleBank.COLUMNS) return null
        if (startBar < 1 || startBar > this.lengthBars) return null
        val len = lengthBars.coerceIn(1, this.lengthBars - startBar + 1)
        if (clips.any { it.track == track && it.startBar <= startBar + len - 1 && startBar <= it.endBar }) return null
        val clip = SongClip(
            id = "clip-${nextNumber++}", track = track,
            startBar = startBar, lengthBars = len, pad = pad, name = name,
        )
        clips.add(clip)
        return clip
    }

    fun removeClip(id: String): Boolean = clips.removeAll { it.id == id }

    fun moveClip(id: String, newTrack: Int, newStartBar: Int): Boolean {
        val clip = clips.firstOrNull { it.id == id } ?: return false
        if (newTrack !in 0 until SampleBank.COLUMNS) return false
        if (newStartBar < 1 || newStartBar + clip.lengthBars - 1 > lengthBars) return false
        if (clips.any { it.id != id && it.track == newTrack &&
                it.startBar <= newStartBar + clip.lengthBars - 1 && newStartBar <= it.endBar }) return false
        clips.remove(clip)
        clips.add(clip.copy(track = newTrack, startBar = newStartBar))
        return true
    }

    fun resizeClip(id: String, newLengthBars: Int): Boolean {
        val clip = clips.firstOrNull { it.id == id } ?: return false
        val len = newLengthBars.coerceIn(1, lengthBars - clip.startBar + 1)
        if (clips.any { it.id != id && it.track == clip.track &&
                it.startBar <= clip.startBar + len - 1 && clip.startBar <= it.endBar }) return false
        clips.remove(clip)
        clips.add(clip.copy(lengthBars = len))
        return true
    }

    /** Duplicate a clip to another bar on the same track. */
    fun duplicateClip(id: String, newStartBar: Int): SongClip? {
        val clip = clips.firstOrNull { it.id == id } ?: return null
        return addClip(clip.track, newStartBar, clip.lengthBars, clip.pad, clip.name)
    }

    /** Cut a clip at [bar] (must be strictly inside it): the left
     * part keeps the id, the right part is a new clip. Returns the
     * new clip, or null when the cut is not inside the clip. */
    fun splitClip(id: String, bar: Int): SongClip? {
        val clip = clips.firstOrNull { it.id == id } ?: return null
        if (bar <= clip.startBar || bar > clip.endBar) return null
        val leftLen = bar - clip.startBar
        val rightLen = clip.lengthBars - leftLen
        clips.remove(clip)
        clips.add(clip.copy(lengthBars = leftLen))
        return addClip(clip.track, bar, rightLen, clip.pad, clip.name)
    }

    /** Replace this timeline's contents with [other]'s (project load). */
    fun loadFrom(other: SongTimeline) {
        lengthBars = other.lengthBars
        clips.clear()
        clips.addAll(other.list())
        nextNumber = (clips.mapNotNull { it.id.removePrefix("clip-").toIntOrNull() }.maxOrNull() ?: 0) + 1
    }

    fun clear() {
        clips.clear()
    }

    fun toJson(): String {
        val sb = StringBuilder()
        sb.append("{\"lengthBars\":").append(lengthBars).append(",\"clips\":[")
        sb.append(list().joinToString(",") { c ->
            "{\"id\":\"${c.id}\",\"track\":${c.track},\"startBar\":${c.startBar}," +
                "\"lengthBars\":${c.lengthBars},\"pad\":${c.pad}," +
                "\"name\":\"${c.name.replace("\"", "\\\"")}\"}"
        })
        sb.append("]}")
        return sb.toString()
    }

    companion object {
        fun fromJson(json: String): SongTimeline? {
            val len = Regex("\"lengthBars\":(\\d+)").find(json)?.groupValues?.get(1)?.toIntOrNull()
                ?: return null
            val timeline = SongTimeline(len.coerceIn(1, 64))
            val clipRe = Regex(
                "\\{\"id\":\"([^\"]+)\",\"track\":(\\d+),\"startBar\":(\\d+)," +
                    "\"lengthBars\":(\\d+),\"pad\":(\\d+),\"name\":\"([^\"]*)\"\\}",
            )
            for (m in clipRe.findAll(json)) {
                val clip = SongClip(
                    id = m.groupValues[1],
                    track = m.groupValues[2].toInt(),
                    startBar = m.groupValues[3].toInt(),
                    lengthBars = m.groupValues[4].toInt(),
                    pad = m.groupValues[5].toInt(),
                    name = m.groupValues[6],
                ).sanitized(len)
                timeline.clips.add(clip)
                timeline.nextNumber = maxOf(
                    timeline.nextNumber,
                    clip.id.removePrefix("clip-").toIntOrNull()?.plus(1) ?: timeline.nextNumber,
                )
            }
            return timeline
        }
    }
}
