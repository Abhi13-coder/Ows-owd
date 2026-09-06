package com.owsowd.core.json

/**
 * Minimal, dependency-free JSON parser/writer.
 *
 * Replaces the previous org.json.JSONObject / org.json.JSONArray usage in VM.kt.
 * org.json is bundled with the Android framework (a stub jar at compile time,
 * the real implementation only exists on-device), so any non-Android host
 * (Linux/Windows/macOS JVM, or a plain `java -jar` CLI) would crash the
 * moment an OWS program called http.get / json_parse / json_stringify.
 * This file has zero platform dependencies, so ows-core no longer needs
 * Android to run the language runtime.
 *
 * Values map onto the same Kotlin types the VM already uses elsewhere:
 * String, Double, Boolean, null, MutableList<Any?>, MutableMap<String, Any?>.
 */
object Json {

    fun parse(text: String): Any? {
        val p = JsonParser(text)
        val v = p.parseValue()
        p.skipWhitespace()
        return v
    }

    fun stringify(value: Any?): String {
        val sb = StringBuilder()
        write(value, sb)
        return sb.toString()
    }

    private fun write(value: Any?, sb: StringBuilder) {
        when (value) {
            null -> sb.append("null")
            is String -> writeString(value, sb)
            is Boolean -> sb.append(value.toString())
            is Number -> {
                val d = value.toDouble()
                if (d == Math.floor(d) && !d.isInfinite() && Math.abs(d) < 1e15) {
                    sb.append(d.toLong().toString())
                } else {
                    sb.append(d.toString())
                }
            }
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, v) in value) {
                    if (!first) sb.append(',')
                    first = false
                    writeString(k.toString(), sb)
                    sb.append(':')
                    write(v, sb)
                }
                sb.append('}')
            }
            is List<*> -> {
                sb.append('[')
                var first = true
                for (v in value) {
                    if (!first) sb.append(',')
                    first = false
                    write(v, sb)
                }
                sb.append(']')
            }
            else -> writeString(value.toString(), sb)
        }
    }

    private fun writeString(s: String, sb: StringBuilder) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c.code < 0x20) sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        sb.append('"')
    }

    private class JsonParser(private val s: String) {
        var i = 0

        fun skipWhitespace() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        fun parseValue(): Any? {
            skipWhitespace()
            if (i >= s.length) return null
            return when (s[i]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't' -> { expect("true"); true }
                'f' -> { expect("false"); false }
                'n' -> { expect("null"); null }
                else -> parseNumber()
            }
        }

        private fun expect(lit: String) {
            if (i + lit.length > s.length || s.substring(i, i + lit.length) != lit) {
                throw JsonException("Expected '$lit' at position $i")
            }
            i += lit.length
        }

        private fun parseObject(): MutableMap<String, Any?> {
            val map = linkedMapOf<String, Any?>()
            i++ // {
            skipWhitespace()
            if (i < s.length && s[i] == '}') { i++; return map }
            while (true) {
                skipWhitespace()
                val key = parseString()
                skipWhitespace()
                if (i >= s.length || s[i] != ':') throw JsonException("Expected ':' at position $i")
                i++
                val value = parseValue()
                map[key] = value
                skipWhitespace()
                if (i < s.length && s[i] == ',') { i++; continue }
                if (i < s.length && s[i] == '}') { i++; break }
                throw JsonException("Expected ',' or '}' at position $i")
            }
            return map
        }

        private fun parseArray(): MutableList<Any?> {
            val list = mutableListOf<Any?>()
            i++ // [
            skipWhitespace()
            if (i < s.length && s[i] == ']') { i++; return list }
            while (true) {
                list.add(parseValue())
                skipWhitespace()
                if (i < s.length && s[i] == ',') { i++; continue }
                if (i < s.length && s[i] == ']') { i++; break }
                throw JsonException("Expected ',' or ']' at position $i")
            }
            return list
        }

        private fun parseString(): String {
            if (s[i] != '"') throw JsonException("Expected string at position $i")
            i++
            val sb = StringBuilder()
            while (i < s.length && s[i] != '"') {
                val c = s[i]
                if (c == '\\' && i + 1 < s.length) {
                    i++
                    when (s[i]) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'b' -> sb.append('\b')
                        'u' -> {
                            val hex = s.substring(i + 1, i + 5)
                            sb.append(hex.toInt(16).toChar())
                            i += 4
                        }
                        else -> sb.append(s[i])
                    }
                    i++
                } else {
                    sb.append(c)
                    i++
                }
            }
            if (i >= s.length) throw JsonException("Unterminated string")
            i++ // closing quote
            return sb.toString()
        }

        private fun parseNumber(): Double {
            val start = i
            if (i < s.length && (s[i] == '-' || s[i] == '+')) i++
            while (i < s.length && (s[i].isDigit() || s[i] == '.' || s[i] == 'e' || s[i] == 'E' || s[i] == '+' || s[i] == '-')) i++
            val text = s.substring(start, i)
            return text.toDoubleOrNull() ?: throw JsonException("Invalid number '$text' at position $start")
        }
    }

    class JsonException(message: String) : RuntimeException(message)
}
