package com.maysk.camera.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.maysk.camera.ui.theme.AppTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "maysk_settings")

enum class VideoRes(val label: String) { HD("720p"), FHD("1080p"), UHD("4K"), UHD8K("8K") }
enum class Stabilization(val label: String) { OFF("Выкл."), STANDARD("Норм."), ULTRA("Ультра") }
enum class ColorProfile(val label: String) { STANDARD("Стандартный"), LOG("Плоский (LOG)") }
enum class Bitrate(val label: String, val mbpsPer1080: Int) { LOW("Низкий", 8), STANDARD("Средний", 16), HIGH("Высокий", 28) }
enum class AudioMode(val label: String, val hint: String) {
    NORMAL("Обычный", "Без обработки"),
    AROUND("Вокруг", "Убрать ветер, сохранить атмосферу"),
    VOICE("Фокус на голос", "Шумоподавление + усиление голоса"),
    STUDIO("Студия", "Чистый голос, компрессия, без шума")
}

data class AppSettings(
    val theme: AppTheme = AppTheme.DARK,
    val onboardingDone: Boolean = false,
    val photoQualityHigh: Boolean = true,
    val grid: Boolean = true,
    val horizon: Boolean = true,
    val shutterSound: Boolean = true,
    val voiceControl: Boolean = false,
    val volumeShutter: Boolean = true,
    val smartAi: Boolean = true,
    val geotag: Boolean = false,
    val saveOriginals: Boolean = false,
    val mirrorFront: Boolean = true,
    // видео
    val videoRes: VideoRes = VideoRes.FHD,
    val fps: Int = 30,
    val stabilization: Stabilization = Stabilization.STANDARD,
    val colorProfile: ColorProfile = ColorProfile.STANDARD,
    val bitrate: Bitrate = Bitrate.HIGH,
    val audioMode: AudioMode = AudioMode.VOICE,
    // AI улучшение видео
    val aiVideoEnabled: Boolean = true,
    val aiVideoLight: Boolean = true,
    val aiVideoNoise: Boolean = true,
    val aiVideoColor: Boolean = true,
    val aiVideoSkin: Boolean = false,
    // AI красота
    val beautySkin: Float = 0.3f,
    val beautyEyes: Float = 0.2f,
    val beautyFace: Float = 0.15f,
)

class SettingsRepository(private val context: Context) {
    private object K {
        val theme = stringPreferencesKey("theme")
        val onboarding = booleanPreferencesKey("onboarding")
        val photoHigh = booleanPreferencesKey("photo_high")
        val grid = booleanPreferencesKey("grid")
        val horizon = booleanPreferencesKey("horizon")
        val shutterSound = booleanPreferencesKey("shutter_sound")
        val voice = booleanPreferencesKey("voice")
        val volume = booleanPreferencesKey("volume_shutter")
        val smartAi = booleanPreferencesKey("smart_ai")
        val geotag = booleanPreferencesKey("geotag")
        val originals = booleanPreferencesKey("originals")
        val mirror = booleanPreferencesKey("mirror")
        val videoRes = stringPreferencesKey("video_res")
        val fps = intPreferencesKey("fps")
        val stab = stringPreferencesKey("stab")
        val color = stringPreferencesKey("color_profile")
        val bitrate = stringPreferencesKey("bitrate")
        val audio = stringPreferencesKey("audio")
        val aiVideo = booleanPreferencesKey("ai_video")
        val aiLight = booleanPreferencesKey("ai_light")
        val aiNoise = booleanPreferencesKey("ai_noise")
        val aiColor = booleanPreferencesKey("ai_color")
        val aiSkin = booleanPreferencesKey("ai_skin")
        val bSkin = floatPreferencesKey("b_skin")
        val bEyes = floatPreferencesKey("b_eyes")
        val bFace = floatPreferencesKey("b_face")
    }

    val flow: Flow<AppSettings> = context.dataStore.data.map { p -> p.toSettings() }

    private inline fun <reified E : Enum<E>> Preferences.enum(key: Preferences.Key<String>, def: E): E =
        this[key]?.let { v -> enumValues<E>().firstOrNull { it.name == v } } ?: def

    private fun Preferences.toSettings(): AppSettings {
        val d = AppSettings()
        return AppSettings(
            theme = enum(K.theme, d.theme),
            onboardingDone = this[K.onboarding] ?: d.onboardingDone,
            photoQualityHigh = this[K.photoHigh] ?: d.photoQualityHigh,
            grid = this[K.grid] ?: d.grid,
            horizon = this[K.horizon] ?: d.horizon,
            shutterSound = this[K.shutterSound] ?: d.shutterSound,
            voiceControl = this[K.voice] ?: d.voiceControl,
            volumeShutter = this[K.volume] ?: d.volumeShutter,
            smartAi = this[K.smartAi] ?: d.smartAi,
            geotag = this[K.geotag] ?: d.geotag,
            saveOriginals = this[K.originals] ?: d.saveOriginals,
            mirrorFront = this[K.mirror] ?: d.mirrorFront,
            videoRes = enum(K.videoRes, d.videoRes),
            fps = this[K.fps] ?: d.fps,
            stabilization = enum(K.stab, d.stabilization),
            colorProfile = enum(K.color, d.colorProfile),
            bitrate = enum(K.bitrate, d.bitrate),
            audioMode = enum(K.audio, d.audioMode),
            aiVideoEnabled = this[K.aiVideo] ?: d.aiVideoEnabled,
            aiVideoLight = this[K.aiLight] ?: d.aiVideoLight,
            aiVideoNoise = this[K.aiNoise] ?: d.aiVideoNoise,
            aiVideoColor = this[K.aiColor] ?: d.aiVideoColor,
            aiVideoSkin = this[K.aiSkin] ?: d.aiVideoSkin,
            beautySkin = this[K.bSkin] ?: d.beautySkin,
            beautyEyes = this[K.bEyes] ?: d.beautyEyes,
            beautyFace = this[K.bFace] ?: d.beautyFace,
        )
    }

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.dataStore.edit { p ->
            val s = transform(p.toSettings())
            p[K.theme] = s.theme.name
            p[K.onboarding] = s.onboardingDone
            p[K.photoHigh] = s.photoQualityHigh
            p[K.grid] = s.grid
            p[K.horizon] = s.horizon
            p[K.shutterSound] = s.shutterSound
            p[K.voice] = s.voiceControl
            p[K.volume] = s.volumeShutter
            p[K.smartAi] = s.smartAi
            p[K.geotag] = s.geotag
            p[K.originals] = s.saveOriginals
            p[K.mirror] = s.mirrorFront
            p[K.videoRes] = s.videoRes.name
            p[K.fps] = s.fps
            p[K.stab] = s.stabilization.name
            p[K.color] = s.colorProfile.name
            p[K.bitrate] = s.bitrate.name
            p[K.audio] = s.audioMode.name
            p[K.aiVideo] = s.aiVideoEnabled
            p[K.aiLight] = s.aiVideoLight
            p[K.aiNoise] = s.aiVideoNoise
            p[K.aiColor] = s.aiVideoColor
            p[K.aiSkin] = s.aiVideoSkin
            p[K.bSkin] = s.beautySkin
            p[K.bEyes] = s.beautyEyes
            p[K.bFace] = s.beautyFace
        }
    }

    suspend fun reset() {
        context.dataStore.edit { it.clear(); it[K.onboarding] = true }
    }
}
