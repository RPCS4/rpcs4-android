// SPDX-FileCopyrightText: 2024 RPCS4 Android Contributors
// SPDX-License-Identifier: GPL-3.0-or-later
//
// ============================================================================
// SDL-compat implementation over Android platform APIs (SDL_shim.cpp)
// ============================================================================

#include "SDL.h"
#include "log_ring.hpp"

#include <android/log.h>
#include <android/native_window.h>

#include <array>
#include <atomic>
#include <chrono>
#include <cstring>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#define TAG "rpcs4-sdl"

// ---------------------------------------------------------------------------
// Window registry
//
// The core's GCN thread calls SDL_CreateWindow() with a 1920x1080*scale size.
// We cannot actually create windows - we adopt the ANativeWindow that the
// Compose SurfaceView already handed us through Rpcs4Compat_SetAndroidWindow().
// ---------------------------------------------------------------------------

namespace {

struct ShimWindow {
    ANativeWindow* native_window = nullptr;
    std::string title;
};

std::mutex g_window_mutex;
ANativeWindow* g_registered_window = nullptr;   // set from JNI
ShimWindow g_created_window;
std::atomic<bool> g_quit_requested { false };
std::string g_last_error = "no error";

Uint64 monotonicMs()
{
    const auto now = std::chrono::steady_clock::now().time_since_epoch();
    return static_cast<Uint64>(std::chrono::duration_cast<std::chrono::milliseconds>(now).count());
}

}   // namespace

extern "C" {

void Rpcs4Compat_SetAndroidWindow(void* anative_window)
{
    auto* win = static_cast<ANativeWindow*>(anative_window);
    const std::lock_guard<std::mutex> lock(g_window_mutex);

    if (g_registered_window != nullptr) {
        ANativeWindow_release(g_registered_window);
        g_registered_window = nullptr;
    }
    if (win != nullptr) {
        // Acquire our own reference: Surface could die between destroy callback
        // and the emulation thread's next flip.
        ANativeWindow_acquire(win);
        g_registered_window = win;
    }
}

bool Rpcs4Compat_HasAndroidWindow()
{
    const std::lock_guard<std::mutex> lock(g_window_mutex);
    return g_registered_window != nullptr;
}

void Rpcs4Compat_RequestQuit()
{
    g_quit_requested.store(true);
}

const char* SDL_GetError()
{
    return g_last_error.c_str();
}

int SDL_Init(Uint32 flags)
{
    __android_log_print(ANDROID_LOG_INFO, TAG, "SDL_Init(0x%x)", flags);
    return 0;   // All subsystems are lazily provisioned by their own shims.
}

SDL_Window* SDL_CreateWindow(
    const char* title, int x, int y, int w, int h, Uint32 flags)
{
    const std::lock_guard<std::mutex> lock(g_window_mutex);

    if ((flags & SDL_WINDOW_VULKAN) == 0) {
        __android_log_print(ANDROID_LOG_WARN, TAG, "non-Vulkan window requested");
    }

    // Adopt the registered window. When the app has not pushed its surface yet
    // we still succeed - Rpcs4Compat_HasAndroidWindow() lets the UI gate boot -
    // but surface-less boot would fail later at vkCreateAndroidSurfaceKHR, so
    // report failure here to keep the panic actionable in the log viewer.
    if (g_registered_window == nullptr) {
        g_last_error = "no Android surface was registered before SDL_CreateWindow";
        __android_log_print(ANDROID_LOG_ERROR, TAG, "%s", g_last_error.c_str());
        return nullptr;
    }

    g_created_window.native_window = g_registered_window;
    g_created_window.title = title ? title : "";
    return &g_created_window;
}

void SDL_SetWindowTitle(SDL_Window* /*window*/, const char* title)
{
    if (title == nullptr) return;

    static Uint64 last_push_ms = 0;
    const Uint64 now = monotonicMs();
    // The desktop build pumps FPS/frame-time through the window title once per
    // frame. Rate-limit to ~1 Hz so it stays useful in the log viewer without
    // flooding either ring or logcat.
    if (now - last_push_ms >= 1000) {
        last_push_ms = now;
        Rpcs4Log::push(std::string("[Perf   ][Title            ] ") + title);
    }
}

Uint32 SDL_SetWindowFullscreen(SDL_Window* /*window*/, Uint32 flags)
{
    // Handled natively by MainActivity.setImmersive(); nothing to do here.
    return flags;
}

int SDL_ShowCursor(int /*toggle*/)
{
    return 0;
}

int SDL_PollEvent(SDL_Event* event)
{
    if (event == nullptr) return 0;

    if (g_quit_requested.exchange(false)) {
        __android_log_print(ANDROID_LOG_INFO, TAG, "injecting SDL_QUIT");
        event->type = SDL_QUIT;
        return 1;
    }
    return 0;
}

Uint64 SDL_GetTicks64()
{
    return monotonicMs();
}

Uint64 SDL_GetPerformanceCounter()
{
    const auto now = std::chrono::steady_clock::now().time_since_epoch();
    return static_cast<Uint64>(std::chrono::duration_cast<std::chrono::nanoseconds>(now).count());
}

Uint64 SDL_GetPerformanceFrequency()
{
    return 1'000'000'000ULL;   // nanosecond clock
}

void SDL_Delay(Uint32 ms)
{
    std::this_thread::sleep_for(std::chrono::milliseconds(ms));
}

// ---------------------------------------------------------- vulkan surface

char const* const* SDL_Vulkan_GetInstanceExtensions(
    SDL_Window* /*window*/, unsigned* pCount, const char** pNames)
{
    static const std::array<const char*, 2> kExtensions {
        "VK_KHR_surface",
        "VK_KHR_android_surface",
    };

    if (pCount != nullptr) {
        *pCount = static_cast<unsigned>(kExtensions.size());
    }
    if (pNames != nullptr) {
        for (size_t i = 0; i < kExtensions.size() && i < (pCount ? *pCount : kExtensions.size()); ++i) {
            pNames[i] = kExtensions[i];
        }
    }
    return pNames != nullptr ? pNames : kExtensions.data();
}

SDL_bool SDL_Vulkan_CreateSurface(
    SDL_Window* window, VkInstance instance, VkSurfaceKHR* surface)
{
    if (window == nullptr || surface == nullptr) {
        g_last_error = "invalid Vulkan surface arguments";
        return SDL_FALSE;
    }

    const std::lock_guard<std::mutex> lock(g_window_mutex);
    if (g_created_window.native_window == nullptr) {
        g_last_error = "window has no ANativeWindow backing";
        return SDL_FALSE;
    }

    PFN_vkCreateAndroidSurfaceKHR create =
        reinterpret_cast<PFN_vkCreateAndroidSurfaceKHR>(
            vkGetInstanceProcAddr(instance, "vkCreateAndroidSurfaceKHR"));
    if (create == nullptr) {
        g_last_error = "vkCreateAndroidSurfaceKHR unavailable on this loader";
        return SDL_FALSE;
    }

    VkAndroidSurfaceCreateInfoKHR info {};
    info.sType = VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR;
    info.window = g_created_window.native_window;

    const VkResult result = create(instance, &info, nullptr, surface);
    if (result != VK_SUCCESS) {
        g_last_error = "vkCreateAndroidSurfaceKHR failed";
        __android_log_print(ANDROID_LOG_ERROR, TAG, "%s (VkResult=%d)", g_last_error.c_str(), result);
        return SDL_FALSE;
    }
    return SDL_TRUE;
}

void SDL_Vulkan_GetDrawableSize(SDL_Window* window, int* w, int* h)
{
    if (window == nullptr) return;

    const std::lock_guard<std::mutex> lock(g_window_mutex);
    ANativeWindow* nw = g_created_window.native_window;
    if (nw == nullptr) {
        if (w) *w = 1280;
        if (h) *h = 720;
        return;
    }
    if (w) *w = ANativeWindow_getWidth(nw);
    if (h) *h = ANativeWindow_getHeight(nw);
}

}   // extern "C"
