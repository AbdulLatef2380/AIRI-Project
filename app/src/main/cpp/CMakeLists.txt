cmake_minimum_required(VERSION 3.18.1)
project("airi_assistant")

# إعداد مسارات llama.cpp
set(LLAMA_DIR ${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp)

# التحقق من وجود ملفات llama.cpp
if(EXISTS "${LLAMA_DIR}/llama.cpp")
    add_library(airi_native SHARED
        airi_native.cpp
        ${LLAMA_DIR}/llama.cpp
        ${LLAMA_DIR}/ggml.c
        ${LLAMA_DIR}/ggml-alloc.c
        ${LLAMA_DIR}/ggml-backend.c
        ${LLAMA_DIR}/ggml-quants.c
    )
    target_compile_definitions(airi_native PRIVATE USE_LLAMA=1)
else()
    # إذا لم تكن موجودة، قم ببناء مكتبة وهمية لتجنب فشل التجميع
    add_library(airi_native SHARED
        airi_native.cpp
    )
    message(WARNING "llama.cpp source files not found at ${LLAMA_DIR}. Building without llama.cpp support.")
endif()

target_include_directories(airi_native PRIVATE
    ${LLAMA_DIR}
)

find_library(log-lib log)

target_link_libraries(airi_native
    ${log-lib}
    android
)
