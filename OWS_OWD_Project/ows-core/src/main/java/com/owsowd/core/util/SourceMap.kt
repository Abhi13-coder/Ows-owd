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
}
