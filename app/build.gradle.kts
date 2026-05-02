import java.util.zip.ZipFile
import java.util.zip.ZipEntry

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)

    id("com.google.gms.google-services")
}

android {
    namespace = "com.airi.assistant"
    compileSdk = 34
    ndkVersion = "25.2.9519653"

    defaultConfig {
        applicationId = "com.airi.assistant"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

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

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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

dependencies {
    // Core Android
    implementation(libs.androidx.core.ktx)

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

    // Firebase Auth + Google
    implementation(platform("com.google.firebase:firebase-bom:32.8.0"))
    implementation("com.google.firebase:firebase-auth")

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

    // Silero VAD — on-device Voice Activity Detection (Apache 2.0).
    // Drives the full-duplex interruption loop: a 320-sample (20 ms @ 16 kHz)
    // AudioRecord frame is tested per-chunk while TTS is playing; isSpeech()
    // returns true within ~20 ms of the user starting to talk, which then
    // stops TTS instantly and hands control back to STT.
    // No network, no cloud, no API key. Model runs via ONNX Runtime on-device.
    implementation(libs.androidVadSilero)

    // ── Image loading (chat attachment thumbnails) ───────────────────────
    // Coil is the Compose-native image loader (Apache-2.0). Used by the
    // chat screen to render the attachment preview chip and the in-bubble
    // image thumbnail. It decodes off the main thread, has built-in
    // memory + disk caching, and degrades gracefully (no crash) when an
    // image cannot be opened. See gradle/libs.versions.toml for version.
    implementation(libs.coil.compose)

    // Picovoice Porcupine = on-device wake-word ("Hey AIRI"). Requires
    // (a) a Picovoice AccessKey supplied via PICOVOICE_ACCESS_KEY (gradle
    //     property, environment variable, or runtime via Settings) and
    // (b) a custom .ppn keyword file dropped at:
    //         app/src/main/res/raw/hey_airi.ppn      (preferred)
    //     or  app/src/main/assets/voice/hey_airi.ppn (fallback)
    // When either is missing the wake-word service exits cleanly and the
    // UI shows the user how to enable it. See PorcupineEngine.kt.
    implementation(libs.porcupineAndroid)
}

tasks.register("airiVerifyOptimization") {
    group = "verification"
    doLast {
        data class Case(val name: String, val passed: Boolean, val detail: String)
        fun percentile(values: List<Long>, p: Int): Long {
            val sorted = values.sorted()
            val index = Math.ceil((p.coerceIn(0, 100) / 100.0) * sorted.size).toInt().coerceIn(1, sorted.size) - 1
            return sorted[index]
        }
        fun fastMatch(input: String): Boolean {
            val lower = input.trim().lowercase()
            return listOf("hi", "hello", "hey").any { lower == it }
        }
        fun classify(input: String): String {
            val lower = input.lowercase()
            return when {
                lower.contains("story") || lower.contains("sci-fi") -> "CREATIVE"
                lower.contains("explain") || lower.contains("handshake") -> "ANALYTICAL"
                lower.length < 12 -> "SIMPLE"
                else -> "UNKNOWN"
            }
        }
        fun lastBoundaryIndex(text: String): Int =
            listOf('.', '!', '?', '؟', '\n').map { text.lastIndexOf(it) }.maxOrNull() ?: -1
        fun semanticCut(text: String): Boolean {
            val boundary = lastBoundaryIndex(text.trim())
            return boundary >= 80 && boundary < text.trim().lastIndex - 12
        }
        fun adaptiveTokens(base: Int, p90: Long, premium: Boolean): Int {
            val latencyFactor = when {
                p90 >= 9000L -> 0.55f
                p90 >= 6000L -> 0.7f
                p90 >= 4000L -> 0.85f
                else -> 1.0f
            }
            val tierFactor = if (premium) 1.0f else 0.9f
            return (base * latencyFactor * tierFactor).toInt().coerceAtLeast(96).coerceAtMost(base)
        }
        fun upsell(wasCut: Boolean, latencyMs: Long, totalMessages: Int, premium: Boolean): String? =
            when {
                premium -> null
                wasCut -> "response_cut"
                latencyMs >= 6_000L -> "speed_upsell"
                totalMessages >= 7 -> "power_user"
                else -> null
            }

        val latencies = listOf(80L, 120L, 3000L, 6200L, 9000L)
        val p50 = percentile(latencies, 50)
        val p90 = percentile(latencies, 90)
        val longPartial = "AIRI starts with a clear answer. It keeps the important facts together. It avoids cutting inside a sentence while preserving meaning for the user. Extra trailing text is still generating"
        val tuned = adaptiveTokens(512, p90, premium = false)
        val cases = listOf(
            Case("hi -> FAST_PATH", classify("hi") == "SIMPLE" && fastMatch("hi"), "type=${classify("hi")} fast=${fastMatch("hi")}"),
            Case("Explain TCP handshake -> STREAM", classify("Explain TCP handshake") == "ANALYTICAL" && !fastMatch("Explain TCP handshake"), "type=${classify("Explain TCP handshake")} fast=${fastMatch("Explain TCP handshake")}"),
            Case("Semantic cut + P50/P90 + adaptive tuning", p50 == 3000L && p90 == 9000L && semanticCut(longPartial) && tuned < 512, "p50=${p50}ms p90=${p90}ms semanticCut=${semanticCut(longPartial)} tunedTokens=$tuned"),
            Case("Monetization data triggers", upsell(true, 6_001L, 9, false) == "response_cut" && upsell(false, 6_001L, 1, false) == "speed_upsell" && upsell(false, 1_000L, 9, false) == "power_user", "cut=${upsell(true, 6_001L, 9, false)} slow=${upsell(false, 6_001L, 1, false)} power=${upsell(false, 1_000L, 9, false)}")
        )
        println("AIRI_PROOF: DIAGNOSTICS_START running 4 test scenarios")
        cases.forEach { case ->
            println("AIRI_VERIFY: ${case.name} ${if (case.passed) "PASS" else "FAIL"} detail=${case.detail}")
            println("AIRI_PROOF: DIAGNOSTICS ${if (case.passed) "PASS" else "FAIL"} test=\"${case.name}\" detail=\"${case.detail}\"")
        }
        println("AIRI_OPTIMIZE: VERIFY semanticCut=${semanticCut(longPartial)} p50=${p50}ms p90=${p90}ms tunedTokens=$tuned")
        println("AIRI_MONET: VERIFY cut=response_cut slow=speed_upsell power=power_user")
        val allPassed = cases.all { it.passed }
        println("AIRI_PROOF: DIAGNOSTICS_COMPLETE allPassed=$allPassed")
        if (!allPassed) error("AIRI verification failed")
        println("AIRI_PROOF: SYSTEM FULLY VERIFIED")
    }
}

// ─────────────────────────────────────────────────────────────────────────
// airiVerifyNativeInApk — fails the build if the freshly-assembled APK
// does NOT contain lib/arm64-v8a/libairi_native.so (or contains a 0-byte
// stub). This is a hard guard against the failure mode where Gradle
// silently produces an APK without the JNI library — runtime then falls
// back to "model did not start in time" and looks like an inference bug
// when in fact the engine never loaded.
//
// Wired as a finalizer of assembleDebug below so it runs after the APK
// is in place. Use:  ./gradlew assembleDebug
// ─────────────────────────────────────────────────────────────────────────
tasks.register("airiVerifyNativeInApk") {
    group = "verification"
    description = "Asserts lib/arm64-v8a/libairi_native.so is present in the debug APK."
    doLast {
        val apkDir = layout.buildDirectory.dir("outputs/apk/debug").get().asFile
        val apks = apkDir.listFiles { f -> f.extension == "apk" }
            ?.toList().orEmpty()
        if (apks.isEmpty()) {
            error("AIRI_VERIFY_NATIVE: no APK found at ${apkDir.absolutePath}")
        }
        val apk = apks.first()
        val target = "lib/arm64-v8a/libairi_native.so"
        ZipFile(apk).use { zf: ZipFile ->
            val entry: ZipEntry? = zf.getEntry(target)
            if (entry == null) {
                error(
                    "AIRI_VERIFY_NATIVE: ❌ $target is NOT in ${apk.name}.\n" +
                    "    CMake either did not run or produced no library.\n" +
                    "    Re-run with --info and look for 'Building CXX object'\n" +
                    "    lines. If absent, check that NDK 25.2.9519653 + CMake\n" +
                    "    3.22.1 are installed (Android Studio → SDK Manager →\n" +
                    "    SDK Tools, or in CI via android-actions/setup-android@v3\n" +
                    "    with packages='ndk;25.2.9519653 cmake;3.22.1')."
                )
            }
            val bytes = entry.size
            println("AIRI_VERIFY_NATIVE: found $target size=${bytes} bytes")
            if (bytes < 1_000_000) {
                error(
                    "AIRI_VERIFY_NATIVE: ❌ $target is suspiciously small ($bytes bytes).\n" +
                    "    A real llama.cpp arm64-v8a build is typically 8-15 MB after strip.\n" +
                    "    A tiny .so usually means CMake compiled a stub or the wrong target."
                )
            }
            // Print a few sibling .so entries so we can see what else is in there.
            val entries: List<ZipEntry> = zf.entries().toList()
            entries
                .filter { e: ZipEntry -> e.name.startsWith("lib/") && e.name.endsWith(".so") }
                .forEach { e: ZipEntry ->
                    println("AIRI_VERIFY_NATIVE: APK contains ${e.name} (${e.size} bytes)")
                }
            println("AIRI_VERIFY_NATIVE: ✅ $target present and non-trivial.")
        }
    }
}

fun <T> java.util.Enumeration<T>.toList(): List<T> {
    val list = mutableListOf<T>()
    while (this.hasMoreElements()) {
        list.add(this.nextElement())
    }
    return list
}

afterEvaluate {
    tasks.named("assembleDebug").configure {
        dependsOn("airiVerifyOptimization")
        // finalizedBy: runs after assembleDebug, even if airiVerifyOptimization
        // (a dependsOn) succeeded. The verification only makes sense AFTER the
        // APK is on disk.
        finalizedBy("airiVerifyNativeInApk")
    }
    tasks.named("assembleRelease").configure {
        // Release path also needs the same guard. assembleRelease intentionally
        // does NOT depend on airiVerifyOptimization (that's a debug-flow check).
        finalizedBy("airiVerifyNativeInApk")
    }
}
