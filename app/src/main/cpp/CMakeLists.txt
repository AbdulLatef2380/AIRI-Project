cmake_minimum_required(VERSION 3.22.1)

project(airi_native LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# تحسينات أداء
add_compile_options(
        -O3
        -fvisibility=hidden
        -fdata-sections
        -ffunction-sections
        -fno-exceptions
        -fno-rtti
)

# منع تحذيرات Android
add_definitions(-DANDROID)

# مسار llama.cpp
set(LLAMA_DIR ${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp)

# أضف llama كمشروع فرعي
add_subdirectory(${LLAMA_DIR})

# مكتبة AIRI Native
add_library(airi_native SHARED
        airi_native.cpp
)

# ربط المكتبات
target_link_libraries(airi_native
        llama
        android
        log
)

# تضمين الهيدرز
target_include_directories(airi_native PRIVATE
        ${LLAMA_DIR}
        ${LLAMA_DIR}/include
)
