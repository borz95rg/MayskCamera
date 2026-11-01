package com.maysk.camera.ar3d

/** Наборы цветов макияжа (ARGB). Названия — для чипов в интерфейсе. */
object MakeupPalette {
    class Shade(val name: String, val argb: Int)

    val LIPS = listOf(
        Shade("Роза", 0xFFC2335A.toInt()),
        Shade("Красная", 0xFFB3122A.toInt()),
        Shade("Коралл", 0xFFE2574C.toInt()),
        Shade("Нюд", 0xFFB5707A.toInt()),
        Shade("Ягода", 0xFF8E2150.toInt()),
    )
    val SHADOWS = listOf(
        Shade("Лилак", 0xFF8A6AA8.toInt()),
        Shade("Бронза", 0xFFA0703C.toInt()),
        Shade("Роза", 0xFFC2788A.toInt()),
        Shade("Дымка", 0xFF5C5F72.toInt()),
    )
    val BLUSH = listOf(
        Shade("Персик", 0xFFE8707A.toInt()),
        Shade("Роза", 0xFFE0508A.toInt()),
        Shade("Тёплый", 0xFFD9694A.toInt()),
    )
}
