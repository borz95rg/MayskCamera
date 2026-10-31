package com.maysk.camera.ar3d

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Проверка настоящих GLB-моделей из assets: формат, геометрия, нормали, цвета, каталог. */
class GlbLoaderTest {
    private val dir = listOf("src/main/assets/ar_models", "app/src/main/assets/ar_models").map(::File).first { it.isDirectory }

    @Test fun allCatalogModelsLoad() {
        val catalog = ArCatalogData.parse(File(dir, "catalog.json").readText())
        assertEquals(15, catalog.size)
        for (e in catalog) {
            val f = File(dir.parentFile, e.file)
            assertTrue("нет файла ${e.file}", f.isFile)
            val m = GlbLoader.parse(f.readBytes())
            assertTrue("${e.id}: нет частей", m.parts.isNotEmpty())
            assertEquals("${e.id}: число треугольников", e.triangles, m.triangleCount)
            assertEquals(e.title, m.title)
            for (p in m.parts) {
                assertEquals(p.vertexCount * 3, p.normals.size)
                assertEquals(p.vertexCount * 4, p.colors.size)
                assertTrue(p.indices.all { it in 0 until p.vertexCount })
                assertTrue("${e.id}/${p.name}: индексы 16 бит", p.vertexCount < 65536)
                // нормали единичные
                for (i in 0 until p.vertexCount) {
                    val l = Math.sqrt((p.normals[i * 3] * p.normals[i * 3] + p.normals[i * 3 + 1] * p.normals[i * 3 + 1] + p.normals[i * 3 + 2] * p.normals[i * 3 + 2]).toDouble())
                    assertEquals(1.0, l, 1e-3)
                }
                // модели в «лицевых единицах»: в пределах ±3 расстояний между глазами
                assertTrue("${e.id}: размер", p.positions.all { it in -3f..3f })
            }
        }
    }

    @Test fun mouthOpenPartIsTagged() {
        val m = GlbLoader.parse(File(dir, "dog.glb").readBytes())
        assertTrue(m.parts.any { it.showWhen == "mouthOpen" })
    }

    @Test fun transparentPartsDrawnLast() {
        val m = GlbLoader.parse(File(dir, "glasses.glb").readBytes())
        val firstBlend = m.parts.indexOfFirst { it.blend }
        assertTrue(firstBlend >= 0)
        assertTrue(m.parts.drop(firstBlend).all { it.blend })
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonGlb() { GlbLoader.parse(ByteArray(64)) }

    @Test fun nodeTransformsApplied() {
        // минимальный GLB: один треугольник, узел со сдвигом +2 по X и масштабом 2
        val pos = floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f)
        val bin = java.nio.ByteBuffer.allocate(36).order(java.nio.ByteOrder.LITTLE_ENDIAN).apply { pos.forEach { putFloat(it) } }.array()
        val json = """{"asset":{"version":"2.0"},"scene":0,"scenes":[{"nodes":[0]}],
            "nodes":[{"mesh":0,"translation":[2,0,0],"scale":[2,2,2],"name":"tri@mouthOpen"}],
            "meshes":[{"primitives":[{"attributes":{"POSITION":0}}]}],
            "accessors":[{"bufferView":0,"componentType":5126,"count":3,"type":"VEC3"}],
            "bufferViews":[{"buffer":0,"byteOffset":0,"byteLength":36}],"buffers":[{"byteLength":36}]}"""
        val m = GlbLoader.parse(makeGlb(json, bin))
        val p = m.parts.single()
        assertEquals(2f, p.positions[0], 1e-6f)
        assertEquals(4f, p.positions[3], 1e-6f)
        assertEquals(2f, p.positions[7], 1e-6f)
        assertEquals("mouthOpen", p.showWhen)
        assertEquals(1f, Math.abs(p.normals[2]), 1e-5f)   // нормаль посчитана сама
    }

    private fun makeGlb(json: String, bin: ByteArray): ByteArray {
        var js = json.toByteArray(); while (js.size % 4 != 0) js += ' '.code.toByte()
        val total = 12 + 8 + js.size + 8 + bin.size
        return java.nio.ByteBuffer.allocate(total).order(java.nio.ByteOrder.LITTLE_ENDIAN).apply {
            putInt(0x46546C67); putInt(2); putInt(total)
            putInt(js.size); putInt(0x4E4F534A); put(js)
            putInt(bin.size); putInt(0x004E4942); put(bin)
        }.array()
    }
}
