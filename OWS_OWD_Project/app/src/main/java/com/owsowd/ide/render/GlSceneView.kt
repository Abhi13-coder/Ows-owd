package com.owsowd.ide.render

import android.content.Context
import android.opengl.GLSurfaceView
import android.util.AttributeSet
import android.view.MotionEvent
import com.owsowd.core.render.SceneRenderer
import com.owsowd.core.scene.NodeType
import com.owsowd.core.scene.SceneGraph
import com.owsowd.core.scene.SceneNode
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * GLSurfaceView that draws an OWS/OWD [SceneGraph] via OpenGL ES or Vulkan wrapper.
 * Used by live preview and can be embedded in overlays.
 */
class GlSceneView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : GLSurfaceView(context, attrs) {

    private var scene: SceneGraph? = null
    private var rendererBackend: SceneRenderer? = null
    private var dragNode: SceneNode? = null
    private var lastX = 0f
    private var lastY = 0f
    var onButtonClick: ((String) -> Unit)? = null

    private val glRenderer = object : Renderer {
        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            val backend = RenderBackend.create(context.applicationContext)
            rendererBackend = backend
            when (backend) {
                is GlesRenderer -> backend.onSurfaceCreated()
                is VulkanRenderer -> backend.onSurfaceCreated()
            }
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            rendererBackend?.resize(width, height)
        }

        override fun onDrawFrame(gl: GL10?) {
            val s = scene
            val r = rendererBackend
            if (s != null && r != null) r.draw(s)
        }
    }

    init {
        setEGLContextClientVersion(2)
        setRenderer(glRenderer)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    fun setScene(s: SceneGraph?) {
        scene = s
        requestRender()
    }

    fun backendLabel(): String = RenderBackend.label(context)

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val s = scene ?: return false
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                dragNode = s.root?.hitTest(event.x, event.y)
                lastX = event.x
                lastY = event.y
                return dragNode != null
            }
            MotionEvent.ACTION_MOVE -> {
                val n = dragNode ?: return false
                n.x += event.x - lastX
                n.y += event.y - lastY
                lastX = event.x
                lastY = event.y
                requestRender()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val n = dragNode
                if (n != null && n.type == NodeType.BUTTON && n.id != null) {
                    onButtonClick?.invoke(n.id!!)
                }
                dragNode = null
                requestRender()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onDetachedFromWindow() {
        queueEvent {
            rendererBackend?.release()
            rendererBackend = null
        }
        super.onDetachedFromWindow()
    }
}
