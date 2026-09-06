package com.owsowd.core.runtime

/**
 * VM.kt previously called android.util.Log.d(...) directly for the OWS
 * `print`/`log` builtins. That's the other hard Android dependency in the
 * runtime (besides org.json, see json/Json.kt) — it means the VM could not
 * run standalone (e.g. a `ows run app.ows` CLI on Linux/Windows/macOS,
 * or a JVM unit test) without android.jar on the classpath.
 *
 * The VM now calls through this interface instead. Each host wires up
 * whatever's appropriate:
 *   - Android host -> android.util.Log.d("OWS", msg)
 *   - CLI / desktop hosts -> println(msg)
 *   - tests -> capture into a list and assert on it
 *
 * Defaults to println so the VM works out of the box on any JVM.
 */
fun interface OwsLogger {
    fun log(message: String)
}

object DefaultLogger : OwsLogger {
    override fun log(message: String) {
        println(message)
    }
}
