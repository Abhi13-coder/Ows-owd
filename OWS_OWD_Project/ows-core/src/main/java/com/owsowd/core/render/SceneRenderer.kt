package com.owsowd.core.render

import com.owsowd.core.scene.SceneGraph
import com.owsowd.core.scene.SceneNode

/**
 * Backend-agnostic scene renderer.
 * Implementations: Canvas, OpenGL ES, Vulkan (with GLES fallback).
 */
interface SceneRenderer {
    enum class Backend { CANVAS, OPENGL_ES, VULKAN }

    val backend: Backend

    /** Called when surface size changes (pixels). */
    fun resize(width: Int, height: Int)

    /** Draw the full scene graph. */
    fun draw(scene: SceneGraph)

    /** Draw a single node tree (root). */
    fun drawNode(node: SceneNode)

    /** Release GPU resources. */
    fun release()

    companion object {
        fun preferredName(backend: Backend): String = when (backend) {
            Backend.CANVAS -> "Canvas 2D"
            Backend.OPENGL_ES -> "OpenGL ES 2.0"
            Backend.VULKAN -> "Vulkan"
        }
    }
}

/**
 * Host supplies a surface for GL/Vulkan backends.
 * Canvas backends ignore this.
 */
interface RenderSurface {
    fun width(): Int
    fun height(): Int
}
