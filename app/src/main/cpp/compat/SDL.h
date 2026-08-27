// SPDX-FileCopyrightText: 2024 RPCS4 Android Contributors
// SPDX-License-Identifier: GPL-3.0-or-later
//
// ============================================================================
// SDL-compat shim for Android (app/cpp/compat/SDL.h)
// ============================================================================
//
// The upstream core includes <SDL.h> / <SDL_vulkan.h> in exactly seven files.
// Rather than forking the emulation code, this directory shadows those headers
// with a tiny, self-contained re-implementation covering only the API subset
// the core actually calls:
//
//   VulkanRenderer.cpp : SDL_Init, SDL_CreateWindow, SDL_Vulkan_* (surface +
//                        extensions + drawable size), SDL_PollEvent,
//                        SDL_GetTicks64, SDL_SetWindowTitle/Fullscreen,
//                        SDL_ShowCursor, SDL_GetError
//   SceAudioOut.cpp    : SDL_OpenAudioDevice/PauseAudioDevice/QueueAudio/
//                        GetQueuedAudioSize/Delay           -> AAudio backend
//   ScePad.cpp         : gamecontroller enumeration + GetButton/GetAxis/Rumble
//                        (fed from JNI by the on-screen overlay + BT pads)
//   Kernel.cpp         : performance counters
//   UserManagement.cpp : SDL_GetPrefPath                   -> filesDir/home
//
// Header-order is decisive: target_include_directories() in CMakeLists.txt
// places compat/ BEFORE every other include root so these definitions win.
// ============================================================================

#pragma once

#include <cstdint>
#include <cstring>

#ifdef __cplusplus
extern "C" {
#endif

typedef uint8_t  Uint8;
typedef uint16_t Uint16;
typedef uint32_t Uint32;
typedef int32_t  Sint32;
typedef int64_t  Sint64;
typedef uint64_t Uint64;

typedef int SDL_bool;
#define SDL_TRUE  1
#define SDL_FALSE 0

#define SDL_DECLSPEC
#define SDLCALL

// ---------------------------------------------------------------- constants

enum {
    SDL_INIT_VIDEO          = 0x00000020u,
    SDL_INIT_AUDIO          = 0x00000010u,
    SDL_INIT_GAMECONTROLLER = 0x00002000u,
};

enum {
    SDL_WINDOW_FULLSCREEN_DESKTOP = 0x00001001u,
    SDL_WINDOW_SHOWN              = 0x00000004u,
    SDL_WINDOW_VULKAN             = 0x10000000u,
};

enum {
    SDL_DISABLE = 0,
    SDL_ENABLE  = 1,
    SDL_QUERY   = -1,
};

enum {
    SDL_BUTTON_LEFT = 1,
};

// Pixel-format constants used by SceAudioOut's format table. Values mirror real
// SDL2 for debuggability; nothing depends on the actual bit patterns.
enum {
    AUDIO_S16 = 0x8010,
    AUDIO_F32 = 0x8120,
};

#define SDL_AUDIO_ALLOW_FORMAT_CHANGE 0x00000001u

#define SDL_zero(x) do { memset(&(x), 0, sizeof((x))); } while (0)

struct SDL_Window;

// -------------------------------------------------------------------- events

typedef enum {
    SDL_QUIT                  = 0x100,
    SDL_MOUSEBUTTONDOWN       = 0x401,
    SDL_CONTROLLERDEVICEADDED = 0x653,
    SDL_CONTROLLERDEVICEREMOVED = 0x654,
} SDL_EventType;

typedef struct SDL_MouseButtonEvent {
    Uint8 button;
    Uint8 clicks;
} SDL_MouseButtonEvent;

typedef struct SDL_ControllerDeviceEvent {
    Sint32 which;
} SDL_ControllerDeviceEvent;

typedef union SDL_Event {
    Uint32 type;
    SDL_MouseButtonEvent button;
    SDL_ControllerDeviceEvent cdevice;
} SDL_Event;

// --------------------------------------------------------------- window API

int SDL_Init(Uint32 flags);
const char* SDL_GetError();

SDL_Window* SDL_CreateWindow(
    const char* title, int x, int y, int w, int h, Uint32 flags);

void SDL_SetWindowTitle(SDL_Window* window, const char* title);
Uint32 SDL_SetWindowFullscreen(SDL_Window* window, Uint32 flags);
int SDL_ShowCursor(int toggle);

int SDL_PollEvent(SDL_Event* event);

Uint64 SDL_GetTicks64();
Uint64 SDL_GetPerformanceCounter();
Uint64 SDL_GetPerformanceFrequency();
void SDL_Delay(Uint32 ms);

// ------------------------------------------------------------ vulkan bridge

#include <vulkan/vulkan.h>

// Mirrors the real SDL2 two-pass query: pass pNames == nullptr to receive the
// extension count in *pCount, then call again with a filled buffer.
char const* const* SDL_Vulkan_GetInstanceExtensions(
    SDL_Window* window, unsigned* pCount, const char** pNames);

SDL_bool SDL_Vulkan_CreateSurface(
    SDL_Window* window, VkInstance instance, VkSurfaceKHR* surface);

void SDL_Vulkan_GetDrawableSize(SDL_Window* window, int* w, int* h);

// --------------------------------------------------------------------- audio

typedef struct SDL_AudioSpec {
    int freq;
    unsigned short format;
    unsigned char channels;
    unsigned short samples;
    void (*callback)(void*, Uint8*, int);
} SDL_AudioSpec;

typedef Uint32 SDL_AudioDeviceID;

SDL_AudioDeviceID SDL_OpenAudioDevice(
    const char* device, int iscapture,
    const SDL_AudioSpec* desired, SDL_AudioSpec* obtained,
    int allowed_changes);

void SDL_PauseAudioDevice(SDL_AudioDeviceID dev, int pause_on);
int SDL_QueueAudio(SDL_AudioDeviceID dev, const void* data, Uint32 len);
Uint32 SDL_GetQueuedAudioSize(SDL_AudioDeviceID dev);

// ---------------------------------------------------------------------- pad

// Canonical SDL2 numbering - ScePad.cpp switch values depend on it.
typedef enum {
    SDL_CONTROLLER_BUTTON_A = 0,
    SDL_CONTROLLER_BUTTON_B,
    SDL_CONTROLLER_BUTTON_X,
    SDL_CONTROLLER_BUTTON_Y,
    SDL_CONTROLLER_BUTTON_BACK,
    SDL_CONTROLLER_BUTTON_GUIDE,
    SDL_CONTROLLER_BUTTON_START,
    SDL_CONTROLLER_BUTTON_LEFTSTICK,
    SDL_CONTROLLER_BUTTON_RIGHTSTICK,
    SDL_CONTROLLER_BUTTON_LEFTSHOULDER,
    SDL_CONTROLLER_BUTTON_RIGHTSHOULDER,
    SDL_CONTROLLER_BUTTON_DPAD_UP,
    SDL_CONTROLLER_BUTTON_DPAD_DOWN,
    SDL_CONTROLLER_BUTTON_DPAD_LEFT,
    SDL_CONTROLLER_BUTTON_DPAD_RIGHT,
    SDL_CONTROLLER_BUTTON_MISC1,
    SDL_CONTROLLER_BUTTON_TOUCHPAD = 23,
} SDL_GameControllerButton;

typedef enum {
    SDL_CONTROLLER_AXIS_LEFTX = 0,
    SDL_CONTROLLER_AXIS_LEFTY,
    SDL_CONTROLLER_AXIS_RIGHTX,
    SDL_CONTROLLER_AXIS_RIGHTY,
    SDL_CONTROLLER_AXIS_TRIGGERLEFT,
    SDL_CONTROLLER_AXIS_TRIGGERRIGHT,
} SDL_GameControllerAxis;

struct _SDL_GameController;
typedef struct _SDL_GameController SDL_GameController;
struct _SDL_Joystick;
typedef struct _SDL_Joystick SDL_Joystick;

int SDL_NumJoysticks();
int SDL_IsGameController(int index);
SDL_GameController* SDL_GameControllerOpen(int index);
void SDL_GameControllerClose(SDL_GameController* controller);
SDL_Joystick* SDL_GameControllerGetJoystick(SDL_GameController* controller);
Sint32 SDL_JoystickInstanceID(SDL_Joystick* joystick);

Uint8 SDL_GameControllerGetButton(
    SDL_GameController* controller, SDL_GameControllerButton button);
Sint16 SDL_GameControllerGetAxis(
    SDL_GameController* controller, SDL_GameControllerAxis axis);

int SDL_GameControllerSetLED(
    SDL_GameController* controller, Uint8 r, Uint8 g, Uint8 b);
int SDL_GameControllerRumble(
    SDL_GameController* controller,
    Uint16 low_frequency_rumble, Uint16 high_frequency_rumble, Uint32 duration_ms);

// Keyboard-mapping fallback path kept functional for parity with desktop tests.
enum {
    SDL_SCANCODE_A = 4,
    SDL_SCANCODE_C = 6,
    SDL_SCANCODE_D = 7,
    SDL_SCANCODE_I = 12,
    SDL_SCANCODE_J = 13,
    SDL_SCANCODE_K = 14,
    SDL_SCANCODE_L = 15,
    SDL_SCANCODE_S = 22,
    SDL_SCANCODE_W = 26,
    SDL_SCANCODE_X = 27,
    SDL_SCANCODE_Z = 29,
    SDL_SCANCODE_SPACE = 44,
    SDL_SCANCODE_RIGHT = 79,
    SDL_SCANCODE_LEFT = 80,
    SDL_SCANCODE_DOWN = 81,
    SDL_SCANCODE_UP = 82,
};

const Uint8* SDL_GetKeyboardState(int* numkeys);

// ------------------------------------------- android integration entry points

/**
 * Called from JNI with the ANativeWindow owned by the app's SurfaceView.
 * Pass nullptr to release ownership on surface destroy.
 */
void Rpcs4Compat_SetAndroidWindow(void* anative_window);

/** True once an ANativeWindow has been registered (boot gate). */
bool Rpcs4Compat_HasAndroidWindow();

/** Cooperative stop: injects SDL_QUIT into the next event pump. */
void Rpcs4Compat_RequestQuit();

#ifdef __cplusplus
}   // extern "C"
#endif
