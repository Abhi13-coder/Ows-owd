package com.owsowd.core.compiler

import com.owsowd.core.ast.*
import com.owsowd.core.ir.*
import com.owsowd.core.lexer.TokenType
import com.owsowd.core.scene.*

/**
 * Lowers full OWS/OWD AST → bytecode + SceneGraph.
 */
class Compiler {
    val errors = mutableListOf<CompileError>()

    fun compile(program: Program, owdProgram: Program? = null): CompileResult {
        errors.clear()
        val scene = SceneGraph()
        val widgets = program.widgets + (owdProgram?.widgets ?: emptyList())
        if (widgets.isNotEmpty()) {
            scene.root = buildScene(widgets.first())
            for (i in 1 until widgets.size) {
                val child = buildScene(widgets[i])
                scene.root?.children?.add(child)
                child.parent = scene.root
            }
            scene.rebuildIndex()
        }

        val chunk = Chunk()
        val locals = linkedMapOf<String, Int>()
        val localList = mutableListOf<String>()
        val events = mutableMapOf<String, Int>()
        val functions = mutableMapOf<String, FunctionInfo>()
        val classes = mutableMapOf<String, ClassInfo>()

        fun ensureLocal(name: String): Int =
            locals.getOrPut(name) {
                localList.add(name)
                localList.lastIndex
            }

        // register top-level vars
        for (stmt in program.statements) {
            if (stmt is VarDecl) ensureLocal(stmt.name)
        }

        // emit var initializers
        for (stmt in program.statements) {
            if (stmt is VarDecl && stmt.initializer != null) {
                emitExpr(stmt.initializer, chunk, locals)
                chunk.emit(OpCode.STORE, ensureLocal(stmt.name), line = stmt.line)
            }
        }

        // compile function bodies into the same chunk (simple layout)
        for (f in program.functions) {
            val start = chunk.code.size
            // params become locals 0..n-1 relative — for simplicity we map by name in a fresh map
            val funLocals = linkedMapOf<String, Int>()
            f.params.forEachIndexed { i, p -> funLocals[p] = i }
            // also allow outer locals by name
            locals.forEach { (k, v) -> if (!funLocals.containsKey(k)) funLocals[k] = v + 64 }
            for (s in f.body) emitStmt(s, chunk, funLocals, ensureLocal)
            chunk.emit(OpCode.CONST, chunk.addConstant(null), line = f.line)
            chunk.emit(OpCode.RETURN, line = f.line)
            functions[f.name] = FunctionInfo(f.name, f.params.size, start, funLocals.size)
        }

        // classes (metadata only for now; methods compiled similarly)
        for (c in program.classes) {
            val methodMap = mutableMapOf<String, FunctionInfo>()
            for (m in c.methods) {
                val start = chunk.code.size
                val funLocals = linkedMapOf<String, Int>()
                funLocals["this"] = 0
                m.params.forEachIndexed { i, p -> funLocals[p] = i + 1 }
                for (s in m.body) emitStmt(s, chunk, funLocals, ensureLocal)
                chunk.emit(OpCode.CONST, chunk.addConstant(null), line = m.line)
                chunk.emit(OpCode.RETURN, line = m.line)
                methodMap[m.name] = FunctionInfo(m.name, m.params.size, start, funLocals.size)
            }
            classes[c.name] = ClassInfo(c.name, c.fields.map { it.name }, methodMap)
        }

        // when handlers
        for (stmt in program.statements) {
            if (stmt is WhenStmt) {
                val start = chunk.code.size
                events[stmt.event] = start
                for (s in stmt.body) emitStmt(s, chunk, locals, ensureLocal)
                chunk.emit(OpCode.RETURN, line = stmt.line)
            }
        }

        chunk.emit(OpCode.HALT)
        val unit = CompiledUnit(chunk, events, functions, classes, localList, "main")
        return CompileResult(unit, scene, errors.toList())
    }

    private fun buildScene(decl: WidgetDecl): SceneNode {
        val type = when (decl.type) {
            "Widget" -> NodeType.WIDGET
            "Text" -> NodeType.TEXT
            "Button" -> NodeType.BUTTON
            "Image" -> NodeType.IMAGE
            "Rect" -> NodeType.RECT
            "Circle" -> NodeType.CIRCLE
            else -> NodeType.WIDGET
        }
        val node = SceneNode(type, decl.id)
        for ((k, v) in decl.properties) {
            val lit = evalConst(v)
            when (k) {
                "width" -> node.width = (lit as? Number)?.toFloat() ?: node.width
                "height" -> node.height = (lit as? Number)?.toFloat() ?: node.height
                "x" -> node.x = (lit as? Number)?.toFloat() ?: node.x
                "y" -> node.y = (lit as? Number)?.toFloat() ?: node.y
                "radius" -> node.radius = (lit as? Number)?.toFloat() ?: node.radius
                "background" -> if (lit is String) node.background = Color.parse(lit)
                "txt", "text" -> node.text = lit?.toString() ?: ""
                "size" -> node.textSize = (lit as? Number)?.toFloat() ?: node.textSize
                "src" -> node.src = lit?.toString()
            }
        }
        for (child in decl.children) {
            val c = buildScene(child)
            c.parent = node
            node.children.add(c)
        }
        return node
    }

    private fun evalConst(expr: Expr): Any? = when (expr) {
        is Literal -> expr.value
        is Unary -> {
            val r = evalConst(expr.right)
            when (expr.op.type) {
                TokenType.MINUS -> -(r as Number).toDouble()
                TokenType.BANG, TokenType.NOT -> !(r as Boolean)
                else -> null
            }
        }
        is Binary -> {
            val l = evalConst(expr.left)
            val r = evalConst(expr.right)
            when (expr.op.type) {
                TokenType.PLUS -> if (l is String || r is String) "$l$r" else (l as Number).toDouble() + (r as Number).toDouble()
                TokenType.MINUS -> (l as Number).toDouble() - (r as Number).toDouble()
                TokenType.STAR -> (l as Number).toDouble() * (r as Number).toDouble()
                TokenType.SLASH -> (l as Number).toDouble() / (r as Number).toDouble()
                else -> null
            }
        }
        is ListLiteral -> expr.elements.map { evalConst(it) }
        else -> null
    }

    private fun emitStmt(
        stmt: Stmt,
        chunk: Chunk,
        locals: MutableMap<String, Int>,
        ensureLocal: (String) -> Int
    ) {
        when (stmt) {
            is VarDecl -> {
                if (stmt.initializer != null) {
                    emitExpr(stmt.initializer, chunk, locals)
                    chunk.emit(OpCode.STORE, ensureLocal(stmt.name), line = stmt.line)
                } else {
                    ensureLocal(stmt.name)
                }
            }
            is AssignStmt -> {
                emitExpr(stmt.value, chunk, locals)
                when (val t = stmt.target) {
                    is Variable -> chunk.emit(OpCode.STORE, ensureLocal(t.name), line = stmt.line)
                    is GetProp -> {
                        if (t.obj is Variable) {
                            chunk.emit(OpCode.CONST, chunk.addConstant(t.obj.name), line = stmt.line)
                            chunk.emit(OpCode.CONST, chunk.addConstant(t.name), line = stmt.line)
                            chunk.emit(OpCode.SET_PROP, line = stmt.line)
                        } else {
                            emitExpr(t.obj, chunk, locals)
                            chunk.emit(OpCode.CONST, chunk.addConstant(t.name), line = stmt.line)
                            chunk.emit(OpCode.SET_PROP, line = stmt.line)
                        }
                    }
                    is SetProp -> {
                        // already has value on stack from above — re-emit properly
                        // stack should be: value
                        if (t.obj is Variable) {
                            chunk.emit(OpCode.CONST, chunk.addConstant(t.obj.name), line = stmt.line)
                            chunk.emit(OpCode.CONST, chunk.addConstant(t.name), line = stmt.line)
                            chunk.emit(OpCode.SET_PROP, line = stmt.line)
                        }
                    }
                    is IndexGet -> {
                        emitExpr(t.obj, chunk, locals)
                        emitExpr(t.index, chunk, locals)
                        chunk.emit(OpCode.SET_INDEX, line = stmt.line)
                    }
                    else -> errors.add(CompileError("Invalid assignment target", SourceLocation(stmt.line, stmt.column)))
                }
            }
            is ExprStmt -> {
                emitExpr(stmt.expr, chunk, locals)
                chunk.emit(OpCode.POP, line = stmt.line)
            }
            is IfStmt -> {
                emitExpr(stmt.condition, chunk, locals)
                val thenJump = chunk.emit(OpCode.JUMP_IF_FALSE, 0, line = stmt.line)
                for (s in stmt.thenBranch) emitStmt(s, chunk, locals, ensureLocal)
                val elseJump = chunk.emit(OpCode.JUMP, 0, line = stmt.line)
                chunk.patchJump(thenJump)
                stmt.elseBranch?.forEach { emitStmt(it, chunk, locals, ensureLocal) }
                chunk.patchJump(elseJump)
            }
            is WhileStmt -> {
                val loopStart = chunk.code.size
                emitExpr(stmt.condition, chunk, locals)
                val exitJump = chunk.emit(OpCode.JUMP_IF_FALSE, 0, line = stmt.line)
                for (s in stmt.body) emitStmt(s, chunk, locals, ensureLocal)
                chunk.emit(OpCode.JUMP, loopStart - chunk.code.size - 1, line = stmt.line)
                chunk.patchJump(exitJump)
            }
            is ForStmt -> {
                // for x in list { body }
                emitExpr(stmt.iterable, chunk, locals)
                val listLocal = ensureLocal("__iter_${stmt.line}")
                chunk.emit(OpCode.STORE, listLocal, line = stmt.line)
                val idxLocal = ensureLocal("__idx_${stmt.line}")
                chunk.emit(OpCode.CONST, chunk.addConstant(0.0), line = stmt.line)
                chunk.emit(OpCode.STORE, idxLocal, line = stmt.line)
                val loopStart = chunk.code.size
                // idx < len(list)
                chunk.emit(OpCode.LOAD, idxLocal, line = stmt.line)
                chunk.emit(OpCode.LOAD, listLocal, line = stmt.line)
                chunk.emit(OpCode.CALL_NATIVE, chunk.addConstant("len"), 1, line = stmt.line)
                chunk.emit(OpCode.LT, line = stmt.line)
                val exitJump = chunk.emit(OpCode.JUMP_IF_FALSE, 0, line = stmt.line)
                // x = list[idx]
                chunk.emit(OpCode.LOAD, listLocal, line = stmt.line)
                chunk.emit(OpCode.LOAD, idxLocal, line = stmt.line)
                chunk.emit(OpCode.GET_INDEX, line = stmt.line)
                chunk.emit(OpCode.STORE, ensureLocal(stmt.variable), line = stmt.line)
                for (s in stmt.body) emitStmt(s, chunk, locals, ensureLocal)
                // idx = idx + 1
                chunk.emit(OpCode.LOAD, idxLocal, line = stmt.line)
                chunk.emit(OpCode.CONST, chunk.addConstant(1.0), line = stmt.line)
                chunk.emit(OpCode.ADD, line = stmt.line)
                chunk.emit(OpCode.STORE, idxLocal, line = stmt.line)
                chunk.emit(OpCode.JUMP, loopStart - chunk.code.size - 1, line = stmt.line)
                chunk.patchJump(exitJump)
            }
            is ReturnStmt -> {
                if (stmt.value != null) emitExpr(stmt.value, chunk, locals)
                else chunk.emit(OpCode.CONST, chunk.addConstant(null), line = stmt.line)
                chunk.emit(OpCode.RETURN, line = stmt.line)
            }
            is BlockStmt -> stmt.statements.forEach { emitStmt(it, chunk, locals, ensureLocal) }
            is WhenStmt, is AttachStmt, is BreakStmt, is ContinueStmt,
            is FunDeclStmt, is ClassDeclStmt -> { /* top-level / later */ }
        }
    }

    private fun emitExpr(expr: Expr, chunk: Chunk, locals: Map<String, Int>) {
        when (expr) {
            is Literal -> chunk.emit(OpCode.CONST, chunk.addConstant(expr.value), line = expr.line)
            is Variable -> {
                val idx = locals[expr.name]
                if (idx != null) chunk.emit(OpCode.LOAD, idx, line = expr.line)
                else {
                    // global / widget id / builtin name
                    chunk.emit(OpCode.CONST, chunk.addConstant(expr.name), line = expr.line)
                }
            }
            is Binary -> {
                emitExpr(expr.left, chunk, locals)
                emitExpr(expr.right, chunk, locals)
                val op = when (expr.op.type) {
                    TokenType.PLUS -> OpCode.ADD
                    TokenType.MINUS -> OpCode.SUB
                    TokenType.STAR -> OpCode.MUL
                    TokenType.SLASH -> OpCode.DIV
                    TokenType.PERCENT -> OpCode.MOD
                    TokenType.EQEQ -> OpCode.EQ
                    TokenType.NEQ -> OpCode.NEQ
                    TokenType.LT -> OpCode.LT
                    TokenType.GT -> OpCode.GT
                    TokenType.LTE -> OpCode.LTE
                    TokenType.GTE -> OpCode.GTE
                    TokenType.AND, TokenType.AMP -> OpCode.AND
                    TokenType.OR, TokenType.PIPE -> OpCode.OR
                    else -> {
                        errors.add(CompileError("Unknown operator ${expr.op.lexeme}", SourceLocation(expr.line, expr.column)))
                        return
                    }
                }
                chunk.emit(op, line = expr.line)
            }
            is Unary -> {
                emitExpr(expr.right, chunk, locals)
                when (expr.op.type) {
                    TokenType.MINUS -> chunk.emit(OpCode.NEG, line = expr.line)
                    TokenType.BANG, TokenType.NOT -> chunk.emit(OpCode.NOT, line = expr.line)
                    else -> {}
                }
            }
            is Call -> {
                expr.args.forEach { emitExpr(it, chunk, locals) }
                when {
                    // builtins
                    expr.callee is Variable && expr.callee.name in BUILTINS -> {
                        chunk.emit(OpCode.CALL_NATIVE, chunk.addConstant(expr.callee.name), expr.args.size, line = expr.line)
                    }
                    // http.get / http.post via GetProp
                    expr.callee is GetProp && expr.callee.obj is Variable &&
                            (expr.callee.obj.name == "http" || expr.callee.obj.name == "Http") -> {
                        val method = expr.callee.name // get, post, put, delete, json
                        chunk.emit(OpCode.CALL_NATIVE, chunk.addConstant("http.$method"), expr.args.size, line = expr.line)
                    }
                    expr.callee is GetProp -> {
                        // method call: push object then CALL
                        emitExpr(expr.callee.obj, chunk, locals)
                        chunk.emit(OpCode.CALL_NATIVE, chunk.addConstant("method:${expr.callee.name}"), expr.args.size + 1, line = expr.line)
                    }
                    else -> {
                        emitExpr(expr.callee, chunk, locals)
                        chunk.emit(OpCode.CALL, 0, expr.args.size, line = expr.line)
                    }
                }
            }
            is GetProp -> {
                if (expr.obj is Variable) {
                    chunk.emit(OpCode.CONST, chunk.addConstant(expr.obj.name), line = expr.line)
                    chunk.emit(OpCode.CONST, chunk.addConstant(expr.name), line = expr.line)
                    chunk.emit(OpCode.GET_PROP, line = expr.line)
                } else {
                    emitExpr(expr.obj, chunk, locals)
                    chunk.emit(OpCode.CONST, chunk.addConstant(expr.name), line = expr.line)
                    chunk.emit(OpCode.GET_PROP, line = expr.line)
                }
            }
            is SetProp -> {
                emitExpr(expr.value, chunk, locals)
                if (expr.obj is Variable) {
                    chunk.emit(OpCode.CONST, chunk.addConstant(expr.obj.name), line = expr.line)
                    chunk.emit(OpCode.CONST, chunk.addConstant(expr.name), line = expr.line)
                    chunk.emit(OpCode.SET_PROP, line = expr.line)
                } else {
                    emitExpr(expr.obj, chunk, locals)
                    chunk.emit(OpCode.CONST, chunk.addConstant(expr.name), line = expr.line)
                    chunk.emit(OpCode.SET_PROP, line = expr.line)
                }
            }
            is IndexGet -> {
                emitExpr(expr.obj, chunk, locals)
                emitExpr(expr.index, chunk, locals)
                chunk.emit(OpCode.GET_INDEX, line = expr.line)
            }
            is IndexSet -> {
                emitExpr(expr.value, chunk, locals)
                emitExpr(expr.obj, chunk, locals)
                emitExpr(expr.index, chunk, locals)
                chunk.emit(OpCode.SET_INDEX, line = expr.line)
            }
            is ListLiteral -> {
                expr.elements.forEach { emitExpr(it, chunk, locals) }
                chunk.emit(OpCode.MAKE_LIST, expr.elements.size, line = expr.line)
            }
            is MapLiteral -> {
                expr.entries.forEach { (k, v) ->
                    emitExpr(k, chunk, locals)
                    emitExpr(v, chunk, locals)
                }
                chunk.emit(OpCode.MAKE_MAP, expr.entries.size, line = expr.line)
            }
            is NewExpr -> {
                expr.args.forEach { emitExpr(it, chunk, locals) }
                chunk.emit(OpCode.NEW_OBJ, chunk.addConstant(expr.className), expr.args.size, line = expr.line)
            }
            is ThisExpr -> {
                val idx = locals["this"]
                if (idx != null) chunk.emit(OpCode.LOAD, idx, line = expr.line)
                else chunk.emit(OpCode.CONST, chunk.addConstant(null), line = expr.line)
            }
        }
    }

    companion object {
        val BUILTINS = setOf(
            "str", "num", "len", "print", "log",
            "json_parse", "json_stringify",
            "min", "max", "abs", "floor", "ceil", "round",
            "upper", "lower", "trim", "split", "join",
            "now", "sleep"
        )
    }
}

data class CompileResult(
    val unit: CompiledUnit,
    val scene: SceneGraph,
    val errors: List<CompileError>
)
