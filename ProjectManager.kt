package com.owsowd.ide.project

import android.content.Context
import java.io.File

/**
 * Project save/load using internal storage, one folder per named project.
 *
 * Previously this only ever read/wrote a single hardcoded folder called
 * "default" — save() took a name parameter but MainActivity always passed
 * the literal string "default", and listProjects() existed but nothing in
 * the UI ever called it. There was no way to have more than one project, no
 * way to see what you'd saved, and no way to rename or delete anything.
 * That's the actual root cause behind "no open file system, no naming" —
 * this class now backs a real multi-project workflow.
 */
class ProjectManager(private val context: Context) {

    private val root: File
        get() = File(context.filesDir, "projects").also { if (!it.exists()) it.mkdirs() }

    data class ProjectInfo(val name: String, val lastModified: Long)

    fun save(name: String, ows: String, owd: String) {
        val safe = sanitize(name)
        val dir = File(root, safe).also { it.mkdirs() }
        File(dir, "main.ows").writeText(ows)
        File(dir, "main.owd").writeText(owd)
    }

    fun load(name: String): Pair<String, String>? {
        val dir = File(root, sanitize(name))
        val ows = File(dir, "main.ows")
        val owd = File(dir, "main.owd")
        if (!ows.exists()) return null
        return ows.readText() to (if (owd.exists()) owd.readText() else "")
    }

    fun exists(name: String): Boolean = File(root, sanitize(name)).let {
        it.isDirectory && File(it, "main.ows").exists()
    }

    /** Newest first, so "Open" shows what you were just working on at the top. */
    fun listProjects(): List<ProjectInfo> =
        root.listFiles()
            ?.filter { it.isDirectory && File(it, "main.ows").exists() }
            ?.map { ProjectInfo(it.name, File(it, "main.ows").lastModified()) }
            ?.sortedByDescending { it.lastModified }
            ?: emptyList()

    fun rename(oldName: String, newName: String): Boolean {
        val from = File(root, sanitize(oldName))
        val to = File(root, sanitize(newName))
        if (!from.isDirectory || to.exists()) return false
        return from.renameTo(to)
    }

    fun delete(name: String): Boolean {
        val dir = File(root, sanitize(name))
        return dir.deleteRecursively()
    }

    /**
     * Project names become directory names, so strip anything that isn't
     * safe as a single path segment. Kept deliberately simple: this is a
     * user-facing project label, not a general-purpose path sanitizer.
     */
    private fun sanitize(name: String): String =
        name.trim().replace(Regex("[^A-Za-z0-9 _\\-]"), "_").ifBlank { "untitled" }

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
