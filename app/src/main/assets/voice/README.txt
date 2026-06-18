AIRI — Voice assets
===================

This directory serves two independent voice subsystems:

  1. Porcupine wake-word ("Hey AIRI")
  2. Vosk offline speech-recognition model (auto-bundle)


=============================================================
1. PORCUPINE WAKE-WORD ("Hey AIRI")
=============================================================

Read at runtime by PorcupineEngine.kt to locate the "Hey AIRI"
wake-word file when `res/raw/hey_airi.ppn` is NOT present.

How to enable wake-word detection
----------------------------------
1.  Sign in to https://console.picovoice.ai/ (free tier is fine).

2.  Generate a custom keyword:
       Picovoice Console → "Porcupine" → "Create Wake Word"
       Phrase:    Hey AIRI
       Platform:  Android
       Language:  English (or any language Porcupine supports)
    Download the resulting `.ppn` file.

3.  Drop the file at ONE of these paths (preferred first):
       app/src/main/res/raw/hey_airi.ppn        ← preferred
       app/src/main/assets/voice/hey_airi.ppn   ← fallback

    The filename MUST be exactly `hey_airi.ppn` (lower-case, underscore).

4.  Provide your Picovoice AccessKey via either:

       a) build time:
            ./gradlew assembleDebug -PpicovoiceAccessKey=YOUR_KEY_HERE
          or set the PICOVOICE_ACCESS_KEY environment variable.

       b) at runtime: open AIRI → Settings → Voice & Wake Word
          and paste the key. It is stored in EncryptedSharedPreferences
          and overrides the build-time value.

5.  Re-launch the app. The Voice Settings screen will show a green
    "Wake word ready" status. Toggle "Hey AIRI" detection on.

When either the .ppn OR the AccessKey is missing, AIRI silently disables
the wake-word foreground service and the Voice Settings screen tells you
exactly what's missing — no false claims, no fake "listening" state.


=============================================================
2. VOSK OFFLINE SPEECH-RECOGNITION — AUTO-BUNDLE (P0-V1)
=============================================================

AIRI can ship with speech recognition working on a clean install,
with airplane mode enabled, and with no internet required — if the
Vosk model zip is bundled here before building the APK.

How to bundle the model
------------------------
1.  Download the small English model (≈40 MB zip) from:
      https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip

    SHA-256 (lowercase hex):
      30f26242c4eb449f948e42cb302dd7a686cb29a3423a8367f99ff41780942498

2.  Place the downloaded file at EXACTLY this path:
      app/src/main/assets/voice/vosk-model-small-en-us-0.15.zip

3.  The file MUST be named exactly:
      vosk-model-small-en-us-0.15.zip

4.  Rebuild the APK. The zip will be packaged inside assets/voice/.

What happens at runtime (VoskModelManager.extractBundledModelIfPresent)
------------------------------------------------------------------------
- Called from VoskModelManager.init() on first launch (Dispatchers.IO).
- Checks assets/voice/ for the zip via AssetManager.list("voice").
- If present, extracts the zip into:
    <filesDir>/vosk_models/vosk-model-small-en-us-0.15/
  stripping the top-level directory prefix.
- Validates the extraction via isValidVoskModel() — checks that the
  extracted directory contains am/ and conf/ subdirectories.
- On success: auto-selects the model as active.
- On failure (corrupt zip, extraction error): deletes partial output
  and logs a warning — safe no-op, user can download manually.
- ZipSlip protection: every entry path is canonicalized and verified
  to remain inside the destination directory before writing.
- APK signing provides integrity for the bundled asset — no separate
  SHA-256 check is needed on the extracted bundle (unlike downloads).

If the asset is absent (CI builds, development without the 40 MB file)
-----------------------------------------------------------------------
- extractBundledModelIfPresent() is a safe no-op (logs one debug line).
- VoiceSettingsScreen will offer a "Download" button to fetch the model
  over the network via VoskModelManager.triggerFirstRunDownloadIfNeeded().
- All text-mode features work without any Vosk model installed.

Offline guarantee
-----------------
Once the model is extracted (either from the bundle or via download),
AIRI performs speech recognition 100% on-device with NO network calls.
The Vosk recognizer never contacts external servers.
