// SPDX-FileCopyrightText: 2024 RPCS4 Android Contributors
// SPDX-License-Identifier: GPL-3.0-or-later
//
// ============================================================================
// Virtual gamepad + keyboard backend (SDL_input.cpp)
//
// The core's ScePad implementation branches on whether an SDL game controller
// is present:
//   controller == nullptr -> keyboard scan-code mapping (discrete buttons only)
//   controller != nullptr -> analog sticks + triggers via GetButton/GetAxis
//
// We always present one virtual controller so the app gets the ANALOG path -
// that is what makes on-screen thumbsticks and Bluetooth pads behave like a
// real DualShock 4.
//
// Input arrives from JNI (EmulatorScreen's overlay via PadStateMux and
// MainActivity's hardware-gamepad forwarding). Every Android gamepad axis and
// face button we forward is mapped onto canonical SDL_GameController* values,
// after which ScePad.cpp performs its standard SCE_PAD_* translation.
// ============================================================================

#include "SDL.h"
#include "log_ring.hpp"

#include <android/log.h>

#include <algorithm>
#include <array>
#include <atomic>
#include <mutex>
#include <cstring>

#define TAG "rpcs4-input"

namespace {

struct AxisState {
    // Normalized floats; converted to Sint16 on read like real SDL2 does.
    float lx = 0.f, ly = 0.f, rx = 0.f, ry = 0.f, l2 = 0.f, r2 = 0.f;
};

struct VirtualPad {
    std::mutex mtx;
    std::array<bool, 32> buttons {};
    AxisState axes;
};

VirtualPad& pad()
{
    static VirtualPad instance;
    return instance;
}

std::atomic<bool> g_keyboard[256];
std::array<Uint8, 256> g_keyboard_snapshot {};

void refreshKeyboardSnapshot()
{
    for (int i = 0; i < 256; ++i) {
        g_keyboard_snapshot[i] = g_keyboard[i].load(std::memory_order_relaxed) ? 1 : 0;
    }
}

constexpr Sint16 toAxis(float v)
{
    return static_cast<Sint16>(v * 32767.0f);
}

}   // namespace

extern "C" {

// ---------------------------------------------------------------------------
// JNI-facing virtual-pad writer. Buttons use the SCE_PAD_* bitmask because the
// Kotlin overlay already speaks that format (PadBits.kt mirrors ScePad.hpp).
// ---------------------------------------------------------------------------

void Rpcs4Compat_SetPadState(
    unsigned int sceButtonsMask,
    float lx, float ly, float rx, float ry,
    float l2, float r2)
{
    auto& p = pad();
    const std::lock_guard<std::mutex> lock(p.mtx);

    std::fill(p.buttons.begin(), p.buttons.end(), false);

    struct Mapping { unsigned int sceBit; SDL_GameControllerButton button; };
    static constexpr Mapping kMap[] = {
        { 0x00000002u, SDL_CONTROLLER_BUTTON_LEFTSTICK },        // SCE_PAD_BUTTON_L3
        { 0x00000004u, SDL_CONTROLLER_BUTTON_RIGHTSTICK },       // R3
        { 0x00000008u, SDL_CONTROLLER_BUTTON_START },            // OPTIONS
        { 0x00000010u, SDL_CONTROLLER_BUTTON_DPAD_UP },
        { 0x00000020u, SDL_CONTROLLER_BUTTON_DPAD_RIGHT },
        { 0x00000040u, SDL_CONTROLLER_BUTTON_DPAD_DOWN },
        { 0x00000080u, SDL_CONTROLLER_BUTTON_DPAD_LEFT },
        { 0x00000400u, SDL_CONTROLLER_BUTTON_LEFTSHOULDER },     // L1
        { 0x00000800u, SDL_CONTROLLER_BUTTON_RIGHTSHOULDER },    // R1
        { 0x00001000u, SDL_CONTROLLER_BUTTON_Y },                // TRIANGLE
        { 0x00002000u, SDL_CONTROLLER_BUTTON_B },                // CIRCLE
        { 0x00004000u, SDL_CONTROLLER_BUTTON_A },                // CROSS
        { 0x00008000u, SDL_CONTROLLER_BUTTON_X },                // SQUARE
        { 0x00100000u, SDL_CONTROLLER_BUTTON_TOUCHPAD },
    };

    for (const auto& m : kMap) {
        if ((sceButtonsMask & m.sceBit) != 0) {
            p.buttons[m.button] = true;
        }
    }

    p.axes.lx = lx;
    p.axes.ly = ly;
    p.axes.rx = rx;
    p.axes.ry = ry;

    // Analog triggers ride the axis path. The core derives its own digital
    // L2/R2 bits once the floats exceed 220/255 (see ScePad.cpp pollPads), so
    // no extra synthesis happens here.
    p.axes.l2 = l2 < 0.f ? 0.f : l2;
    p.axes.r2 = r2 < 0.f ? 0.f : r2;
}

}   // extern "C"

extern "C" {

int SDL_NumJoysticks()
{
    return 1;   // Always expose exactly one virtual controller.
}

int SDL_IsGameController(int /*index*/)
{
    return 1;
}

SDL_GameController* SDL_GameControllerOpen(int /*index*/)
{
    static Uint8 dummy_storage = 0;
    return reinterpret_cast<SDL_GameController*>(&dummy_storage);
}

void SDL_GameControllerClose(SDL_GameController* /*controller*/)
{
}

SDL_Joystick* SDL_GameControllerGetJoystick(SDL_GameController* controller)
{
    return controller;   // Opaque one-to-one handle.
}

Sint32 SDL_JoystickInstanceID(SDL_Joystick* /*joystick*/)
{
    return 0;
}

Uint8 SDL_GameControllerGetButton(
    SDL_GameController* /*controller*/, SDL_GameControllerButton button)
{
    auto& p = pad();
    return p.buttons[button] ? 1 : 0;
}

Sint16 SDL_GameControllerGetAxis(
    SDL_GameController* /*controller*/, SDL_GameControllerAxis axis)
{
    auto& p = pad();
    switch (axis) {
        case SDL_CONTROLLER_AXIS_LEFTX:         return toAxis(p.axes.lx);
        case SDL_CONTROLLER_AXIS_LEFTY:         return toAxis(p.axes.ly);
        case SDL_CONTROLLER_AXIS_RIGHTX:        return toAxis(p.axes.rx);
        case SDL_CONTROLLER_AXIS_RIGHTY:        return toAxis(p.axes.ry);
        case SDL_CONTROLLER_AXIS_TRIGGERLEFT:   return toAxis(p.axes.l2);
        case SDL_CONTROLLER_AXIS_TRIGGERRIGHT:  return toAxis(p.axes.r2);
    }
    return 0;
}

int SDL_GameControllerSetLED(
    SDL_GameController*, Uint8 /*r*/, Uint8 /*g*/, Uint8 /*b*/)
{
    return 0;   // Lightbar has no Android equivalent yet.
}

int SDL_GameControllerRumble(
    SDL_GameController*, Uint16 /*low*/, Uint16 /*high*/, Uint32 /*duration_ms*/)
{
    return 0;   // VibratorManager wiring is tracked as roadmap work in PORTING.md.
}

const Uint8* SDL_GetKeyboardState(int* numkeys)
{
    refreshKeyboardSnapshot();
    if (numkeys != nullptr) *numkeys = 256;
    return g_keyboard_snapshot.data();
}

void Rpcs4Compat_SetKeyboardKey(int scancode, bool down)
{
    if (scancode >= 0 && scancode < 256) {
        g_keyboard[scancode].store(down, std::memory_order_relaxed);
    }
}

}   // extern "C"
