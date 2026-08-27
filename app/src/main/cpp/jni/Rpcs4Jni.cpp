// SPDX-FileCopyrightText: 2024 RPCS4 Android Contributors
// SPDX-License-Identifier: GPL-3.0-or-later
//
// ============================================================================
// Rpcs4Jni.cpp - JNI bridge for com.rpcs4.android.native.NativeBridge
//
// Mirrors the desktop flow in core/ChonkyStation4.cpp:
//   User::init() -> create default user -> login(1) -> set Configuration ->
//   PS4::loadAndRun(path)   (running on a dedicated std::thread)
//
// Everything else (surface registration, input, logs, quit) is funneled
// through the SDL-compat layer so the emulation core itself stays untouched.
// ============================================================================

#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>

#include <jni.h>

#include <algorithm>
#include <atomic>
#include <cerrno>
#include <cstring>
#include <memory>
#include <string>
#include <thread>
#include <mutex>
#include <unistd.h>

#include "SDL.h"                      // compat shim: window registry + quit injection
#include "log_ring.hpp"

#include <Configuration.hpp>
#include <PlayStation4.hpp>
#include <OS/UserManagement.hpp>

#define TAG "rpcs4-jni"

namespace {

std::mutex g_thread_mutex;
std::thread g_emu_thread;
std::atomic<bool> g_running { false };
bool g_redirection_installed = false;

const char* kMainClass = "com/rpcs4/android/native/NativeBridge";

void setJavaStringArray(JNIEnv* env, jobjectArray arr, const std::vector<std::string>& lines)
{
    for (size_t i = 0; i < lines.size(); ++i) {
        jstring s = env->NewStringUTF(lines[i].c_str());
        env->SetObjectArrayElement(arr, static_cast<jsize>(i), s);
        env->DeleteLocalRef(s);
    }
}

}   // namespace

extern "C" {

// ------------------------------------------------------------------ surface

JNIEXPORT void JNICALL
Java_com_rpcs4_android_native_NativeBridge_nativeSetSurface(
    JNIEnv* env, jobject /*thiz*/, jobject surface)
{
    if (surface == nullptr) {
        Rpcs4Compat_SetAndroidWindow(nullptr);
        return;
    }

    ANativeWindow* window = ANativeWindow_fromSurface(env, surface);
    if (window == nullptr) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "ANativeWindow_fromSurface returned null");
        return;
    }
    // The shim acquires its own reference; drop ours immediately.
    ANativeWindow_acquire(window);
    Rpcs4Compat_SetAndroidWindow(window);
    ANativeWindow_release(window);
}

JNIEXPORT jboolean JNICALL
Java_com_rpcs4_android_native_NativeBridge_nativeIsSurfaceReady(
    JNIEnv* /*env*/, jobject /*thiz*/)
{
    return Rpcs4Compat_HasAndroidWindow() ? JNI_TRUE : JNI_FALSE;
}

// -------------------------------------------------------------- configuration

JNIEXPORT void JNICALL
Java_com_rpcs4_android_native_NativeBridge_nativeSetConfiguration(
    JNIEnv* /*env*/, jobject /*thiz*/,
    jfloat resolutionScale,
    jboolean copyCommandBuffers,
    jboolean skipAsyncComputeDispatches,
    jboolean skipWaitRegMem,
    jboolean disableGnmDetilerTextureSize,
    jboolean disableSgprInitHack,
    jboolean clampGpuBuffers,
    jboolean skipBindlessBuffers,
    jboolean forceInitSceCompositor,
    jboolean lleSsl)
{
    PS4::Configuration::resolution_scale = resolutionScale;
    PS4::Configuration::copy_command_buffers = copyCommandBuffers == JNI_TRUE;
    PS4::Configuration::skip_async_compute_dispatches = skipAsyncComputeDispatches == JNI_TRUE;
    PS4::Configuration::skip_waitregmem = skipWaitRegMem == JNI_TRUE;
    PS4::Configuration::disable_gnmdetiler_texture_size = disableGnmDetilerTextureSize == JNI_TRUE;
    PS4::Configuration::disable_sgpr_init_hack = disableSgprInitHack == JNI_TRUE;
    PS4::Configuration::clamp_gpu_buffers = clampGpuBuffers == JNI_TRUE;
    PS4::Configuration::skip_bindless_buffers = skipBindlessBuffers == JNI_TRUE;
    PS4::Configuration::force_init_sce_compositor = forceInitSceCompositor == JNI_TRUE;
    PS4::Configuration::lle_ssl = lleSsl == JNI_TRUE;

    Rpcs4Log::push("[Config ][Bridge           ] Configuration globals pushed from Android settings");
}

// ---------------------------------------------------------------------- run

JNIEXPORT jboolean JNICALL
Java_com_rpcs4_android_native_NativeBridge_nativeStart(
    JNIEnv* env, jobject /*thiz*/,
    jstring gamePathJstr,
    jstring systemDirJstr,
    jstring systemExDirJstr,
    jstring appDataDirJstr)
{
    const char* gamePathC = env->GetStringUTFChars(gamePathJstr, nullptr);
    const char* systemDirC = env->GetStringUTFChars(systemDirJstr, nullptr);
    const char* systemExDirC = env->GetStringUTFChars(systemExDirJstr, nullptr);
    const char* appDataDirC = env->GetStringUTFChars(appDataDirJstr, nullptr);

    const std::string gamePath = gamePathC ? gamePathC : "";
    const std::string systemDir = systemDirC ? systemDirC : "./system";
    const std::string systemExDir = systemExDirC ? systemExDirC : "./system_ex";
    const std::string appDataDir = appDataDirC ? appDataDirC : ".";

    env->ReleaseStringUTFChars(gamePathJstr, gamePathC);
    env->ReleaseStringUTFChars(systemDirJstr, systemDirC);
    env->ReleaseStringUTFChars(systemExDirJstr, systemExDirC);
    env->ReleaseStringUTFChars(appDataDirJstr, appDataDirC);

    const std::lock_guard<std::mutex> lock(g_thread_mutex);

    if (g_running.load()) {
        __android_log_print(ANDROID_LOG_WARN, TAG, "nativeStart ignored - emulation already running");
        return JNI_FALSE;
    }

    // Home directory: the core resolves user data via SDL_GetPrefPath in the
    // UserManagement shim, which uses this exact location.
    if (::chdir(appDataDir.c_str()) != 0) {
        Rpcs4Log::push(std::string("[FATAL ] chdir(") + appDataDir + ") failed: " +
                       ::strerror(errno));
        __android_log_print(ANDROID_LOG_ERROR, TAG, "chdir failed");
        return JNI_FALSE;
    }

    PS4::Configuration::system_dir_path = systemDir;
    PS4::Configuration::system_ex_dir_path = systemExDir;

    // One-time stdout/stderr -> log-ring redirection so Logger.hpp output
    // reaches logcat AND the Compose LogScreen.
    if (!g_redirection_installed) {
        g_redirection_installed = true;
        Rpcs4Log::installStdoutRedirect();
    }

    g_running.store(true);
    g_emu_thread = std::thread([gamePath]() {
        Rpcs4Log::push("[Loader ][App              ] Booting " + gamePath);
        try {
            PS4::OS::User::init();

            if (!PS4::OS::User::exists(1)) {
                PS4::OS::User::createNew("RPCS4Android");
            }
            if (!PS4::OS::User::login(1)) {
                Rpcs4Log::push("[FATAL ] Failed to login default user");
                g_running.store(false);
                return;
            }

            PS4::loadAndRun(gamePath);
            Rpcs4Log::push("[Loader ][App              ] loadAndRun returned");
        } catch (const std::exception& e) {
            Rpcs4Log::push(std::string("[FATAL ] Unhandled exception: ") + e.what());
            __android_log_print(ANDROID_LOG_ERROR, TAG, "emulation crashed: %s", e.what());
        } catch (...) {
            Rpcs4Log::push("[FATAL ] Unknown unhandled exception");
        }
        g_running.store(false);
    });
    g_emu_thread.detach();   // Joins implicitly via running-flag polling.

    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_rpcs4_android_native_NativeBridge_nativeStop(JNIEnv* /*env*/, jobject /*thiz*/)
{
    Rpcs4Compat_RequestQuit();
    Rpcs4Log::push("[Host   ][Quit             ] Stop requested by Android UI");
}

JNIEXPORT jboolean JNICALL
Java_com_rpcs4_android_native_NativeBridge_nativeIsRunning(JNIEnv* /*env*/, jobject /*thiz*/)
{
    return g_running.load() ? JNI_TRUE : JNI_FALSE;
}

// -------------------------------------------------------------------- input

JNIEXPORT void JNICALL
Java_com_rpcs4_android_native_NativeBridge_nativeSendPad(
    JNIEnv* /*env*/, jobject /*thiz*/,
    jint buttonsMask,
    jfloat lx, jfloat ly, jfloat rx, jfloat ry,
    jfloat l2, jfloat r2)
{
    Rpcs4Compat_SetPadState(
        static_cast<unsigned int>(buttonsMask),
        lx, ly, rx, ry, l2, r2);
}

JNIEXPORT void JNICALL
Java_com_rpcs4_android_native_NativeBridge_nativeSendKey(
    JNIEnv* /*env*/, jobject /*thiz*/, jint scancode, jboolean down)
{
    Rpcs4Compat_SetKeyboardKey(scancode, down == JNI_TRUE);
}

// --------------------------------------------------------------------- logs

JNIEXPORT jobjectArray JNICALL
Java_com_rpcs4_android_native_NativeBridge_nativePollLogs(
    JNIEnv* env, jobject /*thiz*/, jint maxLines)
{
    jclass stringClass = env->FindClass("java/lang/String");
    if (stringClass == nullptr) return nullptr;

    const auto drained = Rpcs4Log::drain(static_cast<size_t>(maxLines < 0 ? 0 : maxLines));

    jobjectArray result =
        env->NewObjectArray(static_cast<jsize>(drained.size()), stringClass, nullptr);
    if (result != nullptr) {
        setJavaStringArray(env, result, drained);
    }
    env->DeleteLocalRef(stringClass);
    return result;
}

// -------------------------------------------------------- process-level init

// Called by Rpcs4Application through NativeBridge.ensureLoaded()'s first use.
JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* /*reserved*/)
{
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK || env == nullptr) {
        return JNI_ERR;
    }
    __android_log_print(ANDROID_LOG_INFO, TAG, "librpcs4_android loaded");

    // Fail fast (with actionable logs) if the Kotlin class and native symbols drift.
    jclass cls = env->FindClass(kMainClass);
    if (cls == nullptr) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "NativeBridge class not found!");
        return JNI_ERR;
    }
    env->DeleteLocalRef(cls);

    return JNI_VERSION_1_6;
}

}   // extern "C"
