cmake_minimum_required(VERSION 3.22.1)

project(airi_native)

# Flags البناء للأندرويد
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# تعريف مسار llama.cpp
set(LLAMA_DIR ${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp)

# قائمة ملفات المصدر الشاملة والمحدثة (يدوية لضمان الاستقرار)
add_library(llama_core STATIC
    # GGML Core
    ${LLAMA_DIR}/ggml.c
    ${LLAMA_DIR}/ggml.cpp
    ${LLAMA_DIR}/ggml-alloc.c
    ${LLAMA_DIR}/ggml-backend.cpp
    ${LLAMA_DIR}/ggml-backend-reg.cpp
    ${LLAMA_DIR}/ggml-quants.c
    ${LLAMA_DIR}/ggml-threading.cpp
    ${LLAMA_DIR}/gguf.cpp
    ${LLAMA_DIR}/ggml-opt.cpp
    
    # GGML CPU Backend
    ${LLAMA_DIR}/ggml-cpu/ggml-cpu.cpp
    ${LLAMA_DIR}/ggml-cpu/binary-ops.cpp
    ${LLAMA_DIR}/ggml-cpu/hbm.cpp
    ${LLAMA_DIR}/ggml-cpu/ops.cpp
    ${LLAMA_DIR}/ggml-cpu/repack.cpp
    ${LLAMA_DIR}/ggml-cpu/traits.cpp
    ${LLAMA_DIR}/ggml-cpu/unary-ops.cpp
    ${LLAMA_DIR}/ggml-cpu/vec.cpp
    
    # Llama Core
    ${LLAMA_DIR}/llama.cpp
    ${LLAMA_DIR}/llama-adapter.cpp
    ${LLAMA_DIR}/llama-arch.cpp
    ${LLAMA_DIR}/llama-batch.cpp
    ${LLAMA_DIR}/llama-chat.cpp
    ${LLAMA_DIR}/llama-context.cpp
    ${LLAMA_DIR}/llama-cparams.cpp
    ${LLAMA_DIR}/llama-grammar.cpp
    ${LLAMA_DIR}/llama-graph.cpp
    ${LLAMA_DIR}/llama-hparams.cpp
    ${LLAMA_DIR}/llama-impl.cpp
    ${LLAMA_DIR}/llama-io.cpp
    ${LLAMA_DIR}/llama-kv-cache.cpp
    ${LLAMA_DIR}/llama-kv-cache-iswa.cpp
    ${LLAMA_DIR}/llama-memory-hybrid.cpp
    ${LLAMA_DIR}/llama-memory-hybrid-iswa.cpp
    ${LLAMA_DIR}/llama-memory-recurrent.cpp
    ${LLAMA_DIR}/llama-memory.cpp
    ${LLAMA_DIR}/llama-mmap.cpp
    ${LLAMA_DIR}/llama-model.cpp
    ${LLAMA_DIR}/llama-model-loader.cpp
    ${LLAMA_DIR}/llama-model-saver.cpp
    ${LLAMA_DIR}/llama-quant.cpp
    ${LLAMA_DIR}/llama-sampling.cpp
    ${LLAMA_DIR}/llama-vocab.cpp
    
    # Model Builders (المسار الصحيح: llama.cpp/models/)
    ${LLAMA_DIR}/models/baichuan.cpp
    ${LLAMA_DIR}/models/bert.cpp
    ${LLAMA_DIR}/models/bloom.cpp
    ${LLAMA_DIR}/models/deci.cpp
    ${LLAMA_DIR}/models/dream.cpp
    ${LLAMA_DIR}/models/falcon.cpp
    ${LLAMA_DIR}/models/llada.cpp
    ${LLAMA_DIR}/models/llama.cpp
    ${LLAMA_DIR}/models/llama-iswa.cpp
    ${LLAMA_DIR}/models/maincoder.cpp
    ${LLAMA_DIR}/models/modern-bert.cpp
    ${LLAMA_DIR}/models/mpt.cpp
    ${LLAMA_DIR}/models/neo-bert.cpp
    ${LLAMA_DIR}/models/qwen.cpp
    ${LLAMA_DIR}/models/qwen2.cpp
    ${LLAMA_DIR}/models/refact.cpp
    ${LLAMA_DIR}/models/stablelm.cpp
    
    # Unicode Support
    ${LLAMA_DIR}/unicode.cpp
    ${LLAMA_DIR}/unicode-data.cpp
)

# حقن تعريفات الإصدار
target_compile_definitions(llama_core PRIVATE
    GGML_VERSION=\"unknown\"
    GGML_COMMIT=\"android-build\"
)

# إضافة المسارات للترويسات
target_include_directories(llama_core PUBLIC
    ${LLAMA_DIR}
    ${LLAMA_DIR}/ggml-cpu
    ${LLAMA_DIR}/models
)

# مكتبة الجسر JNI
add_library(airi_native SHARED
    native-lib.cpp
)

# البحث عن مكتبة log للأندرويد
find_library(log-lib log)

# ربط المكتبات
target_link_libraries(airi_native
    llama_core
    ${log-lib}
    android
    atomic
    m
)
