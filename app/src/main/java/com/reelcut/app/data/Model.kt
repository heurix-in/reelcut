package com.reelcut.app.data

import kotlinx.serialization.Serializable

/** All times are in microseconds, matching Media3. */
@Serializable
data class Project(
    val schemaVersion: Int = 1,
    val id: String,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
    /** Main video track, in play order. */
    val clips: List<Clip> = emptyList(),
    val canvas: Canvas = Canvas(),
    val export: ExportSettings = ExportSettings(),
) {
    val durationUs: Long get() = Timeline.totalUs(clips)
}

@Serializable
data class Clip(
    val id: String,
    val sourceUri: String,
    val sourceDurationUs: Long,
    /** Display size after rotation. */
    val width: Int = 0,
    val height: Int = 0,
    val hasAudio: Boolean = true,
    val trimStartUs: Long = 0,
    /** Exclusive. */
    val trimEndUs: Long,
    val speed: Float = 1f,
    val volume: Float = 1f,
) {
    /** Length of this clip on the timeline. */
    val durationUs: Long get() = ((trimEndUs - trimStartUs) / speed).toLong()
}

@Serializable
enum class Aspect { ORIGINAL, R9_16, R1_1, R16_9, R4_5 }

@Serializable
data class Canvas(
    val aspect: Aspect = Aspect.ORIGINAL,
    val backgroundArgb: Long = 0xFF000000,
    val blurBackground: Boolean = false,
)

@Serializable
enum class Quality { LOW, MEDIUM, HIGH }

@Serializable
data class ExportSettings(
    /** Short side of the output in pixels: 720 or 1080. */
    val height: Int = 1080,
    val fps: Int = 30,
    val quality: Quality = Quality.HIGH,
)
