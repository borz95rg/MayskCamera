package com.maysk.camera.ar3d

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * 3D-модель маски, загруженная из GLB (glTF 2.0, бинарный контейнер).
 *
 * Поддерживается: несколько мешей и узлов (иерархия, matrix или TRS), треугольники,
 * POSITION / NORMAL / COLOR_0, индексы 8/16/32 бит, baseColorFactor и прозрачность материала
 * (alphaMode BLEND). Текстуры и скелетная анимация glTF не поддерживаются — модель
 * рисуется вершинными цветами (так сделаны все модели Maysk, см. tools/ar_models).
 *
 * Имя узла или меша с суффиксом «@mouthOpen» — часть, видимая только при открытом рте.
 */
class GlbModel(val title: String, val category: String, val parts: List<Part>) {
    class Part(
        val name: String,
        val positions: FloatArray,   // xyz на вершину, «лицевые единицы»
        val normals: FloatArray,     // xyz на вершину
        val colors: FloatArray,      // rgba на вершину (уже умножены на baseColorFactor)
        val indices: IntArray,
        val blend: Boolean,
        val showWhen: String?,       // null — всегда; "mouthOpen" — при открытом рте
    ) {
        val vertexCount get() = positions.size / 3
    }

    val triangleCount get() = parts.sumOf { it.indices.size / 3 }
}

object GlbLoader {
    private const val MAGIC = 0x46546C67      // "glTF"
    private const val CHUNK_JSON = 0x4E4F534A
    private const val CHUNK_BIN = 0x004E4942

    fun parse(bytes: ByteArray): GlbModel {
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(bytes.size >= 20 && bb.getInt(0) == MAGIC) { "Не GLB-файл" }
        require(bb.getInt(4) == 2) { "Нужен glTF 2.0" }
        var off = 12
        var json: String? = null
        var bin: ByteBuffer? = null
        while (off + 8 <= bytes.size) {
            val len = bb.getInt(off); val type = bb.getInt(off + 4)
            val start = off + 8
            require(start + len <= bytes.size) { "Повреждённый GLB: блок выходит за файл" }
            when (type) {
                CHUNK_JSON -> json = String(bytes, start, len, Charsets.UTF_8)
                CHUNK_BIN -> bin = ByteBuffer.wrap(bytes, start, len).slice().order(ByteOrder.LITTLE_ENDIAN)
            }
            off = start + len
        }
        val root = MiniJson.parse(requireNotNull(json) { "GLB без JSON" }.trim()).obj()
        return build(root, bin)
    }

    private fun build(root: Map<String, Any?>, bin: ByteBuffer?): GlbModel {
        val extras = root["asset"].obj()["extras"].obj()["maysk"].obj()
        val accessors = root["accessors"].list()
        val views = root["bufferViews"].list()
        val meshes = root["meshes"].list()
        val materials = root["materials"].list()
        val nodes = root["nodes"].list()

        fun read(accIndex: Int): Pair<FloatArray, Int> {
            val acc = accessors[accIndex].obj()
            val comps = when (acc["type"] as? String) { "SCALAR" -> 1; "VEC2" -> 2; "VEC3" -> 3; "VEC4" -> 4; "MAT4" -> 16; else -> error("Тип accessor") }
            val count = acc["count"].int()
            val ct = acc["componentType"].int()
            val normalized = acc["normalized"] == true
            val view = views[acc["bufferView"].int()].obj()
            val b = requireNotNull(bin) { "GLB без бинарного блока" }
            val base = view["byteOffset"].int() + acc["byteOffset"].int()
            val size = when (ct) { 5120, 5121 -> 1; 5122, 5123 -> 2; 5125, 5126 -> 4; else -> error("componentType $ct") }
            val stride = view["byteStride"].int(comps * size).let { if (it == 0) comps * size else it }
            require(base + stride * (count - 1) + comps * size <= b.limit()) { "Accessor выходит за буфер" }
            val out = FloatArray(count * comps)
            for (i in 0 until count) for (c in 0 until comps) {
                val p = base + i * stride + c * size
                out[i * comps + c] = when (ct) {
                    5126 -> b.getFloat(p)
                    5121 -> (b.get(p).toInt() and 0xFF).let { if (normalized) it / 255f else it.toFloat() }
                    5123 -> (b.getShort(p).toInt() and 0xFFFF).let { if (normalized) it / 65535f else it.toFloat() }
                    5125 -> (b.getInt(p).toLong() and 0xFFFFFFFFL).toFloat()
                    5120 -> b.get(p).toFloat().let { if (normalized) maxOf(it / 127f, -1f) else it }
                    5122 -> b.getShort(p).toFloat().let { if (normalized) maxOf(it / 32767f, -1f) else it }
                    else -> 0f
                }
            }
            return out to comps
        }

        val parts = ArrayList<GlbModel.Part>()

        fun visit(nodeIndex: Int, parent: FloatArray) {
            val node = nodes[nodeIndex].obj()
            val world = Mat4.multiply(parent, nodeMatrix(node))
            val nodeName = node["name"] as? String ?: "node$nodeIndex"
            node["mesh"]?.let { meshIdx ->
                val mesh = meshes[meshIdx.int()].obj()
                val meshName = mesh["name"] as? String ?: nodeName
                val normalMat = Mat4.normalMatrix(world)
                for (primAny in mesh["primitives"].list()) {
                    val prim = primAny.obj()
                    val mode = prim["mode"].int(4)
                    if (mode != 4) continue                       // только треугольники
                    val attrs = prim["attributes"].obj()
                    val pos = read(attrs["POSITION"].int(-1).also { require(it >= 0) { "Нет POSITION" } }).first
                    val n = pos.size / 3
                    val nrm = attrs["NORMAL"]?.let { read(it.int()).first } ?: FloatArray(0)
                    val colRaw = attrs["COLOR_0"]?.let { read(it.int()) }
                    val idx = prim["indices"]?.let { a -> read(a.int()).first.map { it.toInt() }.toIntArray() } ?: IntArray(n) { it }
                    require(idx.all { it in 0 until n }) { "Индекс вершины вне диапазона" }
                    val mat = prim["material"]?.let { materials.getOrNull(it.int()).obj() } ?: emptyMap()
                    val base = mat["pbrMetallicRoughness"].obj()["baseColorFactor"].floats().takeIf { it.size == 4 } ?: floatArrayOf(1f, 1f, 1f, 1f)
                    val blend = (mat["alphaMode"] as? String) == "BLEND"
                    // позиции и нормали в мировые координаты модели
                    val p2 = FloatArray(n * 3); val n2 = FloatArray(n * 3)
                    for (i in 0 until n) {
                        Mat4.transformPoint(world, pos, i * 3, p2, i * 3)
                        if (nrm.size == n * 3) Mat4.transformDir(normalMat, nrm, i * 3, n2, i * 3)
                    }
                    if (nrm.size != n * 3) computeNormals(p2, idx, n2) else normalize(n2)
                    val colors = FloatArray(n * 4)
                    for (i in 0 until n) {
                        val (cr, comps) = colRaw ?: (null to 0)
                        for (c in 0 until 4) {
                            val v = if (cr != null && c < comps) cr[i * comps + c] else 1f
                            colors[i * 4 + c] = v * base[c]
                        }
                    }
                    val tag = listOf(nodeName, meshName).firstNotNullOfOrNull { nm -> nm.substringAfter('@', "").takeIf { it.isNotEmpty() } }
                    parts += GlbModel.Part(meshName, p2, n2, colors, idx, blend, tag)
                }
            }
            for (child in node["children"].list()) visit(child.int(), world)
        }

        val scene = root["scenes"].list().getOrNull(root["scene"].int(0)).obj()
        val roots = scene["nodes"].list().map { it.int() }.ifEmpty { nodes.indices.toList() }
        for (r in roots) visit(r, Mat4.identity())
        require(parts.isNotEmpty()) { "В модели нет треугольников" }
        // прозрачные части рисуются последними
        val sorted = parts.sortedBy { if (it.blend) 1 else 0 }
        return GlbModel(extras["title"] as? String ?: "", extras["category"] as? String ?: "", sorted)
    }

    private fun nodeMatrix(node: Map<String, Any?>): FloatArray {
        node["matrix"].floats().takeIf { it.size == 16 }?.let { return it }
        val t = node["translation"].floats().takeIf { it.size == 3 } ?: floatArrayOf(0f, 0f, 0f)
        val r = node["rotation"].floats().takeIf { it.size == 4 } ?: floatArrayOf(0f, 0f, 0f, 1f)
        val s = node["scale"].floats().takeIf { it.size == 3 } ?: floatArrayOf(1f, 1f, 1f)
        return Mat4.trs(t, r, s)
    }

    private fun normalize(n: FloatArray) {
        for (i in 0 until n.size / 3) {
            val l = sqrt(n[i * 3] * n[i * 3] + n[i * 3 + 1] * n[i * 3 + 1] + n[i * 3 + 2] * n[i * 3 + 2]).coerceAtLeast(1e-6f)
            n[i * 3] /= l; n[i * 3 + 1] /= l; n[i * 3 + 2] /= l
        }
    }

    private fun computeNormals(p: FloatArray, idx: IntArray, out: FloatArray) {
        java.util.Arrays.fill(out, 0f)
        for (t in 0 until idx.size / 3) {
            val a = idx[t * 3] * 3; val b = idx[t * 3 + 1] * 3; val c = idx[t * 3 + 2] * 3
            val ux = p[b] - p[a]; val uy = p[b + 1] - p[a + 1]; val uz = p[b + 2] - p[a + 2]
            val vx = p[c] - p[a]; val vy = p[c + 1] - p[a + 1]; val vz = p[c + 2] - p[a + 2]
            val nx = uy * vz - uz * vy; val ny = uz * vx - ux * vz; val nz = ux * vy - uy * vx
            for (k in intArrayOf(a, b, c)) { out[k] += nx; out[k + 1] += ny; out[k + 2] += nz }
        }
        normalize(out)
    }
}

/** Матрицы 4x4 по столбцам (как в glTF и OpenGL). */
object Mat4 {
    fun identity() = FloatArray(16).also { it[0] = 1f; it[5] = 1f; it[10] = 1f; it[15] = 1f }

    fun multiply(a: FloatArray, b: FloatArray): FloatArray {
        val r = FloatArray(16)
        for (c in 0 until 4) for (row in 0 until 4) {
            var s = 0f
            for (k in 0 until 4) s += a[k * 4 + row] * b[c * 4 + k]
            r[c * 4 + row] = s
        }
        return r
    }

    fun trs(t: FloatArray, q: FloatArray, s: FloatArray): FloatArray {
        val (x, y, z, w) = q.toList()
        val m = FloatArray(16)
        m[0] = (1 - 2 * (y * y + z * z)) * s[0]; m[1] = (2 * (x * y + z * w)) * s[0]; m[2] = (2 * (x * z - y * w)) * s[0]
        m[4] = (2 * (x * y - z * w)) * s[1]; m[5] = (1 - 2 * (x * x + z * z)) * s[1]; m[6] = (2 * (y * z + x * w)) * s[1]
        m[8] = (2 * (x * z + y * w)) * s[2]; m[9] = (2 * (y * z - x * w)) * s[2]; m[10] = (1 - 2 * (x * x + y * y)) * s[2]
        m[12] = t[0]; m[13] = t[1]; m[14] = t[2]; m[15] = 1f
        return m
    }

    fun transformPoint(m: FloatArray, src: FloatArray, si: Int, dst: FloatArray, di: Int) {
        val x = src[si]; val y = src[si + 1]; val z = src[si + 2]
        dst[di] = m[0] * x + m[4] * y + m[8] * z + m[12]
        dst[di + 1] = m[1] * x + m[5] * y + m[9] * z + m[13]
        dst[di + 2] = m[2] * x + m[6] * y + m[10] * z + m[14]
    }

    fun transformDir(m: FloatArray, src: FloatArray, si: Int, dst: FloatArray, di: Int) {
        val x = src[si]; val y = src[si + 1]; val z = src[si + 2]
        dst[di] = m[0] * x + m[4] * y + m[8] * z
        dst[di + 1] = m[1] * x + m[5] * y + m[9] * z
        dst[di + 2] = m[2] * x + m[6] * y + m[10] * z
    }

    /** Матрица для нормалей: (M⁻¹)ᵀ верхнего 3x3 блока, упакованная в 4x4. */
    fun normalMatrix(m: FloatArray): FloatArray {
        val a = m[0]; val b = m[4]; val c = m[8]; val d = m[1]; val e = m[5]; val f = m[9]; val g = m[2]; val h = m[6]; val i = m[10]
        val det = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g)
        if (kotlin.math.abs(det) < 1e-12f) return identity()
        val inv = 1f / det
        val r = identity()
        // (M^-1)^T
        r[0] = (e * i - f * h) * inv; r[4] = (f * g - d * i) * inv; r[8] = (d * h - e * g) * inv
        r[1] = (c * h - b * i) * inv; r[5] = (a * i - c * g) * inv; r[9] = (b * g - a * h) * inv
        r[2] = (b * f - c * e) * inv; r[6] = (c * d - a * f) * inv; r[10] = (a * e - b * d) * inv
        return r
    }
}
