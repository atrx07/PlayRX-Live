package com.playrx.live.audio

import java.io.File

/**
 * Projects + Live Set (2026-11-08) — save/load for a whole project,
 * as two layers:
 *
 * - [ProjectFile] is pure Kotlin: one project's JSON. It bundles
 *   the project settings (BPM, key, master, defaults), the
 *   sequences ([SequenceStore.toJson]), the song
 *   ([SongTimeline.toJson]) and the per-pad sample assignments
 *   (pad -> sample id + [PadSample]). Sample *files* are not
 *   duplicated here — they live in SampleStore's manifest and are
 *   looked up by id on load; a pad whose sample was deleted loads
 *   back to the built-in synth, never as an error.
 * - [ProjectStore] is the Android file layer: one JSON file per
 *   project under filesDir/projects/. The Live Set is simply this
 *   list in modified order (newest first), which is the
 *   home-screen set of projects in Remixlive terms.
 *
 * Recording modes live with the engine (see AudioEngine):
 * Song (loop taps become timeline clips), Drums & Sounds (pad
 * taps quantize into the sequence), Master Audio (the live mix is
 * captured and written with [WavWriter]).
 */
enum class RecordMode { OFF, SONG, DRUMS, MASTER }

data class ProjectData(
    val name: String,
    val bpm: Float,
    val projectKeyMidi: Int,
    val masterVolume: Float,
    val defaultQuantizeBeats: Float,
    val defaultSnapToTempo: Boolean,
    val sequencesJson: String,
    val songJson: String,
    /** One object per assigned pad: {"pad":0,"sampleId":"..","assignment":{...}}. */
    val assignmentsJson: String,
)

object ProjectFile {
    fun padAssignmentToJson(pad: Int, sampleId: String, a: PadSample): String {
        return "{\"pad\":$pad,\"sampleId\":\"$sampleId\"," +
            "\"playMode\":\"${a.playMode.name}\"," +
            "\"gain\":${a.gain},\"pan\":${a.pan}," +
            "\"transpose\":${a.transposeSemitones},\"octave\":${a.octaveShift}," +
            "\"snap\":${a.snapToTempo},\"cut\":${a.cutToGrid}," +
            "\"grid\":${a.gridDivisionBeats},\"reverse\":${a.reverse}," +
            "\"start\":${a.startFrame},\"end\":${a.endFrame}," +
            "\"fadeIn\":${a.fadeInFrames},\"fadeOut\":${a.fadeOutFrames}," +
            "\"filter\":${a.filterCutoffHz},\"quantize\":${a.quantizeBeats}," +
            "\"followKey\":${a.followProjectKey}}"
    }

    fun padAssignmentFromJson(obj: String): Triple<Int, String, PadSample>? {
        val pad = Regex("\"pad\":(\\d+)").find(obj)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        val sampleId = Regex("\"sampleId\":\"([^\"]+)\"").find(obj)?.groupValues?.get(1) ?: return null
        fun floatField(name: String, default: Float): Float =
            Regex("\"$name\":([0-9.\\-]+)").find(obj)?.groupValues?.get(1)?.toFloatOrNull() ?: default
        fun intField(name: String, default: Int): Int =
            Regex("\"$name\":(-?\\d+)").find(obj)?.groupValues?.get(1)?.toIntOrNull() ?: default
        fun boolField(name: String, default: Boolean): Boolean =
            Regex("\"$name\":(true|false)").find(obj)?.groupValues?.get(1)?.toBoolean() ?: default
        val mode = Regex("\"playMode\":\"([A-Z_]+)\"").find(obj)?.groupValues?.get(1)
            ?.let { runCatching { PlayMode.valueOf(it) }.getOrNull() } ?: PlayMode.ONE_SHOT
        val assignment = PadSample(
            pad = pad, sampleId = sampleId, playMode = mode,
            gain = floatField("gain", 1f), pan = floatField("pan", 0f),
            transposeSemitones = intField("transpose", 0),
            octaveShift = intField("octave", 0),
            snapToTempo = boolField("snap", true),
            cutToGrid = boolField("cut", false),
            gridDivisionBeats = floatField("grid", 0.25f),
            reverse = boolField("reverse", false),
            startFrame = intField("start", -1),
            endFrame = intField("end", -1),
            fadeInFrames = intField("fadeIn", 0),
            fadeOutFrames = intField("fadeOut", 0),
            filterCutoffHz = floatField("filter", 20000f),
            quantizeBeats = floatField("quantize", 0f),
            followProjectKey = boolField("followKey", true),
        )
        return Triple(pad, sampleId, assignment)
    }

    fun toJson(data: ProjectData): String {
        val sb = StringBuilder()
        sb.append("{\"name\":\"").append(data.name.replace("\"", "\\\"")).append("\",")
        sb.append("\"bpm\":").append(data.bpm).append(",")
        sb.append("\"key\":").append(data.projectKeyMidi).append(",")
        sb.append("\"master\":").append(data.masterVolume).append(",")
        sb.append("\"defaultQuantize\":").append(data.defaultQuantizeBeats).append(",")
        sb.append("\"defaultSnap\":").append(data.defaultSnapToTempo).append(",")
        sb.append("\"sequences\":").append(data.sequencesJson).append(",")
        sb.append("\"song\":").append(data.songJson).append(",")
        sb.append("\"assignments\":").append(data.assignmentsJson).append("}")
        return sb.toString()
    }

    private fun stringField(json: String, field: String): String? {
        val key = "\"$field\":\""
        val start = json.indexOf(key)
        if (start < 0) return null
        val from = start + key.length
        var i = from
        while (i < json.length) {
            if (json[i] == '\\' && i + 1 < json.length) i += 2
            else if (json[i] == '"') return json.substring(from, i).replace("\\\"", "\"")
            else i++
        }
        return null
    }

    private fun balancedSection(json: String, field: String): String? {
        val key = "\"$field\":"
        val start = json.indexOf(key)
        if (start < 0) return null
        var i = start + key.length
        val open = json[i]
        if (open != '[' && open != '{') return null
        val close = if (open == '[') ']' else '}'
        var depth = 0
        while (i < json.length) {
            when (json[i]) {
                open -> depth++
                close -> {
                    depth--
                    if (depth == 0) return json.substring(start + key.length, i + 1)
                }
            }
            i++
        }
        return null
    }

    fun fromJson(json: String): ProjectData? {
        val name = stringField(json, "name") ?: return null
        fun floatField(field: String, default: Float): Float =
            Regex("\"$field\":([0-9.\\-]+)").find(json)?.groupValues?.get(1)?.toFloatOrNull() ?: default
        fun intField(field: String, default: Int): Int =
            Regex("\"$field\":(-?\\d+)").find(json)?.groupValues?.get(1)?.toIntOrNull() ?: default
        return ProjectData(
            name = name,
            bpm = floatField("bpm", 120f),
            projectKeyMidi = intField("key", 0),
            masterVolume = floatField("master", 0.8f),
            defaultQuantizeBeats = floatField("defaultQuantize", 4f),
            defaultSnapToTempo = json.contains("\"defaultSnap\":true"),
            sequencesJson = balancedSection(json, "sequences") ?: "[]",
            songJson = balancedSection(json, "song") ?: "{\"lengthBars\":16,\"clips\":[]}",
            assignmentsJson = balancedSection(json, "assignments") ?: "[]",
        )
    }

    /** Split the assignments array into its top-level objects. */
    fun splitAssignmentObjects(assignmentsJson: String): List<String> {
        val out = mutableListOf<String>()
        var depth = 0
        var start = -1
        for (i in assignmentsJson.indices) {
            when (assignmentsJson[i]) {
                '{' -> {
                    if (depth == 0) start = i
                    depth++
                }
                '}' -> {
                    depth--
                    if (depth == 0 && start >= 0) {
                        out.add(assignmentsJson.substring(start, i + 1))
                        start = -1
                    }
                }
            }
        }
        return out
    }
}

data class ProjectMeta(val name: String, val lastModified: Long)

object ProjectStore {
    private const val DIR = "projects"

    fun sanitizeName(name: String): String {
        val clean = name.trim().replace(Regex("[^A-Za-z0-9 _\\-()]+"), "").trim()
        return clean.ifBlank { "Untitled" }.take(48)
    }

    private fun dir(context: android.content.Context): File {
        val d = File(context.filesDir, DIR)
        if (!d.exists()) d.mkdirs()
        return d
    }

    fun list(context: android.content.Context): List<ProjectMeta> {
        val d = dir(context)
        return (d.listFiles() ?: emptyArray())
            .filter { it.isFile && it.name.endsWith(".json") }
            .map { ProjectMeta(it.name.removeSuffix(".json"), it.lastModified()) }
            .sortedByDescending { it.lastModified }
    }

    fun save(context: android.content.Context, name: String, json: String): Boolean {
        return try {
            val file = File(dir(context), sanitizeName(name) + ".json")
            file.writeText(json)
            true
        } catch (_: Exception) {
            false
        }
    }

    fun load(context: android.content.Context, name: String): String? {
        return try {
            val file = File(dir(context), sanitizeName(name) + ".json")
            if (!file.exists()) null else file.readText()
        } catch (_: Exception) {
            null
        }
    }

    fun delete(context: android.content.Context, name: String): Boolean {
        return try {
            File(dir(context), sanitizeName(name) + ".json").delete()
        } catch (_: Exception) {
            false
        }
    }

    fun recordingsDir(context: android.content.Context): File {
        val d = File(context.filesDir, "recordings")
        if (!d.exists()) d.mkdirs()
        return d
    }
}
