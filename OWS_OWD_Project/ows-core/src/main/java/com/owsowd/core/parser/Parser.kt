package com.owsowd.core.parser

import com.owsowd.core.ast.*
import com.owsowd.core.lexer.Token
import com.owsowd.core.lexer.TokenType

/**
 * Recursive-descent parser for the full OWS + OWD language.
 */
class Parser(private val tokens: List<Token>) {
    private var current = 0
    val errors = mutableListOf<CompileError>()

    fun parse(): Program {
        val statements = mutableListOf<Stmt>()
        val widgets = mutableListOf<WidgetDecl>()
        val functions = mutableListOf<FunDecl>()
        val classes = mutableListOf<ClassDecl>()

        while (!isAtEnd()) {
            try {
                when {
                    check(TokenType.ATTACH) -> statements.add(attachStmt())
                    check(TokenType.FUN) -> {
                        val f = funDecl()
                        functions.add(f)
                        statements.add(FunDeclStmt(f))
                    }
                    check(TokenType.CLASS) -> {
                        val c = classDecl()
                        classes.add(c)
                        statements.add(ClassDeclStmt(c))
                    }
                    check(TokenType.NUMBER_KW) || check(TokenType.STRING_KW) ||
                            check(TokenType.BOOL_KW) || check(TokenType.LIST_KW) ||
                            check(TokenType.MAP_KW) || check(TokenType.VAR) || check(TokenType.LET) ->
                        statements.add(varDecl())
                    check(TokenType.WHEN) -> statements.add(whenStmt())
                    check(TokenType.WIDGET) || check(TokenType.TEXT) || check(TokenType.BUTTON) ||
                            check(TokenType.IMAGE) || check(TokenType.RECT) || check(TokenType.CIRCLE) ->
                        widgets.add(widgetDecl())
                    check(TokenType.IF) -> statements.add(ifStmt())
                    check(TokenType.WHILE) -> statements.add(whileStmt())
                    check(TokenType.FOR) -> statements.add(forStmt())
                    check(TokenType.RETURN) -> statements.add(returnStmt())
                    check(TokenType.BREAK) -> {
                        val t = advance()
                        statements.add(BreakStmt(t.line, t.column))
                        match(TokenType.SEMICOLON)
                    }
                    check(TokenType.CONTINUE) -> {
                        val t = advance()
                        statements.add(ContinueStmt(t.line, t.column))
                        match(TokenType.SEMICOLON)
                    }
                    check(TokenType.LBRACE) -> statements.add(block())
                    else -> {
                        val expr = expression()
                        if (match(TokenType.ASSIGN) || match(TokenType.PLUS_ASSIGN) ||
                            match(TokenType.MINUS_ASSIGN) || match(TokenType.STAR_ASSIGN) ||
                            match(TokenType.SLASH_ASSIGN)
                        ) {
                            val op = previous()
                            val value = expression()
                            val finalValue = when (op.type) {
                                TokenType.PLUS_ASSIGN -> Binary(expr, Token(TokenType.PLUS, "+", null, op.line, op.column, op.offset), value, op.line, op.column)
                                TokenType.MINUS_ASSIGN -> Binary(expr, Token(TokenType.MINUS, "-", null, op.line, op.column, op.offset), value, op.line, op.column)
                                TokenType.STAR_ASSIGN -> Binary(expr, Token(TokenType.STAR, "*", null, op.line, op.column, op.offset), value, op.line, op.column)
                                TokenType.SLASH_ASSIGN -> Binary(expr, Token(TokenType.SLASH, "/", null, op.line, op.column, op.offset), value, op.line, op.column)
                                else -> value
                            }
                            statements.add(AssignStmt(expr, finalValue, expr.line, expr.column))
                        } else {
                            statements.add(ExprStmt(expr, expr.line, expr.column))
                        }
                        match(TokenType.SEMICOLON)
                    }
                }
            } catch (e: ParseException) {
                errors.add(CompileError(e.message ?: "Parse error", SourceLocation(e.line, e.column)))
                synchronize()
            }
        }
        return Program(statements, widgets, functions, classes)
    }

    // ---- declarations ----

    private fun funDecl(): FunDecl {
        val t = consume(TokenType.FUN, "Expected 'fun'")
        val name = consume(TokenType.IDENTIFIER, "Expected function name").lexeme
        consume(TokenType.LPAREN, "Expected '(' after function name")
        val params = mutableListOf<String>()
        if (!check(TokenType.RPAREN)) {
            do {
                params.add(consume(TokenType.IDENTIFIER, "Expected parameter name").lexeme)
            } while (match(TokenType.COMMA))
        }
        consume(TokenType.RPAREN, "Expected ')' after parameters")
        val body = if (check(TokenType.LBRACE)) block().statements else listOf(statement())
        return FunDecl(name, params, body, t.line, t.column)
    }

    private fun classDecl(): ClassDecl {
        val t = consume(TokenType.CLASS, "Expected 'class'")
        val name = consume(TokenType.IDENTIFIER, "Expected class name").lexeme
        consume(TokenType.LBRACE, "Expected '{' after class name")
        val fields = mutableListOf<VarDecl>()
        val methods = mutableListOf<FunDecl>()
        while (!check(TokenType.RBRACE) && !isAtEnd()) {
            when {
                check(TokenType.FUN) -> methods.add(funDecl())
                check(TokenType.NUMBER_KW) || check(TokenType.STRING_KW) ||
                        check(TokenType.BOOL_KW) || check(TokenType.VAR) || check(TokenType.LET) ||
                        check(TokenType.LIST_KW) || check(TokenType.MAP_KW) ->
                    fields.add(varDecl())
                check(TokenType.IDENTIFIER) -> {
                    // bare field: name = value or name
                    val n = advance()
                    var init: Expr? = null
                    if (match(TokenType.ASSIGN)) init = expression()
                    match(TokenType.SEMICOLON)
                    fields.add(VarDecl(n.lexeme, null, init, n.line, n.column))
                }
                else -> advance()
            }
        }
        consume(TokenType.RBRACE, "Expected '}' after class body")
        return ClassDecl(name, fields, methods, t.line, t.column)
    }

    // ---- statements ----

    private fun attachStmt(): AttachStmt {
        val t = consume(TokenType.ATTACH, "Expected 'attach'")
        if (match(TokenType.IDENTIFIER) && previous().lexeme == "src") {
            consume(TokenType.ASSIGN, "Expected '=' after src")
        }
        val pathTok = consume(TokenType.STRING, "Expected string path after attach")
        match(TokenType.SEMICOLON)
        return AttachStmt(pathTok.literal as String, t.line, t.column)
    }

    private fun varDecl(): VarDecl {
        val typeHint = when {
            match(TokenType.NUMBER_KW) -> "number"
            match(TokenType.STRING_KW) -> "string"
            match(TokenType.BOOL_KW) -> "bool"
            match(TokenType.LIST_KW) -> "list"
            match(TokenType.MAP_KW) -> "map"
            match(TokenType.VAR) || match(TokenType.LET) -> null
            else -> null
        }
        val name = consume(TokenType.IDENTIFIER, "Expected variable name")
        var init: Expr? = null
        if (match(TokenType.ASSIGN)) init = expression()
        match(TokenType.SEMICOLON)
        return VarDecl(name.lexeme, typeHint, init, name.line, name.column)
    }

    private fun whenStmt(): WhenStmt {
        val t = consume(TokenType.WHEN, "Expected 'when'")
        val event = if (check(TokenType.STRING)) {
            advance().literal as String
        } else {
            val parts = mutableListOf<String>()
            parts.add(consume(TokenType.IDENTIFIER, "Expected event name").lexeme)
            while (match(TokenType.DOT)) {
                parts.add(consume(TokenType.IDENTIFIER, "Expected property after '.'").lexeme)
            }
            parts.joinToString(".")
        }
        val body = if (check(TokenType.LBRACE)) block().statements else listOf(statement())
        return WhenStmt(event, body, t.line, t.column)
    }

    private fun ifStmt(): IfStmt {
        val t = consume(TokenType.IF, "Expected 'if'")
        val hasParen = match(TokenType.LPAREN)
        val cond = expression()
        if (hasParen) consume(TokenType.RPAREN, "Expected ')' after condition")
        val thenB = if (check(TokenType.LBRACE)) block().statements else listOf(statement())
        var elseB: List<Stmt>? = null
        if (match(TokenType.ELSE)) {
            elseB = if (check(TokenType.LBRACE)) block().statements else listOf(statement())
        }
        return IfStmt(cond, thenB, elseB, t.line, t.column)
    }

    private fun whileStmt(): WhileStmt {
        val t = consume(TokenType.WHILE, "Expected 'while'")
        val hasParen = match(TokenType.LPAREN)
        val cond = expression()
        if (hasParen) consume(TokenType.RPAREN, "Expected ')' after condition")
        val body = if (check(TokenType.LBRACE)) block().statements else listOf(statement())
        return WhileStmt(cond, body, t.line, t.column)
    }

    private fun forStmt(): ForStmt {
        val t = consume(TokenType.FOR, "Expected 'for'")
        val hasParen = match(TokenType.LPAREN)
        val variable = consume(TokenType.IDENTIFIER, "Expected loop variable").lexeme
        consume(TokenType.IN, "Expected 'in' after loop variable")
        val iterable = expression()
        if (hasParen) consume(TokenType.RPAREN, "Expected ')' after for header")
        val body = if (check(TokenType.LBRACE)) block().statements else listOf(statement())
        return ForStmt(variable, iterable, body, t.line, t.column)
    }

    private fun returnStmt(): ReturnStmt {
        val t = consume(TokenType.RETURN, "Expected 'return'")
        val value = if (!check(TokenType.SEMICOLON) && !check(TokenType.RBRACE)) expression() else null
        match(TokenType.SEMICOLON)
        return ReturnStmt(value, t.line, t.column)
    }

    private fun block(): BlockStmt {
        val t = consume(TokenType.LBRACE, "Expected '{'")
        val stmts = mutableListOf<Stmt>()
        while (!check(TokenType.RBRACE) && !isAtEnd()) {
            stmts.add(statement())
        }
        consume(TokenType.RBRACE, "Expected '}'")
        return BlockStmt(stmts, t.line, t.column)
    }

    private fun statement(): Stmt {
        return when {
            check(TokenType.FUN) -> FunDeclStmt(funDecl())
            check(TokenType.CLASS) -> ClassDeclStmt(classDecl())
            check(TokenType.NUMBER_KW) || check(TokenType.STRING_KW) ||
                    check(TokenType.BOOL_KW) || check(TokenType.LIST_KW) ||
                    check(TokenType.MAP_KW) || check(TokenType.VAR) || check(TokenType.LET) -> varDecl()
            check(TokenType.WHEN) -> whenStmt()
            check(TokenType.IF) -> ifStmt()
            check(TokenType.WHILE) -> whileStmt()
            check(TokenType.FOR) -> forStmt()
            check(TokenType.RETURN) -> returnStmt()
            check(TokenType.BREAK) -> {
                val t = advance(); match(TokenType.SEMICOLON)
                BreakStmt(t.line, t.column)
            }
            check(TokenType.CONTINUE) -> {
                val t = advance(); match(TokenType.SEMICOLON)
                ContinueStmt(t.line, t.column)
            }
            check(TokenType.LBRACE) -> block()
            else -> {
                val expr = expression()
                if (match(TokenType.ASSIGN) || match(TokenType.PLUS_ASSIGN) ||
                    match(TokenType.MINUS_ASSIGN) || match(TokenType.STAR_ASSIGN) ||
                    match(TokenType.SLASH_ASSIGN)
                ) {
                    val op = previous()
                    val value = expression()
                    val finalValue = when (op.type) {
                        TokenType.PLUS_ASSIGN -> Binary(expr, Token(TokenType.PLUS, "+", null, op.line, op.column, op.offset), value, op.line, op.column)
                        TokenType.MINUS_ASSIGN -> Binary(expr, Token(TokenType.MINUS, "-", null, op.line, op.column, op.offset), value, op.line, op.column)
                        TokenType.STAR_ASSIGN -> Binary(expr, Token(TokenType.STAR, "*", null, op.line, op.column, op.offset), value, op.line, op.column)
                        TokenType.SLASH_ASSIGN -> Binary(expr, Token(TokenType.SLASH, "/", null, op.line, op.column, op.offset), value, op.line, op.column)
                        else -> value
                    }
                    match(TokenType.SEMICOLON)
                    AssignStmt(expr, finalValue, expr.line, expr.column)
                } else {
                    match(TokenType.SEMICOLON)
                    ExprStmt(expr, expr.line, expr.column)
                }
            }
        }
    }

    // ---- widget ----

    private fun widgetDecl(): WidgetDecl {
        val typeTok = advance()
        val typeName = typeTok.lexeme
        var id: String? = null
        if (check(TokenType.IDENTIFIER)) id = advance().lexeme
        consume(TokenType.LBRACE, "Expected '{' after widget")
        val props = mutableMapOf<String, Expr>()
        val children = mutableListOf<WidgetDecl>()
        while (!check(TokenType.RBRACE) && !isAtEnd()) {
            if (check(TokenType.WIDGET) || check(TokenType.TEXT) || check(TokenType.BUTTON) ||
                check(TokenType.IMAGE) || check(TokenType.RECT) || check(TokenType.CIRCLE)
            ) {
                children.add(widgetDecl())
            } else if (check(TokenType.IDENTIFIER)) {
                val propName = advance().lexeme
                consume(TokenType.COLON, "Expected ':' after property name")
                val value = expression()
                match(TokenType.COMMA)
                match(TokenType.SEMICOLON)
                props[propName] = value
            } else {
                advance()
            }
        }
        consume(TokenType.RBRACE, "Expected '}' after widget body")
        return WidgetDecl(typeName, id, props, children, typeTok.line, typeTok.column)
    }

    // ---- expressions ----

    private fun expression(): Expr = assignment()

    private fun assignment(): Expr {
        val expr = or()
        if (match(TokenType.ASSIGN)) {
            val value = assignment()
            when (expr) {
                is GetProp -> return SetProp(expr.obj, expr.name, value, expr.line, expr.column)
                is IndexGet -> return IndexSet(expr.obj, expr.index, value, expr.line, expr.column)
                else -> { /* handled at statement level for variables */ }
            }
        }
        return expr
    }

    private fun or(): Expr {
        var expr = and()
        while (match(TokenType.OR) || match(TokenType.PIPE)) {
            val op = previous()
            val right = and()
            expr = Binary(expr, op, right, op.line, op.column)
        }
        return expr
    }

    private fun and(): Expr {
        var expr = equality()
        while (match(TokenType.AND) || match(TokenType.AMP)) {
            val op = previous()
            val right = equality()
            expr = Binary(expr, op, right, op.line, op.column)
        }
        return expr
    }

    private fun equality(): Expr {
        var expr = comparison()
        while (match(TokenType.EQEQ, TokenType.NEQ)) {
            val op = previous()
            val right = comparison()
            expr = Binary(expr, op, right, op.line, op.column)
        }
        return expr
    }

    private fun comparison(): Expr {
        var expr = term()
        while (match(TokenType.LT, TokenType.GT, TokenType.LTE, TokenType.GTE)) {
            val op = previous()
            val right = term()
            expr = Binary(expr, op, right, op.line, op.column)
        }
        return expr
    }

    private fun term(): Expr {
        var expr = factor()
        while (match(TokenType.PLUS, TokenType.MINUS)) {
            val op = previous()
            val right = factor()
            expr = Binary(expr, op, right, op.line, op.column)
        }
        return expr
    }

    private fun factor(): Expr {
        var expr = unary()
        while (true) {
            when {
                match(TokenType.STAR, TokenType.SLASH, TokenType.PERCENT) -> {
                    val op = previous()
                    val right = unary()
                    expr = Binary(expr, op, right, op.line, op.column)
                }
                // allow 'x' as multiply: a x b
                check(TokenType.IDENTIFIER) && peek().lexeme == "x" -> {
                    val opTok = advance()
                    val op = Token(TokenType.STAR, "x", null, opTok.line, opTok.column, opTok.offset)
                    val right = unary()
                    expr = Binary(expr, op, right, op.line, op.column)
                }
                else -> break
            }
        }
        return expr
    }

    private fun unary(): Expr {
        if (match(TokenType.BANG, TokenType.MINUS, TokenType.NOT)) {
            val op = previous()
            val right = unary()
            return Unary(op, right, op.line, op.column)
        }
        return call()
    }

    private fun call(): Expr {
        var expr = primary()
        while (true) {
            when {
                match(TokenType.LPAREN) -> {
                    val args = mutableListOf<Expr>()
                    if (!check(TokenType.RPAREN)) {
                        do { args.add(expression()) } while (match(TokenType.COMMA))
                    }
                    val paren = consume(TokenType.RPAREN, "Expected ')' after arguments")
                    expr = Call(expr, args, paren.line, paren.column)
                }
                match(TokenType.DOT) -> {
                    val name = consume(TokenType.IDENTIFIER, "Expected property name after '.'")
                    expr = GetProp(expr, name.lexeme, name.line, name.column)
                }
                match(TokenType.LBRACKET) -> {
                    val index = expression()
                    val br = consume(TokenType.RBRACKET, "Expected ']' after index")
                    expr = IndexGet(expr, index, br.line, br.column)
                }
                else -> break
            }
        }
        return expr
    }

    private fun primary(): Expr {
        val t = peek()
        return when {
            match(TokenType.FALSE) -> Literal(false, t.line, t.column)
            match(TokenType.TRUE) -> Literal(true, t.line, t.column)
            match(TokenType.NULL) -> Literal(null, t.line, t.column)
            match(TokenType.NUMBER) -> Literal(previous().literal, previous().line, previous().column)
            match(TokenType.STRING) -> Literal(previous().literal, previous().line, previous().column)
            match(TokenType.COLOR) -> Literal(previous().literal, previous().line, previous().column)
            match(TokenType.THIS) -> ThisExpr(previous().line, previous().column)
            match(TokenType.NEW) -> {
                val name = consume(TokenType.IDENTIFIER, "Expected class name after new").lexeme
                val args = mutableListOf<Expr>()
                if (match(TokenType.LPAREN)) {
                    if (!check(TokenType.RPAREN)) {
                        do { args.add(expression()) } while (match(TokenType.COMMA))
                    }
                    consume(TokenType.RPAREN, "Expected ')'")
                }
                NewExpr(name, args, t.line, t.column)
            }
            match(TokenType.HTTP) -> {
                // http treated as variable so http.get works via GetProp + Call
                Variable("http", previous().line, previous().column)
            }
            match(TokenType.IDENTIFIER) -> Variable(previous().lexeme, previous().line, previous().column)
            match(TokenType.LBRACKET) -> {
                // list literal [1, 2, 3]
                val elements = mutableListOf<Expr>()
                if (!check(TokenType.RBRACKET)) {
                    do { elements.add(expression()) } while (match(TokenType.COMMA))
                }
                val br = consume(TokenType.RBRACKET, "Expected ']'")
                ListLiteral(elements, t.line, t.column)
            }
            match(TokenType.LBRACE) -> {
                // map literal { "a": 1, "b": 2 }  — careful: could be block at stmt level
                // only used when expression expected
                val entries = mutableListOf<Pair<Expr, Expr>>()
                if (!check(TokenType.RBRACE)) {
                    do {
                        val key = expression()
                        consume(TokenType.COLON, "Expected ':' in map entry")
                        val value = expression()
                        entries.add(key to value)
                    } while (match(TokenType.COMMA))
                }
                consume(TokenType.RBRACE, "Expected '}'")
                MapLiteral(entries, t.line, t.column)
            }
            match(TokenType.LPAREN) -> {
                val expr = expression()
                consume(TokenType.RPAREN, "Expected ')' after expression")
                expr
            }
            else -> throw error(peek(), "Expected expression")
        }
    }

    // ---- helpers ----

    private fun match(vararg types: TokenType): Boolean {
        for (type in types) {
            if (check(type)) { advance(); return true }
        }
        return false
    }

    private fun check(type: TokenType): Boolean =
        if (isAtEnd()) false else peek().type == type

    private fun advance(): Token {
        if (!isAtEnd()) current++
        return previous()
    }

    private fun isAtEnd(): Boolean = peek().type == TokenType.EOF
    private fun peek(): Token = tokens[current]
    private fun previous(): Token = tokens[current - 1]

    private fun consume(type: TokenType, message: String): Token {
        if (check(type)) return advance()
        throw error(peek(), message)
    }

    private fun error(token: Token, message: String): ParseException =
        ParseException(message, token.line, token.column)

    private fun synchronize() {
        advance()
        while (!isAtEnd()) {
            if (previous().type == TokenType.SEMICOLON || previous().type == TokenType.RBRACE) return
            when (peek().type) {
                TokenType.WHEN, TokenType.IF, TokenType.WHILE, TokenType.FOR,
                TokenType.FUN, TokenType.CLASS, TokenType.RETURN,
                TokenType.NUMBER_KW, TokenType.STRING_KW, TokenType.VAR,
                TokenType.WIDGET, TokenType.TEXT, TokenType.BUTTON, TokenType.ATTACH -> return
                else -> advance()
            }
        }
    }

    class ParseException(message: String, val line: Int, val column: Int) : RuntimeException(message)
}
