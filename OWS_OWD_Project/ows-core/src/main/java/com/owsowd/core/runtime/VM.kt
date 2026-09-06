package com.owsowd.core.runtime

import com.owsowd.core.ir.*
import com.owsowd.core.json.Json
import com.owsowd.core.scene.SceneGraph
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.*

/**
 * Stack VM for the full OWS language.
 * Built-ins include math, strings, lists, JSON, and HTTP (http.get / http.post / …).
 */
class VM(
    private val unit: CompiledUnit,
    private val scene: SceneGraph,
    private val logger: OwsLogger = DefaultLogger
) {
    private val stack = ArrayDeque<Any?>()
    private val locals = arrayOfNulls<Any?>(512)
    private var ip = 0
    var lastError: String? = null
    private val callStack = ArrayDeque<Int>() // return addresses

    /** Last completed async HTTP response (map). Read via http.result */
    @Volatile
    var lastHttpResult: Map<String, Any?>? = null
        private set

    /** Named async results: eventName -> response map */
    private val httpResults = ConcurrentHashMap<String, Map<String, Any?>>()

    /**
     * Host sets this to receive async HTTP completion on a safe thread.
     * Args: eventName to fire, response map.
     */
    var onHttpComplete: ((eventName: String, result: Map<String, Any?>) -> Unit)? = null

    private val httpExecutor = Executors.newCachedThreadPool { r ->
        Thread(r, "ows-http").apply { isDaemon = true }
    }

    // simple object instances: Map-based
    private data class ObjInstance(val className: String, val fields: MutableMap<String, Any?>)

    fun runEvent(eventName: String) {
        val start = unit.events[eventName] ?: return
        ip = start
        try {
            execute()
        } catch (e: Exception) {
            lastError = e.message
            e.printStackTrace()
        }
    }

    fun runFromStart() {
        ip = 0
        try { execute() } catch (e: Exception) { lastError = e.message }
    }

    fun callFunction(name: String, args: List<Any?> = emptyList()): Any? {
        val info = unit.functions[name] ?: return null
        args.forEachIndexed { i, v -> locals[i] = v }
        callStack.addLast(-1)
        ip = info.chunkOffset
        try {
            execute()
            return if (stack.isNotEmpty()) pop() else null
        } catch (e: Exception) {
            lastError = e.message
            return null
        }
    }

    private fun execute() {
        val code = unit.chunk.code
        val consts = unit.chunk.constants
        while (ip < code.size) {
            val inst = code[ip++]
            when (inst.op) {
                OpCode.CONST -> push(consts[inst.a])
                OpCode.LOAD -> push(locals[inst.a])
                OpCode.STORE -> locals[inst.a] = pop()
                OpCode.POP -> pop()
                OpCode.DUP -> push(peek())
                OpCode.ADD -> {
                    val b = pop(); val a = pop()
                    push(add(a, b))
                }
                OpCode.SUB -> binaryNum { a, b -> a - b }
                OpCode.MUL -> binaryNum { a, b -> a * b }
                OpCode.DIV -> binaryNum { a, b -> a / b }
                OpCode.MOD -> binaryNum { a, b -> a % b }
                OpCode.NEG -> push(-asNumber(pop()))
                OpCode.EQ -> { val b = pop(); val a = pop(); push(eq(a, b)) }
                OpCode.NEQ -> { val b = pop(); val a = pop(); push(!eq(a, b)) }
                OpCode.LT -> binaryCmp { a, b -> a < b }
                OpCode.GT -> binaryCmp { a, b -> a > b }
                OpCode.LTE -> binaryCmp { a, b -> a <= b }
                OpCode.GTE -> binaryCmp { a, b -> a >= b }
                OpCode.AND -> {
                    val b = truthy(pop()); val a = truthy(pop())
                    push(a && b)
                }
                OpCode.OR -> {
                    val b = truthy(pop()); val a = truthy(pop())
                    push(a || b)
                }
                OpCode.NOT -> push(!truthy(pop()))
                OpCode.GET_PROP -> {
                    val prop = pop()?.toString() ?: ""
                    val target = pop()
                    push(getProp(target, prop))
                }
                OpCode.SET_PROP -> {
                    val prop = pop()?.toString() ?: ""
                    val idOrObj = pop()
                    val value = pop()
                    setProp(idOrObj, prop, value)
                }
                OpCode.GET_INDEX -> {
                    val index = pop()
                    val obj = pop()
                    push(getIndex(obj, index))
                }
                OpCode.SET_INDEX -> {
                    val index = pop()
                    val obj = pop()
                    val value = pop()
                    setIndex(obj, index, value)
                }
                OpCode.MAKE_LIST -> {
                    val n = inst.a
                    val list = MutableList<Any?>(n) { null }
                    for (i in n - 1 downTo 0) list[i] = pop()
                    push(list)
                }
                OpCode.MAKE_MAP -> {
                    val n = inst.a
                    val map = linkedMapOf<Any?, Any?>()
                    val pairs = mutableListOf<Pair<Any?, Any?>>()
                    for (i in 0 until n) {
                        val v = pop()
                        val k = pop()
                        pairs.add(k to v)
                    }
                    pairs.reversed().forEach { (k, v) -> map[k] = v }
                    push(map)
                }
                OpCode.JUMP -> ip += inst.a
                OpCode.JUMP_IF_FALSE -> {
                    if (!truthy(pop())) ip += inst.a
                }
                OpCode.JUMP_IF_TRUE -> {
                    if (truthy(pop())) ip += inst.a
                }
                OpCode.CALL -> {
                    // user function by name on stack
                    val name = pop()?.toString() ?: ""
                    val argc = inst.b
                    val args = (0 until argc).map { pop() }.reversed()
                    val info = unit.functions[name]
                    if (info != null) {
                        callStack.addLast(ip)
                        args.forEachIndexed { i, v -> locals[i] = v }
                        ip = info.chunkOffset
                    } else {
                        push(null)
                    }
                }
                OpCode.CALL_NATIVE -> {
                    val name = consts[inst.a]?.toString() ?: ""
                    val argc = inst.b
                    val args = (0 until argc).map { pop() }.reversed()
                    push(callNative(name, args))
                }
                OpCode.NEW_OBJ -> {
                    val className = consts[inst.a]?.toString() ?: ""
                    val argc = inst.b
                    val args = (0 until argc).map { pop() }.reversed()
                    val info = unit.classes[className]
                    val fields = mutableMapOf<String, Any?>()
                    info?.fields?.forEachIndexed { i, f ->
                        fields[f] = args.getOrNull(i)
                    }
                    push(ObjInstance(className, fields))
                }
                OpCode.RETURN -> {
                    if (callStack.isEmpty()) return
                    val ret = callStack.removeLast()
                    if (ret < 0) return // top-level event / external call
                    ip = ret
                }
                OpCode.HALT -> return
                OpCode.BIND_EVENT -> { }
            }
        }
    }

    // ---- property / index ----

    private fun getProp(target: Any?, prop: String): Any? {
        when (target) {
            is String -> {
                // http.result without call parens
                if (target == "http" && prop == "result") return lastHttpResult
                if (prop == "length" || prop == "len") return target.length.toDouble()
                return scene.getProperty(target, prop)
            }
            is List<*> -> {
                if (prop == "length" || prop == "len" || prop == "size") return target.size.toDouble()
                return null
            }
            is Map<*, *> -> return target[prop]
            is ObjInstance -> return target.fields[prop]
            is Number -> return null
            else -> {
                // try as widget id string
                val id = target?.toString() ?: return null
                return scene.getProperty(id, prop)
            }
        }
    }

    private fun setProp(target: Any?, prop: String, value: Any?) {
        when (target) {
            is String -> scene.setProperty(target, prop, value)
            is ObjInstance -> target.fields[prop] = value
            is MutableMap<*, *> -> {
                @Suppress("UNCHECKED_CAST")
                (target as MutableMap<Any?, Any?>)[prop] = value
            }
            else -> {
                val id = target?.toString() ?: return
                scene.setProperty(id, prop, value)
            }
        }
    }

    private fun getIndex(obj: Any?, index: Any?): Any? {
        val i = when (index) {
            is Number -> index.toInt()
            else -> index?.toString()?.toIntOrNull() ?: return null
        }
        return when (obj) {
            is List<*> -> obj.getOrNull(i)
            is String -> obj.getOrNull(i)?.toString()
            is Map<*, *> -> obj[index] ?: obj[i]
            else -> null
        }
    }

    private fun setIndex(obj: Any?, index: Any?, value: Any?) {
        val i = (index as? Number)?.toInt()
        when (obj) {
            is MutableList<*> -> {
                @Suppress("UNCHECKED_CAST")
                val list = obj as MutableList<Any?>
                if (i != null && i in list.indices) list[i] = value
            }
            is MutableMap<*, *> -> {
                @Suppress("UNCHECKED_CAST")
                (obj as MutableMap<Any?, Any?>)[index] = value
            }
        }
    }

    // ---- natives ----

    private fun callNative(name: String, args: List<Any?>): Any? {
        return try {
            when (name) {
                "str" -> args.firstOrNull()?.toString() ?: ""
                "num" -> asNumber(args.firstOrNull())
                "len" -> {
                    when (val v = args.firstOrNull()) {
                        is String -> v.length.toDouble()
                        is List<*> -> v.size.toDouble()
                        is Map<*, *> -> v.size.toDouble()
                        else -> 0.0
                    }
                }
                "print", "log" -> {
                    val msg = args.joinToString(" ") { it?.toString() ?: "null" }
                    logger.log(msg)
                    null
                }
                "min" -> args.map { asNumber(it) }.minOrNull()
                "max" -> args.map { asNumber(it) }.maxOrNull()
                "abs" -> abs(asNumber(args.firstOrNull()))
                "floor" -> floor(asNumber(args.firstOrNull()))
                "ceil" -> ceil(asNumber(args.firstOrNull()))
                "round" -> round(asNumber(args.firstOrNull()))
                "upper" -> args.firstOrNull()?.toString()?.uppercase()
                "lower" -> args.firstOrNull()?.toString()?.lowercase()
                "trim" -> args.firstOrNull()?.toString()?.trim()
                "split" -> {
                    val s = args.getOrNull(0)?.toString() ?: ""
                    val sep = args.getOrNull(1)?.toString() ?: ","
                    s.split(sep).toMutableList()
                }
                "join" -> {
                    val list = args.getOrNull(0) as? List<*> ?: return ""
                    val sep = args.getOrNull(1)?.toString() ?: ","
                    list.joinToString(sep) { it?.toString() ?: "" }
                }
                "json_parse" -> {
                    val s = args.firstOrNull()?.toString() ?: return null
                    parseJson(s)
                }
                "json_stringify" -> {
                    stringifyJson(args.firstOrNull())
                }
                "now" -> System.currentTimeMillis().toDouble()
                "sleep" -> {
                    val ms = asNumber(args.firstOrNull()).toLong()
                    Thread.sleep(ms.coerceIn(0, 30_000))
                    null
                }
                // HTTP API — sync (short requests only)
                "http.get" -> httpRequest("GET", args)
                "http.post" -> httpRequest("POST", args)
                "http.put" -> httpRequest("PUT", args)
                "http.delete" -> httpRequest("DELETE", args)
                "http.patch" -> httpRequest("PATCH", args)
                "http.json" -> {
                    val method = args.getOrNull(0)?.toString() ?: "GET"
                    val url = args.getOrNull(1)?.toString() ?: return null
                    val body = args.getOrNull(2)
                    val headers = args.getOrNull(3) as? Map<*, *>
                    httpRequest(method, listOf(url, body, headers))
                }
                // HTTP API — async (background thread; fires event when done)
                // http.get_async(url)
                // http.get_async(url, "MyEvent")
                // http.get_async(url, headersMap, "MyEvent")
                // http.post_async(url, body)
                // http.post_async(url, body, "MyEvent")
                // http.post_async(url, body, headersMap, "MyEvent")
                "http.get_async" -> httpRequestAsync("GET", args)
                "http.post_async" -> httpRequestAsync("POST", args)
                "http.put_async" -> httpRequestAsync("PUT", args)
                "http.delete_async" -> httpRequestAsync("DELETE", args)
                "http.patch_async" -> httpRequestAsync("PATCH", args)
                // http.result  or  http.result("MyEvent")
                "http.result" -> {
                    val key = args.firstOrNull()?.toString()
                    if (key.isNullOrBlank()) lastHttpResult
                    else httpResults[key]
                }
                else -> {
                    if (name.startsWith("method:")) {
                        // instance method — first arg is object
                        null
                    } else null
                }
            }
        } catch (e: Exception) {
            lastError = "native $name: ${e.message}"
            null
        }
    }

    /**
     * Async HTTP: runs on background pool, stores result, fires event via onHttpComplete.
     *
     * Arg patterns:
     *   get/delete:  (url) | (url, eventName) | (url, headers, eventName)
     *   post/put:    (url, body) | (url, body, eventName) | (url, body, headers, eventName)
     *
     * Default event name: "http.done"
     * In the when handler:  var res = http.result   or  http.result("MyEvent")
     */
    private fun httpRequestAsync(method: String, args: List<Any?>): Any? {
        val eventName = extractAsyncEventName(method, args)
        val requestArgs = stripAsyncEventName(method, args)
        httpExecutor.execute {
            val result = try {
                httpRequest(method, requestArgs)
            } catch (e: Exception) {
                mapOf(
                    "ok" to false,
                    "error" to (e.message ?: "http error"),
                    "status" to 0.0,
                    "body" to ""
                )
            }
            lastHttpResult = result
            httpResults[eventName] = result
            try {
                onHttpComplete?.invoke(eventName, result)
            } catch (_: Exception) {}
        }
        // return immediately so UI stays responsive
        return mapOf(
            "ok" to true,
            "pending" to true,
            "event" to eventName
        )
    }

    private fun extractAsyncEventName(method: String, args: List<Any?>): String {
        // last string arg that is not a url (no ://) and not first arg → event name
        val last = args.lastOrNull()
        if (last is String && !last.contains("://") && args.size >= 2) {
            // for GET: (url, event) or (url, headers, event)
            // for POST: (url, body, event) or (url, body, headers, event)
            if (method == "GET" || method == "DELETE") {
                if (args.size >= 2 && last is String && args.getOrNull(1) !is Map<*, *>) return last
                if (args.size >= 3 && last is String) return last
            } else {
                if (args.size >= 3 && last is String && args.getOrNull(2) !is Map<*, *>) return last
                if (args.size >= 4 && last is String) return last
            }
        }
        return "http.done"
    }

    private fun stripAsyncEventName(method: String, args: List<Any?>): List<Any?> {
        val event = extractAsyncEventName(method, args)
        if (event == "http.done") return args
        val last = args.lastOrNull()
        return if (last is String && last == event) args.dropLast(1) else args
    }

    /**
     * Sync HTTP for short calls.
     * Prefer http.get_async / http.post_async for long requests.
     */
    private fun httpRequest(method: String, args: List<Any?>): Map<String, Any?> {
        val urlStr = args.getOrNull(0)?.toString()
            ?: return mapOf("ok" to false, "error" to "missing url")
        var body: Any? = null
        var headers: Map<*, *>? = null
        when (method) {
            "GET", "DELETE" -> {
                headers = args.getOrNull(1) as? Map<*, *>
            }
            else -> {
                body = args.getOrNull(1)
                headers = args.getOrNull(2) as? Map<*, *>
            }
        }
        return try {
            val url = URL(urlStr)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 15_000
                readTimeout = 20_000
                doInput = true
                setRequestProperty("User-Agent", "OWS/1.0")
                setRequestProperty("Accept", "application/json, text/plain, */*")
                headers?.forEach { (k, v) ->
                    setRequestProperty(k.toString(), v?.toString() ?: "")
                }
                if (body != null && method != "GET" && method != "DELETE") {
                    doOutput = true
                    val payload = when (body) {
                        is Map<*, *>, is List<*> -> stringifyJson(body)
                        else -> body.toString()
                    }
                    if (body is Map<*, *> || body is List<*>) {
                        setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    }
                    OutputStreamWriter(outputStream, Charsets.UTF_8).use { it.write(payload) }
                }
            }
            val status = conn.responseCode
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.let {
                BufferedReader(InputStreamReader(it, Charsets.UTF_8)).use { br ->
                    br.readText()
                }
            } ?: ""
            val respHeaders = conn.headerFields
                ?.filterKeys { it != null }
                ?.mapKeys { it.key!! }
                ?.mapValues { it.value?.joinToString(", ") }
                ?: emptyMap()
            val jsonVal = try { parseJson(text) } catch (_: Exception) { null }
            mapOf(
                "status" to status.toDouble(),
                "body" to text,
                "headers" to respHeaders,
                "ok" to (status in 200..299),
                "json" to jsonVal
            )
        } catch (e: Exception) {
            mapOf(
                "ok" to false,
                "error" to (e.message ?: "http error"),
                "status" to 0.0,
                "body" to ""
            )
        }
    }

    private fun parseJson(s: String): Any? = Json.parse(s)

    private fun stringifyJson(v: Any?): String = Json.stringify(v)

    // ---- helpers ----

    private fun push(v: Any?) = stack.addLast(v)
    private fun pop(): Any? = stack.removeLast()
    private fun peek(): Any? = stack.last()

    private fun asNumber(v: Any?): Double = when (v) {
        is Number -> v.toDouble()
        is String -> v.toDoubleOrNull() ?: 0.0
        is Boolean -> if (v) 1.0 else 0.0
        else -> 0.0
    }

    private fun truthy(v: Any?): Boolean = when (v) {
        null -> false
        is Boolean -> v
        is Number -> v.toDouble() != 0.0
        is String -> v.isNotEmpty()
        is List<*> -> v.isNotEmpty()
        is Map<*, *> -> v.isNotEmpty()
        else -> true
    }

    private fun eq(a: Any?, b: Any?): Boolean {
        if (a is Number && b is Number) return a.toDouble() == b.toDouble()
        return a == b
    }

    private fun add(a: Any?, b: Any?): Any {
        if (a is String || b is String) return "${a ?: ""}${b ?: ""}"
        return asNumber(a) + asNumber(b)
    }

    private inline fun binaryNum(op: (Double, Double) -> Double) {
        val b = asNumber(pop()); val a = asNumber(pop())
        push(op(a, b))
    }

    private inline fun binaryCmp(op: (Double, Double) -> Boolean) {
        val b = asNumber(pop()); val a = asNumber(pop())
        push(op(a, b))
    }
}
