AIRI — Voice assets
===================

This directory is an optional input for two independent Android voice paths:

  1. Porcupine wake-word ("Hey AIRI")
  2. Vosk offline speech-recognition model

It is not a release asset manifest. A file in this directory, a UI setting, or
an internal CI build does not prove microphone, wake-word, Bluetooth, or model
runtime behavior on a physical device.

=============================================================
1. PORCUPINE WAKE-WORD ("Hey AIRI")
=============================================================

PorcupineEngine.kt can locate a custom keyword file named hey_airi.ppn when it
is supplied in either location (res/raw is preferred):

  app/src/main/res/raw/hey_airi.ppn
  app/src/main/assets/voice/hey_airi.ppn

The keyword file must be named exactly hey_airi.ppn. Porcupine also requires a
valid Picovoice AccessKey. Keep that key outside Git, source, chat, test logs,
and release artifacts. A user-entered runtime key is handled only by the
application's encrypted local storage path; it must never be copied into this
README or a Gradle command committed to the repository.

When the keyword file or valid AccessKey is absent, the wake-word service must
stay disabled and UI must report configuration state rather than listening.
When both are present, a displayed eligible/ready state means only that the
local preconditions were observed; it is not evidence that detection succeeds
for a specific microphone, language, Android version, or background policy.

Required runtime evidence before making a wake-word claim:

  - user-granted microphone permission and denial/revocation behavior;
  - a compatible real device, lock/background behavior, and audio focus;
  - duplicate wake suppression, interruption recovery, and explicit-stop tests;
  - bundled-asset and downloaded-key recovery without secret disclosure.

=============================================================
2. VOSK OFFLINE SPEECH RECOGNITION
=============================================================

A compatible Vosk model zip may be bundled at:

  app/src/main/assets/voice/vosk-model-small-en-us-0.15.zip

VoskModelManager searches assets/voice on first initialization, extracts a
present bundle into app-private filesDir, checks the expected model structure,
and removes partial output after an extraction failure. The extraction path has
ZipSlip path containment checks. If no bundle is present, text-mode features
remain usable and any optional download path remains subject to network and
runtime verification.

The expected model file name and published checksum, if an owner deliberately
adds a model, must be recorded in a controlled asset/SBOM decision. Do not add
large models, provider credentials, or unreviewed binaries during Feature
Freeze. This repository's current unsigned CI evidence does not establish APK
integrity for a distributed binary. Only a later signed release artifact,
verified with its recorded certificate and SHA-256 evidence, can provide that
claim.

Once a valid model is installed, recognition is designed to execute locally;
this design statement is not a real-device guarantee. Verify extraction,
first-run storage, airplane-mode operation, memory/thermal behavior, error
recovery, language quality, and app-data erase/restore on supported devices
before advertising offline voice.
