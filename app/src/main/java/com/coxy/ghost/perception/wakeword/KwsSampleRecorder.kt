package com.coxy.ghost.perception.wakeword

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Records wake-word training clips through the *same* capture path the detector runs on:
 * `MediaRecorder.AudioSource.MIC` at 16 kHz mono 16-bit, i.e. post-HAL (noise reduction included).
 *
 * This matters. `tinycap` and other ALSA-level tools read the raw capture device, which is a
 * different acoustic path than [android.media.AudioRecord] delivers — training on raw ALSA audio
 * would teach the model a signal the detector never sees at runtime.
 *
 * Clips land in `getExternalFilesDir("kws")` so they can be pulled with plain `adb pull`, no root:
 *   /sdcard/Android/data/com.coxy.ghost/files/kws/<label>_<timestampMs>.wav
 *
 * The caller is responsible for pausing [MicListener] first — two concurrent MIC-source
 * AudioRecord clients are not reliable on this hardware.
 */
object KwsSampleRecorder {

    private const val TAG = "KwsSampleRecorder"

    const val SAMPLE_RATE = 16000
    private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
    private const val FORMAT = AudioFormat.ENCODING_PCM_16BIT
    private const val BITS_PER_SAMPLE = 16
    private const val CHANNELS = 1

    const val LABEL_POSITIVE = "positive"
    const val LABEL_NEGATIVE = "negative"

    /** Guard rails so a bad extra can't fill the disk or block the mic indefinitely. */
    private const val MIN_SECONDS = 1
    private const val MAX_SECONDS = 120

    /**
     * Record [seconds] of audio and write it as a 16 kHz mono WAV.
     *
     * Blocking — call from a background dispatcher.
     *
     * @return the written file, or null if the mic was unavailable.
     */
    fun record(context: Context, label: String, seconds: Int): File? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "RECORD_AUDIO not granted — cannot capture training clips")
            return null
        }

        val duration = seconds.coerceIn(MIN_SECONDS, MAX_SECONDS)
        val safeLabel = if (label == LABEL_POSITIVE) LABEL_POSITIVE else LABEL_NEGATIVE
        val totalSamples = SAMPLE_RATE * duration

        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, FORMAT)
        val bufferSize = minBuf.coerceAtLeast(SAMPLE_RATE)  // >= 1 s of headroom
        val record = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.MIC)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(FORMAT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(CHANNEL)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .build()

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            Log.w(TAG, "AudioRecord failed to initialise — is MicListener still holding the mic?")
            runCatching { record.release() }
            return null
        }

        val outDir = File(context.getExternalFilesDir(null), "kws").apply { mkdirs() }
        val outFile = File(outDir, "${safeLabel}_${System.currentTimeMillis()}.wav")

        return try {
            record.startRecording()
            Log.i(TAG, "recording ${duration}s of '$safeLabel' -> ${outFile.absolutePath}")

            val pcm = ShortArray(totalSamples)
            var offset = 0
            while (offset < totalSamples) {
                val read = record.read(pcm, offset, totalSamples - offset)
                if (read <= 0) {
                    Log.w(TAG, "AudioRecord read returned $read — stopping early at $offset samples")
                    break
                }
                offset += read
            }

            writeWav(outFile, pcm, offset)
            Log.i(TAG, "wrote ${outFile.name} (${offset} samples, ${offset / SAMPLE_RATE.toFloat()}s)")
            outFile
        } catch (e: Exception) {
            Log.e(TAG, "capture failed", e)
            null
        } finally {
            runCatching { record.stop() }
            runCatching { record.release() }
        }
    }

    /** Write [count] samples of [pcm] as a canonical 44-byte-header 16-bit mono PCM WAV. */
    private fun writeWav(file: File, pcm: ShortArray, count: Int) {
        val dataBytes = count * 2
        RandomAccessFile(file, "rw").use { out ->
            out.setLength(0)

            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray())
            header.putInt(36 + dataBytes)
            header.put("WAVE".toByteArray())
            header.put("fmt ".toByteArray())
            header.putInt(16)                                        // PCM fmt chunk size
            header.putShort(1)                                       // PCM
            header.putShort(CHANNELS.toShort())
            header.putInt(SAMPLE_RATE)
            header.putInt(SAMPLE_RATE * CHANNELS * BITS_PER_SAMPLE / 8)  // byte rate
            header.putShort((CHANNELS * BITS_PER_SAMPLE / 8).toShort())  // block align
            header.putShort(BITS_PER_SAMPLE.toShort())
            header.put("data".toByteArray())
            header.putInt(dataBytes)
            out.write(header.array())

            val body = ByteBuffer.allocate(dataBytes).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until count) body.putShort(pcm[i])
            out.write(body.array())
        }
    }
}
