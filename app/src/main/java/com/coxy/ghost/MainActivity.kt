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
import com.coxy.ghost.action.ActionCatalog
import com.coxy.ghost.action.ActionExecutor
import com.coxy.ghost.action.ActionParser
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
import com.coxy.ghost.personality.PersonalityProfile
import com.coxy.ghost.personality.PersonalityRepository
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
        btnCapture.isEnabled = GhostService.isRunning && hasCamera()
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
                    if (GhostService.isRunning && hasCamera() &&
                        VISION_KEYWORDS.any { userMsg.contains(it, true) }
                    ) {
                        sendCaptureFrame(CameraCapture.FACING_BACK)
                        delay(VISION_CAPTURE_WAIT_MS)
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
                    val reply = stripGhostPrefix(runActions(rawReply))
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

    /** Phase 7: parse actions from the model reply, execute immediate ones, confirm Tier 3. */
    private suspend fun runActions(reply: String): String {
        val parsed = ActionParser.extract(reply)
        if (parsed.actions.isEmpty() && parsed.errors.isEmpty()) return reply

        val batch = actionExecutor.runImmediate(parsed.actions)
        if (batch.pending.isNotEmpty()) {
            actionExecutor.requestConfirmation(batch.pending)
        }

        val resultLines = buildList {
            addAll(parsed.errors.map { "[ACTION fail parser: $it]" })
            addAll(batch.results.map { it.toReplyLine() })
            if (batch.pending.isNotEmpty()) {
                add("[ACTION pending ${batch.pending.joinToString { ActionCatalog.describe(it.id) }}: requires confirmation]")
            }
        }

        val stripped = parsed.cleanedReply.trim()
        return if (resultLines.isEmpty()) stripped
               else stripped + "\n" + resultLines.joinToString("\n")
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

    private fun hasCamera(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

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
    private fun showPersonalityDialog() {
        lifecycleScope.launch(Dispatchers.IO) {
            val profileRef = personalityRepo.activeProfile()
            withContext(Dispatchers.Main) { buildPersonalityDialog(profileRef) }
        }
    }

    private fun buildPersonalityDialog(profileRef: PersonalityProfile) {
        val container = LayoutInflater.from(this)
            .inflate(R.layout.dialog_personality, null) as ScrollView
        val sliderContainer = container.findViewById<LinearLayout>(R.id.slider_container)
        val btnSnapshot = container.findViewById<MaterialButton>(R.id.btn_snapshot)
        val btnRollback = container.findViewById<MaterialButton>(R.id.btn_rollback)

        // Trait definition: display label + getter/setter against the mutable copy we load.
        data class TraitDef(
            val label: String,
            val minBudget: Int,
            val maxBudget: Int,
            val get: () -> Int,
            val set: (Int) -> Unit
        )

        var mutableProfile = profileRef.copy()

        val traits = listOf(
            TraitDef("Humor", profileRef.humorMin, profileRef.humorMax, { mutableProfile.humor }, { mutableProfile = mutableProfile.copy(humor = it) }),
            TraitDef("Sarcasm", profileRef.sarcasmMin, profileRef.sarcasmMax, { mutableProfile.sarcasm }, { mutableProfile = mutableProfile.copy(sarcasm = it) }),
            TraitDef("Bluntness", profileRef.bluntnessMin, profileRef.bluntnessMax, { mutableProfile.bluntness }, { mutableProfile = mutableProfile.copy(bluntness = it) }),
            TraitDef("Warmth", profileRef.warmthMin, profileRef.warmthMax, { mutableProfile.warmth }, { mutableProfile = mutableProfile.copy(warmth = it) }),
            TraitDef("Formality", profileRef.formalityMin, profileRef.formalityMax, { mutableProfile.formality }, { mutableProfile = mutableProfile.copy(formality = it) }),
            TraitDef("Verbosity", profileRef.verbosityMin, profileRef.verbosityMax, { mutableProfile.verbosity }, { mutableProfile = mutableProfile.copy(verbosity = it) }),
            TraitDef("Curiosity", profileRef.curiosityMin, profileRef.curiosityMax, { mutableProfile.curiosity }, { mutableProfile = mutableProfile.copy(curiosity = it) }),
            TraitDef("Initiative", profileRef.initiativeMin, profileRef.initiativeMax, { mutableProfile.initiative }, { mutableProfile = mutableProfile.copy(initiative = it) })
        )

        // Per-trait slider rows: label | SeekBar 0..100 | value label.
        traits.forEach { def ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 8, 0, 8)
            }
            val label = TextView(this).apply {
                text = def.label
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val seekBar = SeekBar(this).apply {
                max = 100
                progress = def.get()
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2f)
            }
            val valueLabel = TextView(this).apply {
                text = def.get().toString()
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.5f)
                textAlignment = TextView.TEXT_ALIGNMENT_TEXT_END
            }
            seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    val clamped = progress.coerceIn(def.minBudget, def.maxBudget)
                    def.set(clamped)
                    if (progress != clamped) seekBar?.progress = clamped
                    valueLabel.text = clamped.toString()
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
            row.addView(label)
            row.addView(seekBar)
            row.addView(valueLabel)
            sliderContainer.addView(row, sliderContainer.childCount - 2)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.ghost_personality_title))
            .setView(container)
            .setPositiveButton(R.string.ghost_personality_save, null)
            .setNegativeButton(R.string.ghost_personality_reset, null)
            .setNeutralButton(R.string.ghost_personality_snapshots, null)
            .create()

        // Helper: refresh all sliders from mutableProfile.
        fun refreshSliders() {
            traits.forEachIndexed { idx, def ->
                val row = sliderContainer.getChildAt(idx) as LinearLayout
                val seekBar = row.getChildAt(1) as SeekBar
                val valueLabel = row.getChildAt(2) as TextView
                seekBar.progress = def.get()
                valueLabel.text = def.get().toString()
            }
        }

        // Helper: pick a snapshot to roll back to and trigger service reload.
        fun showRollbackPicker(snapshots: List<PersonalitySnapshot>) {
            val names = snapshots.map { it.reason }
            if (names.isEmpty()) {
                Toast.makeText(this, "No snapshots yet", Toast.LENGTH_SHORT).show()
                return
            }
            AlertDialog.Builder(this)
                .setTitle(R.string.ghost_personality_rollback)
                .setItems(names.toTypedArray()) { _, which ->
                    lifecycleScope.launch(Dispatchers.IO) {
                        val restored = personalityRepo.rollback(snapshots[which])
                        withContext(Dispatchers.Main) {
                            mutableProfile = restored
                            refreshSliders()
                            Toast.makeText(this@MainActivity, "Rolled back to ${snapshots[which].reason}", Toast.LENGTH_SHORT).show()
                            startServiceWithAction(GhostService.ACTION_RELOAD_PERSONALITY)
                        }
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        dialog.setOnShowListener {
            // Save: persist clamped profile, then ask the service to reload.
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                lifecycleScope.launch(Dispatchers.IO) {
                    val clamped = PersonalityTranslator.clamp(mutableProfile)
                    personalityRepo.save(clamped)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, R.string.ghost_personality_applied, Toast.LENGTH_SHORT).show()
                        startServiceWithAction(GhostService.ACTION_RELOAD_PERSONALITY)
                        dialog.dismiss()
                    }
                }
            }
            // Reset to baseline defaults and persist/reload.
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                lifecycleScope.launch(Dispatchers.IO) {
                    val reset = personalityRepo.resetToBaseline()
                    withContext(Dispatchers.Main) {
                        mutableProfile = reset
                        refreshSliders()
                        Toast.makeText(this@MainActivity, R.string.ghost_personality_baseline, Toast.LENGTH_SHORT).show()
                        startServiceWithAction(GhostService.ACTION_RELOAD_PERSONALITY)
                    }
                }
            }
            // Neutral: snapshot/rollback chooser.
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                lifecycleScope.launch(Dispatchers.IO) {
                    val snapshots = personalityRepo.recentSnapshots(20)
                    val names = snapshots.map { it.reason } + "+ New snapshot"
                    withContext(Dispatchers.Main) {
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle(R.string.ghost_personality_snapshots)
                            .setItems(names.toTypedArray()) { _, which ->
                                if (which == names.lastIndex) {
                                    val name = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                                        .format(Date())
                                    lifecycleScope.launch(Dispatchers.IO) {
                                        personalityRepo.snapshot(name, mutableProfile)
                                        withContext(Dispatchers.Main) {
                                            Toast.makeText(this@MainActivity, "Snapshot saved: $name", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                } else {
                                    lifecycleScope.launch(Dispatchers.IO) {
                                        val restored = personalityRepo.rollback(snapshots[which])
                                        withContext(Dispatchers.Main) {
                                            mutableProfile = restored
                                            refreshSliders()
                                            Toast.makeText(this@MainActivity, "Rolled back to ${snapshots[which].reason}", Toast.LENGTH_SHORT).show()
                                            startServiceWithAction(GhostService.ACTION_RELOAD_PERSONALITY)
                                        }
                                    }
                                }
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                    }
                }
            }
            // Dedicated snapshot button inside the dialog also creates a new snapshot.
            btnSnapshot.setOnClickListener {
                val name = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                    .format(Date())
                lifecycleScope.launch(Dispatchers.IO) {
                    personalityRepo.snapshot(name, mutableProfile)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "Snapshot saved: $name", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            btnRollback.setOnClickListener {
                lifecycleScope.launch(Dispatchers.IO) {
                    val snapshots = personalityRepo.recentSnapshots(20)
                    withContext(Dispatchers.Main) { showRollbackPicker(snapshots) }
                }
            }
        }
        dialog.show()
    }

    /**
     * Phase 8: configure the Ollama Cloud brain — API key, on/off toggle, privacy filter, and
     * distillation logging toggle.
     */
    private fun showCloudDialog() {
        lifecycleScope.launch(Dispatchers.IO) {
            val settings = GhostPrefs.cloudSettings(applicationContext)
            val currentKey = CloudKeyStorage.getKey(applicationContext) ?: ""
            withContext(Dispatchers.Main) { buildCloudDialog(currentKey, settings) }
        }
    }

    private fun buildCloudDialog(currentKey: String, settings: com.coxy.ghost.cloud.CloudSettings) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 16, 32, 16)
        }

        val keyInput = EditText(this).apply {
            hint = getString(R.string.ghost_cloud_key_hint)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(currentKey)
        }
        container.addView(keyInput)

        val enabledSwitch = android.widget.Switch(this).apply {
            text = getString(R.string.ghost_cloud_enabled)
            isChecked = settings.enabled
        }
        container.addView(enabledSwitch)

        val loggingSwitch = android.widget.Switch(this).apply {
            text = getString(R.string.ghost_cloud_logging)
            isChecked = settings.loggingEnabled
        }
        container.addView(loggingSwitch)

        val modes = com.coxy.ghost.cloud.CloudPromptFilter.Mode.entries.map { it.name.lowercase() }
        val modeLabel = TextView(this).apply {
            text = getString(R.string.ghost_cloud_privacy)
            setPadding(0, 16, 0, 8)
        }
        container.addView(modeLabel)
        val modeSpinner = android.widget.Spinner(this).apply {
            adapter = android.widget.ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                modes
            )
            setSelection(modes.indexOf(settings.privacyMode.name.lowercase()).coerceAtLeast(0))
        }
        container.addView(modeSpinner)

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.ghost_cloud_title))
            .setView(container)
            .setPositiveButton(R.string.ghost_cloud_save) { _, _ ->
                val newKey = keyInput.text.toString().trim()
                val newMode = com.coxy.ghost.cloud.CloudPromptFilter.Mode.entries[modeSpinner.selectedItemPosition]
                lifecycleScope.launch(Dispatchers.IO) {
                    CloudKeyStorage.setKey(applicationContext, newKey)
                    GhostPrefs.setCloudEnabled(applicationContext, enabledSwitch.isChecked)
                    GhostPrefs.setCloudLoggingEnabled(applicationContext, loggingSwitch.isChecked)
                    GhostPrefs.setCloudPrivacyMode(applicationContext, newMode)
                    withContext(Dispatchers.Main) {
                        val status = if (enabledSwitch.isChecked && newKey.isNotBlank()) "enabled"
                                     else if (enabledSwitch.isChecked) "enabled (key missing)"
                                     else "disabled"
                        Toast.makeText(this@MainActivity, "Cloud brain $status", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onStop() {
        generationJob?.cancel()
        super.onStop()
    }

    // NOTE: deliberately NOT calling engine.destroy() here — the Service owns the engine lifecycle.

    companion object {
        private val TAG = MainActivity::class.java.simpleName
        private const val DIRECTORY_MODELS = "models"

        // Vision-related queries → trigger an on-demand frame capture so the metadata rides the
        // [CONTEXT] block. Kept broad on purpose (D2 vision auto-capture).
        private val VISION_KEYWORDS = listOf("see", "look", "camera", "frame", "in front", "what's there")
        private const val VISION_CAPTURE_WAIT_MS = 1500L

        private fun Boolean.toRunLabel() = if (this) "running" else "stopped"
    }
}