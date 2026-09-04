package com.owsowd.core.lexer

/**
 * Full token set for OWS/OWD — a real language, not a sample DSL.
 * Operators use symbols: + - * / %  (and optional 'x' as multiply alias)
 * Supports functions, classes, HTTP builtins, arrays, maps.
 */
enum class TokenType {
    // Literals & identifiers
    IDENTIFIER,
    NUMBER,
    STRING,
    COLOR,          // #RRGGBB or #AARRGGBB

    // Keywords
    ATTACH, WHEN, NUMBER_KW, STRING_KW, BOOL_KW, LIST_KW, MAP_KW,
    WIDGET, TEXT, BUTTON, IMAGE, RECT, CIRCLE,
    TRUE, FALSE, NULL,
    IF, ELSE, WHILE, FOR, RETURN, FUN, VAR, LET, CLASS, NEW, THIS,
    AND, OR, NOT, IN, BREAK, CONTINUE, IMPORT, ASYNC, AWAIT,
    HTTP,           // namespace for http.get / http.post

    // Operators (symbolic — the normal way to write math)
    PLUS,           // +
    MINUS,          // -
    STAR,           // *   (also accepts 'x' as alias in lexer)
    SLASH,          // /
    PERCENT,        // %
    EQ, EQEQ, NEQ,  // =  ==  !=
    LT, GT, LTE, GTE,
    ASSIGN,
    PLUS_ASSIGN, MINUS_ASSIGN, STAR_ASSIGN, SLASH_ASSIGN,
    DOT, COLON, COMMA, SEMICOLON,
    ARROW,          // ->
    QUESTION, BANG, // ?  !
    PIPE,           // |
    AMP,            // &

    // Delimiters
    LBRACE, RBRACE,
    LPAREN, RPAREN,
    LBRACKET, RBRACKET,

    // Special
    EOF,
    ERROR
}

data class Token(
    val type: TokenType,
    val lexeme: String,
    val literal: Any? = null,
    val line: Int,
    val column: Int,
    val offset: Int
) {
    override fun toString(): String =
        if (literal != null) "$type($lexeme=$literal)@$line:$column"
        else "$type($lexeme)@$line:$column"
}
