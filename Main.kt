package com.owsowd.cli

import com.owsowd.core.compiler.Pipeline
import com.owsowd.core.host.Capability
import com.owsowd.core.host.Host
import com.owsowd.core.host.JvmHostManager
import com.owsowd.core.host.OverlayHandle
import com.owsowd.core.runtime.OwsLogger
import com.owsowd.core.util.SourceMap
import java.io.File

/**
 * Unified CLI:
 *   ows run app.ows            -> compile + run headless (no GUI)
 *   ows run overlay app.ows    -> compile + ask the Host Manager for an overlay
 *
 * The distinction the project brief asked for explicitly: this process is a
 * plain JVM (Linux/Windows/macOS) userland program. Even if it happens to be
 * running inside Termux/PRoot on an Android phone, it is a LINUX host from
 * this runtime's point of view (see JvmHostManager kdoc) — it does NOT reach
 * into Android's WindowManager, and it says so instead of pretending.
 * A real overlay requires the Android app (:app module, AndroidHostManager)
 * running as an actual Android host, which is a different process with a
 * different permission model entirely — this CLI cannot bridge into it.
 */
object Main {
    private val host = JvmHostManager()

    @JvmStatic
    fun main(args: Array<String>) {
        if (args.isEmpty() || args[0] != "run") {
            printUsage()
            return
        }
        val overlayMode = args.getOrNull(1) == "overlay"
        val pathArg = if (overlayMode) args.getOrNull(2) else args.getOrNull(1)
        if (pathArg == null) {
            printUsage()
            return
        }

        val owsFile = File(pathArg)
        if (!owsFile.exists()) {
            System.err.println("error: no such file: $pathArg")
            return
        }
        val owdFile = File(owsFile.parentFile ?: File("."), owsFile.nameWithoutExtension + ".owd")
        val owsSource = owsFile.readText()
        val owdSource = if (owdFile.exists()) owdFile.readText() else null

        val result = Pipeline.compile(owsSource, owdSource)
        if (result.errors.isNotEmpty()) {
            result.errors.forEach { err ->
                println(err.toString())
                val snippet = SourceMap.snippet(owsSource, err.location.line, err.location.column)
                if (snippet.isNotBlank()) println(snippet)
            }
            if (result.errors.any { it.severity == com.owsowd.core.ast.CompileError.Severity.ERROR }) return
        }

        if (overlayMode) {
            runOverlay(owsSource, owdSource, pathArg)
        } else {
            runHeadless(result)
        }
    }

    private fun runHeadless(result: Pipeline.Result) {
        val unit = result.unit
        val scene = result.scene
        if (unit == null || scene == null) {
            System.err.println("error: compilation failed, nothing to run")
            return
        }
        val vm = com.owsowd.core.runtime.VM(unit, scene, logger = OwsLogger { println(it) })
        println("host: ${host.detectHost()} (${host.detectArchitecture()})")
        println("capabilities: ${host.detectCapabilities()}")
        println("running headless - ${unit.events.size} event handler(s) registered, no GUI")
        // Headless mode has nothing to click, so it just proves the program
        // compiles + a VM instance can be constructed and is ready for events.
        // A future `ows run --fire EventName app.ows` could invoke a specific
        // event non-interactively; not added here to avoid inventing a fake
        // "interactive" mode this CLI can't actually back yet (no terminal
        // widget renderer exists — see CanvasRenderer/GlSceneView, both GUI).
    }

    private fun runOverlay(owsSource: String, owdSource: String?, path: String) {
        val detected = host.detectHost()
        if (!host.supports(Capability.OVERLAY)) {
            println("host: $detected: overlay is not supported here.")
            if (isLikelyAndroidUnderproot()) {
                println(
                    "This looks like a Linux userland running on an Android device " +
                        "(Termux/PRoot or similar). That does not grant WindowManager " +
                        "access: this process is still a Linux host, not an Android host. " +
                        "To actually show an overlay, run this .ows/.owd through the OWS/OWD " +
                        "Android app (:app module) on the same device instead; it uses " +
                        "AndroidHostManager, which has real overlay-permission and " +
                        "WindowManager access."
                )
            } else {
                println(
                    "Overlay is currently only implemented for the Android host. " +
                        "$detected is an interface/stub only right now - see HostManager " +
                        "kdoc for the implemented/partial/stub distinction."
                )
            }
            return
        }
        // Reachable only once a real desktop HostManager implementation exists.
        host.createOverlay(OverlayHandle(path, owsSource, owdSource))
    }

    /** Best-effort heuristic, not a security check: Termux sets $PREFIX to a
     *  path under /data/data/com.termux; a plain Alpine/PRoot chroot commonly
     *  carries no such marker but often still has /system or /sdcard visible
     *  from the host. Good enough to point someone in the right direction in
     *  an error message — never used to grant any actual capability. */
    private fun isLikelyAndroidUnderproot(): Boolean {
        val prefix = System.getenv("PREFIX") ?: ""
        return prefix.contains("com.termux") || File("/system/build.prop").exists()
    }

    private fun printUsage() {
        println(
            """
            Usage:
              ows run <file.ows>           Compile and run headless (no GUI)
              ows run overlay <file.ows>   Compile and request an overlay from the Host Manager

            A matching <file.owd> next to <file.ows>, if present, is loaded automatically.
            """.trimIndent()
        )
    }
}
