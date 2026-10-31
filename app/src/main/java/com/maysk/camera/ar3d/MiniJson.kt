package com.maysk.camera.ar3d

/**
 * Минимальный JSON-парсер для заголовка glTF (без зависимостей от Android —
 * поэтому загрузчик GLB проверяется обычными JVM-тестами).
 * Объекты → Map<String, Any?>, массивы → List<Any?>, числа → Double.
 */
object MiniJson {
    fun parse(text: String): Any? {
        val p = Parser(text)
        val v = p.value()
        p.ws()
        require(p.i == text.length) { "JSON: лишние символы в позиции ${p.i}" }
        return v
    }

    private class Parser(val s: String) {
        var i = 0
        fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun value(): Any? {
            ws()
            require(i < s.length) { "JSON: неожиданный конец" }
            return when (val c = s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> { expect("true"); true }
                'f' -> { expect("false"); false }
                'n' -> { expect("null"); null }
                else -> if (c == '-' || c.isDigit()) num() else throw IllegalArgumentException("JSON: символ '$c' в позиции $i")
            }
        }
        fun expect(w: String) { require(s.startsWith(w, i)) { "JSON: ожидалось $w в позиции $i" }; i += w.length }
        fun obj(): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>()
            i++; ws()
            if (s[i] == '}') { i++; return m }
            while (true) {
                ws(); val k = str(); ws()
                require(s[i] == ':') { "JSON: нет ':' в позиции $i" }; i++
                m[k] = value(); ws()
                when (s[i]) { ',' -> i++; '}' -> { i++; return m }; else -> throw IllegalArgumentException("JSON: объект в позиции $i") }
            }
        }
        fun arr(): List<Any?> {
            val l = ArrayList<Any?>()
            i++; ws()
            if (s[i] == ']') { i++; return l }
            while (true) {
                l.add(value()); ws()
                when (s[i]) { ',' -> i++; ']' -> { i++; return l }; else -> throw IllegalArgumentException("JSON: массив в позиции $i") }
            }
        }
        fun str(): String {
            require(s[i] == '"') { "JSON: ожидалась строка в позиции $i" }
            i++
            val sb = StringBuilder()
            while (true) {
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        when (val e = s[i++]) {
                            'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'r' -> sb.append('\r'); 'b' -> sb.append('\b'); 'f' -> sb.append('\u000C')
                            'u' -> { sb.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                            else -> sb.append(e)
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }
        fun num(): Double {
            val st = i
            if (s[i] == '-') i++
            while (i < s.length && (s[i].isDigit() || s[i] == '.' || s[i] == 'e' || s[i] == 'E' || s[i] == '+' || s[i] == '-')) i++
            return s.substring(st, i).toDouble()
        }
    }
}

@Suppress("UNCHECKED_CAST")
internal fun Any?.obj(): Map<String, Any?> = this as? Map<String, Any?> ?: emptyMap()
@Suppress("UNCHECKED_CAST")
internal fun Any?.list(): List<Any?> = this as? List<Any?> ?: emptyList()
internal fun Any?.int(def: Int = 0): Int = (this as? Double)?.toInt() ?: def
internal fun Any?.float(def: Float = 0f): Float = (this as? Double)?.toFloat() ?: def
internal fun Any?.floats(): FloatArray = list().map { (it as? Double)?.toFloat() ?: 0f }.toFloatArray()
