# AIRI Core Dependency Inventory

> **Generated from declared Gradle/catalog sources.** This is an engineering inventory, not a legal opinion, an SBOM, or a complete resolved transitive graph. A qualified reviewer must verify current license, notice, export, model, and distribution obligations before commercial release.

## Direct Android dependencies

| Component | Declared version | Source | Commercial review disposition |
|---|---:|---|---|
| `ai.picovoice:porcupine-android` | `3.0.1` | Declared Gradle dependency | Review upstream license, notices and redistribution terms before distribution |
| `androidx.activity:activity-compose` | `1.8.2` | Declared Gradle dependency | Review upstream license, notices and redistribution terms before distribution |
| `androidx.biometric:biometric` | `1.1.0` | Declared Gradle dependency | Review upstream license, notices and redistribution terms before distribution |
| `androidx.compose.material3:material3` | `BOM-managed` | Declared Gradle dependency | Review upstream license, notices and redistribution terms before distribution |
| `androidx.compose.material:material-icons-extended` | `BOM-managed` | Declared Gradle dependency | Review upstream license, notices and redistribution terms before distribution |
| `androidx.compose.ui:ui-text-google-fonts` | `BOM-managed` | Declared Gradle dependency | Review upstream license, notices and redistribution terms before distribution |
| `androidx.compose.ui:ui-tooling-preview` | `BOM-managed` | Declared Gradle dependency | Review upstream license, notices and redistribution terms before distribution |
| `androidx.compose.ui:ui` | `BOM-managed` | Declared Gradle dependency | Review upstream license, notices and redistribution terms before distribution |
| `androidx.core:core-ktx` | `1.12.0` | Declared Gradle dependency | Review upstream license, notices and redistribution terms before distribution |
| `androidx.lifecycle:lifecycle-runtime-compose` | `2.7.0` | Declared Gradle dependency | Review upstream license, notices and redistribution terms before distribution |
| `androidx.lifecycle:lifecycle-runtime-ktx` | `2.7.0` | Declared Gradle dependency | Review upstream license, notices and redistribution terms before distribution |
| `androidx.lifecycle:lifecycle-viewmodel-compose` | `2.7.0` | Declared Gradle dependency | Review upstream license, notices and redistribution terms before distribution |
| `androidx.lifecycle:lifecycle-viewmodel-ktx` | `2.7.0` | Declared Gradle dependency | Review upstream license, notices and redistribution terms before distribution |
| `androidx.navigation:navigation-compose` | `2.7.7` | Declared Gradle dependency | Review upstream license, notices and redistribution terms before distribution |
| `androidx.room:room-compiler` | `2.8.4` | Declared Gradle dependency | Review upstream license, notices and redistribution terms before distribution |
| `androidx.room:room-ktx` | `2.8.4` | Declared Gradle dependency | Review upstream license, notices and redistribution terms before distribution |
| `androidx.room:room-runtime` | `2.8.4` | Declared Gradle dependency | Review upstream license, notices and redistribution terms before distribution |
| `androidx.room:room-testing` | `${libs.versions.room.get()}` | Declared Gradle dependency | Review upstream license, notices and redistribution terms before distribution |
| `androidx.security:security-crypto` | `1.1.0-alpha06` | Declared Gradle dependency | Review upstream license, notices and redistribution terms before distribution |
| `androidx.test.ext:junit` | `1.1.5` | Declared Gradle dependency | Review upstream license, notices and redistribution terms before distribution |
| `androidx.test:core` | `1.5.0` | Declared Gradle dependency | Review upstream license, notices and redistribution terms before distribution |
| `androidx.test:runner` | `1.5.2` | Declared Gradle dependency | Review upstream license, notices and redistribution terms before distribution |
| `androidx.work:work-runtime-ktx` | `2.9.0` | Declared Gradle dependency | Review upstream license, notices and redistribution terms before distribution |
| `com.alphacephei:vosk-android` | `0.3.47` | Declared Gradle dependency | Review upstream license, notices and redistribution terms before distribution |
| `com.android.billingclient:billing-ktx` | `6.2.1` | Declared Gradle dependency | Review upstream license, notices and redistribution terms before distribution |
| `com.google.accompanist:accompanist-permissions` | `0.32.0` | Declared Gradle dependency | Review upstream license, notices and redistribution terms before distribution |
| `com.google.android.gms:play-services-auth` | `20.7.0` | Declared Gradle dependency | Review upstream license, notices and redistribution terms before distribution |
| `com.google.android.play:integrity` | `1.3.0` | Declared Gradle dependency | Review upstream license, notices and redistribution terms before distribution |
| `com.google.code.gson:gson` | `2.10.1` | Declared Gradle dependency | Review upstream license, notices and redistribution terms before distribution |
| `com.google.firebase:firebase-analytics` | `BOM-managed` | Declared Gradle dependency | Review upstream license, notices and redistribution terms before distribution |
| `com.google.firebase:firebase-auth` | `BOM-managed` | Declared Gradle dependency | Review upstream license, notices and redistribution terms before distribution |
| `com.google.firebase:firebase-bom` | `32.8.0` | Declared Gradle dependency | Review upstream license, notices and redistribution terms before distribution |
| `com.google.firebase:firebase-crashlytics` | `BOM-managed` | Declared Gradle dependency | Review upstream license, notices and redistribution terms before distribution |
| `com.google.firebase:firebase-crashlytics-ndk` | `BOM-managed` | Declared Gradle dependency | Review upstream license, notices and redistribution terms before distribution |
| `com.google.firebase:firebase-firestore` | `BOM-managed` | Declared Gradle dependency | Review upstream license, notices and redistribution terms before distribution |
| `com.squareup.okhttp3:okhttp` | `4.12.0` | Declared Gradle dependency | Review upstream license, notices and redistribution terms before distribution |
| `io.coil-kt:coil-compose` | `2.6.0` | Declared Gradle dependency | Review upstream license, notices and redistribution terms before distribution |
| `junit:junit` | `4.13.2` | Declared Gradle dependency | Review upstream license, notices and redistribution terms before distribution |
| `org.jetbrains.kotlinx:kotlinx-coroutines-android` | `1.7.3` | Declared Gradle dependency | Review upstream license, notices and redistribution terms before distribution |
| `org.jetbrains.kotlinx:kotlinx-coroutines-play-services` | `1.7.3` | Declared Gradle dependency | Review upstream license, notices and redistribution terms before distribution |
| `org.tensorflow:tensorflow-lite-support` | `0.4.4` | Declared Gradle dependency | Review upstream license, notices and redistribution terms before distribution |
| `org.tensorflow:tensorflow-lite` | `2.14.0` | Declared Gradle dependency | Review upstream license, notices and redistribution terms before distribution |

## Native and runtime components

| Component | Type | Commercial review disposition |
|---|---|---|
| llama.cpp via JNI/CMake | Native/runtime component | Confirm source, license notice, model or access-key terms before redistribution |
| Picovoice Porcupine runtime | Native/runtime component | Confirm source, license notice, model or access-key terms before redistribution |
| TensorFlow Lite runtime | Native/runtime component | Confirm source, license notice, model or access-key terms before redistribution |

## Reproduction

```bash
python3 scripts/supply_chain_inventory.py --output docs/commercial/DEPENDENCY_INVENTORY.md
./gradlew :app:dependencies --configuration releaseRuntimeClasspath
```

The Gradle command is required before a transaction or release to capture the complete resolved, transitive dependency graph for the exact build environment.
