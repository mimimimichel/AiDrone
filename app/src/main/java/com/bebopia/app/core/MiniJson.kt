package com.bebopia.app.core

/**
 * Petit lecteur/écrivain JSON sans dépendance.
 * Objets -> Map<String, Any?>, tableaux -> List<Any?>, nombres -> Double.
 */
object MiniJson {

    fun parse(text: String): Any? {
        val p = Parser(text)
        p.skipWs()
        val v = p.value()
        p.skipWs()
        if (p.pos != text.length) p.fail("caractères en trop")
        return v
    }

    fun stringify(value: Any?): String = StringBuilder().also { write(it, value) }.toString()

    private fun write(sb: StringBuilder, v: Any?) {
        when (v) {
            null -> sb.append("null")
            is String -> quote(sb, v)
            is Boolean -> sb.append(v)
            is Int, is Long, is Short, is Byte -> sb.append(v.toString())
            is Number -> {
                val d = v.toDouble()
                when {
                    d.isNaN() || d.isInfinite() -> sb.append("null")
                    d == Math.floor(d) && Math.abs(d) < 1e15 -> sb.append(d.toLong())
                    else -> sb.append(d)
                }
            }
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, value) in v) {
                    if (!first) sb.append(',')
                    first = false
                    quote(sb, k.toString())
                    sb.append(':')
                    write(sb, value)
                }
                sb.append('}')
            }
            is Iterable<*> -> {
                sb.append('[')
                var first = true
                for (item in v) {
                    if (!first) sb.append(',')
                    first = false
                    write(sb, item)
                }
                sb.append(']')
            }
            is Array<*> -> write(sb, v.toList())
            else -> quote(sb, v.toString())
        }
    }

    private fun quote(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (c < ' ') sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
            }
        }
        sb.append('"')
    }

    private class Parser(val s: String) {
        var pos = 0

        fun skipWs() {
            while (pos < s.length && s[pos].isWhitespace()) pos++
        }

        fun value(): Any? {
            skipWs()
            if (pos >= s.length) fail("fin inattendue")
            val c = s[pos]
            return when {
                c == '{' -> obj()
                c == '[' -> arr()
                c == '"' -> str()
                c == 't' -> lit("true", true)
                c == 'f' -> lit("false", false)
                c == 'n' -> lit("null", null)
                c == '-' || c.isDigit() -> num()
                else -> fail("caractère inattendu '$c'")
            }
        }

        private fun obj(): Map<String, Any?> {
            pos++ // {
            val m = LinkedHashMap<String, Any?>()
            skipWs()
            if (peek() == '}') {
                pos++
                return m
            }
            while (true) {
                skipWs()
                if (peek() != '"') fail("clé attendue")
                val k = str()
                skipWs()
                expect(':')
                m[k] = value()
                skipWs()
                when (next()) {
                    ',' -> continue
                    '}' -> return m
                    else -> fail("',' ou '}' attendu")
                }
            }
        }

        private fun arr(): List<Any?> {
            pos++ // [
            val l = ArrayList<Any?>()
            skipWs()
            if (peek() == ']') {
                pos++
                return l
            }
            while (true) {
                l.add(value())
                skipWs()
                when (next()) {
                    ',' -> continue
                    ']' -> return l
                    else -> fail("',' ou ']' attendu")
                }
            }
        }

        private fun str(): String {
            pos++ // "
            val sb = StringBuilder()
            while (true) {
                if (pos >= s.length) fail("chaîne non terminée")
                val c = s[pos++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (pos >= s.length) fail("échappement incomplet")
                        when (val e = s[pos++]) {
                            '"', '\\', '/' -> sb.append(e)
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 > s.length) fail("\\u incomplet")
                                val code = s.substring(pos, pos + 4).toIntOrNull(16) ?: fail("\\u invalide")
                                sb.append(code.toChar())
                                pos += 4
                            }
                            else -> fail("échappement inconnu")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun num(): Double {
            val start = pos
            if (peek() == '-') pos++
            while (pos < s.length && (s[pos].isDigit() || s[pos] in ".eE+-")) pos++
            return s.substring(start, pos).toDoubleOrNull() ?: fail("nombre invalide")
        }

        private fun lit(word: String, v: Any?): Any? {
            if (!s.startsWith(word, pos)) fail("littéral invalide")
            pos += word.length
            return v
        }

        private fun peek(): Char = if (pos < s.length) s[pos] else '\u0000'
        private fun next(): Char = if (pos < s.length) s[pos++] else '\u0000'

        private fun expect(c: Char) {
            if (next() != c) fail("'$c' attendu")
        }

        fun fail(msg: String): Nothing = throw IllegalArgumentException("JSON invalide ($msg) à la position $pos")
    }
}
