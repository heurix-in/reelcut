package com.reelcut.app.media

import android.content.Context
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import com.reelcut.app.data.AudioClip
import com.reelcut.app.data.AudioKind
import com.reelcut.app.data.ProjectRepository
import com.reelcut.app.engine.VoiceRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Records the microphone as AAC in an .m4a file inside the app's private storage. */
class AndroidVoiceRecorder(private val context: Context, private val dir: File) : VoiceRecorder {

    private var recorder: MediaRecorder? = null
    private var file: File? = null
    private var startedAt = 0L

    override fun start(): Boolean {
        if (recorder != null) return false
        dir.mkdirs()
        val out = File(dir, "voice_" + ProjectRepository.newId() + ".m4a")
        @Suppress("DEPRECATION")
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else MediaRecorder()
        return try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioChannels(1)
            r.setAudioSamplingRate(44_100)
            r.setAudioEncodingBitRate(128_000)
            r.setOutputFile(out.absolutePath)
            r.prepare()
            r.start()
            recorder = r
            file = out
            startedAt = SystemClock.elapsedRealtime()
            true
        } catch (e: Exception) {
            runCatching { r.release() }
            out.delete()
            false
        }
    }

    override val elapsedUs: Long
        get() = if (recorder == null) 0L else (SystemClock.elapsedRealtime() - startedAt) * 1000

    override suspend fun stop(name: String): AudioClip? = withContext(Dispatchers.IO) {
        val r = recorder ?: return@withContext null
        val f = file
        recorder = null
        file = null
        // stop() throws if almost nothing was recorded.
        val stopped = runCatching { r.stop() }.isSuccess
        runCatching { r.release() }
        if (f == null) return@withContext null
        if (!stopped) {
            f.delete()
            return@withContext null
        }
        val uri = Uri.fromFile(f)
        val dur = MediaProbe.audioDurationUs(context, uri)
        if (dur == null || dur < 300_000L) {
            f.delete()
            return@withContext null
        }
        AudioClip(
            id = ProjectRepository.newId(),
            kind = AudioKind.VOICE,
            name = name,
            sourceUri = uri.toString(),
            sourceDurationUs = dur,
            trimEndUs = dur,
        )
    }

    override fun cancel() {
        val r = recorder ?: return
        val f = file
        recorder = null
        file = null
        runCatching { r.stop() }
        runCatching { r.release() }
        f?.delete()
    }
}
