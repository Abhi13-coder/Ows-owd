package com.owsowd.core.ast

import com.owsowd.core.lexer.Token

/**
 * Full AST for OWS (logic) + OWD (design).
 * Supports: variables, functions, classes, control flow, lists/maps, HTTP, events.
 */

sealed class Node {
    abstract val line: Int
    abstract val column: Int
}

// ---------- Program ----------

data class Program(
    val statements: List<Stmt>,
    val widgets: List<WidgetDecl>,
    val functions: List<FunDecl> = emptyList(),
    val classes: List<ClassDecl> = emptyList(),
    override val line: Int = 1,
    override val column: Int = 1
) : Node()

// ---------- Declarations ----------

data class FunDecl(
    val name: String,
    val params: List<String>,
    val body: List<Stmt>,
    override val line: Int,
    override val column: Int
) : Node()

data class ClassDecl(
    val name: String,
    val fields: List<VarDecl>,
    val methods: List<FunDecl>,
    override val line: Int,
    override val column: Int
) : Node()

// ---------- Statements ----------

sealed class Stmt : Node()

data class AttachStmt(
    val path: String,
    override val line: Int,
    override val column: Int
) : Stmt()

data class VarDecl(
    val name: String,
    val typeHint: String?,
    val initializer: Expr?,
    override val line: Int,
    override val column: Int
) : Stmt()

data class AssignStmt(
    val target: Expr,
    val value: Expr,
    override val line: Int,
    override val column: Int
) : Stmt()

data class WhenStmt(
    val event: String,
    val body: List<Stmt>,
    override val line: Int,
    override val column: Int
) : Stmt()

data class ExprStmt(
    val expr: Expr,
    override val line: Int,
    override val column: Int
) : Stmt()

data class IfStmt(
    val condition: Expr,
    val thenBranch: List<Stmt>,
    val elseBranch: List<Stmt>?,
    override val line: Int,
    override val column: Int
) : Stmt()

data class WhileStmt(
    val condition: Expr,
    val body: List<Stmt>,
    override val line: Int,
    override val column: Int
) : Stmt()

data class ForStmt(
    val variable: String,
    val iterable: Expr,
    val body: List<Stmt>,
    override val line: Int,
    override val column: Int
) : Stmt()

data class ReturnStmt(
    val value: Expr?,
    override val line: Int,
    override val column: Int
) : Stmt()

data class BlockStmt(
    val statements: List<Stmt>,
    override val line: Int,
    override val column: Int
) : Stmt()

data class BreakStmt(override val line: Int, override val column: Int) : Stmt()
data class ContinueStmt(override val line: Int, override val column: Int) : Stmt()

// FunDecl / ClassDecl also appear as statements when nested or top-level collected

data class FunDeclStmt(
    val decl: FunDecl,
    override val line: Int = decl.line,
    override val column: Int = decl.column
) : Stmt()

data class ClassDeclStmt(
    val decl: ClassDecl,
    override val line: Int = decl.line,
    override val column: Int = decl.column
) : Stmt()

// ---------- Expressions ----------

sealed class Expr : Node()

data class Literal(
    val value: Any?,
    override val line: Int,
    override val column: Int
) : Expr()

data class Variable(
    val name: String,
    override val line: Int,
    override val column: Int
) : Expr()

data class Binary(
    val left: Expr,
    val op: Token,
    val right: Expr,
    override val line: Int,
    override val column: Int
) : Expr()

data class Unary(
    val op: Token,
    val right: Expr,
    override val line: Int,
    override val column: Int
) : Expr()

data class Call(
    val callee: Expr,
    val args: List<Expr>,
    override val line: Int,
    override val column: Int
) : Expr()

data class GetProp(
    val obj: Expr,
    val name: String,
    override val line: Int,
    override val column: Int
) : Expr()

data class SetProp(
    val obj: Expr,
    val name: String,
    val value: Expr,
    override val line: Int,
    override val column: Int
) : Expr()

data class IndexGet(
    val obj: Expr,
    val index: Expr,
    override val line: Int,
    override val column: Int
) : Expr()

data class IndexSet(
    val obj: Expr,
    val index: Expr,
    val value: Expr,
    override val line: Int,
    override val column: Int
) : Expr()

data class ListLiteral(
    val elements: List<Expr>,
    override val line: Int,
    override val column: Int
) : Expr()

data class MapLiteral(
    val entries: List<Pair<Expr, Expr>>,
    override val line: Int,
    override val column: Int
) : Expr()

data class NewExpr(
    val className: String,
    val args: List<Expr>,
    override val line: Int,
    override val column: Int
) : Expr()

data class ThisExpr(
    override val line: Int,
    override val column: Int
) : Expr()

// ---------- OWD widget tree ----------

data class WidgetDecl(
    val type: String,
    val id: String?,
    val properties: Map<String, Expr>,
    val children: List<WidgetDecl>,
    override val line: Int,
    override val column: Int
) : Node()

// ---------- Diagnostics ----------

data class SourceLocation(val line: Int, val column: Int, val offset: Int = 0)

data class CompileError(
    val message: String,
    val location: SourceLocation,
    val severity: Severity = Severity.ERROR
) {
    enum class Severity { ERROR, WARNING, INFO }
    override fun toString(): String = "[$severity] ${location.line}:${location.column}: $message"
}
