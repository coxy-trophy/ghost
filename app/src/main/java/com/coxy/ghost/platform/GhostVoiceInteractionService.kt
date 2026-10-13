package com.coxy.ghost.platform

import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.util.AtomicFile
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.FileDescriptor
import java.io.PrintWriter
import java.lang.reflect.InvocationTargetException

/** Opt-in, model-free framework entry point. Probes capability without starting microphone capture. */
class GhostVoiceInteractionService : VoiceInteractionService() {
    private var softwareProbe: ProbeLifetime? = null
    private var probeGeneration = 0
    private val probeHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var report = JSONObject().put("lifecycle", "CREATED")

    override fun onReady() {
        super.onReady()
        // This bridge needs no foreground app content or screenshot.
        setDisabledShowContext(VoiceInteractionSession.SHOW_WITH_ASSIST or VoiceInteractionSession.SHOW_WITH_SCREENSHOT)
        probe()
    }

    private fun probe() {
        val active = isActiveService(this, ComponentName(this, javaClass))
        val methods = VoiceInteractionService::class.java.methods
        var count: Int? = null
        var failure: String? = null
        if (active) {
            try {
                // Test API absent from the public SDK. Respect runtime access checks; never
                // change hidden-API policy, enrollment, module state or recognition here.
                val method = methods.firstOrNull { it.name == "listModuleProperties" && it.parameterCount == 0 }
                    ?: throw NoSuchMethodException("listModuleProperties")
                val modules = method.invoke(this) as? List<*>
                    ?: throw IllegalStateException("Unexpected module list")
                count = modules.size
            } catch (error: Exception) {
                val cause = if (error is InvocationTargetException) error.targetException else error
                failure = cause.javaClass.simpleName
            } catch (error: LinkageError) {
                failure = error.javaClass.simpleName
            }
        }
        fun granted(permission: String) = checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
        report = JSONObject()
            .put("lifecycle", "READY")
            .put("elapsed_ms", SystemClock.elapsedRealtime())
            .put("active_service", active)
            .put("assessment", PlatformVoiceCapability.assessment(active, count, failure))
            .put("module_count", count ?: JSONObject.NULL)
            .put("probe_failure", failure ?: JSONObject.NULL)
            .put("record_audio_granted", granted("android.permission.RECORD_AUDIO"))
            .put("capture_audio_hotword_granted", granted("android.permission.CAPTURE_AUDIO_HOTWORD"))
            .put("manage_hotword_detection_granted", granted("android.permission.MANAGE_HOTWORD_DETECTION"))
            .put("dsp_detector_api_visible", methods.any { it.name == "createAlwaysOnHotwordDetector" })
            .put("software_detector_api_visible", methods.any { it.name == "createHotwordDetector" })
            .put("recognition_started", false)
            .put("enrollment_verified", false)
            .put("indicator_behavior_verified", false)
        probeSoftware()
        publish()
    }

    private fun probeSoftware() {
        val mine = ++probeGeneration
        probeHandler.removeCallbacksAndMessages(null)
        runCatching { softwareProbe?.close() }; softwareProbe = null
        if (!report.optBoolean("manage_hotword_detection_granted") || !report.optBoolean("capture_audio_hotword_granted")) {
            report.put("software_probe", "PERMISSION_REQUIRED")
            return
        }
        report.put("software_probe", "WAITING_INITIALIZATION")
        val lifetime = ProbeLifetime()
        softwareProbe = lifetime
        fun finish(status: String) {
            if (mine != probeGeneration || lifetime.finished || report.optString("lifecycle") != "READY") return
            // This is an initialization-only probe. Even success releases the sandbox.
            report.put("software_probe", status)
            probeHandler.removeCallbacksAndMessages(null)
            lifetime.close()
            publish()
        }
        try {
            lifetime.attach(PlatformSoftwareProbe.create(this) { status -> finish(status) })
            probeHandler.postDelayed({
                finish("TIMEOUT")
            }, 15_000)
        } catch (error: Exception) {
            val cause = if (error is InvocationTargetException) error.targetException else error
            finish("BLOCKED_" + cause.javaClass.simpleName)
        } catch (error: LinkageError) {
            finish("BLOCKED_" + error.javaClass.simpleName)
        }
    }

    private fun publish() {
        Log.i("GhostPlatformVoice", report.toString())
        // Private diagnostic output only, with no ambient content or credentials.
        val file = AtomicFile(File(filesDir, "voice-platform-probe.json"))
        var stream: java.io.FileOutputStream? = null
        try {
            stream = file.startWrite()
            stream.write(report.toString(2).toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (error: Exception) {
            if (stream != null) file.failWrite(stream)
            Log.w("GhostPlatformVoice", "Could not persist capability report", error)
        }
    }

    override fun onLaunchVoiceAssistFromKeyguard() { showSession(Bundle(), 0) }

    override fun onShutdown() {
        probeGeneration++
        probeHandler.removeCallbacksAndMessages(null)
        runCatching { softwareProbe?.close() }; softwareProbe = null
        report.put("lifecycle", "SHUTDOWN").put("active_service", false)
            .put("assessment", "NOT_SELECTED").put("elapsed_ms", SystemClock.elapsedRealtime())
        publish()
        super.onShutdown()
    }

    override fun dump(fd: FileDescriptor, writer: PrintWriter, args: Array<out String>) {
        writer.println(report.toString(2))
    }
}
