package com.coxy.ghost.perception

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import androidx.core.content.ContextCompat

/**
 * Phase 5 on-demand camera perception (D2: camera on-demand). Camera2 (not CameraX, per GHOST.md
 * §4) one-shot capture: open the camera, grab a single downscaled JPEG, close immediately — no
 * preview Surface and no repeating request, so the camera is only held for the brief capture.
 *
 * The 1.5B text model cannot ingest pixels (D1: Tier A), so the frame reaches the model only as
 * compact metadata in [ContextState] (`camera=captured(back,br N)`) — a luma-mean brightness
 * computed from a decoded bitmap, plus facing + timestamp. The raw JPEG is handed to
 * [RetentionManager] for the rolling window.
 *
 * Runs on its own `HandlerThread("ghost-camera")` for all Camera2 callbacks (cam0↔cam1 are
 * mutually exclusive on this device per recon). `runCatching` wraps every HAL call — the Unisoc
 * LIMITED HAL + ColorOS can throw; on any failure we set `cameraAvailable=false` and the daemon
 * carries on. **Never touches the InferenceEngine.**
 */
class CameraCapture(
    private val ctx: Context,
    private val retention: RetentionManager? = null,
) {

    companion object {
        private const val TAG = "CameraCapture"
        const val FACING_BACK = "back"
        const val FACING_FRONT = "front"

        @Volatile
        @JvmStatic
        var isBusy: Boolean = false
            private set
    }

    private val cameraThread = HandlerThread("ghost-camera").apply { start() }
    private val cameraHandler = Handler(cameraThread.looper)
    private val cameraManager = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    @Volatile private var cameraDevice: CameraDevice? = null
    @Volatile private var imageReader: ImageReader? = null
    @Volatile private var pendingFacing: String = FACING_BACK

    /**
     * Trigger a single on-demand capture. Returns immediately; the result lands in [ContextStore]
     * + [RetentionManager] asynchronously on the camera thread.
     */
    fun captureOnDemand(facing: String = FACING_BACK) {
        if (isBusy) {
            Log.w(TAG, "capture already in progress — skipping")
            return
        }
        if (!hasPermission(Manifest.permission.CAMERA)) {
            Log.i(TAG, "CAMERA not granted — camera disabled")
            ContextStore.update { it.copy(cameraAvailable = false) }
            return
        }
        pendingFacing = facing
        val id = cameraIdFor(facing)
        if (id == null) {
            fail("no camera id for facing=$facing")
            return
        }
        isBusy = true
        Log.i(TAG, "captureOnDemand facing=$facing id=$id")
        runCatching {
            cameraManager.openCamera(id, deviceStateCb, cameraHandler)
        }.onFailure { fail("openCamera failed", it) }
    }

    private val deviceStateCb = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            cameraDevice = camera
            runCatching { startStillCapture(camera) }
                .onFailure { fail("startStillCapture failed", it); closeCamera() }
        }

        override fun onDisconnected(camera: CameraDevice) {
            fail("camera disconnected")
            runCatching { camera.close() }
        }

        override fun onError(camera: CameraDevice, error: Int) {
            fail("camera device error: $error")
            runCatching { camera.close() }
        }

        override fun onClosed(camera: CameraDevice) {
            cameraDevice = null
        }
    }

    private fun startStillCapture(camera: CameraDevice) {
        val id = cameraIdFor(pendingFacing) ?: throw IllegalStateException("lost camera id")
        val chars = cameraManager.getCameraCharacteristics(id)
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: throw IllegalStateException("no stream config map")
        val jpegSizes = map.getOutputSizes(ImageFormat.JPEG)
            ?: throw IllegalStateException("no JPEG output sizes")
        val target = jpegSizes.sortedBy { it.width * it.height }
            .first { it.width >= 640 && it.height >= 480 }
            .let { it }
        val w = target.width
        val h = target.height
        val sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90

        imageReader = ImageReader.newInstance(w, h, ImageFormat.JPEG, 2)
        imageReader!!.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage()
            if (image != null) {
                runCatching {
                    val buffer = image.planes[0].buffer
                    val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
                    val brightness = computeBrightness(bytes)
                    val now = System.currentTimeMillis()
                    ContextStore.update {
                        it.copy(
                            sceneBrightness = brightness,
                            lastFrameMs = now,
                            lastFrameFacing = pendingFacing,
                            cameraAvailable = true,
                            timestampMs = if (it.timestampMs == 0L) now else it.timestampMs,
                        )
                    }
                    retention?.putFrame(bytes, now)
                    Log.i(TAG, "frame $pendingFacing ${w}x${h} br=${brightness.toInt()} ${bytes.size}B")
                }.onFailure { Log.w(TAG, "frame read failed", it) }
                runCatching { image.close() }
            }
            finishShot()
        }, cameraHandler)

        val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
            addTarget(imageReader!!.surface)
            set(CaptureRequest.JPEG_ORIENTATION, sensorOrientation)
            // Autofocus + auto-exposure for a quick grab.
            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH)
        }
        camera.createCaptureSession(
            listOf(imageReader!!.surface),
            object : android.hardware.camera2.CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: android.hardware.camera2.CameraCaptureSession) {
                    runCatching {
                        session.capture(request.build(), object : android.hardware.camera2.CameraCaptureSession.CaptureCallback() {},
                            cameraHandler)
                    }.onFailure { fail("capture submit failed", it); finishShot() }
                }

                override fun onConfigureFailed(session: android.hardware.camera2.CameraCaptureSession) {
                    fail("capture session configure failed")
                    finishShot()
                }
            },
            cameraHandler,
        )
    }

    /** Tear down after a one-shot (or on failure), release the camera for the next capture. */
    private fun finishShot() {
        runCatching { imageReader?.close() }
        imageReader = null
        closeCamera()
        isBusy = false
    }

    private fun closeCamera() {
        runCatching { cameraDevice?.close() }
    }

    /** Mark camera unavailable + release the busy flag; the daemon keeps running. */
    private fun fail(msg: String, cause: Throwable? = null) {
        Log.w(TAG, msg, cause)
        ContextStore.update { it.copy(cameraAvailable = false) }
        isBusy = false
    }

    /** Release everything (called on service destroy). */
    fun close() {
        cameraHandler.removeCallbacksAndMessages(null)
        runCatching { imageReader?.close() }
        imageReader = null
        runCatching { cameraDevice?.close() }
        cameraDevice = null
        runCatching { cameraThread.quitSafely() }
    }

    private fun cameraIdFor(facing: String): String? = runCatching {
        val want = if (facing == FACING_FRONT) CameraCharacteristics.LENS_FACING_FRONT
                   else CameraCharacteristics.LENS_FACING_BACK
        cameraManager.cameraIdList.firstOrNull { id ->
            runCatching {
                cameraManager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.LENS_FACING) == want
            }.getOrDefault(false)
        }
    }.getOrElse { null }

    private fun computeBrightness(jpeg: ByteArray): Float = runCatching {
        val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
        val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, opts) ?: return@runCatching -1f
        var sum = 0L
        var count = 0
        for (y in 0 until bmp.height step 2) {
            for (x in 0 until bmp.width step 2) {
                val p = bmp.getPixel(x, y)
                sum += (0.299 * Color.red(p) + 0.587 * Color.green(p) + 0.114 * Color.blue(p)).toInt()
                count++
            }
        }
        bmp.recycle()
        if (count == 0) -1f else sum.toFloat() / count
    }.getOrElse { -1f }

    private fun hasPermission(perm: String): Boolean =
        ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED

    @Suppress("unused")
    private fun rotationToDegrees(rotation: Int): Int = when (rotation) {
        Surface.ROTATION_0 -> 0
        Surface.ROTATION_90 -> 90
        Surface.ROTATION_180 -> 180
        Surface.ROTATION_270 -> 270
        else -> 0
    }
}