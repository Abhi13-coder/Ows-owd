package com.owsowd.ide.preview

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.widget.FrameLayout
import com.owsowd.core.render.CanvasRenderer
import com.owsowd.core.scene.SceneGraph
import com.owsowd.ide.render.GlSceneView
import com.owsowd.ide.render.RenderBackend

/**
 * Live preview: prefers OpenGL ES / Vulkan (via [GlSceneView]), falls back to Canvas.
 */
class PreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : FrameLayout(context, attrs, defStyle) {

    private var scene: SceneGraph? = null
    private var glView: GlSceneView? = null
    private val canvasRenderer = CanvasRenderer()
    private var useGl = true

    init {
        setWillNotDraw(false)
        try {
            if (RenderBackend.mode != RenderBackend.Mode.CANVAS) {
                val gv = GlSceneView(context)
                glView = gv
                addView(gv, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
                useGl = true
            } else {
                useGl = false
            }
        } catch (e: Exception) {
            useGl = false
            glView = null
        }
    }

    fun setScene(s: SceneGraph?) {
        scene = s
        if (useGl) glView?.setScene(s)
        else invalidate()
    }

    fun backendName(): String =
        if (useGl) glView?.backendLabel() ?: "OpenGL ES"
        else "Canvas 2D"

    override fun dispatchDraw(canvas: Canvas) {
        if (!useGl) {
            canvas.drawColor(0xFF111111.toInt())
            canvasRenderer.begin(canvas)
            scene?.let { canvasRenderer.draw(it) }
            canvasRenderer.end()
        }
        super.dispatchDraw(canvas)
    }
}
