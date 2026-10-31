package com.reelcut.app.media

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Small frames for the timeline and project cards. Frames are decoded one at a time on a
 * background thread, with a few retrievers kept open so neighbouring frames are quick.
 */
class ThumbnailCache(private val context: Context) {

    private val memory = object : LruCache<String, ImageBitmap>(maxKb()) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4 / 1024
    }
    private val retrievers = object : LinkedHashMap<String, MediaMetadataRetriever>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, MediaMetadataRetriever>?): Boolean {
            if (size > 3) {
                eldest?.value?.let { runCatching { it.release() } }
                return true
            }
            return false
        }
    }
    private val lock = Mutex()

    /** Rounds to half a second so nearby requests share one frame. */
    fun quantize(timeUs: Long): Long = (timeUs / STEP_US) * STEP_US

    fun peek(uri: String, timeUs: Long, heightPx: Int): ImageBitmap? = memory.get(key(uri, timeKey(uri, timeUs), heightPx))

    /** Photos use one frame for every time. */
    private fun timeKey(uri: String, timeUs: Long): Long = if (imageUris[uri] == true) 0L else quantize(timeUs)

    suspend fun get(uri: String, timeUs: Long, heightPx: Int): ImageBitmap? {
        val t = timeKey(uri, timeUs)
        val k = key(uri, t, heightPx)
        memory.get(k)?.let { return it }
        return withContext(Dispatchers.IO) {
            lock.withLock {
                memory.get(k) ?: decode(uri, t, heightPx)?.also { memory.put(k, it) }
            }
        }
    }

    private val imageUris = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    private fun decode(uri: String, timeUs: Long, heightPx: Int): ImageBitmap? {
        val isImage = imageUris.getOrPut(uri) { MediaProbe.isImage(context, Uri.parse(uri)) }
        return if (isImage) decodePhoto(uri, heightPx) else decodeVideo(uri, timeUs, heightPx)
    }

    /** Photos look the same at every time, so one frame per size is cached. */
    private fun decodePhoto(uri: String, heightPx: Int): ImageBitmap? {
        val bmp = MediaProbe.decodePhoto(context, Uri.parse(uri), heightPx * 2) ?: return null
        val outW = (bmp.width.toLong() * heightPx / bmp.height).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(bmp, outW, heightPx, true)
        if (scaled !== bmp) bmp.recycle()
        return scaled.asImageBitmap()
    }

    private fun decodeVideo(uri: String, timeUs: Long, heightPx: Int): ImageBitmap? = try {
        val r = retrievers.getOrPut(uri) {
            MediaMetadataRetriever().apply { setDataSource(context, Uri.parse(uri)) }
        }
        val frame: Bitmap? = if (Build.VERSION.SDK_INT >= 27) {
            val (w, h) = displaySize(r)
            val outH = heightPx
            val outW = if (h > 0) (w.toLong() * outH / h).toInt().coerceAtLeast(1) else outH
            r.getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, outW, outH)
        } else {
            r.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let { full ->
                val outW = (full.width.toLong() * heightPx / full.height).toInt().coerceAtLeast(1)
                val scaled = Bitmap.createScaledBitmap(full, outW, heightPx, true)
                if (scaled !== full) full.recycle()
                scaled
            }
        }
        frame?.asImageBitmap()
    } catch (e: Exception) {
        retrievers.remove(uri)?.let { runCatching { it.release() } }
        null
    }

    private fun displaySize(r: MediaMetadataRetriever): Pair<Int, Int> {
        val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
        val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
        val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        return if (rot == 90 || rot == 270) h to w else w to h
    }

    private fun key(uri: String, t: Long, h: Int) = "$uri|$t|$h"

    companion object {
        const val STEP_US = 500_000L
        private fun maxKb(): Int = (Runtime.getRuntime().maxMemory() / 1024 / 8).toInt()
    }
}
