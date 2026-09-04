package com.owsowd.core.ir

/**
 * Stack-based IR designed for a full language + future JIT.
 */
enum class OpCode {
    CONST, LOAD, STORE, POP, DUP,
    ADD, SUB, MUL, DIV, MOD, NEG,
    EQ, NEQ, LT, GT, LTE, GTE,
    AND, OR, NOT,
    GET_PROP, SET_PROP,
    GET_INDEX, SET_INDEX,
    MAKE_LIST, MAKE_MAP,
    JUMP, JUMP_IF_FALSE, JUMP_IF_TRUE,
    CALL, CALL_NATIVE, RETURN,
    NEW_OBJ,
    BIND_EVENT, HALT
}

data class Instruction(
    val op: OpCode,
    val a: Int = 0,
    val b: Int = 0,
    val c: Int = 0
)

data class Chunk(
    val code: MutableList<Instruction> = mutableListOf(),
    val constants: MutableList<Any?> = mutableListOf(),
    val lines: MutableList<Int> = mutableListOf()
) {
    fun emit(op: OpCode, a: Int = 0, b: Int = 0, c: Int = 0, line: Int = 0): Int {
        code.add(Instruction(op, a, b, c))
        lines.add(line)
        return code.lastIndex
    }

    fun addConstant(value: Any?): Int {
        // intern simple values
        val idx = constants.indexOf(value)
        if (idx >= 0 && value !is MutableList<*> && value !is MutableMap<*, *>) return idx
        constants.add(value)
        return constants.lastIndex
    }

    fun patchJump(offset: Int) {
        val jump = code.size - offset - 1
        val old = code[offset]
        code[offset] = old.copy(a = jump)
    }
}

data class FunctionInfo(
    val name: String,
    val arity: Int,
    val chunkOffset: Int,
    val localCount: Int = 0
)

data class ClassInfo(
    val name: String,
    val fields: List<String>,
    val methods: Map<String, FunctionInfo>
)

data class CompiledUnit(
    val chunk: Chunk,
    val events: Map<String, Int>,
    val functions: Map<String, FunctionInfo> = emptyMap(),
    val classes: Map<String, ClassInfo> = emptyMap(),
    val localNames: List<String> = emptyList(),
    val sourceName: String = ""
)
