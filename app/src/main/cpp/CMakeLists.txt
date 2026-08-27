# ============================================================================
# RPCS4 Android - NDK build
#
# Produces librpcs4_android.so, linking the vendored/submoduled PS4 emulation
# core against an SDL-compat shim layer that maps window/audio/input APIs onto
# ANativeWindow + AAudio + JNI-fed virtual pads.
#
# Layout:
#   compat/        - shadow headers (SDL.h) + platform backends  [ours]
#   jni/           - JNI bridge                                  [ours]
#   core/          - vendored snapshot via scripts/vendor_core.sh [generated]
#   OR             - <repoRoot>/rpcs4/core git submodule          [upstream]
#
# Dependencies resolve from whichever of the two trees exists first:
#   <vendored>/../Dependencies  or  <repoRoot>/rpcs4/Dependencies
#
# ABI policy: x86_64 only (see app/build.gradle.kts and PORTING.md).
# ============================================================================

cmake_minimum_required(VERSION 3.22.1)

project(rpcs4 LANGUAGES CXX C)

set(CMAKE_CXX_STANDARD 23)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# Vulkan platform extension macros are required before any vulkan.h inclusion.
add_definitions(-DVK_USE_PLATFORM_ANDROID_KHR)
add_definitions(-DVMA_STATIC_VULKAN_FUNCTIONS=0)
add_definitions(-DVMA_DYNAMIC_VULKAN_FUNCTIONS=0)
if(UNIX)
    add_definitions(-D_GNU_SOURCE)
endif()

# ----------------------------------------------------------------------------
# Resolve upstream source trees
# ----------------------------------------------------------------------------

set(RPCS4_ANDROID_ROOT ${CMAKE_CURRENT_SOURCE_DIR}/../../../..)
get_filename_component(RPCS4_ANDROID_ROOT "${RPCS4_ANDROID_ROOT}" ABSOLUTE)

function(resolve_dir out_var first_candidate)
    set(candidates ${first_candidate} ${ARGN})
    foreach(dir IN LISTS candidates)
        get_filename_component(ABS "${dir}" ABSOLUTE)
        if(EXISTS "${ABS}")
            set(${out_var} "${ABS}" PARENT_SCOPE)
            return()
        endif()
    endforeach()
endfunction()

resolve_dir(CORE_ROOT
    ${CMAKE_CURRENT_SOURCE_DIR}/core                       # vendored snapshot
    ${RPCS4_ANDROID_ROOT}/rpcs4/core                       # git submodule
)
resolve_dir(DEPS_DIR
    ${CMAKE_CURRENT_SOURCE_DIR}/Dependencies               # vendored snapshot
    ${CORE_ROOT}/../Dependencies
    ${RPCS4_ANDROID_ROOT}/rpcs4/Dependencies
)

if(NOT EXISTS "${DEPS_DIR}")
    message(FATAL_ERROR
        "Upstream dependencies not found.\n"
        "Either initialize the rpcs4 submodule:\n"
        "    git submodule update --init --recursive\n"
        "Or snapshot the core into this repo:\n"
        "    ./scripts/vendor_core.sh")
endif()

message(STATUS "Core root:     ${CORE_ROOT}")
message(STATUS "Deps dir:      ${DEPS_DIR}")

# ----------------------------------------------------------------------------
# Collect core sources (mirrors upstream core/CMakeLists.txt exactly,
# minus: Emulator.cpp [Qt], ChonkyStation4.cpp [CLI main], NVIDIA aftermath
# [desktop-only profiler]) plus third-party .c deps.
# ----------------------------------------------------------------------------

file(GLOB CORE_ROOT_SOURCES           ${CORE_ROOT}/*.cpp)
list(FILTER CORE_ROOT_SOURCES EXCLUDE REGEX ".*ChonkyStation4\\.cpp$")
list(FILTER CORE_ROOT_SOURCES EXCLUDE REGEX ".*Emulator\\.cpp$")

file(GLOB CORE_COMMON_SOURCES         ${CORE_ROOT}/Common/*.cpp)
file(GLOB CORE_GCN_SOURCES            ${CORE_ROOT}/GCN/*.cpp)
file(GLOB CORE_GCN_SHADER_SOURCES     ${CORE_ROOT}/GCN/Shader/*.cpp)
file(GLOB CORE_GCN_VULKAN_SOURCES     ${CORE_ROOT}/GCN/Backends/Vulkan/*.cpp)
file(GLOB CORE_GCN_DETILER_C_SOURCES  ${CORE_ROOT}/GCN/Detiler/*.c)
file(GLOB CORE_GCN_DETILER_GNM_SOURCES ${CORE_ROOT}/GCN/Detiler/gnm/*.c)

file(GLOB CORE_LOADERS_SOURCES        ${CORE_ROOT}/Loaders/*.cpp)
file(GLOB CORE_LOADERS_ELF_SOURCES    ${CORE_ROOT}/Loaders/ELF/*.cpp)
file(GLOB CORE_LOADERS_LINKER_SOURCES ${CORE_ROOT}/Loaders/Linker/*.cpp)
file(GLOB CORE_LOADERS_APP_SOURCES    ${CORE_ROOT}/Loaders/App/*.cpp)
file(GLOB CORE_LOADERS_SFO_SOURCES    ${CORE_ROOT}/Loaders/SFO/*.cpp)

file(GLOB CORE_OS_SOURCES             ${CORE_ROOT}/OS/*.cpp)
file(GLOB CORE_PSN_SOURCES            ${CORE_ROOT}/PSN/Providers/*/*.cpp)
file(GLOB LIBS_SUBDIRS RELATIVE ${CORE_ROOT}/OS/Libraries ${CORE_ROOT}/OS/Libraries/*)
set(LIB_SOURCES "")
foreach(lib ${LIBS_SUBDIRS})
    if(IS_DIRECTORY ${CORE_ROOT}/OS/Libraries/${lib})
        file(GLOB TMP ${CORE_ROOT}/OS/Libraries/${lib}/*.cpp
                      ${CORE_ROOT}/OS/Libraries/${lib}/*/*.cpp)
        list(APPEND LIB_SOURCES ${TMP})
    endif()
endforeach()

# Third-party compiled straight into the static lib (same as desktop build).
file(GLOB DEP_XXHASH_SOURCES ${DEPS_DIR}/xxHash/xxhash.c)
file(GLOB DEP_MINIZ_SOURCES  ${DEPS_DIR}/miniz/miniz.c
                             ${DEPS_DIR}/miniz/miniz_tinfl.c
                             ${DEPS_DIR}/miniz/miniz_tdef.c
                             ${DEPS_DIR}/miniz/miniz_zip.c)
file(GLOB DEP_ZYDIS_SOURCES  ${DEPS_DIR}/zydis/src/*.c
                             ${DEPS_DIR}/zydis/dependencies/zycore/src/*.c
                             ${DEPS_DIR}/zydis/dependencies/zycore/src/API/*.c)
set(DEP_LIBCO_SOURCE ${DEPS_DIR}/cppco/thirdparty/libco/libco.c)

set(ALL_CORE_SOURCES
    ${CORE_ROOT_SOURCES}
    ${CORE_COMMON_SOURCES}
    ${CORE_GCN_SOURCES}
    ${CORE_GCN_SHADER_SOURCES}
    ${CORE_GCN_VULKAN_SOURCES}
    ${CORE_GCN_DETILER_C_SOURCES}
    ${CORE_GCN_DETILER_GNM_SOURCES}
    ${CORE_LOADERS_SOURCES}
    ${CORE_LOADERS_ELF_SOURCES}
    ${CORE_LOADERS_LINKER_SOURCES}
    ${CORE_LOADERS_APP_SOURCES}
    ${CORE_LOADERS_SFO_SOURCES}
    ${CORE_OS_SOURCES}
    ${CORE_PSN_SOURCES}
    ${LIB_SOURCES}
    ${DEP_XXHASH_SOURCES}
    ${DEP_MINIZ_SOURCES}
    ${DEP_ZYDIS_SOURCES}
    ${DEP_LIBCO_SOURCE}
)

list(REMOVE_DUPLICATES ALL_CORE_SOURCES)
if(NOT ALL_CORE_SOURCES)
    message(FATAL_ERROR "No core sources found under ${CORE_ROOT}")
endif()

# ----------------------------------------------------------------------------
# Include path resolution - mirrors upstream root CMakeLists.txt
# ----------------------------------------------------------------------------

set(UPSTREAM_INCLUDE_DIRS
    ${RPCS4_ANDROID_ROOT}/rpcs4      # angle-bracket includes like <Common.hpp>
    ${CMAKE_SOURCE_DIR}
    ${CORE_ROOT}
    ${CORE_ROOT}/Common
    ${CORE_ROOT}/GCN
    ${DEPS_DIR}
    ${DEPS_DIR}/ELFIO
    ${DEPS_DIR}/TinySHA1
    ${DEPS_DIR}/xbyak
    ${DEPS_DIR}/xxHash
    ${DEPS_DIR}/cpp-httplib
    ${DEPS_DIR}/asio/asio/include
    ${DEPS_DIR}/cppco/include
    ${DEPS_DIR}/cppco/thirdparty/libco
    ${DEPS_DIR}/miniz
    ${DEPS_DIR}/VulkanMemoryAllocator/include
    ${DEPS_DIR}/glslang
    ${DEPS_DIR}/glslang/glslang
    ${DEPS_DIR}/Dolphin
    ${DEPS_DIR}/zydis/include
    ${DEPS_DIR}/zydis/src
    ${DEPS_DIR}/zydis/dependencies/zycore/include
    ${DEPS_DIR}/SDL/src/video/khronos
    ${DEPS_DIR}/SDL/src/video/khronos/vulkan
)

set(FILTERED_INCLUDE_DIRS "")
foreach(_dir ${UPSTREAM_INCLUDE_DIRS})
    if(EXISTS "${_dir}")
        list(APPEND FILTERED_INCLUDE_DIRS "${_dir}")
    else()
        message(STATUS "Skipping missing include dir: ${_dir}")
    endif()
endforeach()

# ----------------------------------------------------------------------------
# glslang (shader translation backend used by GCN decompiler)
# ----------------------------------------------------------------------------

set(ENABLE_GLSLANG_BINARIES OFF CACHE BOOL "" FORCE)
set(GLSLANG_TESTS OFF CACHE BOOL "" FORCE)
set(ENABLE_OPT OFF CACHE BOOL "" FORCE)
set(BUILD_EXTERNAL OFF CACHE BOOL "" FORCE)

if(EXISTS "${DEPS_DIR}/glslang/CMakeLists.txt")
    add_subdirectory(${DEPS_DIR}/glslang glslang-build EXCLUDE_FROM_ALL)
else()
    message(WARNING "glslang submodule not populated; shader compilation will not link")
endif()

# ----------------------------------------------------------------------------
# Static core library
# ----------------------------------------------------------------------------

add_library(rpcs4_core STATIC ${ALL_CORE_SOURCES})

# miniz export shim (identical trick to upstream to avoid touching submodule).
file(WRITE "${CMAKE_BINARY_DIR}/miniz_export.h" "#pragma once\n#define MINIZ_EXPORT\n")

target_include_directories(rpcs4_core PUBLIC
    # compat/ MUST come first so <SDL.h>/<SDL_vulkan.h> hit our shim.
    ${CMAKE_CURRENT_SOURCE_DIR}/compat
    ${FILTERED_INCLUDE_DIRS}
    ${CMAKE_BINARY_DIR}
)

target_compile_definitions(rpcs4_core PRIVATE MINIZ_EXPORT=)

set_source_files_properties(
    ${CORE_GCN_DETILER_C_SOURCES}
    ${CORE_GCN_DETILER_GNM_SOURCES}
    ${DEP_XXHASH_SOURCES}
    ${DEP_MINIZ_SOURCES}
    ${DEP_ZYDIS_SOURCES}
    PROPERTIES LANGUAGE C COMPILE_OPTIONS "-Wno-format-truncation;-Wno-unused-function;-std=c11"
)

target_compile_options(rpcs4_core PRIVATE
    -Wall -Wextra
    -Wno-unused-parameter
    -Wno-missing-field-initializers
    -Wno-unused-variable
    -Wno-sign-compare
    -Wno-format-security
    -include pthread_compat.h
    # Upstream relies on heavy C++23 constructs; clang accepts them but can be
    # noisy with deprecation notices from newer libc++.
    -Wno-deprecated-declarations
)

# ----------------------------------------------------------------------------
# Shared JNI library loaded by the app
# ----------------------------------------------------------------------------

add_library(rpcs4_android SHARED
    jni/Rpcs4Jni.cpp
    compat/log_ring.cpp
    compat/SDL_shim.cpp
    compat/SDL_input.cpp
    compat/audio_aaudio.cpp
)

target_link_libraries(rpcs4_android
    PUBLIC  rpcs4_core
    PRIVATE android
    PRIVATE log
    PRIVATE aaudio
    PRIVATE vulkan
    PRIVATE EGL            # required by some ANativeWindow paths in bionic
)

if(TARGET glslang)
    target_link_libraries(rpcs4_android PUBLIC glslang SPIRV glslang-default-resource-limits)
elseif(TARGET glslang::glslang)
    target_link_libraries(rpcs4_android PUBLIC glslang::glslang glslang::SPIRV glslang::glslang-default-resource-limits)
endif()

message(STATUS "")
message(STATUS "=== RPCS4 Android native configuration ===")
message(STATUS "  Core sources : ${ALL_CORE_SOURCES}")
message(STATUS "  ABI filter   : x86_64 (see PORTING.md)")
message(STATUS "===========================================")
