package com.reelcut.app.data

import kotlinx.coroutines.delay

/** A project store held in memory, with switches for slow and failing writes. */
class InMemoryStore : ProjectStore {
    val files = LinkedHashMap<String, String>()
    var failWrites = false
    var writeDelayMs = 0L
    var writes = 0

    override suspend fun readAll(): List<StoredProject> = files.map { StoredProject(it.key, it.value) }

    override suspend fun read(id: String): String? = files[id]

    override suspend fun write(id: String, text: String) {
        if (writeDelayMs > 0) delay(writeDelayMs)
        if (failWrites) throw IllegalStateException("disk full")
        files[id] = text
        writes++
    }

    override suspend fun delete(id: String) {
        files.remove(id)
    }
}

fun sampleClip(id: String, durationUs: Long = 4_000_000L, trimStartUs: Long = 0L, trimEndUs: Long = durationUs) = Clip(
    id = id,
    sourceUri = "content://media/$id",
    sourceDurationUs = durationUs,
    width = 1080,
    height = 1920,
    trimStartUs = trimStartUs,
    trimEndUs = trimEndUs,
)

/** A project that uses every part of the model, so a round trip would notice a lost field. */
fun fullProject(id: String = "p1", updatedAt: Long = 2_000L) = Project(
    id = id,
    name = "Café 🎬",
    createdAt = 1_000L,
    updatedAt = updatedAt,
    clips = listOf(
        sampleClip("a").copy(speed = 2f, volume = 1.5f, rotation = 90, flipH = true, fill = true),
        sampleClip("b", durationUs = 6_000_000L, trimStartUs = 1_000_000L, trimEndUs = 5_000_000L),
        sampleClip("c").copy(kind = ClipKind.PHOTO, mimeType = "image/png", hasAudio = false),
    ),
    canvas = Canvas(aspect = Aspect.R9_16, backgroundArgb = 0xFF336699, blurBackground = true),
    export = ExportSettings(height = 720, fps = 60, quality = Quality.LOW),
    muteOriginal = true,
    overlays = listOf(
        Overlay(
            id = "t1", kind = OverlayKind.TEXT, startUs = 0, endUs = 2_000_000, text = "Hello",
            font = TextFont.SERIF, colorArgb = 0xFFFF0000, outlineArgb = 0xFF000000, shadow = true,
            labelArgb = 0x80000000, align = TextAlignment.LEFT, x = 0.2f, y = 0.8f, scale = 1.5f, rotation = 15f,
        ),
        Overlay(
            id = "l1", kind = OverlayKind.LOGO, startUs = 0, endUs = 5_000_000,
            imageUri = "content://media/logo", imageWidth = 200, imageHeight = 100, opacity = 0.5f,
        ),
    ),
    audio = listOf(
        AudioClip(
            id = "m1", kind = AudioKind.MUSIC, name = "Song", sourceUri = "content://audio/song",
            sourceDurationUs = 30_000_000, trimStartUs = 1_000_000, trimEndUs = 9_000_000,
            startUs = 500_000, volume = 0.7f, fadeInUs = 1_000_000, fadeOutUs = 2_000_000,
        ),
        AudioClip(
            id = "v1", kind = AudioKind.VOICE, name = "Voice 1", sourceUri = "file:///voice1.m4a",
            sourceDurationUs = 3_000_000, trimEndUs = 3_000_000,
        ),
    ),
)
