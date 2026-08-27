// SPDX-FileCopyrightText: 2024 RPCS4 Android Contributors
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Bounded in-memory log ring shared by:
//   - the stdout/stderr redirect reader thread (captures MAKE_LOG_FUNCTION output)
//   - the SDL-compat shim (window-title FPS updates)
//   - the JNI bridge exposing nativePollLogs() to the Compose log viewer

#pragma once

#include <string>
#include <vector>

namespace Rpcs4Log {

// Reserve enough for a chatty boot; older lines are dropped FIFO.
constexpr size_t kMaxLines = 4096;

/** Append one line (thread safe, trims newlines, drops oldest beyond kMaxLines). */
void push(const std::string& line);

/**
 * Drain up to [maxLines] entries, removing them from the ring.
 * Returned vector is ordered oldest -> newest.
 */
std::vector<std::string> drain(size_t maxLines);

/** Install pipe redirection for stdout+stderr and start the reader thread. */
void installStdoutRedirect();

}   // namespace Rpcs4Log
