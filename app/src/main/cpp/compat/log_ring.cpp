// SPDX-FileCopyrightText: 2024 RPCS4 Android Contributors
// SPDX-License-Identifier: GPL-3.0-or-later

#include "log_ring.hpp"

#include <android/log.h>
#include <unistd.h>

#include <algorithm>
#include <atomic>
#include <condition_variable>
#include <deque>
#include <mutex>
#include <thread>

namespace Rpcs4Log {

namespace {

struct Ring {
    std::mutex mtx;
    std::deque<std::string> lines;
};

Ring& ring()
{
    static Ring instance;
    return instance;
}

const char* kTag = "rpcs4";

bool isFatal(const std::string& line)
{
    return line.find("FATAL") != std::string::npos;
}

// ---------------------------------------------------------------------------
// stdout/stderr -> pipe -> reader thread
//
// The core logs through printf/vprintf on stdout (see Common/Logger.hpp).
// On Android stdout goes nowhere unless redirected; duplicating it through a
// pipe lets us feed BOTH logcat and the in-app viewer from one source of truth.
// ---------------------------------------------------------------------------

void readerThread(int readFd)
{
    std::string pending;
    char buf[4096];

    while (true) {
        const ssize_t n = ::read(readFd, buf, sizeof(buf));
        if (n <= 0) break;

        pending.append(buf, static_cast<size_t>(n));

        size_t newlinePos;
        while ((newlinePos = pending.find('\n')) != std::string::npos) {
            std::string line = pending.substr(0, newlinePos);
            pending.erase(0, newlinePos + 1);
            push(line);
            __android_log_print(
                isFatal(line) ? ANDROID_LOG_ERROR : ANDROID_LOG_INFO,
                kTag,
                "%s",
                line.c_str());
        }

        // Bound runaway single-line spam (core prints some huge panic payloads).
        if (pending.size() > 256 * 1024) {
            push(pending.substr(0, 4096));
            __android_log_print(ANDROID_LOG_ERROR, kTag, "(truncated) %s", pending.c_str());
            pending.clear();
        }
    }
}

}   // namespace

void push(const std::string& line)
{
    auto& r = ring();
    const std::lock_guard<std::mutex> lock(r.mtx);
    r.lines.push_back(line);
    while (r.lines.size() > kMaxLines) {
        r.lines.pop_front();
    }
}

std::vector<std::string> drain(size_t maxLines)
{
    auto& r = ring();
    std::vector<std::string> out;

    const std::lock_guard<std::mutex> lock(r.mtx);
    out.reserve(std::min(maxLines, r.lines.size()));
    for (size_t i = 0; i < maxLines && !r.lines.empty(); ++i) {
        out.push_back(std::move(r.lines.front()));
        r.lines.pop_front();
    }
    return out;
}

void installStdoutRedirect()
{
    static std::atomic<bool> installed { false };
    bool expected = false;
    if (!installed.compare_exchange_strong(expected, true)) {
        return;   // Already wired.
    }

    int fds[2];
    if (::pipe(fds) != 0) {
        __android_log_print(ANDROID_LOG_WARN, kTag, "stdout redirect pipe() failed");
        return;
    }

    // Route both standard streams into the pipe. Keep the original fds around
    // via dup in case native debugging wants them (fd 1023 heuristic slot).
    ::dup2(fds[1], STDOUT_FILENO);
    ::dup2(fds[1], STDERR_FILENO);

    if (fds[1] > STDERR_FILENO) {
        ::close(fds[1]);   // Writer end now lives at STDOUT/ERR_FILENO.
    }

    std::thread(readerThread, fds[0]).detach();
}

}   // namespace Rpcs4Log
