cmake_minimum_required(VERSION 3.18.1)
project("airi_assistant")

# إعداد مسارات llama.cpp (يفترض وجودها في المجلد)
set(LLAMA_DIR ${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp)

add_library(airi_native SHARED
    airi_native.cpp
    ${LLAMA_DIR}/llama.cpp
    ${LLAMA_DIR}/ggml.c
    ${LLAMA_DIR}/ggml-alloc.c
    ${LLAMA_DIR}/ggml-backend.c
    ${LLAMA_DIR}/ggml-quants.c
)

target_include_directories(airi_native PRIVATE
    ${LLAMA_DIR}
)

find_library(log-lib log)

target_link_libraries(airi_native
    ${log-lib}
    android
)
