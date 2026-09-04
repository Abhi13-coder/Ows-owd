package com.owsowd.ide.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.tabs.TabLayout
import com.owsowd.core.compiler.Pipeline
import com.owsowd.core.runtime.VM
import com.owsowd.core.scene.SceneGraph
import com.owsowd.ide.R
import com.owsowd.ide.editor.SyntaxHighlighter
import com.owsowd.ide.overlay.OverlayService
import com.owsowd.ide.render.RenderBackend
import com.owsowd.ide.preview.PreviewView
import com.owsowd.ide.project.ProjectManager

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
    private val projectManager by lazy { ProjectManager(this) }

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
                R.id.action_sample -> {
                    loadSample()
                    true
                }
                R.id.action_api_sample -> {
                    loadSample("api_demo")
                    true
                }
                R.id.action_save -> {
                    saveProject()
                    true
                }
                R.id.action_new -> {
                    owsEditor.setText("")
                    owdEditor.setText("")
                    true
                }
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

    private fun compile() {
        val ows = owsEditor.text.toString()
        val owd = owdEditor.text.toString()
        statusBar.text = "Compiling…"

        val result = Pipeline.compile(ows, owd.ifBlank { null })
        currentScene = result.scene
        currentVm = if (result.unit != null && result.scene != null) {
            VM(result.unit, result.scene)
        } else null

        if (result.errors.isEmpty()) {
            errorsView.text = "No errors"
            statusBar.text = "Compile OK – ${result.unit?.events?.size ?: 0} event(s)"
            previewView.setScene(result.scene)
            Toast.makeText(this, "Compile successful", Toast.LENGTH_SHORT).show()
        } else {
            val sb = StringBuilder()
            result.errors.forEach { sb.appendLine(it.toString()) }
            errorsView.text = sb.toString()
            statusBar.text = "${result.errors.size} error(s)"
            // still show scene if partial
            previewView.setScene(result.scene)
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
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, R.string.permission_overlay, Toast.LENGTH_LONG).show()
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
            return
        }
        val intent = Intent(this, OverlayService::class.java).apply {
            action = OverlayService.ACTION_START
            // pass sources so service can recompile if needed
            putExtra(OverlayService.EXTRA_OWS, owsEditor.text.toString())
            putExtra(OverlayService.EXTRA_OWD, owdEditor.text.toString())
        }
        ContextCompat.startForegroundService(this, intent)
        statusBar.text = "Overlay added (multi OK)"
        Toast.makeText(this, "Overlay added (multiple allowed) – drag / tap buttons", Toast.LENGTH_LONG).show()
    }

    private fun stopOverlay() {
        val intent = Intent(this, OverlayService::class.java).apply {
            action = OverlayService.ACTION_STOP_ALL
        }
        startService(intent)
        statusBar.text = "All overlays stopped"
        Toast.makeText(this, "All overlays stopped", Toast.LENGTH_SHORT).show()
    }

    private fun loadSample(name: String = "counter") {
        val sample = projectManager.loadSample(name)
        owsEditor.setText(sample.first)
        owdEditor.setText(sample.second)
        statusBar.text = "Sample loaded: $name"
    }

    private fun saveProject() {
        projectManager.save("default", owsEditor.text.toString(), owdEditor.text.toString())
        Toast.makeText(this, "Project saved", Toast.LENGTH_SHORT).show()
        statusBar.text = "Saved"
    }
}
