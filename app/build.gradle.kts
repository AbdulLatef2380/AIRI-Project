import java.util.Base64
import java.util.zip.ZipFile
import java.util.zip.ZipEntry

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)

    id("com.google.gms.google-services")

    // Crashlytics — uploads ProGuard mapping for de-obfuscated stack traces in release
    id("com.google.firebase.crashlytics")
}

android {
    namespace = "com.airi.assistant"
    compileSdk = 36
    ndkVersion = "25.2.9519653"

    defaultConfig {
        applicationId = "com.airi.assistant"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        vectorDrawables {
            useSupportLibrary = true
        }

        ndk {
            abiFilters += listOf("arm64-v8a")
        }

        // ── Picovoice Porcupine AccessKey ────────────────────────────────
        // Read from (in order): -PpicovoiceAccessKey=… gradle prop, then
        // PICOVOICE_ACCESS_KEY env var. When empty the wake-word service
        // refuses to start and the Voice Settings screen tells the user
        // exactly what to do. The user can ALSO paste a key at runtime
        // (stored in EncryptedSharedPreferences) without a rebuild.
        val picovoiceKey: String =
            (project.findProperty("picovoiceAccessKey") as? String).orEmpty()
                .ifBlank { System.getenv("PICOVOICE_ACCESS_KEY").orEmpty() }
        buildConfigField("String", "PICOVOICE_ACCESS_KEY", "\"" + picovoiceKey.replace("\"", "\\\"") + "\"")


        // Native (llama.cpp + JNI bridge) is built from source — see
        // app/src/main/cpp/CMakeLists.txt. No prebuilt .so is shipped; if
        // libairi_native.so ever appears in jniLibs/ it would shadow the
        // freshly compiled one, so the directory MUST stay empty of that name.
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                arguments += listOf(
                    "-DANDROID_TOOLCHAIN=clang",
                    "-DANDROID_STL=c++_shared",
                    "-DCMAKE_BUILD_TYPE=Release"
                )
            }
        }
    }

    // ── Release signing config ────────────────────────────────────────────────
    // Reads from environment variables injected by CI (GitHub Actions secrets)
    // or a local ~/.gradle/gradle.properties override. When the vars are absent
    // (e.g. a dev machine without the keystore) the signing block is skipped and
    // Gradle produces an unsigned APK — suitable for local debug use only.
    //
    // Required CI secrets:
    //   KEYSTORE_BASE64  — base64 of release.jks  (keytool RSA-4096, 10000-day)
    //   STORE_PASSWORD   — keystore store password
    //   KEY_ALIAS        — key alias (e.g. "airi")
    //   KEY_PASSWORD     — key password
    signingConfigs {
        val keystoreB64    = System.getenv("KEYSTORE_BASE64").orEmpty()
        val storePassword  = System.getenv("STORE_PASSWORD").orEmpty()
        val keyAlias       = System.getenv("KEY_ALIAS").orEmpty()
        val keyPassword    = System.getenv("KEY_PASSWORD").orEmpty()

        if (keystoreB64.isNotBlank() && storePassword.isNotBlank()
                && keyAlias.isNotBlank() && keyPassword.isNotBlank()) {
            create("release") {
                val ksFile = rootProject.file("release.keystore")
                ksFile.writeBytes(Base64.getDecoder().decode(keystoreB64))
                storeFile      = ksFile
                this.storePassword = storePassword
                this.keyAlias      = keyAlias
                this.keyPassword   = keyPassword
            }
        }
    }

    buildTypes {
        release {
            // R8 full-mode: dead-code elimination, shrinking, obfuscation.
            // Cuts APK size and removes unreachable dead paths (e.g. BrainManager).
            isMinifyEnabled   = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Apply signing config when keystore env vars are present (CI / release engineer).
            // Absent → unsigned APK (local dev / fork builds with no secrets).
            signingConfigs.findByName("release")?.let { signingConfig = it }

            // Crashlytics mapping file upload is controlled via the
            // com.google.firebase.crashlytics Gradle plugin at task execution time.
            // The firebaseCrashlytics {} DSL block requires the plugin classpath
            // to be fully resolved at script compile time — omit it here and let
            // the plugin defaults apply (upload enabled for release, disabled for debug).
        }
        debug {
            // Keep debug builds unminified for readable stack traces.
            isMinifyEnabled = false
            // AIRI_EXECUTE_GRAPH_ENABLED is now enabled globally in defaultConfig (Phase 7).
            // No per-variant override needed here.
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.10"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    // Build native sources from CMakeLists.txt in src/main/cpp/. Requires
    // Android NDK r25c (25.2.9519653) — declared via `ndkVersion` above.
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    // Core Android
    implementation(libs.androidx.core.ktx)

    // Testing
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test:core:1.5.0")
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("androidx.room:room-testing:2.6.1")

    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation("androidx.navigation:navigation-compose:2.7.7")
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.activity.compose)
    implementation("androidx.compose.ui:ui-text-google-fonts:1.6.1")
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Lifecycle
    implementation(libs.androidx.lifecycle.runtime.ktx)
    // lifecycle-runtime-compose provides LocalLifecycleOwner for Compose DisposableEffect.
    // Declared as a literal coordinate (not via version catalog) so that PR branches
    // with older libs.versions.toml that predate the catalog entry do not break.
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    // Networking
    implementation(libs.okhttp)
    implementation(libs.gson)

    // Firebase BOM — pins all Firebase / Crashlytics library versions together.
    // Crashlytics auto-collection is DISABLED via manifest meta-data
    // (firebase_crashlytics_collection_enabled = false) and enabled at runtime
    // only after the user grants telemetry consent in OnboardingScreen.
    implementation(platform("com.google.firebase:firebase-bom:32.8.0"))
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-firestore")
    implementation("com.google.firebase:firebase-analytics")

    // Crashlytics — production crash and non-fatal error reporting.
    // NDK variant adds native (C++) crash symbolication for llama.cpp crashes.
    implementation("com.google.firebase:firebase-crashlytics")
    implementation("com.google.firebase:firebase-crashlytics-ndk")

    // Play Integrity API — verifies APK authenticity and device integrity.
    // Used by PlayIntegrityVerifier.kt at startup + before high-trust actions.
    implementation("com.google.android.play:integrity:1.3.0")

    // Coroutines bridge for Firebase/Play-Services Tasks (.await() extension)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.7.3")

    // Biometric auth (BiometricPrompt)
    implementation("androidx.biometric:biometric:1.1.0")

    // Google Sign-In
    implementation("com.google.android.gms:play-services-auth:20.7.0")

    // Secure Storage (EncryptedSharedPreferences)
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // WorkManager (background agent)
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    // Google Play Billing
    implementation("com.android.billingclient:billing-ktx:6.2.1")

    // ── Voice (fully offline, no Google APIs) ────────────────────────────
    // Vosk = on-device speech-to-text (Apache 2.0). Models are NOT shipped
    // in the APK; the in-app downloader (VoskModelManager) fetches a chosen
    // Vosk model zip into internal storage, verifies SHA-256 (when known),
    // and extracts it. See VoskModelManager.kt + VoiceSettingsScreen.kt.
    implementation(libs.voskAndroid)

    // ── Image loading (chat attachment thumbnails) ───────────────────────
    // Coil is the Compose-native image loader (Apache-2.0). Used by the
    // chat screen to render the attachment preview chip and the in-bubble
    // image thumbnail. It decodes off the main thread, has built-in
    // memory + disk caching, and degrades gracefully (no crash) when an
    // image cannot be opened. See gradle/libs.versions.toml for version.
    implementation(libs.coil.compose)

    // Accompanist permissions — runtime permission helpers for Compose (OnboardingScreen).
    // Version 0.32.0 is compatible with Compose BOM 2023.10.01 (Compose 1.5.x).
    implementation("com.google.accompanist:accompanist-permissions:0.32.0")

    // Picovoice Porcupine = on-device wake-word ("Hey AIRI"). Requires
    // (a) a Picovoice AccessKey supplied via PICOVOICE_ACCESS_KEY (gradle
    //     property, environment variable, or runtime via Settings) and
    // (b) a custom .ppn keyword file dropped at:
    //         app/src/main/res/raw/hey_airi.ppn      (preferred)
    //     or  app/src/main/assets/voice/hey_airi.ppn (fallback)
    // When either is missing the wake-word service exits cleanly and the
    // UI shows the user how to enable it. See PorcupineEngine.kt.
    implementation(libs.porcupineAndroid)

    // ── TensorFlow Lite (OpenWakeWord — P0-V2) ───────────────────────────
    // TFLite runtime for OpenWakeWord on-device wake-word inference.
    // OpenWakeWord is Apache 2.0 — no account or API key required.
    // The model asset (hey_airi.tflite ~6MB) is extracted from
    // app/src/main/assets/voice/hey_airi.tflite by OpenWakeWordEngine.kt.
    // When the asset is absent the engine returns ready=false and the
    // existing Porcupine path is used as a fallback. See HotwordService.kt.
    implementation("org.tensorflow:tensorflow-lite:2.14.0")
    implementation("org.tensorflow:tensorflow-lite-support:0.4.4")
}

// Performance verification belongs in device benchmarks and CI test reports.
// Do not synthesize performance success from fixed values in the build script.

// ─────────────────────────────────────────────────────────────────────────
// Native-library verification is bound to the APK of each assembled variant.
// This catches missing JNI output without making a Release build depend on a
// Debug artifact that is not part of that build.
// ─────────────────────────────────────────────────────────────────────────
fun registerNativeApkVerification(variant: String) {
    val taskName = "airiVerifyNativeIn${variant.replaceFirstChar { it.titlecase() }}Apk"
    tasks.register(taskName) {
        group = "verification"
        description = "Asserts lib/arm64-v8a/libairi_native.so is present in the $variant APK."
        doLast {
            val apkDir = layout.buildDirectory.dir("outputs/apk/$variant").get().asFile
            val apk = apkDir.listFiles { file -> file.extension == "apk" }
                ?.maxByOrNull { it.lastModified() }
                ?: error("AIRI_VERIFY_NATIVE: no $variant APK found at ${apkDir.absolutePath}")
            val target = "lib/arm64-v8a/libairi_native.so"
            ZipFile(apk).use { archive ->
                val entry = archive.getEntry(target)
                    ?: error("AIRI_VERIFY_NATIVE: $target is absent from ${apk.name}")
                check(entry.size >= 1_000_000) {
                    "AIRI_VERIFY_NATIVE: $target is unexpectedly small (${entry.size} bytes)"
                }
                println("AIRI_VERIFY_NATIVE: $variant APK contains $target (${entry.size} bytes)")
            }
        }
    }
}

registerNativeApkVerification("debug")
registerNativeApkVerification("release")

afterEvaluate {
    tasks.named("assembleDebug").configure {
        finalizedBy("airiVerifyNativeInDebugApk")
    }
    tasks.named("assembleRelease").configure {
        finalizedBy("airiVerifyNativeInReleaseApk")
    }
}
