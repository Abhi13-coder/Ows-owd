package com.owsowd.ide.ui

import android.os.Bundle
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.tabs.TabLayout
import com.owsowd.core.compiler.Pipeline
import com.owsowd.core.host.Capability
import com.owsowd.core.host.OverlayHandle
import com.owsowd.core.host.PermissionState
import com.owsowd.core.runtime.VM
import com.owsowd.core.scene.SceneGraph
import com.owsowd.core.util.SourceMap
import com.owsowd.ide.R
import com.owsowd.ide.editor.SyntaxHighlighter
import com.owsowd.ide.host.AndroidHostManager
import com.owsowd.ide.render.RenderBackend
import com.owsowd.ide.preview.PreviewView
import com.owsowd.ide.project.ProjectManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var owsEditor: EditText
    private lateinit var owdEditor: EditText
    private lateinit var previewView: PreviewView
    private lateinit var errorsView: TextView
    private lateinit var statusBar: TextView
    private lateinit var owsScroll: ScrollView
    private lateinit var owdScroll: ScrollView
    private lateinit var errorsScroll: ScrollView

    private var currentScene: SceneGraph? = null
    private var currentVm: VM? = null

    /** null = new/unsaved project (has never been given a name). */
    private var currentProjectName: String? = null

    private val projectManager by lazy { ProjectManager(this) }
    private val hostManager by lazy { AndroidHostManager(this) }

    /** Stable id for this app's single overlay slot; reused across run/stop
     *  so re-running replaces the same overlay instead of stacking new ones. */
    private val overlayId = "main"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)

        owsEditor = findViewById(R.id.owsEditor)
        owdEditor = findViewById(R.id.owdEditor)
        previewView = findViewById(R.id.previewView)
        errorsView = findViewById(R.id.errorsView)
        statusBar = findViewById(R.id.statusBar)
        owsScroll = findViewById(R.id.owsScroll)
        owdScroll = findViewById(R.id.owdScroll)
        errorsScroll = findViewById(R.id.errorsScroll)

        val tabLayout = findViewById<TabLayout>(R.id.tabLayout)
        tabLayout.addTab(tabLayout.newTab().setText(R.string.tab_ows))
        tabLayout.addTab(tabLayout.newTab().setText(R.string.tab_owd))
        tabLayout.addTab(tabLayout.newTab().setText(R.string.tab_preview))
        tabLayout.addTab(tabLayout.newTab().setText(R.string.tab_errors))

        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                showTab(tab?.position ?: 0)
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })

        findViewById<MaterialButton>(R.id.btnCompile).setOnClickListener { compile() }
        findViewById<MaterialButton>(R.id.btnRun).setOnClickListener { runOverlay() }
        findViewById<MaterialButton>(R.id.btnStop).setOnClickListener { stopOverlay() }

        toolbar.inflateMenu(R.menu.main_menu)
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_sample -> { loadSample(); true }
                R.id.action_api_sample -> { loadSample("api_demo"); true }
                R.id.action_open -> { showOpenDialog(); true }
                R.id.action_save -> { saveProject(); true }
                R.id.action_save_as -> { showSaveAsDialog(); true }
                R.id.action_rename -> { showRenameDialog(); true }
                R.id.action_delete -> { confirmDelete(); true }
                R.id.action_new -> { newProject(); true }
                R.id.action_render_auto -> {
                    RenderBackend.mode = RenderBackend.Mode.AUTO
                    statusBar.text = "Renderer: " + RenderBackend.label(this)
                    true
                }
                R.id.action_render_gles -> {
                    RenderBackend.mode = RenderBackend.Mode.OPENGL_ES
                    statusBar.text = "Renderer: OpenGL ES 2.0"
                    true
                }
                R.id.action_render_vulkan -> {
                    RenderBackend.mode = RenderBackend.Mode.VULKAN
                    statusBar.text = "Renderer: " + RenderBackend.label(this)
                    true
                }
                R.id.action_render_canvas -> {
                    RenderBackend.mode = RenderBackend.Mode.CANVAS
                    statusBar.text = "Renderer: Canvas 2D"
                    true
                }
                else -> false
            }
        }

        // syntax highlighting (lightweight)
        SyntaxHighlighter.attach(owsEditor)
        SyntaxHighlighter.attach(owdEditor)

        loadSample()
        showTab(0)
    }

    private fun showTab(index: Int) {
        owsScroll.visibility = if (index == 0) android.view.View.VISIBLE else android.view.View.GONE
        owdScroll.visibility = if (index == 1) android.view.View.VISIBLE else android.view.View.GONE
        previewView.visibility = if (index == 2) android.view.View.VISIBLE else android.view.View.GONE
        errorsScroll.visibility = if (index == 3) android.view.View.VISIBLE else android.view.View.GONE
    }

    // ---- compile / run ----

    private fun compile() {
        val ows = owsEditor.text.toString()
        val owd = owdEditor.text.toString()
        statusBar.text = "Compiling…"

        val result = Pipeline.compile(ows, owd.ifBlank { null })
        currentScene = result.scene
        val unit = result.unit
        val scene = result.scene
        currentVm = if (unit != null && scene != null) VM(unit, scene) else null

        if (result.errors.isEmpty()) {
            errorsView.text = "No errors"
            statusBar.text = titleWithProject("Compile OK – ${result.unit?.events?.size ?: 0} event(s)")
            previewView.setScene(result.scene)
            Toast.makeText(this, "Compile successful", Toast.LENGTH_SHORT).show()
        } else {
            // Each error now gets its source line + a caret under the column,
            // not just "line:col: message" — that bare form is what made the
            // errors tab feel like it was "just showing 17:10" with no
            // explanation, even though a message was always there after the
            // colon. Which source (.ows vs .owd) an error belongs to isn't
            // tracked yet, so we show the .ows line for now — see the
            // deliverable notes on this limitation.
            val sb = StringBuilder()
            result.errors.forEach { err ->
                sb.appendLine(err.toString())
                val snippet = SourceMap.snippet(ows, err.location.line, err.location.column)
                if (snippet.isNotBlank()) sb.appendLine(snippet)
                sb.appendLine()
            }
            errorsView.text = sb.toString().trimEnd()
            statusBar.text = titleWithProject("${result.errors.size} error(s)")
            previewView.setScene(result.scene) // still show partial scene if any
        }
    }

    private fun runOverlay() {
        if (currentScene == null) {
            compile()
            if (currentScene == null) {
                Toast.makeText(this, "Fix errors first", Toast.LENGTH_SHORT).show()
                return
            }
        }
        if (!hostManager.supports(Capability.OVERLAY)) {
            // Honest refusal rather than a silent no-op: this build/host
            // genuinely cannot create an overlay (see HostManager kdoc on
            // "do not overengineer" / no fake platform support).
            Toast.makeText(this, "Overlay isn't available on this host.", Toast.LENGTH_LONG).show()
            return
        }
        when (hostManager.getPermissionState(Capability.OVERLAY)) {
            PermissionState.GRANTED -> {
                hostManager.createOverlay(
                    OverlayHandle(overlayId, owsEditor.text.toString(), owdEditor.text.toString())
                )
                statusBar.text = titleWithProject("Overlay running")
                Toast.makeText(this, "Overlay added – drag / tap buttons", Toast.LENGTH_LONG).show()
            }
            PermissionState.REQUIRES_SYSTEM_SETTING -> {
                Toast.makeText(this, R.string.permission_overlay, Toast.LENGTH_LONG).show()
                hostManager.requestPermission(Capability.OVERLAY)
            }
            else -> {
                Toast.makeText(this, "Overlay permission unavailable.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun stopOverlay() {
        hostManager.destroyAllOverlays()
        statusBar.text = titleWithProject("Overlay stopped")
        Toast.makeText(this, "Overlay stopped", Toast.LENGTH_SHORT).show()
    }

    // ---- samples ----

    private fun loadSample(name: String = "counter") {
        val sample = projectManager.loadSample(name)
        owsEditor.setText(sample.first)
        owdEditor.setText(sample.second)
        currentProjectName = null
        statusBar.text = "Sample loaded: $name (unsaved)"
    }

    // ---- project file management ----

    private fun newProject() {
        promptForName(
            title = "New project",
            initial = "",
            confirmLabel = "Create"
        ) { name ->
            if (projectManager.exists(name)) {
                Toast.makeText(this, "A project named \"$name\" already exists.", Toast.LENGTH_SHORT).show()
                return@promptForName
            }
            owsEditor.setText("")
            owdEditor.setText("")
            currentScene = null
            currentVm = null
            errorsView.text = ""
            currentProjectName = name
            projectManager.save(name, "", "")
            statusBar.text = titleWithProject("Created")
        }
    }

    private fun saveProject() {
        val name = currentProjectName
        if (name == null) {
            // No project name yet — same situation as a plain-text editor
            // hitting Ctrl+S on an untitled buffer: fall through to Save As
            // instead of silently saving into an anonymous slot.
            showSaveAsDialog()
            return
        }
        projectManager.save(name, owsEditor.text.toString(), owdEditor.text.toString())
        Toast.makeText(this, "Saved \"$name\"", Toast.LENGTH_SHORT).show()
        statusBar.text = titleWithProject("Saved")
    }

    private fun showSaveAsDialog() {
        promptForName(
            title = "Save as",
            initial = currentProjectName ?: "",
            confirmLabel = "Save"
        ) { name ->
            if (projectManager.exists(name) && name != currentProjectName) {
                confirm("\"$name\" already exists. Overwrite it?") {
                    projectManager.save(name, owsEditor.text.toString(), owdEditor.text.toString())
                    currentProjectName = name
                    statusBar.text = titleWithProject("Saved")
                }
            } else {
                projectManager.save(name, owsEditor.text.toString(), owdEditor.text.toString())
                currentProjectName = name
                statusBar.text = titleWithProject("Saved")
            }
        }
    }

    private fun showOpenDialog() {
        val projects = projectManager.listProjects()
        if (projects.isEmpty()) {
            Toast.makeText(this, R.string.no_projects_saved, Toast.LENGTH_LONG).show()
            return
        }
        val fmt = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())
        val labels = projects.map { "${it.name}  ·  ${fmt.format(Date(it.lastModified))}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Open project")
            .setItems(labels) { _, which ->
                val name = projects[which].name
                val loaded = projectManager.load(name)
                if (loaded != null) {
                    owsEditor.setText(loaded.first)
                    owdEditor.setText(loaded.second)
                    currentProjectName = name
                    currentScene = null
                    currentVm = null
                    errorsView.text = ""
                    statusBar.text = titleWithProject("Opened")
                } else {
                    Toast.makeText(this, "Couldn't open \"$name\".", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showRenameDialog() {
        val oldName = currentProjectName
        if (oldName == null) {
            Toast.makeText(this, "Save this project first, then rename it.", Toast.LENGTH_SHORT).show()
            return
        }
        promptForName(title = "Rename project", initial = oldName, confirmLabel = "Rename") { newName ->
            if (newName == oldName) return@promptForName
            if (projectManager.exists(newName)) {
                Toast.makeText(this, "A project named \"$newName\" already exists.", Toast.LENGTH_SHORT).show()
                return@promptForName
            }
            if (projectManager.rename(oldName, newName)) {
                currentProjectName = newName
                statusBar.text = titleWithProject("Renamed")
            } else {
                Toast.makeText(this, "Couldn't rename project.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun confirmDelete() {
        val name = currentProjectName
        if (name == null) {
            Toast.makeText(this, "Nothing saved to delete yet.", Toast.LENGTH_SHORT).show()
            return
        }
        confirm("Delete project \"$name\"? This can't be undone.") {
            if (projectManager.delete(name)) {
                currentProjectName = null
                owsEditor.setText("")
                owdEditor.setText("")
                errorsView.text = ""
                statusBar.text = "Deleted \"$name\""
            }
        }
    }

    // ---- small dialog helpers ----

    private fun titleWithProject(status: String): String {
        val name = currentProjectName ?: "untitled"
        return "$status — $name"
    }

    private fun promptForName(title: String, initial: String, confirmLabel: String, onConfirm: (String) -> Unit) {
        val input = EditText(this).apply {
            setText(initial)
            setSelection(text.length)
            hint = "Project name"
        }
        val padding = (16 * resources.displayMetrics.density).toInt()
        val container = FrameLayout(this).apply {
            setPadding(padding, padding / 2, padding, 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(container)
            .setPositiveButton(confirmLabel) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isBlank()) {
                    Toast.makeText(this, "Enter a name.", Toast.LENGTH_SHORT).show()
                } else {
                    onConfirm(name)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirm(message: String, onConfirm: () -> Unit) {
        AlertDialog.Builder(this)
            .setMessage(message)
            .setPositiveButton("OK") { _, _ -> onConfirm() }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
