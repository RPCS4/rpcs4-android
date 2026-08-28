package com.rpcs4.android.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.File

private val Context.dataStore by preferencesDataStore(name = "rpcs4_settings")

/**
 * Persistent emulator configuration.
 *
 * Field names intentionally mirror PS4::Configuration.hpp globals so a reader
 * can diff app behavior against the desktop command-line options 1:1
 * (see core/ChonkyStation4.cpp --help).
 */
data class EmuConfig(
    val resolutionScale: Float = 1.0f,
    val copyCommandBuffers: Boolean = false,
    val skipAsyncComputeDispatches: Boolean = false,
    val skipWaitRegMem: Boolean = false,
    val disableGnmDetilerTextureSize: Boolean = false,
    val disableSgprInitHack: Boolean = false,
    val clampGpuBuffers: Boolean = false,
    val skipBindlessBuffers: Boolean = false,
    val forceInitSceCompositor: Boolean = false,
    val lleSsl: Boolean = false,
)

/** Where games come from. */
enum class SourceMode { IMPORT, DIRECT }

object SettingsRepository {

    private object Keys {
        const val GAMES_TREE_URI = "games_tree_uri"
        const val SOURCE_MODE = "source_mode"
        const val DIRECT_ROOT = "direct_root"
        const val RESOLUTION_SCALE = "resolution_scale"
        val TOGGLES = mapOf(
            "copy_command_buffers" to "copyCommandBuffers",
            "skip_async_compute" to "skipAsyncComputeDispatches",
            "skip_waitregmem" to "skipWaitRegMem",
            "disable_detiler_texsize" to "disableGnmDetilerTextureSize",
            "disable_sgpr_hack" to "disableSgprInitHack",
            "clamp_gpu_buffers" to "clampGpuBuffers",
            "skip_bindless" to "skipBindlessBuffers",
            "force_compositor" to "forceInitSceCompositor",
            "lle_ssl" to "lleSsl",
        )
    }

    private fun key(s: String) = stringPreferencesKey(s)

    private val K_TREE_URI = key(Keys.GAMES_TREE_URI)
    private val K_SOURCE_MODE = key(Keys.SOURCE_MODE)
    private val K_DIRECT_ROOT = key(Keys.DIRECT_ROOT)
    private val K_RES_SCALE = floatPreferencesKey(Keys.RESOLUTION_SCALE)

    // Toggle keys resolved once: name -> booleanPreferencesKey
    private val toggleKeys: Map<String, androidx.datastore.preferences.core.Preferences.Key<Boolean>> =
        Keys.TOGGLES.keys.associateWith { booleanPreferencesKey(it) }

    suspend fun ensureInitialized(context: Context) {
        // Create native-side working dirs eagerly so chdir() in the JNI layer
        // always lands somewhere writable.
        listOf(
            File(context.filesDir, "home"),
            File(context.filesDir, "system"),
            File(context.filesDir, "system_ex"),
            File(context.filesDir, "games"),
            File(context.cacheDir, "logs"),
        ).forEach { it.mkdirs() }
    }

    // ------------------------------------------------------------- game library

    fun gamesTreeUri(context: Context): Flow<String> = context.dataStore.data.map { it[K_TREE_URI].orEmpty() }
    fun directRoot(context: Context): Flow<String> = context.dataStore.data.map { it[K_DIRECT_ROOT].orEmpty() }
    fun sourceMode(context: Context): Flow<SourceMode> = context.dataStore.data.map {
        when (it[K_SOURCE_MODE]) {
            SourceMode.DIRECT.name -> SourceMode.DIRECT
            else -> SourceMode.IMPORT
        }
    }

    suspend fun setGamesTreeUri(context: Context, uri: String) {
        context.dataStore.edit { it[K_TREE_URI] = uri }
    }

    suspend fun setDirectRoot(context: Context, path: String) {
        context.dataStore.edit { it[K_DIRECT_ROOT] = path }
    }

    suspend fun setSourceMode(context: Context, mode: SourceMode) {
        context.dataStore.edit { it[K_SOURCE_MODE] = mode.name }
    }

    // ------------------------------------------------------------ core config

    fun emuConfig(context: Context): Flow<EmuConfig> = context.dataStore.data.map { prefs ->
        EmuConfig(
            resolutionScale = prefs[K_RES_SCALE] ?: 1.0f,
            copyCommandBuffers = prefs[toggleKeys.getValue("copy_command_buffers")] ?: false,
            skipAsyncComputeDispatches = prefs[toggleKeys.getValue("skip_async_compute")] ?: false,
            skipWaitRegMem = prefs[toggleKeys.getValue("skip_waitregmem")] ?: false,
            disableGnmDetilerTextureSize = prefs[toggleKeys.getValue("disable_detiler_texsize")] ?: false,
            disableSgprInitHack = prefs[toggleKeys.getValue("disable_sgpr_hack")] ?: false,
            clampGpuBuffers = prefs[toggleKeys.getValue("clamp_gpu_buffers")] ?: false,
            skipBindlessBuffers = prefs[toggleKeys.getValue("skip_bindless")] ?: false,
            forceInitSceCompositor = prefs[toggleKeys.getValue("force_compositor")] ?: false,
            lleSsl = prefs[toggleKeys.getValue("lle_ssl")] ?: false,
        )
    }

    suspend fun setResolutionScale(context: Context, scale: Float) {
        context.dataStore.edit { it[K_RES_SCALE] = scale }
    }

    suspend fun setToggle(context: Context, configField: String, value: Boolean) {
        val dataStoreKey = Keys.TOGGLES.entries.firstOrNull { it.value == configField }?.key
            ?: return
        context.dataStore.edit { it[booleanPreferencesKey(dataStoreKey)] = value }
    }

    /** Snapshot for the pre-boot push to native. */
    suspend fun snapshot(context: Context): EmuConfig = emuConfig(context).first()
}
