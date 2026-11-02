package com.playrx.live.audio

import android.content.Context
import android.net.Uri
import java.io.File

/**
 * App-private sample library: files + manifest.
 *
 * - Files live in filesDir/samples/ (private, no storage permission
 *   needed for playback, and no sample is ever uploaded anywhere).
 * - The manifest is filesDir/samples/manifest.json, written by
 *   SampleEngine.manifestToJson and read by manifestFromJson.
 * - Import flow: SAF picker -> copy bytes -> WAV parse, or
 *   MediaCodec decode for OGG/MP3/AAC -> write a canonical WAV
 *   copy? No — v1 keeps the original bytes as copied, and stores
 *   the decoded PCM in memory only. On next launch, the manifest
 *   is re-read and each file is re-decoded (WAV fast, MediaCodec
 *   for the rest). That keeps the store simple and the manifest
 *   the single source of truth.
 * - Original BPM / key default to the project values at import
 *   time, so tempo snap starts at 1.0x and key dynamics start at
 *   0 semitones. The user edits them in the Edit panel (transpose
 *   is per pad; original BPM/key are per sample and are edited
 *   in a future pass — see docs/SAMPLE-ENGINE.md).
 */
object SampleStore {
    private const val DIR = "samples"
    private const val MANIFEST = "manifest.json"

    private fun dir(context: Context): File {
        val d = File(context.filesDir, DIR)
        if (!d.exists()) d.mkdirs()
        return d
    }

    fun loadManifest(context: Context): List<SampleInfo> {
        val f = File(dir(context), MANIFEST)
        if (!f.exists()) return emptyList()
        return runCatching { SampleEngine.manifestFromJson(f.readText()) }.getOrDefault(emptyList())
    }

    private fun saveManifest(context: Context, samples: List<SampleInfo>) {
        File(dir(context), MANIFEST).writeText(SampleEngine.manifestToJson(samples))
    }

    fun sampleFile(context: Context, info: SampleInfo): File = File(dir(context), info.fileName)

    /** Replace one manifest entry (original BPM/key edits) and persist it. */
    fun updateSampleInfo(context: Context, updated: SampleInfo) {
        saveManifest(context, loadManifest(context).map { if (it.id == updated.id) updated else it })
    }

    /**
     * Import [uri] into the library. Returns the new SampleInfo, or
     * null if the file could not be decoded. [projectBpm] and
     * [projectKeyMidi] become the sample's defaults (see header).
     */
    fun importUri(
        context: Context,
        uri: Uri,
        projectBpm: Float,
        projectKeyMidi: Int?,
        category: SampleCategory = SampleCategory.OTHER,
    ): SampleInfo? {
        val bytes = runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull() ?: return null
        val displayName = uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: "sample-${System.currentTimeMillis()}"
        val safeName = displayName.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80)
        val fileName = "${System.currentTimeMillis()}_$safeName"
        val outFile = File(dir(context), fileName)
        outFile.writeBytes(bytes)

        val wav = SampleEngine.parseWav(bytes)
        // Decoded frames + rate, from WAV or MediaCodec, for both
        // the manifest and tempo detection below.
        val decoded: SampleEngine.WavData? = if (wav != null) {
            wav
        } else {
            val d = SampleDecoder.decodeToPcm(context, uri) ?: run {
                outFile.delete()
                return null
            }
            SampleEngine.WavData(d.sampleRate, d.channels, d.frames)
        }
        // Autodetect tempo on import (2026-11-02). If detection
        // finds nothing clear, fall back to the project BPM — the
        // user can still edit it in the EDIT panel afterwards.
        val detectedBpm = SampleEngine.detectBpm(decoded.frames, decoded.sampleRate)
        val info = SampleInfo(
            id = fileName,
            name = displayName,
            fileName = fileName,
            sampleRate = decoded.sampleRate,
            channels = decoded.channels,
            frames = decoded.frames.size,
            originalBpm = detectedBpm ?: projectBpm,
            originalKeyMidi = projectKeyMidi,
            category = category,
        )
        val existing = loadManifest(context)
        saveManifest(context, existing + info)
        return info
    }

    /** Decode a stored sample back to mono float frames. */
    fun decodeStored(context: Context, info: SampleInfo): SampleEngine.WavData? {
        val file = sampleFile(context, info)
        if (!file.exists()) return null
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return null
        val wav = SampleEngine.parseWav(bytes)
        if (wav != null) return wav
        val decoded = SampleDecoder.decodeToPcm(context, Uri.fromFile(file)) ?: return null
        return SampleEngine.WavData(decoded.sampleRate, decoded.channels, decoded.frames)
    }

    fun categoryForColumn(col: Int): SampleCategory = when (col) {
        0, 1, 2 -> SampleCategory.DRUM
        3 -> SampleCategory.BASS
        4 -> SampleCategory.MELODY
        5 -> SampleCategory.PAD
        6 -> SampleCategory.VOX
        7 -> SampleCategory.FX
        else -> SampleCategory.OTHER
    }
}
