# Third-party components

GHOST's own source is MIT (see `LICENSE`). It does not ship anybody else's code in this
repository — every component below is either a pinned submodule you check out yourself, or an
artefact a script downloads at build time. That distinction is the whole point of this file: the
repository is MIT-clean, and a **binary you build from it is not necessarily MIT-clean**.

## Read this before you distribute an APK

`libsherpa-onnx-jni.so`, the native library that makes the Piper voice work, has **espeak-ng
compiled into it**. espeak-ng is **GPL-3.0**. Confirmed by inspection: the symbols and strings are
present in the shipped `.so`.

That means:

- **Building and running GHOST yourself: nothing to think about.** You are not distributing.
- **Handing someone an APK built with the Piper voice: GPL-3.0 obligations attach to that APK.**
  Not to this repository, and not to GHOST's source — to the binary, because of what is linked into
  it.
- **Want to distribute a permissively licensed build?** Leave the Piper voice out. Everything else
  here is MIT or Apache-2.0, and GHOST falls back to Android's own speech engine when no Piper
  voice is installed — `PiperVoice.isInstalled()` is checked at runtime, so a build without it
  works.

I am not a lawyer and this is not legal advice. It is a pointer at the one thing in this project
that is easy to get wrong, from someone who went and looked.

## Components

| Component | Licence | How it gets here |
|---|---|---|
| [llama.cpp](https://github.com/ggml-org/llama.cpp) | MIT | `vendor/llama.cpp`, pinned submodule |
| [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) | Apache-2.0 | AAR downloaded by `tools/install_piper_voice.sh` |
| espeak-ng | **GPL-3.0** | compiled inside the sherpa-onnx native library — see above |
| [ONNX Runtime](https://github.com/microsoft/onnxruntime) | MIT | inside the sherpa-onnx AAR |
| Piper voice model (`en_GB-southern_english_female`) | check upstream | downloaded by `tools/install_piper_voice.sh` |
| [all-MiniLM-L6-v2](https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2) (TFLite) | Apache-2.0 | `app/src/main/assets/embedder.tflite` |
| Qwen2.5-1.5B-Instruct (GGUF) | Apache-2.0 | you supply the weights; nothing is bundled |
| TensorFlow Lite, AndroidX, Material Components | Apache-2.0 | Gradle dependencies |

Piper's voice models do not all carry the same licence — they are trained on different corpora, and
some datasets are more restrictive than Piper itself. If you intend to distribute anything
containing one, check the model card for the specific voice rather than trusting this table.

## Origin

GHOST began as llama.cpp's `examples/llama.android` sample and still contains its descendants: the
`:lib` module's JNI bridge and the `com.arm.aichat` package names are from that sample. llama.cpp is
MIT, this project is MIT, and the copyright notice in `LICENSE` does not displace theirs —
`vendor/llama.cpp/LICENSE` applies to that code wherever it appears.
