package com.maysk.camera.ar3d

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Пакет шаблонов эффектов: все кусочки, рамки и фоны на месте, привязки к лицу в разумных пределах. */
class EffectTemplatesTest {
    private val assets = listOf("src/main/assets", "app/src/main/assets").map(::File).first { it.isDirectory }

    @Test fun packIsComplete() {
        val p = EffectTemplateData.parse(File(assets, "effects/templates.json").readText())
        assertEquals(21, p.faces.size)
        assertEquals(3, p.frames.size)
        assertEquals(8, p.backgrounds.size)
        for (f in p.faces) {
            assertTrue("${f.id}: нет кусочков", f.pieces.isNotEmpty())
            assertTrue("${f.id}: превью", File(assets, f.thumb).isFile)
            for (pc in f.pieces) {
                assertTrue("${f.id}: ${pc.png}", File(assets, pc.png).isFile)
                assertEquals(3, pc.at.size)
                assertTrue("${f.id}: ширина", pc.w in 0.1f..3f)
                assertTrue("${f.id}: точка на лице", pc.at.all { it in -2f..2f })
                assertTrue(pc.align == "center" || pc.align == "bottom")
            }
        }
        p.frames.forEach { assertTrue(File(assets, it.png).isFile) }
        p.backgrounds.forEach { assertTrue(File(assets, it.jpg).isFile); assertTrue(File(assets, it.thumb).isFile) }
    }
}
