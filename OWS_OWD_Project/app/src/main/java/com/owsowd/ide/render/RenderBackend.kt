package com.owsowd.ide.render

import android.content.Context
import com.owsowd.core.render.CanvasRenderer
import com.owsowd.core.render.SceneRenderer

/**
 * Selects Canvas / OpenGL ES / Vulkan (Vulkan→GLES until NDK lib is present).
 */
object RenderBackend {
    enum class Mode { AUTO, CANVAS, OPENGL_ES, VULKAN }

    @Volatile
    var mode: Mode = Mode.AUTO

    fun create(context: Context?): SceneRenderer {
        return when (mode) {
            Mode.CANVAS -> CanvasRenderer()
            Mode.OPENGL_ES -> GlesRenderer()
            Mode.VULKAN -> VulkanRenderer(context)
            Mode.AUTO -> {
                // Prefer GLES for overlays (wide support). Vulkan wrapper if HW present.
                if (context != null && VulkanRenderer.isVulkanAvailable(context)) {
                    VulkanRenderer(context)
                } else {
                    GlesRenderer()
                }
            }
        }
    }

    fun label(context: Context?): String {
        return when (mode) {
            Mode.CANVAS -> "Canvas 2D"
            Mode.OPENGL_ES -> "OpenGL ES 2.0"
            Mode.VULKAN -> VulkanRenderer.describe(context)
            Mode.AUTO -> {
                if (VulkanRenderer.isVulkanAvailable(context))
                    "AUTO → " + VulkanRenderer.describe(context)
                else
                    "AUTO → OpenGL ES 2.0"
            }
        }
    }
}
