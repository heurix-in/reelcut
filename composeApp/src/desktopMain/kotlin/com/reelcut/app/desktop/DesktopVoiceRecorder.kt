package com.reelcut.app.desktop

import com.reelcut.app.data.AudioClip
import com.reelcut.app.data.AudioKind
import com.reelcut.app.engine.VoiceRecorder
import com.reelcut.app.newId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import javax.sound.sampled.AudioFileFormat
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.TargetDataLine

/** Records the default microphone (44.1 kHz, mono, 16-bit) and saves it as a WAV file in the app's data folder. */
class DesktopVoiceRecorder(private val dir: File) : VoiceRecorder {

    private val format = AudioFormat(SAMPLE_RATE.toFloat(), 16, 1, true, false)
    private var line: TargetDataLine? = null
    private var thread: Thread? = null
    private var pcm: File? = null
    private var startedNanos = 0L
    @Volatile private var recording = false

    override fun start(): Boolean {
        if (line != null) return false
        dir.mkdirs()
        val raw = File(dir, "voice_" + newId() + ".pcm")
        val l = runCatching {
            (AudioSystem.getLine(DataLine.Info(TargetDataLine::class.java, format)) as TargetDataLine).apply {
                open(format)
            }
        }.getOrNull() ?: return false
        l.start()
        line = l
        pcm = raw
        recording = true
        startedNanos = System.nanoTime()
        thread = Thread({
            val buffer = ByteArray(8192)
            runCatching {
                FileOutputStream(raw).use { out ->
                    while (recording) {
                        val n = l.read(buffer, 0, buffer.size)
                        if (n > 0) out.write(buffer, 0, n)
                    }
                }
            }
        }, "voice-recorder").apply { isDaemon = true; start() }
        return true
    }

    override val elapsedUs: Long
        get() = if (line == null) 0L else (System.nanoTime() - startedNanos) / 1000

    private fun halt() {
        recording = false
        runCatching { thread?.join(1000) }
        line?.let { runCatching { it.stop(); it.close() } }
        line = null
        thread = null
    }

    override suspend fun stop(name: String): AudioClip? = withContext(Dispatchers.IO) {
        val raw = pcm ?: return@withContext null
        halt()
        pcm = null
        val frames = raw.length() / 2
        val durationUs = frames * 1_000_000L / SAMPLE_RATE
        if (durationUs < 300_000L) {
            raw.delete()
            return@withContext null
        }
        val wav = File(dir, raw.nameWithoutExtension + ".wav")
        val ok = runCatching {
            FileInputStream(raw).use { input ->
                AudioSystem.write(AudioInputStream(input, format, frames), AudioFileFormat.Type.WAVE, wav)
            }
        }.isSuccess
        raw.delete()
        if (!ok) {
            wav.delete()
            return@withContext null
        }
        AudioClip(
            id = newId(),
            kind = AudioKind.VOICE,
            name = name,
            sourceUri = wav.absolutePath,
            sourceDurationUs = durationUs,
            trimEndUs = durationUs,
        )
    }

    override fun cancel() {
        val raw = pcm
        halt()
        pcm = null
        raw?.delete()
    }

    private companion object {
        const val SAMPLE_RATE = 44_100
    }
}
