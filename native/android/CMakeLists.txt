# Builds libcactusai_native.so: the JNI shim, statically linked to the official Needle engine for
# Android (arm64-v8a) that scripts/fetch-engine.* puts in native-engine/android-arm64/.
cmake_minimum_required(VERSION 3.22.1)
project(cactusai_native LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(CACTUSAI_ENGINE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../native-engine/android-arm64"
    CACHE PATH "Folder holding the Needle engine static library for Android")
option(CACTUSAI_ALLOW_MISSING_ENGINE "Compile without the engine (compile checks only; the result cannot run the AI)" OFF)

add_library(cactusai_native SHARED ../shim/cactusai_jni.cpp)
target_include_directories(cactusai_native PRIVATE ../shim)
target_compile_options(cactusai_native PRIVATE -Wall -Wextra -fvisibility=hidden)

# Keep only the JNI entry points exported.
set_target_properties(cactusai_native PROPERTIES CXX_VISIBILITY_PRESET hidden)

file(GLOB ENGINE_ARCHIVES "${CACTUSAI_ENGINE_DIR}/*.a")

if(ENGINE_ARCHIVES)
    message(STATUS "CactusAI: linking Needle engine archives: ${ENGINE_ARCHIVES}")
    target_compile_definitions(cactusai_native PRIVATE CACTUSAI_ENGINE_LINKED=1)
    # A group lets the linker resolve references between archives in any order.
    target_link_libraries(cactusai_native PRIVATE -Wl,--start-group ${ENGINE_ARCHIVES} -Wl,--end-group)
elseif(CACTUSAI_ALLOW_MISSING_ENGINE)
    message(WARNING "CactusAI: no engine library in ${CACTUSAI_ENGINE_DIR}. Building WITHOUT the AI engine. "
                    "This build can not transcribe or interpret anything.")
    target_compile_definitions(cactusai_native PRIVATE CACTUSAI_ENGINE_LINKED=0)
else()
    message(FATAL_ERROR
        "The Needle engine library was not found in:\n  ${CACTUSAI_ENGINE_DIR}\n"
        "Run scripts/fetch-engine.ps1 (Windows) or scripts/fetch-engine.sh first. See README.md.")
endif()

find_library(LOG_LIB log)
target_link_libraries(cactusai_native PRIVATE ${LOG_LIB} m dl)

# Android 15+ devices may use 16 KB pages.
target_link_options(cactusai_native PRIVATE -Wl,-z,max-page-size=16384 -Wl,--gc-sections)
