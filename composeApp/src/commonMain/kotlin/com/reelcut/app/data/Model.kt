package com.reelcut.app.data

import kotlinx.serialization.Serializable

/**
 * All times are in microseconds, matching Media3.
 * New fields always get defaults so projects saved by older builds still load.
 */
@Serializable
data class Project(
    val schemaVersion: Int = 4,
    val id: String,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
    /** Main video track, in play order. */
    val clips: List<Clip> = emptyList(),
    val canvas: Canvas = Canvas(),
    val export: ExportSettings = ExportSettings(),
    /** "Mute Clip": silences the sound of every clip on the main track. */
    val muteOriginal: Boolean = false,
    /** Text and logos drawn over the video, in drawing order (last on top). */
    val overlays: List<Overlay> = emptyList(),
    /** Music and voice-overs mixed with the video's own sound. */
    val audio: List<AudioClip> = emptyList(),
) {
    val durationUs: Long get() = Timeline.totalUs(clips)
}

@Serializable
enum class ClipKind { VIDEO, PHOTO }

@Serializable
data class Clip(
    val id: String,
    val sourceUri: String,
    /** For photos this is the longest the photo may be shown ([Clip.MAX_PHOTO_US]). */
    val sourceDurationUs: Long,
    /** Display size after the file's own rotation metadata. */
    val width: Int = 0,
    val height: Int = 0,
    val hasAudio: Boolean = true,
    val trimStartUs: Long = 0,
    /** Exclusive. For photos, trimEndUs is how long the photo shows. */
    val trimEndUs: Long,
    /** 0.25 to 4.0. Photos ignore speed. */
    val speed: Float = 1f,
    /** 0 (muted) to 2.0 (200%). */
    val volume: Float = 1f,
    val kind: ClipKind = ClipKind.VIDEO,
    val mimeType: String? = null,
    /** Extra rotation chosen by the user: 0, 90, 180 or 270 degrees clockwise. */
    val rotation: Int = 0,
    val flipH: Boolean = false,
    /** Fill the canvas (crop) instead of fitting inside it. */
    val fill: Boolean = false,
) {
    val isPhoto: Boolean get() = kind == ClipKind.PHOTO

    /** Speed actually applied: photos always play at 1x. */
    val effectiveSpeed: Float get() = if (isPhoto) 1f else speed

    /** Length of this clip on the timeline. */
    val durationUs: Long get() = ((trimEndUs - trimStartUs) / effectiveSpeed).toLong()

    /** Width and height after the user's rotation. */
    val rotatedWidth: Int get() = if (rotation % 180 == 0) width else height
    val rotatedHeight: Int get() = if (rotation % 180 == 0) height else width

    companion object {
        const val DEFAULT_PHOTO_US = 3_000_000L
        const val MAX_PHOTO_US = 30_000_000L
        const val MIN_SPEED = 0.25f
        const val MAX_SPEED = 4f
        const val MAX_VOLUME = 2f
    }
}

@Serializable
enum class Aspect(val label: String, val w: Int, val h: Int) {
    ORIGINAL("Original", 0, 0),
    R9_16("9:16", 9, 16),
    R4_5("4:5", 4, 5),
    R1_1("1:1", 1, 1),
    R16_9("16:9", 16, 9),
}

@Serializable
data class Canvas(
    val aspect: Aspect = Aspect.ORIGINAL,
    val backgroundArgb: Long = 0xFF000000,
    /** Blurred copy of the video behind it instead of plain black bars. */
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
