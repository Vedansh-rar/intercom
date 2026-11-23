cmake_minimum_required(VERSION 3.22.1)
project(intercom_native LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ---- libopus, a pinned official release, fetched at configure time.
# Only the library is built: no programs, tests, docs or the autotools tree.
# Debug builds compile with -O0, which makes libopus far too slow to test on a phone
# (it says so itself at build time). Keep the native code optimised in every build type.
set(CMAKE_C_FLAGS_DEBUG "${CMAKE_C_FLAGS_DEBUG} -O2")
set(CMAKE_CXX_FLAGS_DEBUG "${CMAKE_CXX_FLAGS_DEBUG} -O2")

include(FetchContent)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(OPUS_BUILD_PROGRAMS OFF CACHE BOOL "" FORCE)
set(OPUS_BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(OPUS_INSTALL_PKG_CONFIG_MODULE OFF CACHE BOOL "" FORCE)
set(OPUS_INSTALL_CMAKE_CONFIG_MODULE OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)

FetchContent_Declare(
    opus
    GIT_REPOSITORY https://github.com/xiph/opus.git
    GIT_TAG        v1.5.2
    GIT_SHALLOW    TRUE
)
FetchContent_MakeAvailable(opus)

# ---- Oboe, from the Prefab package (com.google.oboe:oboe in build.gradle.kts).
find_package(oboe REQUIRED CONFIG)

add_library(intercom_native SHARED
    jni_bridge.cpp
    opus_coder.cpp
    audio_engine.cpp
)

target_link_libraries(intercom_native
    PRIVATE
        opus
        oboe::oboe
        log
)

target_compile_options(intercom_native PRIVATE -Wall -Wextra -O2)
