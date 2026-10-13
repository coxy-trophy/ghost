# GHOST

**An ambient AI that lives on a rooted Android phone, as a privileged system app.**

> *"Ghost in the Shell"* — the phone is the shell. This is the ghost.

Not an app you open to ask something. A daemon that starts at boot, stays running, senses what the
phone senses, remembers across restarts, and can act — on the phone, on the network, and on other
devices. The model runs on the phone; a cloud brain is optional and off by default.

Built on a Realme C53 (RMX3760, Unisoc T612) — a £70 phone with 4 GB of RAM. That constraint shaped
almost every decision here, and it is the reason a lot of this is more interesting than it would be
on good hardware.

---

## What actually works

Everything below was verified on the device, not just written. Where something is built but has
never passed a real test, it says so — that distinction is kept honestly throughout `GHOST.md`,
which is the long-form engineering record and the more interesting document.

| | |
|---|---|
| **Runs as a system app** | `/system/priv-app` via a Magisk module, foreground service, survives reboot, starts before first unlock is possible and waits for it |
| **Local inference** | Qwen2.5-1.5B Q4_K_M through llama.cpp, ~5 tok/s, memory-bandwidth-bound |
| **Perception** | activity recognition, fused location, light, proximity, step count, continuous on-device speech, camera frames as metadata |
| **Memory** | Room + an on-device MiniLM embedder, semantic recall, importance-weighted retention |
| **Personality** | eight trait sliders with hard floors, a drift budget, snapshots and rollback |
| **Actions** | tiered by consequence; the top tier requires a notification confirmation |
| **The network** | mDNS + SSDP + ARP discovery, UPnP control, DIAL app launch, Wake-on-LAN, Android TV remote v2 (hand-rolled protobuf and pairing) |
| **Other phones** | an ADB wire-protocol client, so nothing needs installing on the far phone |
| **Voice** | wake word, Piper neural TTS on-device, streamed |

**Not proven:** the 24-hour survival run, movement-scenario perception, the ADB phone client against
a real second phone, iPhone control. All marked as such in `GHOST.md`. Nothing here is dressed up as
finished when it is not.

## This is a personal project, and root is the reason it works

GHOST holds standing permissions, runs unkillable, and acts without asking each time. That is a
reasonable design on **your own rooted phone** and an unreasonable one anywhere else. It is not
hardened against a hostile user, it has no multi-user story, and it assumes the person holding the
phone is the person it belongs to.

The network and device-control code talks to equipment on your own LAN. Point it at somebody
else's and that is on you.

## Building it

You need a rooted Android device, JDK 17, and the Android SDK (NDK 30, CMake 4.1.2).

```bash
git clone --recursive https://github.com/coxy-trophy/ghost.git
cd ghost

# JDK 17 specifically. Android Studio ships a newer JBR that AGP will not accept.
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME="$HOME/Library/Android/sdk"

# Required native speech library, even if you do not install a voice on the device.
# Requires zsh, curl and unzip. No connected phone is needed.
./tools/install_piper_voice.sh --aar-only

# Hash-verified compile-only platform definitions for the opt-in hotword probe.
python3 tools/install_system_api_stubs.py

./gradlew :app:assembleDebug
```

If you forgot `--recursive`, the CMake step will tell you so by name rather than failing somewhere
confusing: `git submodule update --init --recursive`.

**Then you need weights.** No local LLM weights are bundled. Put a Qwen2.5-1.5B-Instruct Q4_K_M GGUF on the
device and point GHOST at it from System → Model.

The speech AAR above is a required compile dependency. The voice model is optional.

**Optional, for the neural voice on the device:** `./tools/install_piper_voice.sh`. Read `THIRD_PARTY.md` first if
you plan to distribute a build — espeak-ng is compiled into that library and it is GPL-3.0.

**Optional, for the cloud brain:** copy `keys.properties.example` to `keys.properties` and add an
Ollama key. It is gitignored. Note that a key baked in this way is a string constant in the dex and
readable by anyone with the APK; a key entered in the UI is stored in `EncryptedSharedPreferences`
and is the better option if you did not build it yourself.

**Installing as a system app:** `./tools/deploy_system_app.sh`. Read it before you run it — it
replaces the APK inside a Magisk module and reboots the phone. It backs up app data first, because
it has eaten a memory database before.

## Layout

```
app/                 the application
lib/                 the JNI bridge to llama.cpp
vendor/llama.cpp/    pinned submodule
magisk-module/       the module that mounts GHOST into /system/priv-app
tools/               deploy, voice install, wake-word training and gates
GHOST.md             the engineering record — phases, gates, and what failed
```

`vendor/llama.cpp` is **pinned**, not tracked. The native build compiles llama.cpp from source with
specific flags, and upstream moves fast enough that "latest" is not a thing this can depend on.
Moving the pin is a deliberate act with a rebuild behind it.

## Why the code reads the way it does

Comments here explain *why*, and most of them exist because something failed first. A JNI callback
that had to be written in Java because Kotlin cannot emit the descriptor. A `<Button>` that Material
silently swaps for one that ignores your icons. A deploy that wipes app data if the phone has not
been unlocked since boot. Those notes are the actual value of reading this repository, and they are
kept in preference to tidier code that would lose them.

## Licence

MIT — see `LICENSE`. It began as llama.cpp's `examples/llama.android` sample, which is also MIT.

Third-party components and one real licensing trap are in `THIRD_PARTY.md`.


### Ghost in the Nocturne island

The new voice bridge drives Nocturne's listening, thinking and speaking animation. Hold the island
for one request; Cancel stops capture, generation and speech. In Ghost, System → **Hey Ghost · wake
word** controls opt-in local keyword listening. Say Hey Ghost, wait for Listening, then speak;
continuous wake-plus-command audio handoff is not implemented yet. The keyword model stays local and ambient audio is
not transcribed or saved. This replaces the disabled old classifier. A real acoustic wake, ASR, cloud request, app launch
and rearm have been observed; broad reliability and continuous-command handoff remain unverified. See [protocol, setup and validation status](docs/ghost-island-protocol.md).

The keyword model can be reproduced with `tools/install_keyword_model.sh`. The current source passes
367 JVM tests. The phone was migrated with explicit approval and a full backup to a persistent
personal signing identity; subsequent releases use normal same-key updates. The original v65 key
was not recovered. Preserve the signing directory and always run the deployment compatibility check.


The current development build also recovers an active conversation into Ghost's overlay if the
Nocturne surface disappears. The session continues without restarting the microphone or model.
`python3 tools/package_release.py --create-key` creates a persistent local signing identity and
builds a verified optimized APK; it does not replace the missing original signing key or install
anything. Deployment now requires a read-only signature/package/version preflight:
`./tools/deploy_system_app.sh --check`. See the protocol document for details.

Local personal-data tools and the platform feasibility work are documented in
[local notifications](docs/local-notifications.md), [contacts and capability onboarding](docs/local-contacts-and-capabilities.md),
and [the opt-in platform voice probe](docs/platform-voice-probe.md). Notification/contact bodies stay
out of the model and memory in these initial local viewers. The system hotword sandbox is an
initialization probe only; it does not establish indicator-free recognition.
