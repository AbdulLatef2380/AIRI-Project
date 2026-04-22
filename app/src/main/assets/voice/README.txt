AIRI — Voice assets
===================

This directory is read at runtime by PorcupineEngine.kt to locate the
"Hey AIRI" wake-word file when a `res/raw/hey_airi.ppn` is NOT present.

How to enable wake-word detection
---------------------------------

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
