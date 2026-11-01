package com.reelcut.app.media

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import com.reelcut.app.data.AudioClip
import com.reelcut.app.data.AudioMath
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * Applies a clip's volume and its fade-in / fade-out to 16-bit sound. The sound it receives starts
 * at the clip's first sample, so the time inside the clip is the number of frames seen so far.
 */
class FadeVolumeProcessor(private val clip: AudioClip) : BaseAudioProcessor() {

    private var framesDone = 0L

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val bytes = inputBuffer.remaining()
        if (bytes == 0) return
        val channels = inputAudioFormat.channelCount
        val rate = inputAudioFormat.sampleRate
        val frames = bytes / (2 * channels)
        val out = replaceOutputBuffer(frames * 2 * channels)
        // The clip is played from its own start, so shift into timeline time by clip.startUs.
        var t = framesDone * 1_000_000.0 / rate
        val step = 1_000_000.0 / rate
        repeat(frames) {
            val g = AudioMath.gain(clip, clip.startUs + t.toLong())
            repeat(channels) {
                val s = inputBuffer.short.toInt()
                out.putShort((s * g).roundToInt().coerceIn(-32768, 32767).toShort())
            }
            t += step
        }
        framesDone += frames
        out.flip()
    }
}

/** Writes a silent WAV (mono, 44.1 kHz, 16-bit) that lasts [durationUs]: used to delay a sound's start. */
object SilenceWav {
    private const val RATE = 44_100

    fun write(file: File, durationUs: Long) {
        val frames = durationUs * RATE / 1_000_000L
        val dataBytes = frames * 2
        file.parentFile?.mkdirs()
        RandomAccessFile(file, "rw").use { raf ->
            raf.setLength(0)
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray())
            header.putInt((36 + dataBytes).toInt())
            header.put("WAVE".toByteArray())
            header.put("fmt ".toByteArray())
            header.putInt(16)
            header.putShort(1) // PCM
            header.putShort(1) // mono
            header.putInt(RATE)
            header.putInt(RATE * 2)
            header.putShort(2)
            header.putShort(16)
            header.put("data".toByteArray())
            header.putInt(dataBytes.toInt())
            raf.write(header.array())
            val zeros = ByteArray(64 * 1024)
            var left = dataBytes
            while (left > 0) {
                val n = minOf(left, zeros.size.toLong()).toInt()
                raf.write(zeros, 0, n)
                left -= n
            }
        }
    }
}
