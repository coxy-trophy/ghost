#!/usr/bin/env python3
"""
Train a tiny TFLite wake-word spotter for "Hey Ghost".

Input  : 1-second mono 16-bit 16 kHz PCM waveform (16,000 samples, float32 normalized to [-1, 1])
Output : float32 logits [wake_word, unknown]

Data:
- Positives: synthetic "Hey Ghost" clips generated via macOS `say` + pitch/speed/noise augmentation.
- Negatives: TensorFlow `mini_speech_commands` dataset (~8 commands, silence/background noise).

The exported TFLite graph is end-to-end: waveform -> STFT spectrogram -> small CNN -> logits,
so Kotlin only has to feed a raw audio buffer.
"""

import os
import random
import shutil
import subprocess
import sys
import tempfile
import warnings
import wave
from pathlib import Path
from typing import Optional

import numpy as np
import tensorflow as tf
from tensorflow import keras
from tensorflow.keras import layers, models

warnings.filterwarnings("ignore")

SAMPLE_RATE = 16000
DURATION = 1.0
NUM_SAMPLES = int(SAMPLE_RATE * DURATION)
# TFLite's RFFT2D (used by the STFT op) requires both FFT length and the number of
# frames to be powers of two. Use a 256-point FFT and pad the 1-second window so the
# STFT produces 128 time frames (a power of two).
FRAME_LENGTH = 256
FRAME_STEP = 128
NFFT = FRAME_LENGTH
NUM_FRAMES_TARGET = 128
PAD_TO_SAMPLES = (NUM_FRAMES_TARGET - 1) * FRAME_STEP + FRAME_LENGTH

POSITIVE_LABEL = "hey_ghost"
NEGATIVE_LABEL = "unknown"

# Tunables
POSITIVE_COUNT = 1000  # use a subset of cached positives for a fast gate iteration
NEGATIVE_RATIO = 1.0  # negatives per positive — keep dataset small for fast iteration
VAL_SPLIT = 0.2
BATCH_SIZE = 256
EPOCHS = 12
PATIENCE = 3
LEARNING_RATE = 0.001

# Voice variants for macOS `say`. Cross-voice synthesis is a cheap form of data augmentation.
# These must exist on the host running the script (`say -v '?'`); multi-word names are quoted.
SAY_VOICES = [
    "Samantha", "Daniel", "Karen", "Moira", "Tessa",
    "Albert", "Kathy", "Ralph", "Fred", "Junior",
]
WAKE_PHRASES = ["Hey Ghost", "Hey, Ghost"]


def seed_all(seed=42):
    random.seed(seed)
    np.random.seed(seed)
    tf.random.set_seed(seed)


def ensure_dirs():
    script_dir = Path(__file__).parent
    assets_dir = script_dir.parent / "app" / "src" / "main" / "assets"
    test_assets_dir = script_dir.parent / "app" / "src" / "androidTest" / "assets"
    data_dir = script_dir / "kws_data"
    positives_dir = data_dir / "positives"
    assets_dir.mkdir(parents=True, exist_ok=True)
    test_assets_dir.mkdir(parents=True, exist_ok=True)
    data_dir.mkdir(parents=True, exist_ok=True)
    positives_dir.mkdir(parents=True, exist_ok=True)
    return assets_dir, test_assets_dir, data_dir, positives_dir


def download_mini_speech_commands(data_dir: Path):
    url = "http://storage.googleapis.com/download.tensorflow.org/data/mini_speech_commands.zip"
    zip_path = tf.keras.utils.get_file(
        origin=url,
        fname="mini_speech_commands.zip",
        cache_dir=str(data_dir),
        cache_subdir="",
        extract=True,
    )
    root = Path(zip_path).parent
    # The zip extracts into root/<name>/mini_speech_commands. Search one level deep.
    candidates = [p for p in root.iterdir() if p.is_dir()]
    for c in candidates:
        if any((c / d).is_dir() for d in ["yes", "no", "up", "down", "left", "right", "on", "off", "stop", "go"]):
            return c
        # Nested case: root/mini_speech_commands_extracted/mini_speech_commands
        nested = c / "mini_speech_commands"
        if nested.is_dir() and any((nested / d).is_dir() for d in ["yes", "no", "up", "down", "left", "right", "on", "off", "stop", "go"]):
            return nested
    raise RuntimeError("Could not locate extracted mini_speech_commands directory")


def resample(data: np.ndarray, orig_sr: int, target_sr: int = SAMPLE_RATE) -> np.ndarray:
    if orig_sr == target_sr:
        return data
    # Simple linear interpolation; good enough for synthetic/augmented training data.
    target_len = int(len(data) * target_sr / orig_sr)
    if target_len == 0:
        return np.zeros(NUM_SAMPLES, dtype=np.float32)
    old_x = np.linspace(0, len(data) - 1, len(data))
    new_x = np.linspace(0, len(data) - 1, target_len)
    return np.interp(new_x, old_x, data).astype(np.float32)


def load_wav(path: Path) -> np.ndarray:
    """Fast minimal WAV loader. All training/test files are 16-bit PCM, so use stdlib wave."""
    with wave.open(str(path), "rb") as wf:
        channels = wf.getnchannels()
        sr = wf.getframerate()
        width = wf.getsampwidth()
        nframes = wf.getnframes()
        raw = wf.readframes(nframes)
    if width == 2:
        data = np.frombuffer(raw, dtype=np.int16).astype(np.float32) / 32768.0
    elif width == 1:
        data = (np.frombuffer(raw, dtype=np.uint8).astype(np.float32) - 128.0) / 128.0
    else:
        raise ValueError(f"Unsupported sample width {width} in {path}")
    if channels > 1:
        data = data.reshape(-1, channels).mean(axis=1)
    data = resample(data, sr, SAMPLE_RATE)
    if len(data) > NUM_SAMPLES:
        data = data[:NUM_SAMPLES]
    else:
        data = np.pad(data, (0, NUM_SAMPLES - len(data)), mode="constant")
    return data.astype(np.float32)


def write_wav(path: Path, data: np.ndarray):
    """Write a 1-second mono 16-bit PCM WAV at 16 kHz."""
    samples = (data * 32767.0).astype(np.int16)
    with wave.open(str(path), "wb") as wf:
        wf.setnchannels(1)
        wf.setsampwidth(2)
        wf.setframerate(SAMPLE_RATE)
        wf.writeframes(samples.tobytes())


def generate_positive_sample(positives_dir: Path, phrase: str, voice: str) -> Optional[Path]:
    out = positives_dir / f"pos_{voice.lower()}_{phrase.replace(' ', '_').replace(',', '')}_{random.randint(0, 1_000_000_000)}.wav"
    # macOS `say` can write AIFF by default; use `afconvert` to get WAV@16kHz afterwards.
    aiff = out.with_suffix(".aiff")
    try:
        subprocess.run(
            ["say", "-v", voice, phrase, "-o", str(aiff)],
            check=True,
            timeout=15,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
        subprocess.run(
            ["afconvert", str(aiff), str(out), "-f", "WAVE", "-d", "LEI16@16000"],
            check=True,
            timeout=15,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
    except subprocess.TimeoutExpired:
        aiff.unlink(missing_ok=True)
        out.unlink(missing_ok=True)
        return None
    finally:
        aiff.unlink(missing_ok=True)
    return out


def augment_waveform(data: np.ndarray, noise_pool: list[np.ndarray]) -> np.ndarray:
    # Speed perturbation (simple resample stretch/crop).
    if random.random() < 0.5:
        factor = random.uniform(0.92, 1.08)
        new_len = int(len(data) / factor)
        old_x = np.linspace(0, len(data) - 1, len(data))
        new_x = np.linspace(0, len(data) - 1, new_len)
        data = np.interp(new_x, old_x, data).astype(np.float32)
        if len(data) > NUM_SAMPLES:
            start = random.randint(0, len(data) - NUM_SAMPLES)
            data = data[start:start + NUM_SAMPLES]
        elif len(data) < NUM_SAMPLES:
            data = np.pad(data, (0, NUM_SAMPLES - len(data)), mode="constant")

    # Random volume.
    data = data * random.uniform(0.7, 1.3)

    # Additive background noise from the negative pool.
    if noise_pool and random.random() < 0.6:
        noise = random.choice(noise_pool)
        if len(noise) < NUM_SAMPLES:
            noise = np.pad(noise, (0, NUM_SAMPLES - len(noise)), mode="wrap")
        else:
            start = random.randint(0, len(noise) - NUM_SAMPLES)
            noise = noise[start:start + NUM_SAMPLES]
        snr_db = random.uniform(10.0, 25.0)
        noise_power = np.mean(noise ** 2) + 1e-10
        signal_power = np.mean(data ** 2) + 1e-10
        noise_scale = np.sqrt(signal_power / noise_power) * (10 ** (-snr_db / 20.0))
        data = data + noise_scale * noise

    # DC offset / small time shift.
    if random.random() < 0.3:
        shift = random.randint(-400, 400)
        data = np.roll(data, shift)
        if shift > 0:
            data[:shift] = 0.0
        else:
            data[shift:] = 0.0

    return np.clip(data, -1.0, 1.0).astype(np.float32)


def collect_negatives(cmd_dir: Path, count: int, noise_pool: list) -> np.ndarray:
    files = list(cmd_dir.rglob("*.wav"))
    random.shuffle(files)
    out = []
    for f in files:
        if len(out) >= count:
            break
        try:
            data = load_wav(f)
        except Exception as e:
            print(f"Skipping {f}: {e}")
            continue
        out.append(data)
    # Augment negatives to hit count if necessary.
    while len(out) < count:
        base = random.choice(out) if out else np.zeros(NUM_SAMPLES, dtype=np.float32)
        out.append(augment_waveform(base.copy(), noise_pool))
    return np.array(out[:count], dtype=np.float32)


def get_spectrogram(waveform):
    """TF graph-compatible spectrogram. Expects batch shape [batch, 16000]."""
    # waveform shape [batch, samples]
    # Pad to a length that yields a power-of-two frame count for TFLite RFFT2D.
    waveform = tf.pad(waveform, [[0, 0], [0, PAD_TO_SAMPLES - NUM_SAMPLES]])
    stft = tf.signal.stft(
        waveform,
        frame_length=FRAME_LENGTH,
        frame_step=FRAME_STEP,
        fft_length=NFFT,
    )
    spectrogram = tf.abs(stft)
    # Add channels dimension: [batch, time, freq, 1]
    spectrogram = spectrogram[..., tf.newaxis]
    return spectrogram


def build_model(input_shape, num_labels):
    norm_layer = layers.Normalization()
    norm_layer.adapt(data=tf.zeros((1,) + input_shape, dtype=tf.float32))  # placeholder; real adapt below

    model = models.Sequential([
        layers.Input(shape=input_shape),
        layers.Resizing(32, 32),
        norm_layer,
        layers.Conv2D(32, 3, activation="relu"),
        layers.Conv2D(64, 3, activation="relu"),
        layers.MaxPooling2D(),
        layers.Dropout(0.25),
        layers.Flatten(),
        layers.Dense(128, activation="relu"),
        layers.Dropout(0.5),
        layers.Dense(num_labels),
    ])
    return model


class TFLiteAudioModel(tf.Module):
    def __init__(self, model, norm_layer):
        self.model = model
        self.norm_layer = norm_layer

    @tf.function(input_signature=[tf.TensorSpec(shape=[1, NUM_SAMPLES], dtype=tf.float32)])
    def __call__(self, waveform):
        spec = get_spectrogram(waveform)  # [1, time, freq, 1]
        spec = self.norm_layer(spec)       # [1, time, freq, 1]
        logits = self.model(spec, training=False)
        return logits


def main():
    seed_all()
    assets_dir, test_assets_dir, data_dir, positives_dir = ensure_dirs()
    model_path = assets_dir / "hey_ghost.tflite"
    test_wav_path = test_assets_dir / "hey_ghost_test.wav"

    print("Downloading mini_speech_commands...")
    cmd_dir = download_mini_speech_commands(data_dir)
    print(f"Extracted at: {cmd_dir}")

    # Build a small noise pool from mini_speech_commands for augmentation.
    noise_files = list(cmd_dir.rglob("*.wav"))
    random.shuffle(noise_files)
    noise_pool = []
    for f in noise_files[:200]:
        try:
            noise_pool.append(load_wav(f))
        except Exception:
            pass
    print(f"Loaded {len(noise_pool)} noise/negative reference clips")

    # Load any cached positives from a previous run, then generate the rest.
    cached = sorted(list(positives_dir.glob("*.wav")))
    positives = []
    if cached:
        use_cached = min(len(cached), POSITIVE_COUNT)
        print(f"Loading {use_cached} cached positive samples from {positives_dir}...")
        for i, f in enumerate(cached[:use_cached]):
            data = load_wav(f)
            data = augment_waveform(data, noise_pool)
            positives.append(data)
            if i % 200 == 0:
                print(f"  cached positives {i}/{use_cached}")
        print(f"Loaded {len(positives)} cached positives")

    need = POSITIVE_COUNT - len(positives)
    if need > 0:
        print(f"Generating {need} additional positive samples...")
        attempts = 0
        max_attempts = need * 4
        while len(positives) < POSITIVE_COUNT and attempts < max_attempts:
            attempts += 1
            voice = random.choice(SAY_VOICES)
            phrase = random.choice(WAKE_PHRASES)
            wav = generate_positive_sample(positives_dir, phrase, voice)
            if wav is None:
                print(f"  say/afconvert timed out for voice={voice} phrase='{phrase}', skipping")
                continue
            data = load_wav(wav)
            data = augment_waveform(data, noise_pool)
            positives.append(data)
            if len(positives) % 50 == 0:
                print(f"  generated {len(positives)}/{POSITIVE_COUNT}")
        if len(positives) < POSITIVE_COUNT:
            print(f"WARNING: only generated {len(positives)}/{POSITIVE_COUNT} positives")
    positives = np.array(positives, dtype=np.float32)

    # Save one clean positive sample for the Android test asset, padded to exactly 1 second
    # so the detector's sliding window fills and returns a probability on the first pass.
    test_wav = generate_positive_sample(positives_dir, "Hey Ghost", "Samantha")
    if test_wav is None:
        # Fallback to the first cached clean sample.
        test_wav = cached[0] if cached else None
    if test_wav is None:
        raise RuntimeError("Could not generate test wake-word clip")
    test_data = load_wav(test_wav)  # already padded to NUM_SAMPLES by load_wav
    write_wav(test_wav_path, test_data)
    print(f"Saved test wake-word clip to {test_wav_path}")

    # Save a negative sample for the Android test asset (any mini_speech_commands word).
    # Check the filename, not the full path — the repo is under a directory named "ghost".
    neg_test_src = random.choice(noise_files)
    while "ghost" in neg_test_src.name.lower():
        neg_test_src = random.choice(noise_files)
    neg_test_wav = test_assets_dir / "negative_test.wav"
    shutil.copy(neg_test_src, neg_test_wav)
    print(f"Saved negative test clip to {neg_test_wav}")

    # Collect negatives.
    negative_count = int(POSITIVE_COUNT * NEGATIVE_RATIO)
    print(f"Collecting {negative_count} negative samples...")
    negatives = collect_negatives(cmd_dir, negative_count, noise_pool)

    # Combine and shuffle.
    x = np.concatenate([positives, negatives], axis=0)
    y = np.concatenate([np.ones(len(positives), dtype=np.int32), np.zeros(len(negatives), dtype=np.int32)], axis=0)
    indices = np.arange(len(y))
    np.random.shuffle(indices)
    x = x[indices]
    y = y[indices]

    # Train/val split.
    split = int(len(y) * (1 - VAL_SPLIT))
    x_train, x_val = x[:split], x[split:]
    y_train, y_val = y[:split], y[split:]

    # Build spectrogram datasets.
    train_ds = tf.data.Dataset.from_tensor_slices((x_train, y_train)).shuffle(2000).batch(BATCH_SIZE).prefetch(tf.data.AUTOTUNE)
    val_ds = tf.data.Dataset.from_tensor_slices((x_val, y_val)).batch(BATCH_SIZE).prefetch(tf.data.AUTOTUNE)

    def spec_map(waveform, label):
        spec = get_spectrogram(waveform)
        return spec, label

    train_spec_ds = train_ds.map(spec_map, num_parallel_calls=tf.data.AUTOTUNE)
    val_spec_ds = val_ds.map(spec_map, num_parallel_calls=tf.data.AUTOTUNE)

    for spec, _ in train_spec_ds.take(1):
        input_shape = spec.shape[1:]
    print(f"Spectrogram input shape: {input_shape}")

    num_labels = 2
    norm_layer = layers.Normalization()
    norm_layer.adapt(data=train_spec_ds.map(lambda spec, label: spec))

    model = build_model(input_shape, num_labels)
    model.layers[2] = norm_layer  # replace placeholder normalization layer

    model.compile(
        optimizer=keras.optimizers.Adam(learning_rate=LEARNING_RATE),
        loss=keras.losses.SparseCategoricalCrossentropy(from_logits=True),
        metrics=["accuracy"],
    )

    print("Training...")
    callbacks = [
        keras.callbacks.EarlyStopping(monitor="val_loss", patience=PATIENCE, restore_best_weights=True),
        keras.callbacks.ReduceLROnPlateau(monitor="val_loss", factor=0.5, patience=2, min_lr=1e-5),
    ]
    history = model.fit(train_spec_ds, validation_data=val_spec_ds, epochs=EPOCHS, callbacks=callbacks)
    print("Training complete")

    # Evaluate on validation.
    val_loss, val_acc = model.evaluate(val_spec_ds, verbose=0)
    print(f"Validation accuracy: {val_acc:.4f}")

    # Convert to end-to-end TFLite (waveform -> logits).
    print("Converting to TFLite...")
    tflite_module = TFLiteAudioModel(model, norm_layer)
    concrete = tflite_module.__call__.get_concrete_function()
    converter = tf.lite.TFLiteConverter.from_concrete_functions([concrete])
    converter.optimizations = [tf.lite.Optimize.DEFAULT]
    converter.target_spec.supported_ops = [tf.lite.OpsSet.TFLITE_BUILTINS]
    tflite_model = converter.convert()
    model_path.write_bytes(tflite_model)
    print(f"Saved TFLite model: {model_path} ({model_path.stat().st_size / 1024:.1f} KB)")

    # Sanity-check the converted model on a fresh positive sample.
    interpreter = tf.lite.Interpreter(model_content=tflite_model)
    interpreter.allocate_tensors()
    input_details = interpreter.get_input_details()
    output_details = interpreter.get_output_details()
    interpreter.set_tensor(input_details[0]["index"], positives[0].reshape(1, -1).astype(np.float32))
    interpreter.invoke()
    logits = interpreter.get_tensor(output_details[0]["index"])[0]
    prob = tf.nn.softmax(logits).numpy()
    print(f"Sanity positive sample prob: wake={prob[1]:.4f}, unknown={prob[0]:.4f}")


if __name__ == "__main__":
    main()
