package com.maysk.camera.ar3d

/** Описание шаблонов эффектов (templates.json) — без Android, проверяется JVM-тестом. */
object EffectTemplateData {
    class Piece(val png: String, val at: FloatArray, val w: Float, val align: String, val aspect: Float)
    class Face(val id: String, val title: String, val category: String, val thumb: String, val pieces: List<Piece>)
    class Frame(val id: String, val title: String, val png: String, val thumb: String, val mode: String)
    class Background(val id: String, val title: String, val jpg: String, val thumb: String)
    class Pack(val faces: List<Face>, val frames: List<Frame>, val backgrounds: List<Background>)

    /** Разбор templates.json (без Android — проверяется JVM-тестом). */
    fun parse(text: String): Pack {
        val root = MiniJson.parse(text).obj()
        val faces = root["faces"].list().map { f ->
            val o = f.obj()
            Face(o["id"] as String, o["title"] as? String ?: "", o["category"] as? String ?: "", o["thumb"] as? String ?: "",
                o["pieces"].list().map { p ->
                    val q = p.obj()
                    Piece(q["png"] as String, q["at"].floats(), q["w"].float(1f), q["align"] as? String ?: "center", q["aspect"].float(1f))
                })
        }
        val frames = root["frames"].list().map { f ->
            val o = f.obj(); Frame(o["id"] as String, o["title"] as? String ?: "", o["png"] as String, o["thumb"] as? String ?: "", o["mode"] as? String ?: "stretch")
        }
        val bgs = root["backgrounds"].list().map { f ->
            val o = f.obj(); Background(o["id"] as String, o["title"] as? String ?: "", o["jpg"] as String, o["thumb"] as? String ?: "")
        }
        return Pack(faces, frames, bgs)
    }

}
