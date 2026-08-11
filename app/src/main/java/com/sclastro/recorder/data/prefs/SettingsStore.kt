package com.sclastro.recorder.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.sclastro.recorder.audio.AudioContainer
import com.sclastro.recorder.audio.BitDepth
import com.sclastro.recorder.audio.Channels
import com.sclastro.recorder.audio.MicSource
import com.sclastro.recorder.audio.RecordingConfig
import com.sclastro.recorder.data.FileNaming
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore("recorder_settings")

enum class ThemeMode(val label: String) { SYSTEM("System"), LIGHT("Light"), DARK("Dark") }

data class AppSettings(
    val config: RecordingConfig = RecordingConfig(),
    val presetName: String = "Meeting",
    val filenameTemplate: String = FileNaming.DEFAULT_TEMPLATE,
    val askNameAfterRecording: Boolean = true,
    val defaultFolder: String = "",
    val keepScreenOn: Boolean = true,
    val trashRetentionDays: Int = 30,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
)

class SettingsStore(private val context: Context) {

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            config = RecordingConfig(
                sampleRate = p[SAMPLE_RATE] ?: 44100,
                bitDepth = enumOr(p[BIT_DEPTH], BitDepth.PCM_16),
                channels = enumOr(p[CHANNELS], Channels.MONO),
                container = enumOr(p[CONTAINER], AudioContainer.M4A),
                bitrateKbps = p[BITRATE] ?: 128,
                source = enumOr(p[SOURCE], MicSource.MIC),
                echoCancel = p[AEC] == true,
                noiseSuppress = p[NS] == true,
                autoGain = p[AGC] == true,
            ),
            presetName = p[PRESET] ?: "Meeting",
            filenameTemplate = p[TEMPLATE] ?: FileNaming.DEFAULT_TEMPLATE,
            askNameAfterRecording = p[ASK_NAME] != false,
            defaultFolder = p[DEFAULT_FOLDER] ?: "",
            keepScreenOn = p[KEEP_SCREEN_ON] != false,
            trashRetentionDays = p[TRASH_DAYS] ?: 30,
            themeMode = enumOr(p[THEME], ThemeMode.SYSTEM),
        )
    }

    suspend fun setConfig(config: RecordingConfig) = context.dataStore.edit { p ->
        p[SAMPLE_RATE] = config.sampleRate
        p[BIT_DEPTH] = config.bitDepth.name
        p[CHANNELS] = config.channels.name
        p[CONTAINER] = config.container.name
        p[BITRATE] = config.bitrateKbps
        p[SOURCE] = config.source.name
        p[AEC] = config.echoCancel
        p[NS] = config.noiseSuppress
        p[AGC] = config.autoGain
    }

    suspend fun setPresetName(name: String) {
        context.dataStore.edit { it[PRESET] = name }
    }

    suspend fun setTemplate(value: String) {
        context.dataStore.edit { it[TEMPLATE] = value }
    }

    suspend fun setAskName(value: Boolean) {
        context.dataStore.edit { it[ASK_NAME] = value }
    }

    suspend fun setDefaultFolder(value: String) {
        context.dataStore.edit { it[DEFAULT_FOLDER] = value }
    }

    suspend fun setKeepScreenOn(value: Boolean) {
        context.dataStore.edit { it[KEEP_SCREEN_ON] = value }
    }

    suspend fun setTrashRetentionDays(value: Int) {
        context.dataStore.edit { it[TRASH_DAYS] = value }
    }

    suspend fun setThemeMode(value: ThemeMode) {
        context.dataStore.edit { it[THEME] = value.name }
    }

    /** Monotonic counter behind the {seq} filename token. */
    suspend fun nextSequence(): Int {
        var next = 1
        context.dataStore.edit { p ->
            next = p[SEQUENCE] ?: 1
            p[SEQUENCE] = next + 1
        }
        return next
    }

    private inline fun <reified T : Enum<T>> enumOr(name: String?, fallback: T): T =
        name?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: fallback

    private companion object {
        val SAMPLE_RATE = intPreferencesKey("sample_rate")
        val BIT_DEPTH = stringPreferencesKey("bit_depth")
        val CHANNELS = stringPreferencesKey("channels")
        val CONTAINER = stringPreferencesKey("container")
        val BITRATE = intPreferencesKey("bitrate")
        val SOURCE = stringPreferencesKey("source")
        val AEC = booleanPreferencesKey("aec")
        val NS = booleanPreferencesKey("ns")
        val AGC = booleanPreferencesKey("agc")
        val PRESET = stringPreferencesKey("preset")
        val TEMPLATE = stringPreferencesKey("template")
        val ASK_NAME = booleanPreferencesKey("ask_name")
        val DEFAULT_FOLDER = stringPreferencesKey("default_folder")
        val KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
        val TRASH_DAYS = intPreferencesKey("trash_days")
        val SEQUENCE = intPreferencesKey("sequence")
        val THEME = stringPreferencesKey("theme")
    }
}
