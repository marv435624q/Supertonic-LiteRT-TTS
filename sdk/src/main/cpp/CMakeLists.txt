cmake_minimum_required(VERSION 3.22.1)
project(supertonic_tts_android LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

if(NOT DEFINED SPEECH_CORE_DIR)
    message(FATAL_ERROR "SPEECH_CORE_DIR is required")
endif()
if(NOT DEFINED LITERT_DIR)
    message(FATAL_ERROR "LITERT_DIR is required")
endif()

set(SPEECH_CORE_WITH_ONNX OFF CACHE BOOL "" FORCE)
set(SPEECH_CORE_WITH_LITERT ON CACHE BOOL "" FORCE)
set(SPEECH_CORE_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(SPEECH_CORE_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(SPEECH_CORE_WITH_HF_DOWNLOAD OFF CACHE BOOL "" FORCE)

add_subdirectory(${SPEECH_CORE_DIR} ${CMAKE_CURRENT_BINARY_DIR}/speech_core)

add_library(speech_android SHARED jni_bridge.cpp)
target_compile_definitions(speech_android PRIVATE SPEECH_ANDROID_WITH_LITERT)
target_compile_options(speech_android PRIVATE -O2)
# Keep the JNI bridge loadable on Android devices that use 16 KB memory pages.
# NDK r29 already defaults to flexible-page-size output, but make the contract
# explicit so a future toolchain change cannot silently regress it.
target_link_options(speech_android PRIVATE
    "-Wl,-z,max-page-size=16384"
    "-Wl,-z,common-page-size=16384"
)
target_link_libraries(speech_android PRIVATE speech_core_models_litert android log)
