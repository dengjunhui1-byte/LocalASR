cmake_minimum_required(VERSION 3.18)
project(qwenasr_jni)

add_library(qwenasr_jni SHARED qwenasr_jni.cpp)

set(MNN_ROOT "${MNN_SOURCE_DIR}")
set(MNN_LIB "${MNN_BUILD_DIR}/libMNN.a")

target_include_directories(qwenasr_jni PRIVATE
    ${MNN_ROOT}/include
    ${MNN_ROOT}/transformers/llm/engine/include
    ${MNN_ROOT}/tools/audio/include
    ${MNN_ROOT}/express
)

find_library(log-lib log)
find_library(android-lib android)
find_library(dl-lib dl)

target_compile_options(qwenasr_jni PRIVATE -fvisibility=hidden -fno-exceptions -fno-rtti)
target_compile_definitions(qwenasr_jni PRIVATE LLM_SUPPORT_AUDIO)

target_link_options(qwenasr_jni PRIVATE
    -Wl,--version-script=${CMAKE_CURRENT_SOURCE_DIR}/qwenasr.lds
    -Wl,--exclude-libs,ALL
)

target_link_libraries(qwenasr_jni
    -Wl,--whole-archive ${MNN_LIB} -Wl,--no-whole-archive
    ${log-lib}
    ${android-lib}
    ${dl-lib}
    atomic
)
