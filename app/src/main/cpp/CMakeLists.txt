cmake_minimum_required(VERSION 3.22.1)
project("airi_native" CXX C)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_C_STANDARD 11)

# === Build flags ===
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -O3 -ffast-math -fno-finite-math-only")
set(CMAKE_C_FLAGS   "${CMAKE_C_FLAGS}   -O3 -ffast-math -fno-finite-math-only")

set(LLAMA_DIR ${CMAKE_SOURCE_DIR}/llama)

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

# === llama core sources ===
file(GLOB LLAMA_MODEL_SOURCES "${LLAMA_DIR}/src/models/*.cpp")

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

# === MTMD multimodal (Phase 3 — vision) ============================================
# Vendored from upstream tools/mtmd at the same commit family as our llama core.
# AUDIO is intentionally excluded: only image multimodal is wired in this phase.
# Build is OPT-OUT via -DAIRI_DISABLE_MTMD=ON so we can fall back if the upstream
# headers ever drift; capability detection on the Kotlin side already gates the
# picker by the loaded model's profile, so a no-mtmd build still ships honestly.
set(MTMD_DIR ${LLAMA_DIR}/tools/mtmd)
set(AIRI_DISABLE_MTMD OFF CACHE BOOL "Disable multimodal (mtmd) build")
set(MTMD_SOURCES "")
set(AIRI_HAS_MTMD 0)
if(NOT AIRI_DISABLE_MTMD AND EXISTS ${MTMD_DIR}/mtmd.cpp)
    file(GLOB MTMD_MODEL_SOURCES "${MTMD_DIR}/models/*.cpp")
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

# Reduce binary size
set_target_properties(airi_native PROPERTIES
    LINK_FLAGS "-Wl,--gc-sections -Wl,--strip-all"
)
