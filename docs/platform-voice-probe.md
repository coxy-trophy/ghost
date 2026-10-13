# Platform voice feasibility probe

v70 adds an opt-in `GhostVoiceInteractionService` and `GhostVoiceSessionService` in the model-free `:voice` process. The interaction service is disabled in the manifest by default. Installing this build preserves the existing AssistActivity and software wake path. No platform detector is created or started, no enrollment changes are made, and no privacy indicator settings are changed.

When selected and ready, the service reports whether it is the active framework service, existing microphone/hotword permission grants, visible detector API entry points, and the result of a read-only `listModuleProperties` call. That API is a Test API absent from the installed public SDK; reflective access respects runtime enforcement and does not install hidden-API exemptions. Failure to access it is not evidence that hardware is absent.

The report is available in `GhostPlatformVoice` logcat, the service dump, and private `files/voice-platform-probe.json`. It contains capability metadata only. A positive module count does not prove a compatible enrolled Hey Ghost model. No result from this probe establishes indicator-free recognition or software detector eligibility. The current ONNX model cannot simply be enrolled as a vendor DSP model.

The session bridge forwards an actual framework summon to the existing GhostService interaction owner. It does not open a second microphone or draw a second overlay. Assist context/screenshot delivery is disabled. GhostApp skips app UI initialization in this process; no inference, Piper, or keyword model is initialized by these new components.

## Coordinated phone trial

Nocturne owns all device writes. Preserve the current role holder and both secure assistant settings first. Install the same-key build normally and update the existing module artifact as already agreed. Do not combine initial activation with unrelated permission or firmware changes.

Enable the opt-in component:

```sh
adb shell pm enable com.coxy.ghost/.platform.GhostVoiceInteractionService
```

Select Ghost's voice service using the system voice/assistant picker where supported. If the existing Ghost role holder does not reselect the new component, the device coordinator may explicitly select `com.coxy.ghost/com.coxy.ghost.platform.GhostVoiceInteractionService` through the approved system/root configuration path. Record exactly what was changed. Existing boot-module assistant settings can override this choice, so initial capability probing need not reboot.

Read-only checks:

```sh
adb shell cmd role get-role-holders --user 0 android.app.role.ASSISTANT
adb shell settings get secure assistant
adb shell settings get secure voice_interaction_service
adb shell dumpsys voiceinteraction
adb shell dumpsys soundtrigger_middleware
adb shell dumpsys activity service com.coxy.ghost/.platform.GhostVoiceInteractionService
adb shell su -c 'cat /data/user/0/com.coxy.ghost/files/voice-platform-probe.json'
```

Verify framework gesture forwarding, cancellation through the existing surface, the separate small process, and continued software wake behavior. The private report is a last-observed lifecycle snapshot; inspect `lifecycle` and `elapsed_ms` rather than treating a stale file as current evidence.

Rollback: restore the captured assistant/voice-interaction settings and Ghost role selection, then disable `com.coxy.ghost/.platform.GhostVoiceInteractionService`. The original AssistActivity and working wake implementation remain present. Do not uninstall or reset app data.

## Next decision

* `NO_DSP_MODULES`: this selected-service probe returned an empty module list. Investigate supported system software detection before proposing DSP work.
* `PROBE_BLOCKED`: resolve the specific platform API/permission limitation; no claim about underlying hardware follows.
* `MODULES_PRESENT_ENROLLMENT_UNVERIFIED`: investigate model architecture, supported phrase/locale enrollment, permissions and proper detector lifecycle. Do not start a second capture owner.
* `NOT_SELECTED` or `UNKNOWN`: validate selection/lifecycle before drawing conclusions.

Moving the current ONNX wake worker into a separate process and implementing a sandboxed system hotword detector remain follow-on work. This probe is the first bounded platform integration artifact, not completion of the entire listening roadmap.

Sources: [VoiceInteractionService lifecycle](https://developer.android.com/reference/android/service/voice/VoiceInteractionService), [Android 14 platform service APIs](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android14-release/core/java/android/service/voice/VoiceInteractionService.java), [AOSP service metadata sample](https://android.googlesource.com/platform/development/+/refs/heads/main/samples/VoiceInteractionService/res/xml/voice_interaction.xml).

Build verification: 343 JVM tests pass; optimized release, lint, signature, 16K alignment and JNI preservation checks pass. All 20 native libraries match v69; update preflight passes. Packaged manifest confirms disabled-by-default, permission-gated VIS in `:voice`. APK `app/build/outputs/verified-release/ghost-0.33.0-70.apk`, SHA-256 `72a0ea3982f33a41ea69cb0f9731cfce295e2785136d169fcf076762d55500d3`. Handed to Nocturne for device validation.

Device v70 evidence: selected :voice process measured about 6.6 MB PSS without models. Probe reported PROBE_BLOCKED/NoSuchMethodException while detector entry points were visible; upstream Android 14 marks module enumeration TestApi, so filtering is possible and hardware remains unknown. Runtime hotword permissions were ungranted. Framework summon forwarded but shell show acknowledgment timed out; v71 defers session finish until onShow returns so the framework can acknowledge before teardown.

## v72 targeted system software initialization

The ROM exposed System API detector entry points even though Test API module enumeration was filtered. v72 therefore adds a separate initialization-only software-detector probe, enabled only when both CAPTURE_AUDIO_HOTWORD and MANAGE_HOTWORD_DETECTION are granted to the selected voice service. It uses the visible `createHotwordDetector` System API, with an isolated `:hotword` HotwordDetectionService and a fifteen-second initialization deadline. No call to startRecognition is made; no model is loaded in that sandbox, and the working ONNX recorder stays the sole capture owner. `INITIALIZED_0` would prove only successful sandbox initialization, not keyword recognition, efficiency or indicator behavior.

The framework class definitions come from the AOSP 34 system SDK, verified against Git blob `278c25727c4e6d8ac47f3e9fdfd6e43d4272ea37`. Run `python3 tools/install_system_api_stubs.py` when building a fresh checkout. The helper generates only definitions missing from the public SDK; Gradle uses them compileOnly. Release packaging checks DEX class definitions against that exact stub set and refuses an APK that bundles one. No hidden-API exemptions or global privacy settings are changed.

Device coordinator: inspect and merge `docs/platform-hotword-privapp.xml` into the same partition's module permission directory before rebooting with the updated privileged APK. It grants only CAPTURE_AUDIO_HOTWORD to this package. MANAGE_HOTWORD_DETECTION was reported as internal|preinstalled on this ROM; verify its actual grant rather than treating it as a signature-only permission or assuming a privapp entry grants it. Preserve existing permission files and the prior module for rollback. If either grant is missing, the probe reports PERMISSION_REQUIRED and performs no creation attempt. ACCESS_VOICE_INTERACTION_SERVICE is not requested by Ghost; the coordinator's root shell uses it for shell diagnostics only.

v72 also stops tearing down a UI-less voice session from onShow. Android's reference implementation acknowledges UI-less show callbacks synchronously after onShow returns; the session now explicitly disables UI in onPrepareShow and finishes when the framework hides it. Actual callback behavior on this ROM still needs verification.

Final v72 build verification: 367 JVM tests and nine Python checks pass. Optimized release/lint/signature/16K alignment/JNI checks pass; DEX contains none of the compile-only platform definitions. All 20 native libraries match v71, and same-key update preflight passes. APK `app/build/outputs/verified-release/ghost-0.34.0-72.apk`, SHA-256 `e63c97e2a12dffbda642499b1d861bf487b78db9b99ad87a16084762ff512e0b`. Handed to Nocturne for coordinated installation/permission staging and physical validation.

## v73 initialization correction

Nocturne's v72 device check confirmed both hotword permission grants and framework show acknowledgment in under one second. Sandbox initialization failed because Android named the isolated process `com.coxy.ghost:hotword:com.coxy.ghost.platform.GhostHotwordDetectionService:hotword_detector_1`; the previous suffix check missed it and attempted app appearance preferences in the isolated context. The coordinator disabled VIS and restored AssistActivity while retaining working software wake.

v73 initializes appearance only in the exact main application process. Regression tests cover the observed sandbox name, a subsequent detector instance, and other secondary processes. The initialization probe now releases its detector on every callback result, including successful initialization, restart and error, as well as timeout. A completed probe ignores late callbacks and releases even a handle returned after synchronous completion. Physical sandbox initialization still requires the coordinator's retest; this change does not establish keyword recognition or indicator behavior.

Verified v73 artifact: `app/build/outputs/verified-release/ghost-0.34.1-73.apk`, SHA-256 `4e9bdd0cf3599ec4cca427dfa281222441b3f0400c56fe5e2776fa3a20d10921`. All 373 JVM tests and nine tooling tests pass, as do optimized release/lint/signature/16K alignment/JNI/platform-definition checks. All 20 native libraries are byte-identical to v72; same-key update preflight passes. Device validation is pending with Nocturne.
