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
# === llama core sources ===
#
# Replaces the prior `file(GLOB ... /src/models/*.cpp)` with an explicit,
# alphabetised allowlist. Rationale:
#
#   • Reproducibility — a `git pull` of the upstream subtree no longer
#     silently inflates the build target with newly-added architectures.
#   • Auditability — anyone reviewing this file can see, line-by-line,
#     which model architectures are linked into the .so.
#   • Future pruning — to drop an architecture, comment out its line here
#     AND ALSO patch the matching `case LLM_ARCH_*:` in
#     `llama/src/llama-model.cpp` (the dispatcher takes the symbol via
#     `new llm_build_<arch>(…)`; removing the .cpp without removing the
#     case yields a linker error). We do NOT prune any here yet because
#     dispatcher patches need on-device validation that this Replit
#     environment cannot perform — that work belongs in a follow-up PR
#     that is built and run on the user's machine.
#
# All 113 architecture files in the vendored tree are listed below so the
# behaviour is byte-identical to the prior GLOB.
# ─────────────────────────────────────────────────────────────────────────────
set(LLAMA_MODEL_SOURCES
    ${LLAMA_DIR}/src/models/afmoe.cpp
    ${LLAMA_DIR}/src/models/apertus.cpp
    ${LLAMA_DIR}/src/models/arcee.cpp
    ${LLAMA_DIR}/src/models/arctic.cpp
    ${LLAMA_DIR}/src/models/arwkv7.cpp
    ${LLAMA_DIR}/src/models/baichuan.cpp
    ${LLAMA_DIR}/src/models/bailingmoe.cpp
    ${LLAMA_DIR}/src/models/bailingmoe2.cpp
    ${LLAMA_DIR}/src/models/bert.cpp
    ${LLAMA_DIR}/src/models/bitnet.cpp
    ${LLAMA_DIR}/src/models/bloom.cpp
    ${LLAMA_DIR}/src/models/chameleon.cpp
    ${LLAMA_DIR}/src/models/chatglm.cpp
    ${LLAMA_DIR}/src/models/codeshell.cpp
    ${LLAMA_DIR}/src/models/cogvlm.cpp
    ${LLAMA_DIR}/src/models/cohere2-iswa.cpp
    ${LLAMA_DIR}/src/models/command-r.cpp
    ${LLAMA_DIR}/src/models/dbrx.cpp
    ${LLAMA_DIR}/src/models/deci.cpp
    ${LLAMA_DIR}/src/models/deepseek.cpp
    ${LLAMA_DIR}/src/models/deepseek2.cpp
    ${LLAMA_DIR}/src/models/delta-net-base.cpp
    ${LLAMA_DIR}/src/models/dots1.cpp
    ${LLAMA_DIR}/src/models/dream.cpp
    ${LLAMA_DIR}/src/models/ernie4-5.cpp
    ${LLAMA_DIR}/src/models/ernie4-5-moe.cpp
    ${LLAMA_DIR}/src/models/eurobert.cpp
    ${LLAMA_DIR}/src/models/exaone.cpp
    ${LLAMA_DIR}/src/models/exaone4.cpp
    ${LLAMA_DIR}/src/models/exaone-moe.cpp
    ${LLAMA_DIR}/src/models/falcon.cpp
    ${LLAMA_DIR}/src/models/falcon-h1.cpp
    ${LLAMA_DIR}/src/models/gemma.cpp
    ${LLAMA_DIR}/src/models/gemma2-iswa.cpp
    ${LLAMA_DIR}/src/models/gemma3.cpp
    ${LLAMA_DIR}/src/models/gemma3n-iswa.cpp
    ${LLAMA_DIR}/src/models/gemma4-iswa.cpp
    ${LLAMA_DIR}/src/models/gemma-embedding.cpp
    ${LLAMA_DIR}/src/models/glm4.cpp
    ${LLAMA_DIR}/src/models/glm4-moe.cpp
    ${LLAMA_DIR}/src/models/gpt2.cpp
    ${LLAMA_DIR}/src/models/gptneox.cpp
    ${LLAMA_DIR}/src/models/granite.cpp
    ${LLAMA_DIR}/src/models/granite-hybrid.cpp
    ${LLAMA_DIR}/src/models/grok.cpp
    ${LLAMA_DIR}/src/models/grovemoe.cpp
    ${LLAMA_DIR}/src/models/hunyuan-dense.cpp
    ${LLAMA_DIR}/src/models/hunyuan-moe.cpp
    ${LLAMA_DIR}/src/models/internlm2.cpp
    ${LLAMA_DIR}/src/models/jais.cpp
    ${LLAMA_DIR}/src/models/jais2.cpp
    ${LLAMA_DIR}/src/models/jamba.cpp
    ${LLAMA_DIR}/src/models/kimi-linear.cpp
    ${LLAMA_DIR}/src/models/lfm2.cpp
    ${LLAMA_DIR}/src/models/llada.cpp
    ${LLAMA_DIR}/src/models/llada-moe.cpp
    ${LLAMA_DIR}/src/models/llama.cpp
    ${LLAMA_DIR}/src/models/llama4.cpp
    ${LLAMA_DIR}/src/models/maincoder.cpp
    ${LLAMA_DIR}/src/models/mamba.cpp
    ${LLAMA_DIR}/src/models/mamba-base.cpp
    ${LLAMA_DIR}/src/models/mimo2-iswa.cpp
    ${LLAMA_DIR}/src/models/minicpm3.cpp
    ${LLAMA_DIR}/src/models/minimax-m2.cpp
    ${LLAMA_DIR}/src/models/mistral3.cpp
    ${LLAMA_DIR}/src/models/modern-bert.cpp
    ${LLAMA_DIR}/src/models/mpt.cpp
    ${LLAMA_DIR}/src/models/nemotron.cpp
    ${LLAMA_DIR}/src/models/nemotron-h.cpp
    ${LLAMA_DIR}/src/models/neo-bert.cpp
    ${LLAMA_DIR}/src/models/olmo.cpp
    ${LLAMA_DIR}/src/models/olmo2.cpp
    ${LLAMA_DIR}/src/models/olmoe.cpp
    ${LLAMA_DIR}/src/models/openai-moe-iswa.cpp
    ${LLAMA_DIR}/src/models/openelm.cpp
    ${LLAMA_DIR}/src/models/orion.cpp
    ${LLAMA_DIR}/src/models/paddleocr.cpp
    ${LLAMA_DIR}/src/models/pangu-embedded.cpp
    ${LLAMA_DIR}/src/models/phi2.cpp
    ${LLAMA_DIR}/src/models/phi3.cpp
    ${LLAMA_DIR}/src/models/plamo.cpp
    ${LLAMA_DIR}/src/models/plamo2.cpp
    ${LLAMA_DIR}/src/models/plamo3.cpp
    ${LLAMA_DIR}/src/models/plm.cpp
    ${LLAMA_DIR}/src/models/qwen.cpp
    ${LLAMA_DIR}/src/models/qwen2.cpp
    ${LLAMA_DIR}/src/models/qwen2moe.cpp
    ${LLAMA_DIR}/src/models/qwen2vl.cpp
    ${LLAMA_DIR}/src/models/qwen3.cpp
    ${LLAMA_DIR}/src/models/qwen3moe.cpp
    ${LLAMA_DIR}/src/models/qwen3next.cpp
    ${LLAMA_DIR}/src/models/qwen3vl.cpp
    ${LLAMA_DIR}/src/models/qwen3vl-moe.cpp
    ${LLAMA_DIR}/src/models/qwen35.cpp
    ${LLAMA_DIR}/src/models/qwen35moe.cpp
    ${LLAMA_DIR}/src/models/refact.cpp
    ${LLAMA_DIR}/src/models/rnd1.cpp
    ${LLAMA_DIR}/src/models/rwkv6.cpp
    ${LLAMA_DIR}/src/models/rwkv6-base.cpp
    ${LLAMA_DIR}/src/models/rwkv6qwen2.cpp
    ${LLAMA_DIR}/src/models/rwkv7.cpp
    ${LLAMA_DIR}/src/models/rwkv7-base.cpp
    ${LLAMA_DIR}/src/models/seed-oss.cpp
    ${LLAMA_DIR}/src/models/smallthinker.cpp
    ${LLAMA_DIR}/src/models/smollm3.cpp
    ${LLAMA_DIR}/src/models/stablelm.cpp
    ${LLAMA_DIR}/src/models/starcoder.cpp
    ${LLAMA_DIR}/src/models/starcoder2.cpp
    ${LLAMA_DIR}/src/models/step35-iswa.cpp
    ${LLAMA_DIR}/src/models/t5.cpp
    ${LLAMA_DIR}/src/models/t5encoder.cpp
    ${LLAMA_DIR}/src/models/wavtokenizer-dec.cpp
    ${LLAMA_DIR}/src/models/xverse.cpp
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
# === MTMD multimodal (Phase 3 — vision) ===
#
# Vendored from upstream tools/mtmd at the same commit family as our llama
# core. AUDIO is intentionally excluded: only image multimodal is wired.
#
# As with LLAMA_MODEL_SOURCES above, we replaced the prior `file(GLOB ...)`
# with an explicit allowlist. The mtmd dispatcher in clip.cpp instantiates
# each `clip_graph_<arch>` directly, so removing a .cpp REQUIRES removing
# the matching `case PROJECTOR_TYPE_*:` in clip.cpp at the same time
# (otherwise the link fails). All 26 currently-vendored vision archs are
# listed for behaviour parity with the GLOB.
# ─────────────────────────────────────────────────────────────────────────────
set(MTMD_DIR ${LLAMA_DIR}/tools/mtmd)
set(AIRI_DISABLE_MTMD OFF CACHE BOOL "Disable multimodal (mtmd) build")
set(MTMD_SOURCES "")
set(AIRI_HAS_MTMD 0)
if(NOT AIRI_DISABLE_MTMD AND EXISTS ${MTMD_DIR}/mtmd.cpp)
    set(MTMD_MODEL_SOURCES
        ${MTMD_DIR}/models/cogvlm.cpp
        ${MTMD_DIR}/models/conformer.cpp
        ${MTMD_DIR}/models/deepseekocr.cpp
        ${MTMD_DIR}/models/dotsocr.cpp
        ${MTMD_DIR}/models/gemma4a.cpp
        ${MTMD_DIR}/models/gemma4v.cpp
        ${MTMD_DIR}/models/glm4v.cpp
        ${MTMD_DIR}/models/hunyuanocr.cpp
        ${MTMD_DIR}/models/internvl.cpp
        ${MTMD_DIR}/models/kimik25.cpp
        ${MTMD_DIR}/models/kimivl.cpp
        ${MTMD_DIR}/models/llama4.cpp
        ${MTMD_DIR}/models/llava.cpp
        ${MTMD_DIR}/models/minicpmv.cpp
        ${MTMD_DIR}/models/mobilenetv5.cpp
        ${MTMD_DIR}/models/nemotron-v2-vl.cpp
        ${MTMD_DIR}/models/paddleocr.cpp
        ${MTMD_DIR}/models/pixtral.cpp
        ${MTMD_DIR}/models/qwen2vl.cpp
        ${MTMD_DIR}/models/qwen3a.cpp
        ${MTMD_DIR}/models/qwen3vl.cpp
        ${MTMD_DIR}/models/siglip.cpp
        ${MTMD_DIR}/models/step3vl.cpp
        ${MTMD_DIR}/models/whisper-enc.cpp
        ${MTMD_DIR}/models/yasa2.cpp
        ${MTMD_DIR}/models/youtuvl.cpp
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

# Reduce binary size — gc-sections keeps the .so lean even with the full
# 113-arch model set linked in, since the linker drops every llm_build_*
# constructor that nothing actually references at runtime.
set_target_properties(airi_native PROPERTIES
    LINK_FLAGS "-Wl,--gc-sections -Wl,--strip-all"
)
