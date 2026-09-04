package com.owsowd.core.scene

/**
 * Runtime scene graph produced from OWD.
 * Rendered by the Android overlay / OpenGL abstraction.
 */
enum class NodeType {
    WIDGET, TEXT, BUTTON, IMAGE, RECT, CIRCLE
}

data class Color(val argb: Int) {
    companion object {
        fun parse(hex: String): Color {
            val h = hex.removePrefix("#")
            val value = when (h.length) {
                6 -> (0xFF shl 24) or h.toInt(16)
                8 -> h.toLong(16).toInt()
                else -> 0xFF000000.toInt()
            }
            return Color(value)
        }
        val TRANSPARENT = Color(0)
        val BLACK = Color(0xFF000000.toInt())
        val WHITE = Color(0xFFFFFFFF.toInt())
    }
}

open class SceneNode(
    val type: NodeType,
    val id: String?,
    var x: Float = 0f,
    var y: Float = 0f,
    var width: Float = 100f,
    var height: Float = 100f,
    var radius: Float = 0f,
    var background: Color = Color.TRANSPARENT,
    var text: String = "",
    var textSize: Float = 16f,
    var textColor: Color = Color.WHITE,
    var src: String? = null,
    val children: MutableList<SceneNode> = mutableListOf()
) {
    var parent: SceneNode? = null
    var visible: Boolean = true
    var enabled: Boolean = true

    /** Absolute position helpers */
    fun absoluteX(): Float = (parent?.absoluteX() ?: 0f) + x
    fun absoluteY(): Float = (parent?.absoluteY() ?: 0f) + y

    fun findById(target: String): SceneNode? {
        if (id == target) return this
        for (c in children) {
            c.findById(target)?.let { return it }
        }
        return null
    }

    fun hitTest(px: Float, py: Float): SceneNode? {
        // reverse order for top-most
        for (i in children.indices.reversed()) {
            children[i].hitTest(px, py)?.let { return it }
        }
        if (!visible || !enabled) return null
        val ax = absoluteX()
        val ay = absoluteY()
        if (px in ax..(ax + width) && py in ay..(ay + height)) return this
        return null
    }
}

class SceneGraph {
    var root: SceneNode? = null
    private val idIndex = mutableMapOf<String, SceneNode>()

    fun rebuildIndex() {
        idIndex.clear()
        fun walk(n: SceneNode) {
            n.id?.let { idIndex[it] = n }
            n.children.forEach { walk(it) }
        }
        root?.let { walk(it) }
    }

    fun find(id: String): SceneNode? = idIndex[id] ?: root?.findById(id)

    fun setProperty(id: String, prop: String, value: Any?) {
        val node = find(id) ?: return
        when (prop) {
            "txt", "text" -> node.text = value?.toString() ?: ""
            "x" -> node.x = (value as? Number)?.toFloat() ?: node.x
            "y" -> node.y = (value as? Number)?.toFloat() ?: node.y
            "width" -> node.width = (value as? Number)?.toFloat() ?: node.width
            "height" -> node.height = (value as? Number)?.toFloat() ?: node.height
            "radius" -> node.radius = (value as? Number)?.toFloat() ?: node.radius
            "size", "textSize" -> node.textSize = (value as? Number)?.toFloat() ?: node.textSize
            "background" -> {
                when (value) {
                    is String -> node.background = Color.parse(value)
                    is Color -> node.background = value
                    is Number -> node.background = Color(value.toInt())
                }
            }
            "src" -> node.src = value?.toString()
            "visible" -> node.visible = value as? Boolean ?: true
        }
    }

    fun getProperty(id: String, prop: String): Any? {
        val node = find(id) ?: return null
        return when (prop) {
            "txt", "text" -> node.text
            "x" -> node.x
            "y" -> node.y
            "width" -> node.width
            "height" -> node.height
            "radius" -> node.radius
            "size", "textSize" -> node.textSize
            "background" -> node.background
            "src" -> node.src
            "visible" -> node.visible
            else -> null
        }
    }
}
