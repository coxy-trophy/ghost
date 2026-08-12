package com.coxy.ghost

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import android.view.LayoutInflater
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.arm.aichat.AiChat
import com.arm.aichat.InferenceEngine
import com.arm.aichat.isModelLoaded
import com.coxy.ghost.action.ActionExecutor
import com.coxy.ghost.action.ActionRunner
import com.coxy.ghost.cloud.CloudKeyStorage
import com.coxy.ghost.cloud.CloudRouter
import com.coxy.ghost.memory.Embedder
import com.coxy.ghost.memory.FactExtractor
import com.coxy.ghost.memory.MemoryStore
import com.coxy.ghost.memory.MemoryStore.Companion.stripGhostPrefix
import com.coxy.ghost.memory.toMemoryBlock
import com.coxy.ghost.perception.CameraCapture
import com.coxy.ghost.perception.ContextStore
import com.coxy.ghost.perception.GhostPrompts
import com.coxy.ghost.perception.MicState
import com.coxy.ghost.perception.VisionTrigger
import com.coxy.ghost.personality.PersonalityProfile
import com.coxy.ghost.personality.PersonalityRepository
import com.coxy.ghost.ui.CloudDialog
import com.coxy.ghost.ui.PersonalityDialog
import com.coxy.ghost.personality.PersonalitySnapshot
import com.coxy.ghost.personality.PersonalityTranslator
import com.coxy.ghost.perception.toStatusLine
import com.google.android.material.button.MaterialButton
import com.google.android.material.floatingactionbutton.FloatingActionButton
import android.provider.Settings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * GHOST control panel + chat UI.
 *
 * The [InferenceEngine] is a process-wide singleton shared with [GhostService]; the Activity only
 * holds a handle for chat and to observe [InferenceEngine.state]. It does NOT destroy the engine —
 * the Service owns teardown, so the model survives the Activity being destroyed/backgrounded.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var statusTv: TextView
    private lateinit var perceptionTv: TextView
    private lateinit var messagesRv: RecyclerView
    private lateinit var userInputEt: EditText
    private lateinit var sendFab: FloatingActionButton
    private lateinit var btnPick: MaterialButton
    private lateinit var btnStart: MaterialButton
    private lateinit var btnStop: MaterialButton
    private lateinit var btnCapture: MaterialButton
    private lateinit var btnPersonality: MaterialButton
    private lateinit var btnCloud: MaterialButton
    private lateinit var btnMic: MaterialButton

    private val engine: InferenceEngine by lazy { AiChat.getInferenceEngine(applicationContext) }

    // Phase 6 memory: persists turns + retrieves them by semantic similarity. Shared with the
    // service via the process-wide GhostDatabase singleton + the Embedder object singleton.
    private val memoryStore: MemoryStore by lazy { MemoryStore(applicationContext) }
    // Phase 6.2: extracts durable facts from completed exchanges in the background.
    private val factExtractor: FactExtractor by lazy {
        FactExtractor(applicationContext, engine, memoryStore)
    }
    // Phase 6.3: trait profile + snapshot/rollback repository.
    private val personalityRepo: PersonalityRepository by lazy {
        PersonalityRepository(applicationContext)
    }
    // Phase 7: action executor. Runs whitelisted device actions parsed from the model reply.
    private val actionExecutor: ActionExecutor by lazy { ActionExecutor(applicationContext) }
    // Phase 8: cloud-brain router. Falls back to the local 1.5B engine when cloud is unavailable.
    private val cloudRouter: CloudRouter by lazy { CloudRouter(applicationContext, engine) }

    private var generationJob: Job? = null
    private var isModelReady = false
    private var modelPath: String? = null

    private val messages = mutableListOf<Message>()
    private val lastAssistantMsg = StringBuilder()
    private val messageAdapter = MessageAdapter(messages)

    private val pickModel = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { handleSelectedModel(it) } }

    private val requestNotifPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* result ignored; the FGS runs regardless, notification just won't post if denied */ }

    // Phase 4 + 5 perception permissions. Fine location is required for the location FGS type +
    // GPS; RECORD_AUDIO is required for the continuous mic (D2). Activity recognition + camera are
    // optional but requested together (the manager disables those modalities if not granted).
    private val requestPerceptionPerms = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val fine = result[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val mic = result[Manifest.permission.RECORD_AUDIO] == true
        if (fine && mic) {
            // Re-arm perception + the FGS types now that the permissions are granted.
            refreshPerception()
            // Proceed with the Start that was gated on the permissions.
            startGhostService()
        } else {
            Toast.makeText(this, "Fine location + mic are needed for perception", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        onBackPressedDispatcher.addCallback { Log.w(TAG, "Ignore back press for simplicity") }

        statusTv = findViewById(R.id.gguf)
        perceptionTv = findViewById(R.id.perception)
        messagesRv = findViewById(R.id.messages)
        messagesRv.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        messagesRv.adapter = messageAdapter
        userInputEt = findViewById(R.id.user_input)
        sendFab = findViewById(R.id.fab)
        btnPick = findViewById(R.id.btn_pick)
        btnStart = findViewById(R.id.btn_start)
        btnStop = findViewById(R.id.btn_stop)
        btnCapture = findViewById(R.id.btn_capture)
        btnPersonality = findViewById(R.id.btn_personality)
        btnCloud = findViewById(R.id.btn_cloud)
        btnMic = findViewById(R.id.btn_mic)

        btnPick.setOnClickListener { pickModel.launch(arrayOf("*/*")) }
        btnStart.setOnClickListener { startGhost() }
        btnStop.setOnClickListener { stopGhost() }
        btnCapture.setOnClickListener { captureFrame() }
        btnPersonality.setOnClickListener { showPersonalityDialog() }
        btnCloud.setOnClickListener { showCloudDialog() }
        btnMic.setOnClickListener { toggleMic() }
        sendFab.setOnClickListener { if (isModelReady) handleUserInput() }

        // Phase 5: prompt once to enable the hardware volume-key kill-switch accessibility service.
        if (!isKillAccessibilityEnabled()) {
            Toast.makeText(this, "For the volume-key kill switch, enable GHOST in Settings → Accessibility", Toast.LENGTH_LONG).show()
        }

        // POST_NOTIFICATIONS is required (Android 13+) for the foreground notification to show.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestNotifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        // Observe engine state → status banner + chat enablement. Prewarm the engine off-main.
        lifecycleScope.launch(Dispatchers.Default) {
            engine // trigger lazy init + JNI load off the main thread
            engine.state.collect { state ->
                withContext(Dispatchers.Main) { applyState(state) }
            }
        }

        // Observe the perception layer → live status line. Read-only on the StateFlow; this does
        // not touch the engine. ContextStore.state is process-wide, so this works even when the
        // service was boot-started before the Activity existed.
        lifecycleScope.launch {
            ContextStore.state.collect { cs ->
                withContext(Dispatchers.Main) {
                    perceptionTv.text = cs.toStatusLine()
                    // Mic button tracks the actual listener state (OFF means disabled by user).
                    updateMicButton(cs.micState != MicState.OFF)
                }
            }
        }

        // Phase 6: initialise the on-device embedder (copies the ~22 MB model asset to filesDir on
        // first run — off the main thread) and load recent turns into the chat list so the user
        // sees continuity across process death (the in-memory list is otherwise lost on kill).
        lifecycleScope.launch(Dispatchers.IO) {
            Embedder.init(applicationContext)
            val recent = memoryStore.recent(20)
            if (recent.isNotEmpty()) {
                withContext(Dispatchers.Main) {
                    val start = messages.size
                    recent.forEach { messages.add(Message(UUID.randomUUID().toString(), it.content, it.role == "user")) }
                    messageAdapter.notifyItemRangeInserted(start, recent.size)
                }
            }
        }

        // Phase 6: render wake-word voice commands + their streamed replies in the chat.
        // GhostService runs the inference and writes voiceCommand/voiceResponse to ContextStore;
        // this mirrors it into the message list (a user bubble for the spoken command, an assistant
        // bubble that grows with the reply). The EngineGate guard in handleUserInput keeps a typed
        // send from racing a voice generation, so the two paths never interleave tokens.
        lifecycleScope.launch {
            var lastSeq = 0L
            var voiceIdx = -1
            var lastShown = ""
            ContextStore.state.collect { cs ->
                val cmd = cs.voiceCommand
                if (cs.voiceCommandSeq != lastSeq) {
                    // Advance regardless of whether the text survived, matching GhostService's
                    // observer so the two can't disagree about which command is current.
                    lastSeq = cs.voiceCommandSeq
                    if (!cmd.isNullOrBlank()) {
                        voiceIdx = -1
                        withContext(Dispatchers.Main) {
                            messages.add(Message(UUID.randomUUID().toString(), cmd, true))
                            messages.add(Message(UUID.randomUUID().toString(), "", false))
                            messageAdapter.notifyItemRangeInserted(messages.size - 2, 2)
                            voiceIdx = messages.size - 1
                        }
                        lastShown = ""
                    }
                }
                val resp = cs.voiceResponse
                if (voiceIdx >= 0 && resp != null && resp != lastShown) {
                    lastShown = resp
                    withContext(Dispatchers.Main) {
                        if (voiceIdx in 0 until messages.size && !messages[voiceIdx].isUser) {
                            messages[voiceIdx] = messages[voiceIdx].copy(content = resp)
                            messageAdapter.notifyItemChanged(voiceIdx)
                        }
                    }
                }
            }
        }

        // Track the persisted model path (enables Start once a model has been picked once).
        lifecycleScope.launch {
            GhostPrefs.modelPathFlow(applicationContext).collect { p ->
                withContext(Dispatchers.Main) {
                    modelPath = p
                    updateButtons()
                    if (p != null) statusTv.text = "Model: ${File(p).name} — press Start GHOST"
                }
            }
        }

        // Phase 8.1: set initial mic-toggle label from the persisted preference.
        lifecycleScope.launch(Dispatchers.IO) {
            val enabled = GhostPrefs.isMicEnabled(applicationContext)
            withContext(Dispatchers.Main) { updateMicButton(enabled) }
        }
    }

    private fun applyState(state: InferenceEngine.State) {
        val modelBasename = modelPath?.let { File(it).name } ?: "none"
        statusTv.text = "GHOST ${GhostService.isRunning.toRunLabel()} — $state — model: $modelBasename"
        isModelReady = state.isModelLoaded
        userInputEt.isEnabled = isModelReady
        sendFab.isEnabled = isModelReady
        if (isModelReady) userInputEt.hint = "Type and send a message!" else userInputEt.hint = "GHOST not ready."
        updateButtons()
    }

    private fun updateButtons() {
        btnStart.isEnabled = (modelPath != null) && !GhostService.isRunning
        btnStop.isEnabled = GhostService.isRunning
        // On-demand camera capture (D2): available while the daemon is up + CAMERA is granted.
        btnCapture.isEnabled = GhostService.isRunning && VisionTrigger.hasCameraPermission(this)
        // Mic toggle: only meaningful while the daemon is up (it owns the listener).
        btnMic.isEnabled = GhostService.isRunning
    }

    private fun toggleMic() {
        lifecycleScope.launch(Dispatchers.IO) {
            val next = !GhostPrefs.isMicEnabled(applicationContext)
            GhostPrefs.setMicEnabled(applicationContext, next)
            withContext(Dispatchers.Main) { updateMicButton(next) }
            // Tell the running service to start/stop the recognizer.
            startServiceWithAction(GhostService.ACTION_TOGGLE_MIC)
        }
    }

    private fun updateMicButton(enabled: Boolean) {
        btnMic.text = if (enabled) getString(R.string.ghost_mic_on) else getString(R.string.ghost_mic_off)
    }

    private fun startGhost() {
        if (modelPath == null) {
            Toast.makeText(this, "Pick a model first", Toast.LENGTH_SHORT).show()
            return
        }
        // Gate on perception permissions first. Fine location is required (location FGS type +
        // GPS); activity recognition is optional but requested together.
        val missing = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) missing += Manifest.permission.ACCESS_FINE_LOCATION
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION)
            != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) missing += Manifest.permission.ACTIVITY_RECOGNITION
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) missing += Manifest.permission.CAMERA
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) missing += Manifest.permission.RECORD_AUDIO
        if (missing.isNotEmpty()) {
            requestPerceptionPerms.launch(missing.toTypedArray())
            return
        }
        startGhostService()
    }

    /** Actually start the foreground service (called once permissions are in place). */
    private fun startGhostService() {
        lifecycleScope.launch {
            GhostPrefs.setEnabled(applicationContext, true)
            val svc = Intent(applicationContext, GhostService::class.java)
            startServiceWithIntent(svc)
            updateButtons()
        }
    }

    /** Start the service in the O+/pre-O correct mode for the given explicit intent. */
    private fun startServiceWithIntent(svc: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(svc)
        } else {
            startService(svc)
        }
    }

    /** Convenience: start the foreground service with a specific action (Phase 6.3 reload). */
    private fun startServiceWithAction(action: String) {
        startServiceWithIntent(Intent(applicationContext, GhostService::class.java).setAction(action))
    }

    /**
     * Tell a running service to restart perception with the now-granted permissions and re-run
     * startForegroundCompat (adds the location FGS type). No-op if the service isn't up.
     */
    private fun refreshPerception() {
        if (!GhostService.isRunning) return
        val svc = Intent(applicationContext, GhostService::class.java)
            .setAction(GhostService.ACTION_REFRESH_PERCEPTION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(svc)
        else startService(svc)
    }

    private fun stopGhost() {
        lifecycleScope.launch {
            GhostPrefs.setEnabled(applicationContext, false)
            stopService(Intent(applicationContext, GhostService::class.java))
            updateButtons()
        }
    }

    private fun handleSelectedModel(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            val name = queryDisplayName(uri) ?: "ghost-model.gguf"
            val dest = File(ensureModelsDirectory(), name)
            if (!dest.exists()) {
                withContext(Dispatchers.Main) { statusTv.text = "Copying model $name..." }
                Log.i(TAG, "Copying model to ${dest.path}")
                contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(dest).use { input.copyTo(it) }
                }
            }
            GhostPrefs.setModelPath(applicationContext, dest.path)
            withContext(Dispatchers.Main) {
                modelPath = dest.path
                // Auto-start the daemon once a model is staged.
                startGhost()
            }
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) return c.getString(idx)
        }
        return null
    }

    private fun handleUserInput() {
        userInputEt.text.toString().also { userMsg ->
            if (userMsg.isEmpty()) {
                Toast.makeText(this, "Input message is empty!", Toast.LENGTH_SHORT).show()
                return
            }
            // EngineGate: the engine is single-threaded; a voice command may already be generating
            // (driven by GhostService). Reject a typed send in that case rather than racing it.
            if (!EngineGate.tryAcquire()) {
                Toast.makeText(this, "GHOST is responding — wait a moment", Toast.LENGTH_SHORT).show()
                return
            }
            userInputEt.text = null
            userInputEt.isEnabled = false
            sendFab.isEnabled = false

            messages.add(Message(UUID.randomUUID().toString(), userMsg, true))
            lastAssistantMsg.clear()
            messages.add(Message(UUID.randomUUID().toString(), lastAssistantMsg.toString(), false))
            // Both rows must be announced. Adding two items and then only calling notifyItemChanged
            // for the last one leaves RecyclerView's cached count behind getItemCount(), which is
            // the "Inconsistency detected" crash — the voice mirror below already does this right.
            messageAdapter.notifyItemRangeInserted(messages.size - 2, 2)

            // Context injection (Phase 4 sensors + Phase 5 mic/camera): the only per-query context
            // channel is the sendUserPrompt string (setSystemPrompt is once-only). We prepend a
            // compact [CONTEXT ...] block; the UI still shows the clean user text above.
            generationJob = lifecycleScope.launch(Dispatchers.Default) {
                var finalReply: String? = null
                try {
                    // On-demand camera (D2): if the query seems to need vision, grab a fresh frame
                    // first so its metadata rides the [CONTEXT] block. The capture is async on the
                    // camera thread; wait briefly for it to land in ContextStore.
                    if (GhostService.isRunning && VisionTrigger.hasCameraPermission(this@MainActivity) &&
                        VisionTrigger.wantsVision(userMsg)
                    ) {
                        sendCaptureFrame(CameraCapture.FACING_BACK)
                        delay(VisionTrigger.CAPTURE_WAIT_MS)
                    }
                    val cs = ContextStore.state.value
                    // Phase 6: recall semantically similar past turns and prepend a [MEMORY] block.
                    // recall runs off llamaDispatcher (TFLite + Room), engine idle. Assembly is
                    // shared with the voice path so the two cannot drift apart again.
                    val memBlock = memoryStore.recall(userMsg).toMemoryBlock()
                    val prompt = GhostPrompts.buildUserPrompt(cs, memBlock, userMsg)

                    cloudRouter.route(prompt, GhostService.currentSystemPrompt, predictLength = 512)
                        .collect { token ->
                            withContext(Dispatchers.Main) {
                                val n = messages.size
                                check(n > 0 && !messages[n - 1].isUser)
                                messages.removeAt(n - 1).copy(
                                    content = lastAssistantMsg.append(token).toString()
                                ).let { messages.add(it) }
                                messageAdapter.notifyItemChanged(messages.size - 1)
                            }
                        }

                    // After the reply completes (engine idle): parse any actions, execute them, strip
                    // the deprecated "Ghost:" prefix, then persist the final reply for future recall.
                    val rawReply = lastAssistantMsg.toString()
                    val reply = stripGhostPrefix(ActionRunner.run(actionExecutor, rawReply))
                    finalReply = reply

                    // Update the assistant bubble if action results changed the text.
                    if (reply != rawReply) {
                        withContext(Dispatchers.Main) {
                            lastAssistantMsg.clear()
                            lastAssistantMsg.append(reply)
                            val n = messages.size
                            check(n > 0 && !messages[n - 1].isUser)
                            messages.removeAt(n - 1).copy(content = reply)
                                .let { messages.add(it) }
                            messageAdapter.notifyItemChanged(messages.size - 1)
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // The engine throws at collection time when no model is loaded
                    // (check(state is ModelReady) in InferenceEngineImpl), and CloudRouter only
                    // falls back to it — it does not catch what the fallback itself throws. Uncaught
                    // here the exception escapes lifecycleScope and takes the Activity down
                    // mid-conversation, which is how a missing GGUF turned into a crash.
                    Log.w(TAG, "typed inference failed", e)
                    showGenerationError(e)
                } finally {
                    // onStop() cancels this job, and everything below the collect used to live in
                    // the cancellable body — so backgrounding the app mid-reply dropped the whole
                    // exchange from memory, and GHOST had no recollection of a conversation the
                    // user had just had. NonCancellable persists whatever was generated, partial
                    // reply included.
                    withContext(NonCancellable + Dispatchers.IO) {
                        val reply = finalReply ?: stripGhostPrefix(lastAssistantMsg.toString())
                        if (reply.isNotBlank()) {
                            runCatching {
                                memoryStore.remember("user", userMsg, "typed")
                                memoryStore.remember("assistant", reply, "typed")
                                memoryStore.prune()
                            }.onFailure { Log.w(TAG, "failed to persist typed turn", it) }
                            // Phase 6.2: durable-fact extraction runs on its own process-surviving
                            // scope and respects EngineGate, so it can't race the next query.
                            factExtractor.extract(userMsg, reply, "typed")
                        }
                    }
                }
            }
            // Fires even if the job is cancelled before its body starts (Activity stopped between
            // the acquire above and the first dispatch), so the gate cannot leak and wedge the
            // engine for the life of the process.
            generationJob?.invokeOnCompletion {
                EngineGate.release()
                runOnUiThread {
                    userInputEt.isEnabled = isModelReady
                    sendFab.isEnabled = isModelReady
                }
            }
        }
    }

    /**
     * Surface a failed generation in the assistant bubble instead of letting it crash the Activity.
     * Uses a guard rather than `check` so a failure while the bubble list is in an unexpected shape
     * can't throw a second time out of the error handler.
     */
    private suspend fun showGenerationError(e: Exception) {
        val text = "[GHOST couldn't answer: ${e.message ?: e.javaClass.simpleName}]"
        withContext(Dispatchers.Main) {
            lastAssistantMsg.clear()
            lastAssistantMsg.append(text)
            val n = messages.size
            if (n > 0 && !messages[n - 1].isUser) {
                messages.removeAt(n - 1).copy(content = text).let { messages.add(it) }
                messageAdapter.notifyItemChanged(messages.size - 1)
            }
        }
    }


    private fun ensureModelsDirectory() =
        File(filesDir, DIRECTORY_MODELS).also {
            if (it.exists() && !it.isDirectory) it.delete()
            if (!it.exists()) it.mkdir()
        }

    /** Trigger an on-demand camera capture (Phase 5, D2). Fire-and-forget; metadata lands async. */
    private fun captureFrame() {
        sendCaptureFrame(CameraCapture.FACING_BACK)
        Toast.makeText(this, "Capturing frame…", Toast.LENGTH_SHORT).show()
    }

    private fun sendCaptureFrame(facing: String) {
        if (!GhostService.isRunning) return
        val svc = Intent(applicationContext, GhostService::class.java)
            .setAction(GhostService.ACTION_CAPTURE_FRAME)
            .putExtra(GhostService.EXTRA_FACING, facing)
        // The service is already foreground while the Activity is open → plain startService.
        runCatching { startService(svc) }
    }

    /** Whether the hardware volume-key kill-switch accessibility service is enabled. */
    private fun isKillAccessibilityEnabled(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        val flat = "$packageName/${GhostAccessibilityService::class.java.name}"
        return enabled.split(':').any { it.equals(flat, ignoreCase = true) }
    }

    /**
     * Phase 6.3: open the trait editor. 8 sliders (0-100), hard-floor clamping via
     * PersonalityTranslator, snapshot + rollback versioning, and a broadcast that tells
     * GhostService to reload the model so the new prompt block takes effect.
     */
    private fun showPersonalityDialog() =
        PersonalityDialog(this, lifecycleScope, personalityRepo, ::startServiceWithAction).show()

    private fun showCloudDialog() = CloudDialog(this, lifecycleScope).show()

    override fun onStop() {
        generationJob?.cancel()
        super.onStop()
    }

    // NOTE: deliberately NOT calling engine.destroy() here — the Service owns the engine lifecycle.

    companion object {
        private val TAG = MainActivity::class.java.simpleName
        private const val DIRECTORY_MODELS = "models"

        private fun Boolean.toRunLabel() = if (this) "running" else "stopped"
    }
}