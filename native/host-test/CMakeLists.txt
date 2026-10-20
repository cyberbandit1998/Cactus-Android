# TEST-ONLY build: the JNI shim linked to a test double of the engine's C ABI, so the JVM tests can
# check string/array marshalling, UTF-8 handling and error paths on a normal computer.
# This library is never packaged into the APK.
cmake_minimum_required(VERSION 3.16)
project(cactusai_native_hosttest LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

find_package(JNI REQUIRED)

add_library(cactusai_native_hosttest SHARED ../shim/cactusai_jni.cpp fake_needle.cpp)
target_include_directories(cactusai_native_hosttest PRIVATE ../shim ${JNI_INCLUDE_DIRS})
target_compile_definitions(cactusai_native_hosttest PRIVATE CACTUSAI_ENGINE_LINKED=1)
target_compile_options(cactusai_native_hosttest PRIVATE -Wall -Wextra -Werror)
