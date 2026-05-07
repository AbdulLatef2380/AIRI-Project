# AIRI - Android Artificial Intelligence Runtime Interface

## Project Overview

AIRI is an autonomous on-device AI assistant for Android built with Kotlin and Jetpack Compose. It runs entirely on-device using llama.cpp for local LLM inference, providing intelligent automation while maintaining complete privacy.

**This is an Android mobile application — it cannot run as a web server.** To use AIRI, the APK must be installed on a physical Android device or emulator running Android 8.0+ (API 26+).

## Tech Stack

- **Language:** Kotlin 1.9.22
- **UI:** Jetpack Compose (BOM 2023.10.01)
- **AI Inference:** llama.cpp via JNI/NDK (arm64-v8a)
- **Voice:** Vosk (offline STT), custom wake-word engine
- **Database:** Room (local SQLite)
- **Backend Services:** Firebase Auth, Firestore, Crashlytics, Analytics
- **Build:** Gradle 8.2.2 with KSP, Android NDK r25c (25.2.9519653), CMake 3.22.1
- **Target:** Android API 26–34, arm64-v8a only

## Architecture

9-layer Clean Architecture:
1. **UI Layer** — Jetpack Compose screens, ViewModels, navigation
2. **Core Layer** — UnifiedCognitiveLoop, ServiceLocator, IntentRouter
3. **Agent Layer** — Planning, Execution, Decision, Learning
4. **World Layer** — Device state, context, risk, sensors
5. **Memory Layer** — Room DB, cache, conversation history
6. **Accessibility Layer** — AiriAccessibilityService, UITreeScanner, ActionExecutor
7. **AI Layer** — LLM inference, model management, prompts
8. **Tools Layer** — Tool registry, external integrations
9. **Voice Layer** — LiveVoiceService, HotwordService, Vosk STT

## Key Files

- `app/src/main/java/com/airi/assistant/MainActivity.kt` — Entry point
- `app/src/main/java/com/airi/assistant/core/UnifiedCognitiveLoop.kt` — Main AI engine
- `app/src/main/java/com/airi/assistant/ui/ChatScreen.kt` — Main chat UI
- `app/src/main/cpp/CMakeLists.txt` — Native (llama.cpp) build config
- `app/src/main/cpp/LlamaBridge.cpp` — JNI bridge to llama.cpp
- `app/google-services.json` — Firebase configuration
- `gradle/libs.versions.toml` — Dependency version catalog

## Building

Building requires:
- **Android SDK** with platform `android-34` and build-tools `34.0.0`
- **Android NDK** version `25.2.9519653`
- **CMake** version `3.22.1`
- **JDK 17**

These are NOT available in the Replit environment. Build is handled via GitHub Actions CI (see `.github/workflows/android_build.yml`).

A pre-built debug APK is available at: `airi-debug.apk`

## Installation

1. Enable "Install from unknown sources" on your Android device
2. Transfer `airi-debug.apk` to your device
3. Tap the APK to install
4. Grant required permissions (Accessibility, Microphone, etc.)
5. Download an LLM model from within the app (Settings → Model)

## Firebase Setup

The app uses Firebase services. `app/google-services.json` must be present for the build to succeed. Replace with your own Firebase project config for production builds.

## CI/CD

GitHub Actions workflow at `.github/workflows/android_build.yml` builds the APK on every push. Release builds require keystore secrets:
- `KEYSTORE_BASE64`
- `STORE_PASSWORD`
- `KEY_ALIAS`
- `KEY_PASSWORD`

## User Preferences

- Keep architecture layered and modular per Clean Architecture principles
- No cloud inference — all AI must run on-device
- Privacy-first: no data leaves the device
