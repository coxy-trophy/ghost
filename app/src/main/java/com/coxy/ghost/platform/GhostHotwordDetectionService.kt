package com.coxy.ghost.platform

import android.os.PersistableBundle
import android.os.SharedMemory
import android.service.voice.HotwordDetectionService
import java.util.function.IntConsumer

/** Initialization-only system sandbox probe. No model, microphone, storage, or network access. */
class GhostHotwordDetectionService : HotwordDetectionService() {
    override fun onUpdateState(options: PersistableBundle?, sharedMemory: SharedMemory?, callbackTimeoutMillis: Long,
        statusCallback: IntConsumer?) {
        // Successful sandbox initialization is not recognition readiness or model enrollment.
        statusCallback?.accept(INITIALIZATION_STATUS_SUCCESS)
    }
}
