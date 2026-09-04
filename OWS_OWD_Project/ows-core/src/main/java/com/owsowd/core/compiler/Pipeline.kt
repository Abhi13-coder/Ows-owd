package com.owsowd.core.compiler

import com.owsowd.core.ast.CompileError
import com.owsowd.core.ast.Program
import com.owsowd.core.ast.SourceLocation
import com.owsowd.core.ir.CompiledUnit
import com.owsowd.core.lexer.Lexer
import com.owsowd.core.parser.Parser
import com.owsowd.core.runtime.VM
import com.owsowd.core.scene.SceneGraph

/**
 * Single shared toolchain entry point:
 * source → lexer → parser → AST → resolve OWD → compiler → bytecode + scene → VM
 *
 * This module is the standalone OWS/OWD language package that the IDE and
 * other tools consume.
 */
object Pipeline {

    data class Result(
        val unit: CompiledUnit?,
        val scene: SceneGraph?,
        val errors: List<CompileError>,
        val program: Program? = null
    )

    fun compile(source: String, owdSource: String? = null): Result {
        val allErrors = mutableListOf<CompileError>()

        // OWS (or combined)
        val lexer = Lexer(source)
        val tokens = lexer.tokenize()
        val parser = Parser(tokens)
        val program = parser.parse()
        allErrors.addAll(parser.errors)

        var owdProgram: Program? = null
        if (owdSource != null) {
            val owdLexer = Lexer(owdSource)
            val owdTokens = owdLexer.tokenize()
            val owdParser = Parser(owdTokens)
            owdProgram = owdParser.parse()
            allErrors.addAll(owdParser.errors)
        }

        if (allErrors.any { it.severity == CompileError.Severity.ERROR }) {
            return Result(null, null, allErrors, program)
        }

        val compiler = Compiler()
        val result = compiler.compile(program, owdProgram)
        allErrors.addAll(result.errors)

        return Result(result.unit, result.scene, allErrors, program)
    }

    fun compileAndCreateVM(source: String, owdSource: String? = null): Pair<VM?, Result> {
        val result = compile(source, owdSource)
        if (result.unit == null || result.scene == null) return null to result
        val vm = VM(result.unit, result.scene)
        return vm to result
    }
}
