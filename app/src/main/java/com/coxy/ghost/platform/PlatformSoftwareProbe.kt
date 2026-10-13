package com.coxy.ghost.platform

import android.os.PersistableBundle
import android.os.SharedMemory
import android.service.voice.AlwaysOnHotwordDetector
import android.service.voice.HotwordDetector
import android.service.voice.HotwordRejectedResult
import android.service.voice.VoiceInteractionService
import java.util.concurrent.Executor

/** Bridges the visible System API using compile-only definitions; no hidden-API policy changes. */
internal object PlatformSoftwareProbe {
    fun create(service: VoiceInteractionService, report: (String) -> Unit): AutoCloseable {
        val callback = object : HotwordDetector.Callback {
            override fun onDetected(eventPayload: AlwaysOnHotwordDetector.EventPayload) { report("UNEXPECTED_DETECTION_IGNORED") }
            override fun onError() { report("SERVICE_ERROR") }
            override fun onRecognitionPaused() { report("PAUSED") }
            override fun onRecognitionResumed() { report("RESUMED") }
            override fun onRejected(result: HotwordRejectedResult) { report("REJECTED") }
            override fun onHotwordDetectionServiceInitialized(status: Int) { report("INITIALIZED_$status") }
            override fun onHotwordDetectionServiceRestarted() { report("SANDBOX_RESTARTED") }
        }
        val method = VoiceInteractionService::class.java.getMethod("createHotwordDetector",
            PersistableBundle::class.java, SharedMemory::class.java, Executor::class.java, HotwordDetector.Callback::class.java)
        val options = PersistableBundle().apply { putBoolean("probe_only", true) }
        val detector = method.invoke(service, options, null, service.mainExecutor, callback) as HotwordDetector
        // Deliberately never call startRecognition: working software KWS still owns capture.
        return AutoCloseable { detector.destroy() }
    }
}
