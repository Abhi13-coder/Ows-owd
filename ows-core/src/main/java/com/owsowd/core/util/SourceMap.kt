package com.owsowd.core.util

/**
 * Helpers for source locations and diagnostics.
 */
object SourceMap {
    fun lineColumnOf(source: String, offset: Int): Pair<Int, Int> {
        var line = 1
        var col = 1
        for (i in 0 until minOf(offset, source.length)) {
            if (source[i] == '\n') {
                line++
                col = 1
            } else {
                col++
            }
        }
        return line to col
    }

    /**
     * Renders the offending line with a `^` caret under the reported column,
     * e.g.:
     *
     *   3 | numbr count = 0
     *       ^
     *
     * CompileError.toString() alone only gives "line:col: message", which on
     * a phone screen with no code minimap makes people feel like the IDE is
     * "just spitting out numbers" even though a message is right there after
     * the colon. Showing the actual source line next to the caret is what
     * makes the position legible at a glance.
     */
    fun snippet(source: String, line: Int, column: Int, contextLines: Int = 1): String {
        val lines = source.split("\n")
        if (line < 1 || line > lines.size) return ""
        val start = maxOf(1, line - contextLines)
        val end = minOf(lines.size, line + contextLines)
        val gutterWidth = end.toString().length
        val sb = StringBuilder()
        for (n in start..end) {
            val text = lines[n - 1]
            sb.append(n.toString().padStart(gutterWidth)).append(" | ").append(text).append('\n')
            if (n == line) {
                val caretPos = (column - 1).coerceAtLeast(0)
                sb.append(" ".repeat(gutterWidth)).append(" | ")
                    .append(" ".repeat(minOf(caretPos, text.length)))
                    .append('^').append('\n')
            }
        }
        return sb.toString().trimEnd('\n')
    }
}
