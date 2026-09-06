package com.owsowd.ide.render

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import com.owsowd.core.render.SceneRenderer
import com.owsowd.core.scene.NodeType
import com.owsowd.core.scene.SceneGraph
import com.owsowd.core.scene.SceneNode

/**
 * Software / Canvas 2D renderer (always available).
 */
class CanvasRenderer : SceneRenderer {
    override val backend = SceneRenderer.Backend.CANVAS

    private var canvas: Canvas? = null
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = 0x55FFFFFF
    }

    fun begin(c: Canvas) {
        canvas = c
    }

    fun end() {
        canvas = null
    }

    override fun resize(width: Int, height: Int) {}

    override fun draw(scene: SceneGraph) {
        scene.root?.let { drawNode(it) }
    }

    override fun drawNode(node: SceneNode) {
        val c = canvas ?: return
        if (!node.visible) return
        val l = node.absoluteX()
        val t = node.absoluteY()
        val r = l + node.width
        val b = t + node.height
        val rect = RectF(l, t, r, b)

        when (node.type) {
            NodeType.WIDGET, NodeType.RECT, NodeType.BUTTON -> {
                fill.color = node.background.argb
                val rad = node.radius
                if (rad > 0) c.drawRoundRect(rect, rad, rad, fill)
                else c.drawRect(rect, fill)
                if (node.type == NodeType.BUTTON) {
                    c.drawRoundRect(rect, rad.coerceAtLeast(8f), rad.coerceAtLeast(8f), stroke)
                }
            }
            NodeType.CIRCLE -> {
                fill.color = node.background.argb
                c.drawCircle(
                    l + node.width / 2, t + node.height / 2,
                    minOf(node.width, node.height) / 2, fill
                )
            }
            NodeType.TEXT, NodeType.IMAGE -> {
                if (node.background.argb != 0) {
                    fill.color = node.background.argb
                    c.drawRoundRect(rect, node.radius, node.radius, fill)
                }
            }
        }
        if (node.text.isNotEmpty()) {
            text.textSize = node.textSize
            text.color = node.textColor.argb
            c.drawText(node.text, l + 8f, t + node.textSize + 4f, text)
        }
        node.children.forEach { drawNode(it) }
    }

    override fun release() {
        canvas = null
    }
}
