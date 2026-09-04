package com.owsowd.core.lexer

/**
 * Hand-written lexer for the full OWS/OWD language.
 * Whitespace-insensitive. Symbolic operators: + - * / %
 * Optional: bare 'x' between numbers treated as multiply (a x b).
 */
class Lexer(private val source: String) {
    private var start = 0
    private var current = 0
    private var line = 1
    private var column = 1
    private var startColumn = 1
    private val tokens = mutableListOf<Token>()

    private val keywords = mapOf(
        "attach" to TokenType.ATTACH,
        "when" to TokenType.WHEN,
        "number" to TokenType.NUMBER_KW,
        "string" to TokenType.STRING_KW,
        "bool" to TokenType.BOOL_KW,
        "list" to TokenType.LIST_KW,
        "map" to TokenType.MAP_KW,
        "Widget" to TokenType.WIDGET,
        "Text" to TokenType.TEXT,
        "Button" to TokenType.BUTTON,
        "Image" to TokenType.IMAGE,
        "Rect" to TokenType.RECT,
        "Circle" to TokenType.CIRCLE,
        "true" to TokenType.TRUE,
        "false" to TokenType.FALSE,
        "null" to TokenType.NULL,
        "if" to TokenType.IF,
        "else" to TokenType.ELSE,
        "while" to TokenType.WHILE,
        "for" to TokenType.FOR,
        "return" to TokenType.RETURN,
        "fun" to TokenType.FUN,
        "var" to TokenType.VAR,
        "let" to TokenType.LET,
        "class" to TokenType.CLASS,
        "new" to TokenType.NEW,
        "this" to TokenType.THIS,
        "and" to TokenType.AND,
        "or" to TokenType.OR,
        "not" to TokenType.NOT,
        "in" to TokenType.IN,
        "break" to TokenType.BREAK,
        "continue" to TokenType.CONTINUE,
        "import" to TokenType.IMPORT,
        "async" to TokenType.ASYNC,
        "await" to TokenType.AWAIT,
        "http" to TokenType.HTTP,
    )

    fun tokenize(): List<Token> {
        tokens.clear()
        while (!isAtEnd()) {
            start = current
            startColumn = column
            scanToken()
        }
        tokens.add(Token(TokenType.EOF, "", null, line, column, current))
        return tokens
    }

    private fun scanToken() {
        val c = advance()
        when (c) {
            '(' -> addToken(TokenType.LPAREN)
            ')' -> addToken(TokenType.RPAREN)
            '{' -> addToken(TokenType.LBRACE)
            '}' -> addToken(TokenType.RBRACE)
            '[' -> addToken(TokenType.LBRACKET)
            ']' -> addToken(TokenType.RBRACKET)
            ',' -> addToken(TokenType.COMMA)
            ';' -> addToken(TokenType.SEMICOLON)
            '.' -> addToken(TokenType.DOT)
            ':' -> addToken(TokenType.COLON)
            '?' -> addToken(TokenType.QUESTION)
            '|' -> addToken(TokenType.PIPE)
            '&' -> addToken(TokenType.AMP)
            '+' -> {
                when {
                    match('=') -> addToken(TokenType.PLUS_ASSIGN)
                    else -> addToken(TokenType.PLUS)
                }
            }
            '-' -> {
                when {
                    match('>') -> addToken(TokenType.ARROW)
                    match('=') -> addToken(TokenType.MINUS_ASSIGN)
                    else -> addToken(TokenType.MINUS)
                }
            }
            '*' -> {
                if (match('=')) addToken(TokenType.STAR_ASSIGN)
                else addToken(TokenType.STAR)
            }
            '/' -> {
                when {
                    match('/') -> {
                        while (peek() != '\n' && !isAtEnd()) advance()
                    }
                    match('*') -> {
                        while (!(peek() == '*' && peekNext() == '/') && !isAtEnd()) {
                            if (peek() == '\n') { line++; column = 0 }
                            advance()
                        }
                        if (!isAtEnd()) { advance(); advance() }
                    }
                    match('=') -> addToken(TokenType.SLASH_ASSIGN)
                    else -> addToken(TokenType.SLASH)
                }
            }
            '%' -> addToken(TokenType.PERCENT)
            '!' -> {
                if (match('=')) addToken(TokenType.NEQ)
                else addToken(TokenType.BANG)
            }
            '=' -> {
                if (match('=')) addToken(TokenType.EQEQ)
                else addToken(TokenType.ASSIGN)
            }
            '<' -> {
                if (match('=')) addToken(TokenType.LTE)
                else addToken(TokenType.LT)
            }
            '>' -> {
                if (match('=')) addToken(TokenType.GTE)
                else addToken(TokenType.GT)
            }
            '"' -> string()
            '\'' -> string('\'')
            '#' -> color()
            '\n' -> { line++; column = 1 }
            ' ', '\r', '\t' -> { /* ignore */ }
            else -> {
                when {
                    c.isDigit() -> number()
                    c.isLetter() || c == '_' -> identifier()
                    else -> addToken(TokenType.ERROR, "Unexpected character: $c")
                }
            }
        }
    }

    private fun string(quote: Char = '"') {
        val sb = StringBuilder()
        while (peek() != quote && !isAtEnd()) {
            if (peek() == '\n') { line++; column = 0 }
            if (peek() == '\\') {
                advance()
                when (peek()) {
                    'n' -> { sb.append('\n'); advance() }
                    't' -> { sb.append('\t'); advance() }
                    'r' -> { sb.append('\r'); advance() }
                    '"', '\'' -> { sb.append(advance()) }
                    '\\' -> { sb.append('\\'); advance() }
                    'u' -> {
                        advance()
                        val hex = buildString {
                            repeat(4) { if (peek().isLetterOrDigit()) append(advance()) }
                        }
                        sb.append(hex.toIntOrNull(16)?.toChar() ?: '?')
                    }
                    else -> sb.append(advance())
                }
            } else {
                sb.append(advance())
            }
        }
        if (isAtEnd()) {
            addToken(TokenType.ERROR, "Unterminated string")
            return
        }
        advance()
        addToken(TokenType.STRING, sb.toString())
    }

    private fun color() {
        while (peek().isLetterOrDigit()) advance()
        val value = source.substring(start, current)
        if (value.length == 7 || value.length == 9) {
            addToken(TokenType.COLOR, value)
        } else {
            addToken(TokenType.ERROR, "Invalid color: $value")
        }
    }

    private fun number() {
        while (peek().isDigit()) advance()
        if (peek() == '.' && peekNext().isDigit()) {
            advance()
            while (peek().isDigit()) advance()
        }
        // scientific
        if (peek() == 'e' || peek() == 'E') {
            advance()
            if (peek() == '+' || peek() == '-') advance()
            while (peek().isDigit()) advance()
        }
        val text = source.substring(start, current)
        val value = text.toDoubleOrNull() ?: 0.0
        addToken(TokenType.NUMBER, value)
    }

    private fun identifier() {
        while (peek().isLetterOrDigit() || peek() == '_') advance()
        val text = source.substring(start, current)
        // 'x' as multiply alias when used as operator-like identifier
        // We keep it as IDENTIFIER; parser/compiler can treat lone 'x' between exprs as *
        // Better: if exactly "x" and not a keyword, still IDENTIFIER — users can write a * b
        val type = keywords[text] ?: TokenType.IDENTIFIER
        addToken(type)
    }

    private fun addToken(type: TokenType, literal: Any? = null) {
        val text = source.substring(start, current)
        tokens.add(Token(type, text, literal, line, startColumn, start))
    }

    private fun isAtEnd() = current >= source.length
    private fun advance(): Char {
        val c = source[current++]
        column++
        return c
    }
    private fun match(expected: Char): Boolean {
        if (isAtEnd() || source[current] != expected) return false
        current++
        column++
        return true
    }
    private fun peek(): Char = if (isAtEnd()) '\u0000' else source[current]
    private fun peekNext(): Char =
        if (current + 1 >= source.length) '\u0000' else source[current + 1]
}
