package com.coxy.ghost.perception

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Phase 5 rolling-window retention (GHOST.md §8: decide the retention policy *before* Phase 5).
 * Captured camera frames + mic transcript segments persist to [cacheDir]/perception under a size
 * cap + per-modality TTL, pruned on a schedule by [com.coxy.ghost.GhostService]. Raw media is wiped
 * on [clear] (called when perception stops and on kill-switch).
 *
 * cacheDir is app-private (the app is a priv-app), so no READ/WRITE_MEDIA_* permissions are needed.
 * Thread-safe: every public method synchronizes on [lock] — callers are the mic thread, the camera
 * thread, and the service prune coroutine.
 *
 * The model never reads from here — the prompt only carries the metadata in [ContextState]. This
 * store exists so recent context can be re-queried and for diagnostics; it is deliberately bounded.
 */
class RetentionManager(ctx: Context) {

    private val root = File(ctx.cacheDir, DIR)
    private val framesDir = File(root, "frames")
    private val transcriptsDir = File(root, "transcripts")

    private val lock = Any()

    /** Latest transcript snippets, newest last (in-memory ring for instant prompt/diagnostic use). */
    private val recentTranscripts = ArrayDeque<String>()

    init {
        framesDir.mkdirs()
        transcriptsDir.mkdirs()
    }

    /** Persist a captured JPEG frame. Returns the file written, or null on failure. */
    fun putFrame(jpegBytes: ByteArray, tsMs: Long): File? = synchronized(lock) {
        runCatching {
            val f = File(framesDir, "$tsMs.jpg")
            f.writeBytes(jpegBytes)
            Log.i(TAG, "putFrame ${f.name} (${jpegBytes.size}B)")
            f
        }.onFailure { Log.w(TAG, "putFrame failed", it) }.getOrNull()
    }

    /** Persist a transcript segment + keep it in the in-memory ring. */
    fun putTranscript(text: String, tsMs: Long) = synchronized(lock) {
        if (text.isBlank()) return@synchronized
        runCatching {
            File(transcriptsDir, "$tsMs.txt").writeText(text)
        }.onFailure { Log.w(TAG, "putTranscript failed", it) }
        recentTranscripts.addLast(text)
        while (recentTranscripts.size > RING_TRANSCRIPTS) recentTranscripts.removeFirst()
    }

    /** Newest-first snapshot of recent transcripts (diagnostic / re-query). */
    fun recentTranscripts(): List<String> = synchronized(lock) {
        recentTranscripts.toList().reversed()
    }

    /** Delete oldest files until under the byte cap and all expired files are gone. */
    fun prune() = synchronized(lock) {
        val now = System.currentTimeMillis()
        // 1. TTL sweep per modality.
        framesDir.listFiles()?.forEach { f ->
            if (now - lastModifiedTs(f) > FRAME_TTL_MS) f.delete()
        }
        transcriptsDir.listFiles()?.forEach { f ->
            if (now - lastModifiedTs(f) > TRANSCRIPT_TTL_MS) f.delete()
        }
        // 2. Cap sweep: across both dirs, delete oldest until total ≤ MAX_CACHE_BYTES.
        val all = (framesDir.listFiles().orEmpty().toList() + transcriptsDir.listFiles().orEmpty().toList())
            .filterNotNull()
            .filter { it.isFile }
            .sortedBy { lastModifiedTs(it) }
        var total = all.sumOf { it.length() }
        for (f in all) {
            if (total <= MAX_CACHE_BYTES) break
            total -= f.length()
            f.delete()
        }
    }

    /** Wipe everything (called on stop + kill-switch). */
    fun clear() = synchronized(lock) {
        runCatching {
            framesDir.listFiles()?.forEach { it.delete() }
            transcriptsDir.listFiles()?.forEach { it.delete() }
            recentTranscripts.clear()
        }
        Log.i(TAG, "cleared")
    }

    /** Approximate total bytes used (for the heartbeat monitor). */
    fun bytesUsed(): Long = synchronized(lock) {
        (framesDir.listFiles().orEmpty().toList() + transcriptsDir.listFiles().orEmpty().toList())
            .filterNotNull().filter { it.isFile }.sumOf { it.length() }
    }

    private fun lastModifiedTs(f: File): Long =
        runCatching { f.name.removeSuffix(".jpg").removeSuffix(".txt").toLong() }
            .getOrElse { f.lastModified() }

    companion object {
        private const val TAG = "RetentionManager"
        private const val DIR = "perception"
        private const val MAX_CACHE_BYTES = 50L * 1024 * 1024   // 50 MiB
        private const val FRAME_TTL_MS = 5 * 60 * 1000L          // 5 min
        private const val TRANSCRIPT_TTL_MS = 60 * 60 * 1000L    // 1 h
        private const val RING_TRANSCRIPTS = 16
    }
}