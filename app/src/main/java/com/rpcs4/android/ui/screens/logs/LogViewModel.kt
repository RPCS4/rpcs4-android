package com.rpcs4.android.ui.screens.logs

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.rpcs4.android.native.NativeBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.ArrayDeque

/**
 * Log viewer fed by the native MAKE_LOG_FUNCTION output stream.
 *
 * The C++ shim redirects core stdout into a bounded ring buffer; this VM polls
 * it at 4 Hz and keeps the newest 4000 lines in memory.
 */
class LogViewModel(app: Application) : AndroidViewModel(app) {

    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines.asStateFlow()

    private val buffer = ArrayDeque<String>(LOG_CAPACITY + 16)
    private var poller: Job? = null

    fun startPolling() {
        if (poller?.isActive == true) return
        poller = viewModelScope.launch(Dispatchers.Default) {
            NativeBridge.ensureLoaded()
            while (isActive) {
                drainNative()
                delay(250)
            }
        }
    }

    fun stopPolling() {
        poller?.cancel()
        poller = null
    }

    fun clear() {
        viewModelScope.launch(Dispatchers.Default) {
            buffer.clear()
            _lines.value = emptyList()
        }
    }

    private fun drainNative() {
        val fresh = NativeBridge.nativePollLogs(256)
        if (fresh.isEmpty()) return

        synchronized(buffer) {
            for (line in fresh) {
                buffer.addLast(line)
                if (buffer.size > LOG_CAPACITY) buffer.removeFirst()
            }
        }
        _lines.value = ArrayList(buffer)
    }

    companion object {
        private const val LOG_CAPACITY = 4000
    }
}
