# Ghost in the Nocturne island

The island renders Ghost's actual microphone, generation and playback lifecycle. Long-press the
collapsed island for one request; Cancel discards capture or cancels generation and speech. An
incoming or outgoing call takes priority. Locked-screen rendering hides conversation text.
Reduced-motion settings stop continuous ribbon and perimeter motion.

## IPC v1 (Android 14+)

Ghost sends `com.coxy.ghost.ipc.STATE` explicitly to `com.coxy.nocturne.ui`.
Nocturne sends `com.coxy.ghost.ipc.CONTROL` explicitly to
`com.coxy.ghost/.ipc.GhostControlReceiver`.
Both use `BroadcastOptions.setShareIdentityEnabled(true)`. Receivers verify Android's sender
package and its installed UID. Unknown senders and older platforms are rejected; identity in an
extra is never accepted. The apps do not need the same signing certificate.

| State extra | Type | Meaning |
|---|---|---|
| protocol_version | int | 1 |
| service_instance_id | string | UUID per service lifetime |
| session_id | string | UUID per voice interaction; retained after completion |
| request_id | string | Echo of the controlling START; empty for wake-word sessions |
| seq | long | Increasing snapshot sequence per service lifetime |
| state | string | OFF, ARMED, LISTENING, THINKING, SPEAKING, ERROR |
| surface | string | island or overlay; may recover once from island to overlay |
| mic_mode | string | OFF, PUSH_TO_TALK, OPEN, WAKE_WORD |
| audio_level | float | 0–1 recognizer envelope or spoken-word pulse, not calibrated dB |
| transcript / response | string | Bounded to 1000 / 4000 characters |
| error_code / error_message | string | Bounded failure details |
| updated_elapsed_ms | long | Android elapsed realtime |

Controls have protocol_version, command (START/CANCEL/QUERY), request_id and session_id.
Nocturne also advertises `surface_enabled` on each control and queries every five seconds. Ghost
accepts that advertisement only after sender authentication; it expires after 15 seconds and is
cleared across service restarts. Closing Nocturne explicitly revokes its advertisement.
Assistant gestures, the Quick Settings tile, notification Summon, accessibility volume-up and wake
all share one guarded capture path. A fresh enabled advertisement selects the island; otherwise
Ghost uses its summon overlay. Packets identify the chosen surface, so a late advertisement cannot
open a second voice UI. Fallback text is redacted while locked, exposes capture errors while
unlocked, and its Tap to speak control uses an accessible click action. Calls dismiss it immediately.
If Nocturne dies or revokes its advertisement during an active interaction, Ghost checks once per
second and transfers that same session to its fallback overlay after lease loss. Session/request
IDs, transcript, generation and speech remain intact; no second capture starts. The transfer is
one-way for that interaction, so a returning Nocturne removes its card on `surface=overlay` and
cannot duplicate the UI. If the fallback cannot attach, Ghost cancels the interaction. Device
validation must still cover surface loss while listening, thinking, speaking and locked.
START is a single automatic-endpoint capture, never a persistent open-mic latch. CANCEL targets
the current session, or the matching nonempty request_id when START is still pending. Nocturne
keeps cancellation pending until Ghost reports idle/error. Unrelated snapshots cannot acknowledge
a pending start. QUERY never starts a stopped service or microphone.

Snapshots heartbeat every five seconds, with faster audio updates while active. Packets older than
15 seconds, duplicate/reversed sequences and future timestamps are rejected. A five-second
watchdog displays disconnected after roughly 15–20 seconds without a fresh snapshot and queries
again immediately. Service restarts never inherit another session's completion.

## Wake word

Ghost has an explicit Hey Ghost switch in its System settings (Hey Ghost · wake word).
The default is off. The old saturated TFLite classifier stays unused. The replacement is the
[sherpa-onnx English streaming keyword model](https://k2-fsa.github.io/sherpa/onnx/kws/pretrained_models/index.html),
Zipformer GigaSpeech 3.3M, int8, with a single BPE-tokenized HEY GHOST keyword. Raw ambient audio
exists only in the local keyword stream; it is not sent to ASR, cloud, logs or memory. Capture
hands off to the existing on-device speech recognizer after detection. For this implementation,
say **Hey Ghost**, wait for the island to show **Listening**, then speak. There is no PCM/preroll
handoff to SpeechRecognizer yet: words immediately following the keyword can be lost. A combined
synthetic keyword fixture passing does not prove that its following command was transcribed. Keyword acceptance is
suppressed during calls, own speech, an active interaction and pocket detection.

A successful synthetic smoke test is not a reliability claim. Validate held-out user speech,
distance, accents, noise, screen-off, own TTS, fast wake-plus-command, cancellation/rearm and at
least 30 minutes of varied negative audio on the actual phone before enabling it routinely.
Measure false activations per hour and detection latency; longer field testing is still needed.

## Validation and deployment

`python3 -m unittest discover -s tests -v` covers ordering/restarts/staleness and existing island,
privacy, heartbeat, daemon and network regressions. `tests/build-check.py` compiles all five APKs.
Ghost's separate Gradle project has its own unit tests and native build.

The phone's existing Ghost is v65/0.31.0, while this checkout began at v64/0.30.0. Its signing
certificate SHA-256 is `4af1d61015c6a8d9a4a7fa20fc9ce5aa7327d9f05575e47635e3a3bd8c7fe023`.
The original APK is backed up locally. The supplied `ghost.zip` contains the byte-identical v65 APK
and its source. Its assistant/summon additions have been reconciled into this checkout, retaining
the current native source layout and the new keyword detector. The archived old detector's
`wake_word_enabled` flag is deliberately not migrated; explicit opt-in enables the new detector.
No matching signing keystore was found in the archive. Obtain the original machine's
`~/.android/debug.keystore` (or its exact backup) before updating; do not uninstall or replace the system mount to bypass signature checks.

### Results from 2026-09-12

- Nocturne: 13 regression tests pass, including packet ordering, service/session restart,
  stale-state handling and cancellation before START acknowledgment. All five modules compile
  resources, Java and DEX. UI 3.8 (14) and SystemUI hook 2.0 (11) installed with matching existing
  certificates; previous APKs backed up. SystemUI restarted and full-pill long press verified.
- Ghost: v0.32.0 (66) native APK builds; 327 unit tests pass. One existing case-normalization test
  compared different MAC addresses; its uppercase fixture now comes from the same source value.
  **Ghost is not installed:** the original installed v65 signing key is still required.
- Keyword smoke test, same five synthetic Android TTS fixtures on host and phone: both Hey Ghost
  positives trigger once; ordinary speech, Hey Google, and a sentence mentioning a ghost do not.
  Android native processing is 237–518 ms for 2.27–4.41 seconds of audio. Cold model load is about
  4.5 seconds; production retains the model across rearming. The old one-second positive fixture
  does not trigger this model. These results do not establish real acoustic/wake-word reliability.
- Renderer inspected on the real phone; the [island preview](ghost-island-preview.png) shows the
  actual awaiting-connection card. Continuous animation follows display vsync and stops off-screen
  or with reduced motion. Frame timings are only a smoke measurement; validate again with actual
  inference and playback active.
- Agentbus listener fixed to close its per-poll SQLite connection. A 300-poll regression test
  holds the descriptor count stable; automatic delivery resumed after restarting its user service.

Remaining device validation: signing-compatible Ghost update, microphone handoff, real spoken
Hey Ghost at varied distances/noise, fast wake-plus-command, ASR partials, generation cancellation,
Piper cancellation/drain, calls, screen-off/rearm and lock-screen redaction. Wake listening defaults
OFF and should stay off until these checks pass. Do not present synthetic fixtures as field results.


## Local release packaging and update checks

`python3 tools/package_release.py --create-key` builds the optimized release, creates a private
persistent key under `~/.local/share/ghost/signing` if absent, aligns the APK, signs it, and verifies
its signature and ZIP alignment. Subsequent runs reuse that identity. Back up the complete private
key directory securely; never commit it. This is a new signing identity, not a reconstruction of
the missing v65 key. JNI keep rules retain sherpa model/config classes and the boxed PCM callback
in optimized builds.

Verified artifacts, public certificate fingerprint and checksums are written under
`app/build/outputs/verified-release`. This operation does not install anything.

Before a device update, run:

```sh
python3 tools/check_apk_update.py --apk /path/to/candidate.apk
# Read-only comparison against a saved installed APK instead:
python3 tools/check_apk_update.py --apk /path/to/candidate.apk --installed-apk /path/to/backup.apk
```

The check verifies both signatures and requires matching package/current signer certificates and
a nondecreasing version. A certificate mismatch fails; there is no bypass flag. Matching checks do
not prove runtime or data-migration compatibility. `tools/deploy_system_app.sh --check` performs
only this read-only preflight. All actual deploy modes must pass it before backups, pushes,
force-stop, module writes or reboot. `GHOST_APK` selects an explicit deployment artifact. The
installed v65 remains untouched until a compatible update or a separately reviewed migration is
available.


### Follow-up verification

The recovery build passed all 327 Ghost JVM tests, including one-way handoff, lease expiry and
revocation, stale callbacks and peer return. Six Python deployment checks pass, including a test
that every deployment mode stops before any device call when preflight fails. The real installed
v65 APK passes self-comparison; the new debug APK is correctly rejected against the connected
phone's v65 certificate. No production app or device data was changed by these checks.


The optimized release also builds successfully and its signature and 16-KB ZIP alignment checks
pass. Packaging verifies retained JNI class names and the boxed `SamplesCallback.invoke(float[])`
method before signing. The artifact is
`app/build/outputs/verified-release/ghost-0.32.0-66.apk`, with adjacent public metadata and SHA-256
files. Its new signer certificate is
`70a84d21b8da7719c4e61f03ee1eaceeb309a4cab7206c44d283d54934ccbfcd`.
It does **not** match installed v65, so the update preflight correctly rejects an in-place update.
Inspection of 685 small historical blobs from the ZIP's two embedded Git repositories also found
no candidate original signing key. Signing tools never printed key/password material.

### Android 14 boot foreground access (v67)

The deployed v66 release exposed a BOOT_COMPLETED crash: granted RECORD_AUDIO did not imply while-in-use eligibility. Ghost now establishes specialUse first, then independently requests microphone, location and camera foreground types. SecurityException defers only the rejected type. Microphone and camera capture require a successful type promotion; opening the activity or another eligible service interaction retries deferred promotion without interrupting an active session. This follows Android's [while-in-use foreground service restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start).

Regression coverage includes boot rejection, later eligible promotion, independent type rejection, and failure of the base foreground service. Physical boot verification is coordinated by the Nocturne session.

Voice inference also has an independent UI deadline: 30 seconds without nonblank output, 90 seconds total. Timeout reports RESPONSE_TIMEOUT and requests cancellation on a sibling main-thread coroutine; it does not wait for native inference to cooperate. EngineGate remains held until the inference coroutine actually exits, and completed speech playback is not subject to this generation deadline. Session/job guards prevent a stale watchdog from cancelling a newer interaction. Existing archived personal cloud configuration was restored privately after the signing migration invalidated encrypted preferences.

Final v67 JVM verification: 335 tests, zero failures/errors/skips (including four foreground access and four response deadline regressions).

Final optimized v67 artifact: `app/build/outputs/verified-release/ghost-0.32.1-67.apk`, SHA-256 `96b692323e03117189ba000f15f9e8b2a53d9f5f4cc58f39a4177d4d7e9d3725`. Signature/JNI preservation/16K alignment verified; read-only compatibility check passes against new-identity v66. Handed to Nocturne for deployment and physical verification; Ghost session performed no device writes.

Physical reboot validation reported by Nocturne (agentbus #77): installed v67/0.32.1 started automatically from BOOT_COMPLETED as specialUse-only (`0x40000000`) after while-in-use promotion denial, with zero Ghost crash-buffer entries. A subsequent system-UID/root start promoted the same process to microphone/location/camera plus specialUse (`0x400000C8`). Voice retry remains pending.

### ART GC stall during native inference (v68)

Nocturne's v67 device stacks (agentbus #80/#82) showed AudioRecord initialization waiting for GC while HeapTaskDaemon waited at its MarkRoots barrier. The inference wrapper annotated all ten JNI methods with FastNative, including model loading and prompt processing. These unbounded calls must use ordinary JNI transitions so ART can complete GC; see [Android JNI guidance](https://developer.android.com/ndk/guides/jni-tips#fast_critical_native). v68 removes those annotations without changing native signatures or model-loading policy. Physical confirmation is pending the rebuilt APK.

Final v68 verification: 335 JVM tests pass with zero failures/errors/skips. Both debug and release compiled wrappers retain ten native methods with no FastNative annotations. Optimized release/lint/signature/16K alignment/JNI preservation checks pass. All 20 native libraries are byte-identical to v67. Signed APK `app/build/outputs/verified-release/ghost-0.32.2-68.apk`, SHA-256 `b66b062292a51566c33c4d1375903f3050bad99435d3ac190e0c24bc10bbabe4`; compatibility check passes against v67. Handed to Nocturne for physical validation.

Assistant-role migration finding (Nocturne #87–88): after reboot Android had restored Google as assistant/voice interaction service, and device audio diagnostics showed Google recording while Ghost was silenced. Nocturne corrected the ASSISTANT role through Android role management; the reported role holder became Ghost, assistant component Ghost/.AssistActivity, and voice_interaction_service cleared automatically. Persistence after the v68 reboot remains unverified. Follow-up: inspect AssistantRole raw-settings selection for consistency with RoleManager; no runtime change made in v68 for this separate finding.

Physical v68 cold-reboot verification reported by Nocturne (#91): Ghost retained the ASSISTANT role, assistant component Ghost/.AssistActivity, and an empty voice_interaction_service. AudioRecord started approximately 20 seconds after service startup and 10 seconds after foreground promotion while the local model was still warming, versus the previously observed minutes-long stall. No Ghost crash was observed. This supports the FastNative fix on this device; full live cloud/TTS response verification remains pending.

### Wake diagnostics (v69)

GhostKeyword emits aggregate windows approximately every five seconds of capture progress: wall duration, audio duration (16 kHz samples), read/sample/nonzero counts, RMS/peak in signed-16-bit amplitude units, samples fed, decode iterations, detection counts and per-reason suppressed samples. Reasons distinguish capture eligibility, busy inference/island, call, speech playback, active interaction, pocket and post-block warmup. Additional breadcrumbs show capture readiness and whether ready/keyword callbacks are accepted or stale. No PCM, recognized speech, or transcripts are retained by these diagnostics; only numeric aggregates and fixed reason labels are logged. Detector thresholds and native libraries are unchanged. Compare audioMs with windowMs for throughput; zero amplitude with successful reads distinguishes silence from nonzero input discarded by a gate. These diagnostics do not independently prove acoustic recognition quality.

Final v69 verification: 339 JVM tests pass, zero failures/errors/skips; release/lint/signature/16K alignment/JNI preservation pass. All 20 native libraries match v68 byte-for-byte and update preflight passes against v68. Artifact `app/build/outputs/verified-release/ghost-0.32.3-69.apk`, SHA-256 `4e7220b61b6a5cb974f758dd3da26ff67e742ab3bc60651863f43a1bfad8eeb2`. Handed to Nocturne for device diagnostics.

Initial v69 device diagnostics (Nocturne #100): recording started about five seconds after root startup. Subsequent five-second windows processed about five seconds of audio, fed 80,000 samples, reported 15–16 decode iterations and no suppression after warmup. Input was mostly nonzero (ambient RMS 48–189, peak 303–855); no keyword hit was observed in these windows. This rules out gate suppression and a sustained throughput backlog for the sampled intervals; user speech-correlated detection testing remains pending.

Successful live v69 trial reported by Nocturne (#101): user reported it appeared to work. Keyword detected at 15:49:08.011 with accepted callback (RMS 949, peak 14,014, one hit, no suppression); ASR dispatched the command at 15:49:14.479, cloud request began at 15:49:14.867, and idle wake rearmed at 15:49:25.667. Detector threshold remained unchanged. This verifies real acoustic wake, handoff, cloud routing and rearm for one trial; browser action outcome and TTS completion were not yet confirmed, and broad acoustic reliability remains unproven.
