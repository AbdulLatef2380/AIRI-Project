# AIRI third-party notices and review register

This is an engineering notice register, not a legal opinion. `REVIEW_REQUIRED` means the source or package is used/declared but commercial redistribution still needs a license and notice review for the exact artifact.

| Component | Source / version | License evidence | Status |
|---|---|---|---|
| llama.cpp / ggml | https://github.com/ggml-org/llama.cpp; local snapshot manifest | Official repository: MIT; verify included notices against the vendored snapshot | REVIEW_REQUIRED |
| Vosk Android runtime | `com.alphacephei:vosk-android:0.3.47`; https://alphacephei.com/vosk/ | Runtime and each downloaded model have separate terms | REVIEW_REQUIRED |
| Vosk models | https://alphacephei.com/vosk/models | Official catalogue lists model-specific licenses; model URL/hash must be recorded per install | REVIEW_REQUIRED |
| Picovoice Porcupine | `ai.picovoice:porcupine-android:3.0.1` | Vendor package/terms and access-key terms must be reviewed for distribution | REVIEW_REQUIRED |
| AndroidX / Kotlin / Compose / Material | Versions in `gradle/libs.versions.toml` | Upstream package metadata and repository licenses | REVIEW_REQUIRED; SBOM is generated |
| Firebase Admin / Firebase Functions / Google APIs | Functions exact direct versions plus `functions/package-lock.json` | npm lock records package license/integrity; verify notices for release bundle | REVIEW_REQUIRED |
| npm transitive packages | `functions/package-lock.json` | npm metadata and integrity are captured; license expressions are not independently adjudicated | REVIEW_REQUIRED |
| Tesseract Arabic traineddata | `app/src/main/assets/tessdata/ara.traineddata` | Source/license not recorded in repository | **NOT_VERIFIED** |
| Integration and launcher icons | `app/src/main/res/...` | Local assets; brand-use requirements may apply to provider marks | REVIEW_REQUIRED |

## Native upstream acknowledgements

The official llama.cpp repository acknowledges third-party components such as `cpp-httplib`, `stb`, `nlohmann/json`, `miniaudio`, and `subprocess.h`. The AIRI snapshot's exact included files must be compared against those notices before release; the native tree currently lacks a retained upstream commit mapping.

## Release gate

A release is blocked when a shipped component is `NOT_VERIFIED`, when an artifact hash is absent, or when a license/notice required by the upstream component is not included in the final distribution. The SBOM files in this directory intentionally use `NOASSERTION` where evidence is missing.
