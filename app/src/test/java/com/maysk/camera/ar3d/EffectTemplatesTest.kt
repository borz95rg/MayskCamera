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
        // Расширенный набор: животные / 3D / украшения / макияж (раньше было 21)
        assertTrue("faces: expected >= 21, got ${p.faces.size}", p.faces.size >= 21)
        assertEquals(3, p.frames.size)
        assertEquals(8, p.backgrounds.size)
        val ids = mutableSetOf<String>()
        for (f in p.faces) {
            assertTrue("${f.id}: duplicate id", ids.add(f.id))
            assertTrue("${f.id}: нет кусочков", f.pieces.isNotEmpty())
            assertTrue("${f.id}: превью ${f.thumb}", File(assets, f.thumb).isFile)
            for (pc in f.pieces) {
                assertTrue("${f.id}: ${pc.png}", File(assets, pc.png).isFile)
                assertEquals("${f.id}: at size", 3, pc.at.size)
                assertTrue("${f.id}: ширина ${pc.w}", pc.w in 0.1f..3f)
                assertTrue("${f.id}: точка на лице ${pc.at.toList()}", pc.at.all { it in -2f..2f })
                assertTrue("${f.id}: align ${pc.align}", pc.align == "center" || pc.align == "bottom")
                assertTrue("${f.id}: aspect ${pc.aspect}", pc.aspect > 0f && pc.aspect < 5f)
                // kids coords optional but must be valid if present
                pc.atKids?.let { kids ->
                    assertEquals("${f.id}: atKids size", 3, kids.size)
                    assertTrue("${f.id}: atKids range", kids.all { it in -2f..2f })
                }
                if (pc.wKids > 0f) {
                    assertTrue("${f.id}: wKids ${pc.wKids}", pc.wKids in 0.1f..3f)
                }
            }
        }
        p.frames.forEach { assertTrue(it.png, File(assets, it.png).isFile) }
        p.backgrounds.forEach {
            assertTrue(it.jpg, File(assets, it.jpg).isFile)
            assertTrue(it.thumb, File(assets, it.thumb).isFile)
        }
    }
}
