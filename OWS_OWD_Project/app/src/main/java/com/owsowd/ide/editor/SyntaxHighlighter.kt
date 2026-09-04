package com.owsowd.ide.editor

import android.graphics.Color
import android.text.Editable
import android.text.Spannable
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.widget.EditText
import java.util.regex.Pattern

/**
 * Lightweight regex-based syntax highlighter for OWS/OWD.
 * Keywords, strings, numbers, comments, widget types.
 */
object SyntaxHighlighter {

    private val KEYWORD = Pattern.compile(
        "\\b(attach|when|number|string|bool|var|let|if|else|while|for|fun|return|true|false|null|and|or|not)\\b"
    )
    private val WIDGET = Pattern.compile(
        "\\b(Widget|Text|Button|Image|Rect|Circle)\\b"
    )
    private val STRING = Pattern.compile("\"([^\"\\\\]|\\\\.)*\"")
    private val COLOR = Pattern.compile("#[0-9A-Fa-f]{6,8}")
    private val NUMBER = Pattern.compile("\\b\\d+(\\.\\d+)?\\b")
    private val COMMENT = Pattern.compile("//[^\\n]*|/\\*[\\s\\S]*?\\*/")

    private val C_KEYWORD = Color.parseColor("#FF7B72")
    private val C_WIDGET = Color.parseColor("#D2A8FF")
    private val C_STRING = Color.parseColor("#A5D6FF")
    private val C_NUMBER = Color.parseColor("#79C0FF")
    private val C_COMMENT = Color.parseColor("#8B949E")
    private val C_DEFAULT = Color.parseColor("#E6EDF3")

    fun attach(editor: EditText) {
        editor.addTextChangedListener(object : TextWatcher {
            private var busy = false
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (busy || s == null) return
                busy = true
                highlight(s)
                busy = false
            }
        })
        // initial
        highlight(editor.text)
    }

    fun highlight(editable: Editable) {
        // clear existing
        val spans = editable.getSpans(0, editable.length, ForegroundColorSpan::class.java)
        spans.forEach { editable.removeSpan(it) }

        apply(editable, COMMENT, C_COMMENT)
        apply(editable, STRING, C_STRING)
        apply(editable, COLOR, C_STRING)
        apply(editable, NUMBER, C_NUMBER)
        apply(editable, KEYWORD, C_KEYWORD)
        apply(editable, WIDGET, C_WIDGET)
    }

    private fun apply(editable: Editable, pattern: Pattern, color: Int) {
        val matcher = pattern.matcher(editable)
        while (matcher.find()) {
            editable.setSpan(
                ForegroundColorSpan(color),
                matcher.start(),
                matcher.end(),
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
    }
}
