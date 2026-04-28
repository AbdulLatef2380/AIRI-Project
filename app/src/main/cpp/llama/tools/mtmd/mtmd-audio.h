// SPDX-License-Identifier: MIT
//
// Stub `mtmd-audio.h` for the AIRI pruned llama.cpp tree.
//
// Why this file exists
// --------------------
// The upstream `tools/mtmd/mtmd.cpp` references the audio preprocessing
// types (`mtmd_audio_mel`, `mtmd_audio_preprocessor` and its three concrete
// subclasses `_whisper`, `_conformer`, `_gemma4a`). In the unpruned tree
// these live in `tools/mtmd/mtmd-audio.{h,cpp}` together with a non-trivial
// implementation that pulls in extra dependencies (mel-spectrogram code,
// FFT, Whisper-style preprocessing, etc.).
//
// AIRI ships a vision-only mtmd build for the Android target. We never load
// an audio mmproj at runtime, so the audio code path inside `mtmd.cpp` is
// dead. But the *symbols* must still resolve at compile time, otherwise the
// translation unit cannot be built. Removing the audio branches from
// `mtmd.cpp` would diverge our copy from upstream — explicitly forbidden by
// the project rules ("never remove mtmd").
//
// The same trick that solved the missing-debug-overlay problem for Phase 1
// (`mtmd-debug.h`) applies here: we provide a header-only stub whose types
// satisfy the compiler. The base preprocessor is a concrete class whose
// virtual `preprocess()` always returns false — the existing error path in
// `mtmd.cpp` (`if (!ok) { LOG_ERR(...); return 2; }`) catches that and
// fails the call gracefully. `initialize()` is a no-op. The three derived
// classes carry no extra state.
//
// Loading an audio mmproj (i.e. constructing `ctx->ctx_a`) is what gates
// entry into `init_audio()` and the audio branches of `preprocess`. Since
// AIRI never loads an audio mmproj, none of these methods are ever invoked
// in practice — but the types they operate on must still exist so the
// translation unit compiles.
//
// If a real audio implementation is ever shipped, replace this header
// (and add the corresponding `mtmd-audio.cpp`) without touching `mtmd.cpp`.

#pragma once

#include "clip.h"

#include <cstddef>
#include <vector>

#define MTMD_INTERNAL_HEADER

// Single mel-spectrogram chunk. Three fields are consumed by `mtmd.cpp`:
//   * `n_len`  — number of time-frames
//   * `n_mel`  — number of mel-frequency bins
//   * `data`   — flattened [n_mel][n_len] float buffer
struct mtmd_audio_mel {
    int                n_len = 0;
    int                n_mel = 0;
    std::vector<float> data;
};

// Base class for audio preprocessors. Concrete (not abstract) so derived
// classes don't have to override anything; the default `preprocess` simply
// reports failure, which is what we want for a vision-only build.
struct mtmd_audio_preprocessor {
    explicit mtmd_audio_preprocessor(const clip_ctx * /*ctx*/) {}
    virtual ~mtmd_audio_preprocessor() = default;

    // Called once after construction. No state to set up in the stub.
    virtual void initialize() {}

    // Returns false to signal "audio preprocessing not available". The
    // caller in `mtmd.cpp` already handles a false return cleanly:
    //   if (!ok) { LOG_ERR("Unable to preprocess audio\n"); return 2; }
    virtual bool preprocess(const float * /*samples*/,
                            std::size_t   /*n_samples*/,
                            std::vector<mtmd_audio_mel> & /*out*/) {
        return false;
    }
};

// Concrete subclasses — no extra fields, no overrides, no behavior.
// They exist purely so `std::make_unique<...>(ctx_a)` resolves.

struct mtmd_audio_preprocessor_whisper : mtmd_audio_preprocessor {
    using mtmd_audio_preprocessor::mtmd_audio_preprocessor;
};

struct mtmd_audio_preprocessor_conformer : mtmd_audio_preprocessor {
    using mtmd_audio_preprocessor::mtmd_audio_preprocessor;
};

struct mtmd_audio_preprocessor_gemma4a : mtmd_audio_preprocessor {
    using mtmd_audio_preprocessor::mtmd_audio_preprocessor;
};
