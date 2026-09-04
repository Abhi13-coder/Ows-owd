package com.owsowd.ide.project

import android.content.Context
import java.io.File

/**
 * Simple project save/load using internal storage.
 * Also loads samples from assets.
 */
class ProjectManager(private val context: Context) {

    private val root: File
        get() = File(context.filesDir, "projects").also { if (!it.exists()) it.mkdirs() }

    fun save(name: String, ows: String, owd: String) {
        val dir = File(root, name).also { it.mkdirs() }
        File(dir, "main.ows").writeText(ows)
        File(dir, "main.owd").writeText(owd)
    }

    fun load(name: String): Pair<String, String>? {
        val dir = File(root, name)
        val ows = File(dir, "main.ows")
        val owd = File(dir, "main.owd")
        if (!ows.exists()) return null
        return ows.readText() to (if (owd.exists()) owd.readText() else "")
    }

    fun listProjects(): List<String> =
        root.listFiles()?.filter { it.isDirectory }?.map { it.name } ?: emptyList()

    fun loadSample(name: String): Pair<String, String> {
        return try {
            val ows = context.assets.open("samples/$name/main.ows").bufferedReader().readText()
            val owd = context.assets.open("samples/$name/main.owd").bufferedReader().readText()
            ows to owd
        } catch (e: Exception) {
            DEFAULT_OWS to DEFAULT_OWD
        }
    }

    companion object {
        val DEFAULT_OWS = """
            // OWS — logic (operators: + - * /)
            attach src = "widget.owd"
            number count = 0

            fun formatCount(n) {
                return "Count: " + str(n)
            }

            when Plus.clicked {
                count = count + 1
                Counter.txt = formatCount(count)
            }

            when Minus.clicked {
                count = count - 1
                if count < 0 {
                    count = 0
                }
                Counter.txt = formatCount(count)
            }
        """.trimIndent()

        val DEFAULT_OWD = """
            Widget CounterRoot {
                width: 260
                height: 160
                radius: 24
                background: "#151515"

                Text Counter {
                    txt: "Count: 0"
                    size: 28
                    x: 16
                    y: 24
                }

                Button Minus {
                    txt: "-"
                    x: 16
                    y: 90
                    width: 50
                    height: 48
                    radius: 12
                    background: "#333333"
                }

                Button Plus {
                    txt: "+"
                    x: 190
                    y: 90
                    width: 50
                    height: 48
                    radius: 12
                    background: "#1E88E5"
                }
            }
        """.trimIndent()
    }
}
