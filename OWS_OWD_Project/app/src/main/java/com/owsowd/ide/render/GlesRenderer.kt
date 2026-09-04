package com.owsowd.ide.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.opengl.GLES20
import android.opengl.GLUtils
import android.opengl.Matrix
import com.owsowd.core.render.SceneRenderer
import com.owsowd.core.scene.NodeType
import com.owsowd.core.scene.SceneGraph
import com.owsowd.core.scene.SceneNode
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * OpenGL ES 2.0 scene renderer.
 * Draws colored quads (widgets/buttons/rects/circles-as-quads) and text via
 * Canvas→texture uploads (simple, reliable on all API 29+ devices).
 */
class GlesRenderer : SceneRenderer {
    override val backend = SceneRenderer.Backend.OPENGL_ES

    private var program = 0
    private var positionHandle = 0
    private var colorHandle = 0
    private var mvpHandle = 0
    private var useTextureHandle = 0
    private var texCoordHandle = 0
    private var textureHandle = 0

    private val mvp = FloatArray(16)
    private val scratch = FloatArray(16)
    private var viewW = 1
    private var viewH = 1
    private var ready = false

    // text texture cache: key -> tex id
    private val textCache = HashMap<String, Int>()
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        typeface = Typeface.DEFAULT
        isSubpixelText = true
    }

    private val quadVerts: FloatBuffer = ByteBuffer
        .allocateDirect(4 * 2 * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .put(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f))
        .also { it.position(0) }

    private val quadUV: FloatBuffer = ByteBuffer
        .allocateDirect(4 * 2 * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .put(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f))
        .also { it.position(0) }

    fun onSurfaceCreated() {
        val vs = """
            uniform mat4 uMVP;
            attribute vec2 aPos;
            attribute vec2 aUV;
            varying vec2 vUV;
            void main() {
              vUV = aUV;
              gl_Position = uMVP * vec4(aPos, 0.0, 1.0);
            }
        """.trimIndent()
        val fs = """
            precision mediump float;
            uniform vec4 uColor;
            uniform int uUseTex;
            uniform sampler2D uTex;
            varying vec2 vUV;
            void main() {
              if (uUseTex == 1) {
                vec4 t = texture2D(uTex, vUV);
                gl_FragColor = t * uColor;
              } else {
                gl_FragColor = uColor;
              }
            }
        """.trimIndent()
        program = link(vs, fs)
        positionHandle = GLES20.glGetAttribLocation(program, "aPos")
        texCoordHandle = GLES20.glGetAttribLocation(program, "aUV")
        colorHandle = GLES20.glGetUniformLocation(program, "uColor")
        mvpHandle = GLES20.glGetUniformLocation(program, "uMVP")
        useTextureHandle = GLES20.glGetUniformLocation(program, "uUseTex")
        textureHandle = GLES20.glGetUniformLocation(program, "uTex")
        GLES20.glClearColor(0.07f, 0.07f, 0.07f, 1f)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        ready = true
    }

    override fun resize(width: Int, height: Int) {
        viewW = width.coerceAtLeast(1)
        viewH = height.coerceAtLeast(1)
        GLES20.glViewport(0, 0, viewW, viewH)
        // Ortho: origin top-left, y down (match Canvas / scene coords)
        Matrix.orthoM(mvp, 0, 0f, viewW.toFloat(), viewH.toFloat(), 0f, -1f, 1f)
    }

    override fun draw(scene: SceneGraph) {
        if (!ready) return
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        scene.root?.let { drawNode(it) }
    }

    override fun drawNode(node: SceneNode) {
        if (!ready || !node.visible) return
        val x = node.absoluteX()
        val y = node.absoluteY()
        val w = node.width
        val h = node.height

        when (node.type) {
            NodeType.WIDGET, NodeType.RECT, NodeType.BUTTON, NodeType.IMAGE -> {
                if (node.background.argb != 0 || node.type == NodeType.BUTTON) {
                    drawSolid(x, y, w, h, node.background.argb)
                }
            }
            NodeType.CIRCLE -> {
                // approximate circle as quad with solid color (full GLES circle needs more geometry)
                drawSolid(x, y, w, h, node.background.argb)
            }
            NodeType.TEXT -> {
                if (node.background.argb != 0) drawSolid(x, y, w, h, node.background.argb)
            }
        }
        if (node.text.isNotEmpty()) {
            drawText(node.text, x + 8f, y + 4f, node.textSize, node.textColor.argb)
        }
        node.children.forEach { drawNode(it) }
    }

    private fun drawSolid(x: Float, y: Float, w: Float, h: Float, argb: Int) {
        val a = ((argb ushr 24) and 0xFF) / 255f
        val r = ((argb ushr 16) and 0xFF) / 255f
        val g = ((argb ushr 8) and 0xFF) / 255f
        val b = (argb and 0xFF) / 255f
        Matrix.setIdentityM(scratch, 0)
        Matrix.translateM(scratch, 0, x, y, 0f)
        Matrix.scaleM(scratch, 0, w, h, 1f)
        val out = FloatArray(16)
        Matrix.multiplyMM(out, 0, mvp, 0, scratch, 0)

        GLES20.glUseProgram(program)
        GLES20.glUniformMatrix4fv(mvpHandle, 1, false, out, 0)
        GLES20.glUniform4f(colorHandle, r, g, b, a)
        GLES20.glUniform1i(useTextureHandle, 0)
        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 0, quadVerts)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(positionHandle)
    }

    private fun drawText(str: String, x: Float, y: Float, size: Float, argb: Int) {
        val key = "$str|$size|$argb"
        var tex = textCache[key]
        if (tex == null) {
            tex = createTextTexture(str, size, argb)
            textCache[key] = tex
        }
        textPaint.textSize = size
        val tw = (textPaint.measureText(str) + 4f).coerceAtLeast(4f)
        val th = (size * 1.4f).coerceAtLeast(4f)

        val a = ((argb ushr 24) and 0xFF) / 255f
        Matrix.setIdentityM(scratch, 0)
        Matrix.translateM(scratch, 0, x, y, 0f)
        Matrix.scaleM(scratch, 0, tw, th, 1f)
        val out = FloatArray(16)
        Matrix.multiplyMM(out, 0, mvp, 0, scratch, 0)

        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
        GLES20.glUniform1i(textureHandle, 0)
        GLES20.glUniformMatrix4fv(mvpHandle, 1, false, out, 0)
        GLES20.glUniform4f(colorHandle, 1f, 1f, 1f, a.coerceAtLeast(0.01f))
        GLES20.glUniform1i(useTextureHandle, 1)
        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glEnableVertexAttribArray(texCoordHandle)
        GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 0, quadVerts)
        GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, 0, quadUV)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(texCoordHandle)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
    }

    private fun createTextTexture(str: String, size: Float, argb: Int): Int {
        textPaint.textSize = size
        textPaint.color = argb
        val w = (textPaint.measureText(str) + 4f).toInt().coerceAtLeast(2)
        val h = (size * 1.4f).toInt().coerceAtLeast(2)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawText(str, 2f, size, textPaint)
        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
        bmp.recycle()
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        return tex[0]
    }

    override fun release() {
        textCache.values.forEach { id ->
            val arr = intArrayOf(id)
            GLES20.glDeleteTextures(1, arr, 0)
        }
        textCache.clear()
        if (program != 0) {
            GLES20.glDeleteProgram(program)
            program = 0
        }
        ready = false
    }

    private fun link(vsSrc: String, fsSrc: String): Int {
        val vs = compile(GLES20.GL_VERTEX_SHADER, vsSrc)
        val fs = compile(GLES20.GL_FRAGMENT_SHADER, fsSrc)
        val prog = GLES20.glCreateProgram()
        GLES20.glAttachShader(prog, vs)
        GLES20.glAttachShader(prog, fs)
        GLES20.glLinkProgram(prog)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) {
            val log = GLES20.glGetProgramInfoLog(prog)
            GLES20.glDeleteProgram(prog)
            throw RuntimeException("GL link failed: $log")
        }
        GLES20.glDeleteShader(vs)
        GLES20.glDeleteShader(fs)
        return prog
    }

    private fun compile(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(s)
            GLES20.glDeleteShader(s)
            throw RuntimeException("GL compile failed: $log")
        }
        return s
    }
}
