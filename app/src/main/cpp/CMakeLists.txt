cmake_minimum_required(VERSION 3.22.1)
project("airi_native" CXX C)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_C_STANDARD 11)

# === Build flags ===
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -O3 -ffast-math -fno-finite-math-only")
set(CMAKE_C_FLAGS   "${CMAKE_C_FLAGS}   -O3 -ffast-math -fno-finite-math-only")

set(LLAMA_DIR ${CMAKE_SOURCE_DIR}/llama)

# ─────────────────────────────────────────────────────────────────────────────
# CI memory cap — bound concurrent C++ compile units.
#
# The vendored llama.cpp tree is large (≈ 200 translation units, several with
# heavy template instantiation). On hosted CI runners with ~7GB RAM, a default
# `make -j$(nproc)` saturates physical memory and the OOM-killer reaps random
# compile workers — surfacing as "ninja: error: subcommand failed". Capping
# concurrent COMPILE jobs to 2 keeps peak RSS bounded (~3-4GB) at a small
# wall-time cost. LINK is still serial (default), so this only affects the
# parallel compile phase. Override on workstations with:
#   ./gradlew assembleDebug -PcompileJobs=4
# ─────────────────────────────────────────────────────────────────────────────
set_property(GLOBAL PROPERTY JOB_POOLS airi_compile_pool=2 airi_link_pool=1)
set(CMAKE_JOB_POOL_COMPILE airi_compile_pool)
set(CMAKE_JOB_POOL_LINK    airi_link_pool)

# ─────────────────────────────────────────────────────────────────────────────
# HARD-PRUNED native build (no opt-in/opt-out toggles).
#
# AIRI ships a fixed, minimal set of LLM architectures: LLaMA family
# (LLaMA, LLaMA4), Qwen family text + vision (Qwen, Qwen2, Qwen3,
# Qwen2-VL, Qwen3-VL), and Gemma family (Gemma, Gemma3 incl. vision via
# SigLIP/Gemma4V projector). Every other architecture has been physically
# removed from the source tree's build target — both the model `.cpp`
# files (LLAMA_MODEL_SOURCES below) AND every `case LLM_ARCH_*:` arm in
# `llama-model.cpp`, `llama-arch.cpp`, and `tools/mtmd/clip.cpp`.
#
# The reduction is enforced statically — there is no runtime toggle and
# no `option()` to re-enable a dropped architecture. To re-introduce one
# you must:
#   1. Add the `.cpp` back to LLAMA_MODEL_SOURCES (or MTMD_MODEL_SOURCES).
#   2. Re-add its `case LLM_ARCH_*:` arms in llama-model.cpp +
#      llama-arch.cpp (or `case PROJECTOR_TYPE_*:` in clip.cpp).
#   3. Re-add its enum to the LLM_ARCH_NAMES table in llama-arch.cpp.
# Run `python3 scripts/prune_native.py` from the repo root to regenerate
# this set if the keep-list in that script is edited.
# ─────────────────────────────────────────────────────────────────────────────

# ARM NEON (required on arm64-v8a)
if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -march=armv8-a+fp+simd")
    set(CMAKE_C_FLAGS   "${CMAKE_C_FLAGS}   -march=armv8-a+fp+simd")
    add_compile_definitions(GGML_USE_CPU_AARCH64)
    set(GGML_ARCH_SOURCES
        ${LLAMA_DIR}/ggml/src/ggml-cpu/arch/arm/quants.c
        ${LLAMA_DIR}/ggml/src/ggml-cpu/arch/arm/repack.cpp
        ${LLAMA_DIR}/ggml/src/ggml-cpu/arch/arm/cpu-feats.cpp
    )
    include_directories(${LLAMA_DIR}/ggml/src/ggml-cpu/arch/arm)
else()
    set(GGML_ARCH_SOURCES "")
endif()

# Threading
find_package(Threads REQUIRED)

# === Include paths ===
include_directories(
    ${LLAMA_DIR}/include
    ${LLAMA_DIR}/src
    ${LLAMA_DIR}/ggml/include
    ${LLAMA_DIR}/ggml/src
    ${LLAMA_DIR}/ggml/src/ggml-cpu
    ${LLAMA_DIR}/vendor
    ${LLAMA_DIR}/tools/mtmd
    ${LLAMA_DIR}/tools/mtmd/models
    ${CMAKE_SOURCE_DIR}
)

# === Preprocessor definitions ===
add_compile_definitions(
    GGML_USE_CPU
    GGML_CPU_BACKEND
    LOG_DISABLE_LOGS
    GGML_VERSION="airi-b5399"
    GGML_COMMIT="airi"
)

# Force-disable MTMD audio pipeline (header not vendored)
add_compile_definitions(MTMD_DISABLE_AUDIO)

# === GGML core sources ===
set(GGML_SOURCES
    ${LLAMA_DIR}/ggml/src/ggml.c
    ${LLAMA_DIR}/ggml/src/ggml.cpp
    ${LLAMA_DIR}/ggml/src/gguf.cpp
    ${LLAMA_DIR}/ggml/src/ggml-alloc.c
    ${LLAMA_DIR}/ggml/src/ggml-quants.c
    ${LLAMA_DIR}/ggml/src/ggml-threading.cpp
    ${LLAMA_DIR}/ggml/src/ggml-backend.cpp
    ${LLAMA_DIR}/ggml/src/ggml-backend-reg.cpp
    ${LLAMA_DIR}/ggml/src/ggml-backend-meta.cpp
    ${LLAMA_DIR}/ggml/src/ggml-opt.cpp
)

# ggml-backend-dl.cpp requires dlopen; include only if available
set(GGML_SOURCES ${GGML_SOURCES}
    ${LLAMA_DIR}/ggml/src/ggml-backend-dl.cpp
)

# === ggml-cpu backend ===
set(GGML_CPU_SOURCES
    ${LLAMA_DIR}/ggml/src/ggml-cpu/ggml-cpu.c
    ${LLAMA_DIR}/ggml/src/ggml-cpu/ggml-cpu.cpp
    ${LLAMA_DIR}/ggml/src/ggml-cpu/ops.cpp
    ${LLAMA_DIR}/ggml/src/ggml-cpu/binary-ops.cpp
    ${LLAMA_DIR}/ggml/src/ggml-cpu/unary-ops.cpp
    ${LLAMA_DIR}/ggml/src/ggml-cpu/repack.cpp
    ${LLAMA_DIR}/ggml/src/ggml-cpu/traits.cpp
    ${LLAMA_DIR}/ggml/src/ggml-cpu/vec.cpp
    ${LLAMA_DIR}/ggml/src/ggml-cpu/quants.c
    ${LLAMA_DIR}/ggml/src/ggml-cpu/hbm.cpp
    ${LLAMA_DIR}/ggml/src/ggml-cpu/amx/amx.cpp
    ${LLAMA_DIR}/ggml/src/ggml-cpu/amx/mmq.cpp
)

# llamafile sgemm (NEON-optimized GEMM)
if(EXISTS ${LLAMA_DIR}/ggml/src/ggml-cpu/llamafile/sgemm.cpp)
    set(GGML_CPU_SOURCES ${GGML_CPU_SOURCES}
        ${LLAMA_DIR}/ggml/src/ggml-cpu/llamafile/sgemm.cpp
    )
    include_directories(${LLAMA_DIR}/ggml/src/ggml-cpu/llamafile)
endif()

# ─────────────────────────────────────────────────────────────────────────────
# === llama model sources — HARD-PRUNED minimal set (9 of the original 113) ===
#
# This is the ONLY source-of-truth list of model architectures compiled into
# `airi_native.so`. Adding a file here without also patching the dispatcher
# in llama-model.cpp / llama-arch.cpp will leave the new `case LLM_ARCH_*:`
# arms dangling (or, if missing, the loaded GGUF will resolve to UNKNOWN).
#
# The 104 architectures NOT in this list have been physically removed from
# the dispatcher (zero `case LLM_ARCH_*:` arms remain that reference their
# `llm_build_X` symbols), so the linker has no incoming edge that could
# pull their .o back in even if a stray reference slipped through.
# ─────────────────────────────────────────────────────────────────────────────
set(LLAMA_MODEL_SOURCES
    # LLaMA family (text)
    ${LLAMA_DIR}/src/models/llama.cpp
    ${LLAMA_DIR}/src/models/llama4.cpp

    # Qwen family (text)
    ${LLAMA_DIR}/src/models/qwen.cpp
    ${LLAMA_DIR}/src/models/qwen2.cpp
    ${LLAMA_DIR}/src/models/qwen3.cpp

    # Qwen family (vision)
    ${LLAMA_DIR}/src/models/qwen2vl.cpp
    ${LLAMA_DIR}/src/models/qwen3vl.cpp

    # Gemma family (text + vision via Gemma3)
    ${LLAMA_DIR}/src/models/gemma.cpp
    ${LLAMA_DIR}/src/models/gemma2-iswa.cpp
    ${LLAMA_DIR}/src/models/gemma3.cpp
)

set(LLAMA_SOURCES
    ${LLAMA_DIR}/src/llama.cpp
    ${LLAMA_DIR}/src/llama-adapter.cpp
    ${LLAMA_DIR}/src/llama-arch.cpp
    ${LLAMA_DIR}/src/llama-batch.cpp
    ${LLAMA_DIR}/src/llama-chat.cpp
    ${LLAMA_DIR}/src/llama-context.cpp
    ${LLAMA_DIR}/src/llama-cparams.cpp
    ${LLAMA_DIR}/src/llama-grammar.cpp
    ${LLAMA_DIR}/src/llama-graph.cpp
    ${LLAMA_DIR}/src/llama-hparams.cpp
    ${LLAMA_DIR}/src/llama-impl.cpp
    ${LLAMA_DIR}/src/llama-io.cpp
    ${LLAMA_DIR}/src/llama-kv-cache.cpp
    ${LLAMA_DIR}/src/llama-kv-cache-iswa.cpp
    ${LLAMA_DIR}/src/llama-memory.cpp
    ${LLAMA_DIR}/src/llama-memory-hybrid.cpp
    ${LLAMA_DIR}/src/llama-memory-hybrid-iswa.cpp
    ${LLAMA_DIR}/src/llama-memory-recurrent.cpp
    ${LLAMA_DIR}/src/llama-mmap.cpp
    ${LLAMA_DIR}/src/llama-model.cpp
    ${LLAMA_DIR}/src/llama-model-loader.cpp
    ${LLAMA_DIR}/src/llama-model-saver.cpp
    ${LLAMA_DIR}/src/llama-quant.cpp
    ${LLAMA_DIR}/src/llama-sampler.cpp
    ${LLAMA_DIR}/src/llama-vocab.cpp
    ${LLAMA_DIR}/src/unicode.cpp
    ${LLAMA_DIR}/src/unicode-data.cpp
    ${LLAMA_MODEL_SOURCES}
)

# ─────────────────────────────────────────────────────────────────────────────
# === MTMD multimodal (vision-only) — HARD-PRUNED to 5 of the original 26 ===
#
# Audio multimodal architectures (whisper-enc, conformer, qwen3a, gemma4a,
# kimik25, ultravox, voxtral, music-flamingo, etc.) are excluded entirely.
# Vision architectures kept correspond 1:1 to LLM models we ship:
#   llava.cpp    → MLP / MLP_NORM (legacy LLaVA-family fallback)
#   siglip.cpp   → SigLIP encoder (Gemma3 vision)
#   gemma4v.cpp  → Gemma3/Gemma4V projector
#   qwen2vl.cpp  → Qwen2-VL / Qwen2.5-VL projector
#   qwen3vl.cpp  → Qwen3-VL projector
# Every other `case PROJECTOR_TYPE_*:` in clip.cpp's dispatcher has been
# physically removed; no `clip_graph_X` symbol survives that points at a
# dropped translation unit.
# ─────────────────────────────────────────────────────────────────────────────
set(MTMD_DIR ${LLAMA_DIR}/tools/mtmd)
set(AIRI_DISABLE_MTMD OFF CACHE BOOL "Disable multimodal (mtmd) build")
set(MTMD_SOURCES "")
set(AIRI_HAS_MTMD 0)
if(NOT AIRI_DISABLE_MTMD AND EXISTS ${MTMD_DIR}/mtmd.cpp)
    set(MTMD_MODEL_SOURCES
        ${MTMD_DIR}/models/llava.cpp
        ${MTMD_DIR}/models/siglip.cpp
        ${MTMD_DIR}/models/gemma4v.cpp
        ${MTMD_DIR}/models/qwen2vl.cpp
        ${MTMD_DIR}/models/qwen3vl.cpp
    )
    set(MTMD_SOURCES
        ${MTMD_DIR}/mtmd.cpp
        ${MTMD_DIR}/mtmd-image.cpp
        ${MTMD_DIR}/mtmd-helper.cpp
        ${MTMD_DIR}/clip.cpp
        ${MTMD_MODEL_SOURCES}
    )
    set(AIRI_HAS_MTMD 1)
    add_compile_definitions(AIRI_HAS_MTMD=1)
    # mtmd uses stb_image / miniaudio in helper — silence the upstream warnings
    # that already require -Wno-* in their own CMakeLists.
    add_compile_options(-Wno-cast-qual -Wno-missing-prototypes
                        -Wno-unused-function -Wno-unused-variable)

    # ─────────────────────────────────────────────────────────────────────────
    # MTMD debug-instrumentation overlay scoping.
    #
    # Background: `mtmd.cpp` historically did `#include "debug/mtmd-debug.h"`
    # unconditionally, but that header was a developer-local file that was
    # never committed. Result: every clean Release CI build of arm64-v8a
    # failed with "fatal error: 'debug/mtmd-debug.h' file not found".
    #
    # Fix is layered:
    #   (a) `mtmd.cpp` now wraps the include in __has_include + provides
    #       no-op fallback macros.
    #   (b) An in-tree release-safe stub lives at
    #       `tools/mtmd/debug/mtmd-debug.h`. The MTMD_DIR include path
    #       (already added at the top of this file) makes
    #       `#include "debug/mtmd-debug.h"` resolve to it.
    #   (c) Debug builds get MTMD_ENABLE_DEBUG_INSTRUMENTATION defined so
    #       a developer who replaces the stub with a real overlay gets
    #       their tracing wired up automatically — and Release builds
    #       never see that define, so even a bad overlay can't leak in.
    #
    # The generator expression `$<$<CONFIG:Debug>:...>` is evaluated by
    # CMake at build time per-configuration, so the same buildsystem
    # serves both `assembleDebug` and `assembleRelease` cleanly without
    # cross-contamination.
    # ─────────────────────────────────────────────────────────────────────────
    add_compile_definitions(
        $<$<CONFIG:Debug>:MTMD_ENABLE_DEBUG_INSTRUMENTATION>
    )
else()
    add_compile_definitions(AIRI_HAS_MTMD=0)
endif()

# === JNI Bridge ===
set(JNI_SOURCES
    ${CMAKE_SOURCE_DIR}/LlamaBridge.cpp
)

# === Build the shared library ===
add_library(
    airi_native
    SHARED
    ${GGML_SOURCES}
    ${GGML_CPU_SOURCES}
    ${GGML_ARCH_SOURCES}
    ${LLAMA_SOURCES}
    ${MTMD_SOURCES}
    ${JNI_SOURCES}
)

# === Link libraries ===
find_library(LOG_LIB log)
find_library(DL_LIB  dl)
find_library(M_LIB   m)
find_library(ANDROID_LIB android)

target_link_libraries(
    airi_native
    ${LOG_LIB}
    ${DL_LIB}
    ${M_LIB}
    ${ANDROID_LIB}
    Threads::Threads
)

# Reduce binary size — with the dispatcher hard-pruned to 9 model archs and
# 5 vision projectors, gc-sections + --strip-all squeeze any remaining dead
# helpers out of the .so on top of the source-level reduction.
set_target_properties(airi_native PROPERTIES
    LINK_FLAGS "-Wl,--gc-sections -Wl,--strip-all"
)
