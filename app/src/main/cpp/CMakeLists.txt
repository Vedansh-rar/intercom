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

# ---- RNNoise (Xiph.Org, BSD-3-Clause), the optional "AI noise reduction".
# Pinned to the commit of 2021-03-12, the last long-stable state of the library, whose
# trained model is a source file in the repository (later versions download it from a
# separate server at build time, which would add a second thing to trust). The upstream
# repository has no CMake build, so the handful of C files are compiled here.
FetchContent_Declare(
    rnnoise
    GIT_REPOSITORY https://github.com/xiph/rnnoise.git
    GIT_TAG        7f449bf8bd3b933891d12c30112268c4090e4d59
)
FetchContent_GetProperties(rnnoise)
if(NOT rnnoise_POPULATED)
    FetchContent_Populate(rnnoise)
endif()

add_library(rnnoise_lib STATIC
    ${rnnoise_SOURCE_DIR}/src/denoise.c
    ${rnnoise_SOURCE_DIR}/src/rnn.c
    ${rnnoise_SOURCE_DIR}/src/rnn_data.c
    ${rnnoise_SOURCE_DIR}/src/pitch.c
    ${rnnoise_SOURCE_DIR}/src/kiss_fft.c
    ${rnnoise_SOURCE_DIR}/src/celt_lpc.c
)
target_include_directories(rnnoise_lib
    PUBLIC  ${rnnoise_SOURCE_DIR}/include
    PRIVATE ${rnnoise_SOURCE_DIR}/src
)
# RNNoise's FFT and pitch code is a copy of code that libopus also contains, under the same
# global names (opus_fft_impl and others), so linking both into one library fails with
# duplicate symbols. Give every RNNoise symbol except its public rnnoise_* API a private name.
set(RNNOISE_PRIVATE_SYMBOLS
    _celt_autocorr _celt_lpc celt_fir celt_iir celt_pitch_xcorr common
    compute_band_corr compute_band_energy compute_dense compute_gru compute_rnn
    interp_band_gain opus_fft_alloc opus_fft_alloc_arch_c opus_fft_alloc_twiddles
    opus_fft_c opus_fft_free opus_fft_free_arch_c opus_fft_impl opus_ifft_c
    pitch_downsample pitch_filter pitch_search remove_doubling)
foreach(sym IN LISTS RNNOISE_PRIVATE_SYMBOLS)
    string(REGEX REPLACE "^_" "" bare "${sym}")
    target_compile_definitions(rnnoise_lib PRIVATE "${sym}=rnnoise_priv_${bare}")
endforeach()

# Third-party code: optimise it, and do not hold it to this project's warning level.
target_compile_options(rnnoise_lib PRIVATE -O3 -w)
# It is linked into a shared library, so its objects must be position independent.
set_target_properties(rnnoise_lib PROPERTIES POSITION_INDEPENDENT_CODE ON)

# ---- Oboe, from the Prefab package (com.google.oboe:oboe in build.gradle.kts).
find_package(oboe REQUIRED CONFIG)

add_library(intercom_native SHARED
    jni_bridge.cpp
    opus_coder.cpp
    audio_engine.cpp
    rnnoise_denoiser.cpp
)

target_link_libraries(intercom_native
    PRIVATE
        opus
        rnnoise_lib
        oboe::oboe
        log
)

target_compile_options(intercom_native PRIVATE -Wall -Wextra -O2)
