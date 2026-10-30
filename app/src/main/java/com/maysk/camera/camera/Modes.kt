package com.maysk.camera.camera

import androidx.camera.extensions.ExtensionMode

enum class ModeGroup { PHOTO, VIDEO, SPECIAL }

/**
 * Все режимы съёмки из ТЗ. [extension] — аппаратный режим производителя телефона
 * (Night/HDR/Bokeh/Retouch) через CameraX Extensions; если телефон его не поддерживает,
 * Maysk использует собственную многокадровую обработку.
 */
enum class CameraMode(
    val title: String,
    val subtitle: String,
    val group: ModeGroup,
    val isVideo: Boolean = false,
    val filterId: String? = null,
    val extension: Int? = null,
    val evBias: Int = 0,
    val tip: String? = null,
) {
    AI_AUTO("AI Авто", "Умная съёмка", ModeGroup.PHOTO, extension = ExtensionMode.AUTO),
    PHOTO("Фото", "Обычная съёмка", ModeGroup.PHOTO),
    PRO("Профи", "Ручные настройки", ModeGroup.PHOTO),
    PORTRAIT("Портрет", "Размытие фона", ModeGroup.PHOTO, filterId = "natural_skin", extension = ExtensionMode.BOKEH),
    NIGHT("Ночь", "Съёмка в темноте", ModeGroup.PHOTO, filterId = "night", extension = ExtensionMode.NIGHT,
        tip = "Съёмка нескольких кадров с автоматической обработкой. Не двигайте камеру"),
    LANDSCAPE("Пейзаж", "Природа и города", ModeGroup.PHOTO, filterId = "nature", extension = ExtensionMode.HDR),
    FOOD("Еда", "Яркие цвета", ModeGroup.PHOTO, filterId = "food", evBias = 1),
    MACRO("Макро", "Съёмка крупным планом", ModeGroup.SPECIAL, tip = "Поднесите камеру на 3–10 см к объекту"),
    DOCUMENT("Документы", "Сканер", ModeGroup.SPECIAL),
    PANORAMA("Панорама", "360° панорама", ModeGroup.SPECIAL, tip = "Плавно двигайте камеру в указанном направлении"),
    PET("Питомцы", "Быстрый затвор", ModeGroup.SPECIAL, filterId = "vivid"),
    BABY("Дети", "Мягкие тона", ModeGroup.SPECIAL, filterId = "soft_portrait"),
    CAR("Авто", "Блеск и контраст", ModeGroup.SPECIAL, filterId = "hollywood"),
    // Видео
    VIDEO("Видео", "Обычное (1080p)", ModeGroup.VIDEO, isVideo = true),
    VIDEO_4K("4K", "4K / 60fps", ModeGroup.VIDEO, isVideo = true),
    VIDEO_8K("8K", "8K / 30fps", ModeGroup.VIDEO, isVideo = true),
    SLOW_MOTION("Замедленная", "120 / 240 fps", ModeGroup.VIDEO, isVideo = true),
    TIMELAPSE("Таймлапс", "Ускоренное видео", ModeGroup.VIDEO, isVideo = true),
    CINEMATIC("Кино режим", "Кинокачество", ModeGroup.VIDEO, isVideo = true, filterId = "cinema"),
    LOG("Лог режим", "Плоский профиль", ModeGroup.VIDEO, isVideo = true),
    VLOG("Влог", "Фронтальная, стабилизация", ModeGroup.VIDEO, isVideo = true, filterId = "natural_skin"),
    INTERVIEW("Интервью", "Фокус на голос и лицо", ModeGroup.VIDEO, isVideo = true, filterId = "studio");

    /** Режим пишет видео через VideoCapture (таймлапс собирается из кадров). */
    val recordsVideo: Boolean get() = isVideo && this != TIMELAPSE
}
