# AIRI runtime asset manifest

Generated from the tracked source tree on 2026-11-12. Hashes are reproducible with the command shown below. `NOT_VERIFIED` means the repository contains the asset but no trusted upstream hash/license evidence has been attached yet.

| Path | Role | SHA-256 | License/source status |
|---|---|---|---|
| `app/src/main/assets/tessdata/ara.traineddata` | Arabic OCR data | `2005976778bbc14fc56a4ea8d43c6080847aeee72fcc2201488f240daca15c5b` | **NOT_VERIFIED**; record Tesseract language-data source and redistribution terms before release |
| `app/src/main/assets/voice/README.txt` | Voice asset instructions | `be48f9fcdeaaf9e2a6b4e1206f32deec5499be25d9235b861e95255d864a25b8` | Project documentation |
| `app/src/main/res/drawable/ic_integration_github.png` | UI icon | `6747602af32192d93a6aeb157174fead631839129570c86470b119ef7b7cbb08` | AIRI asset; confirm brand-use terms before public marketing |
| `app/src/main/res/drawable/ic_integration_google.png` | UI icon | `c525a3ff22facb127b484e068703ad2e6237fb785f30c2384cb9548c0d6b9eed` | AIRI asset; confirm brand-use terms before public marketing |
| `app/src/main/res/drawable/ic_integration_telegram.png` | UI icon | `0c8d7fe5195084fe7af57a189f5568157c747e680bd6f5b132c6b1d714b23b14` | AIRI asset; confirm brand-use terms before public marketing |
| `app/src/main/res/drawable/ic_launcher_fg.png` | Application branding | `677e6f7a97a76345dd7b87030f161c53bb6ffcaee57faedd7fab623c94997631` | AIRI-owned/generated asset unless a third-party source is documented |

The repository currently does not contain the bundled Vosk ZIP, Porcupine keyword file, TFLite wake-word model, or GGUF model files referenced by runtime fallbacks. Their runtime source paths are therefore catalogue/optional-download paths, not shipped assets. Each downloaded model must be admitted only with URL, exact version/model ID, SHA-256, license, and terms.

## Hash command

```bash
find app/src/main/assets app/src/main/res -type f \\
  | sort \\
  | while read -r f; do sha256sum "$f"; done
```

Vosk's official catalogue states that small mobile models may be around 50 MB and lists per-model licenses; it must be consulted for every selected model: <https://alphacephei.com/vosk/models>.
