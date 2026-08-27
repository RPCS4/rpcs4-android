package com.rpcs4.android.ui.screens.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.rpcs4.android.data.EmuConfig
import com.rpcs4.android.data.SettingsRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(app: Application) : AndroidViewModel(app) {

    val config: StateFlow<EmuConfig> = SettingsRepository.emuConfig(app)
        .stateIn(viewModelScope, SharingStarted.Eagerly, EmuConfig())

    fun setResolutionScale(scale: Float) {
        viewModelScope.launch { SettingsRepository.setResolutionScale(getApplication(), scale) }
    }

    /** [configField] matches EmuConfig property names, keeping the UI table-free. */
    fun setToggle(configField: String, value: Boolean) {
        viewModelScope.launch { SettingsRepository.setToggle(getApplication(), configField, value) }
    }
}
