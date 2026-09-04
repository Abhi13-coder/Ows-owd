package com.owsowd.ide.overlay

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.*
import android.widget.FrameLayout
import androidx.core.app.NotificationCompat
import com.owsowd.core.compiler.Pipeline
import com.owsowd.core.runtime.VM
import com.owsowd.core.scene.NodeType
import com.owsowd.core.scene.SceneGraph
import com.owsowd.core.scene.SceneNode
import com.owsowd.ide.R
import com.owsowd.ide.render.GlSceneView
import com.owsowd.ide.render.RenderBackend
import com.owsowd.ide.ui.MainActivity
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Floating overlays via WindowManager.
 * Supports **multiple overlays at once** — each Run adds a new instance;
 * Stop can remove one id or all.
 */
class OverlayService : Service() {

    private var windowManager: WindowManager? = null
    private val overlays = ConcurrentHashMap<String, OverlayInstance>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var nextSlot = 0

    data class OverlayInstance(
        val id: String,
        val view: android.view.View,
        val params: WindowManager.LayoutParams,
        val scene: SceneGraph,
        val vm: VM
    )

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val ows = intent.getStringExtra(EXTRA_OWS) ?: return START_NOT_STICKY
                val owd = intent.getStringExtra(EXTRA_OWD)
                val preferredId = intent.getStringExtra(EXTRA_OVERLAY_ID)
                addOverlay(ows, owd, preferredId)
            }
            ACTION_STOP -> {
                val id = intent.getStringExtra(EXTRA_OVERLAY_ID)
                if (id.isNullOrBlank()) removeAllOverlays()
                else removeOverlay(id)
                if (overlays.isEmpty()) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                } else {
                    updateNotification()
                }
            }
            ACTION_STOP_ALL -> {
                removeAllOverlays()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun addOverlay(ows: String, owd: String?, preferredId: String?) {
        val (vmInstance, result) = Pipeline.compileAndCreateVM(ows, owd)
        val scene = result.scene ?: return
        if (vmInstance == null) return

        val id = preferredId?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString().take(8)
        // replace same id if re-run with explicit id
        if (overlays.containsKey(id)) removeOverlay(id)

        // wire async HTTP completion back onto this VM on main thread
        vmInstance.onHttpComplete = { eventName, _ ->
            mainHandler.post {
                try {
                    vmInstance.runEvent(eventName)
                    overlays[id]?.view?.invalidate()
                } catch (_: Exception) {}
            }
        }

        val host: android.view.View = try {
            // GPU path: OpenGL ES / Vulkan wrapper
            val gl = GlSceneView(this)
            gl.setScene(scene)
            gl.onButtonClick = { btnId ->
                vmInstance.runEvent("$btnId.clicked")
                gl.requestRender()
            }
            // drag whole window: wrap in OverlayDragFrame
            OverlayDragFrame(this, gl, id)
        } catch (e: Exception) {
            // Canvas fallback
            OverlayHostView(this, id, scene, vmInstance) { overlayId ->
                removeOverlay(overlayId)
                if (overlays.isEmpty()) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                } else updateNotification()
            }
        }

        val slot = nextSlot++
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        // stagger so multiple overlays are visible
        params.x = 48 + (slot % 4) * 36
        params.y = 160 + (slot % 6) * 48

        scene.root?.let {
            params.width = it.width.toInt().coerceAtLeast(100)
            params.height = it.height.toInt().coerceAtLeast(80)
        }

        try {
            windowManager?.addView(host, params)
            overlays[id] = OverlayInstance(id, host, params, scene, vmInstance)
            updateNotification()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun removeOverlay(id: String) {
        val inst = overlays.remove(id) ?: return
        try {
            windowManager?.removeView(inst.view)
        } catch (_: Exception) {}
        inst.vm.onHttpComplete = null
    }

    private fun removeAllOverlays() {
        val ids = overlays.keys.toList()
        ids.forEach { removeOverlay(it) }
        overlays.clear()
    }

    override fun onDestroy() {
        removeAllOverlays()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.channel_overlay),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.channel_overlay_desc)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val pending = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val count = overlays.size
        val backend = try { RenderBackend.label(this) } catch (_: Exception) { "GPU" }
        val text = if (count <= 0) "Floating widgets ready ($backend)"
        else "$count overlay(s) · $backend"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("OWS Overlay")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pending)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification())
    }

    /**
     * Wraps a child (e.g. GlSceneView) and moves the WindowManager layout on drag.
     * Button taps are handled by the child.
     */
    class OverlayDragFrame(
        context: Context,
        child: View,
        private val overlayId: String
    ) : FrameLayout(context) {
        private var dragOffsetX = 0f
        private var dragOffsetY = 0f
        private var dragging = false
        private var moved = false

        init {
            addView(child, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        }

        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    dragging = false
                    moved = false
                    dragOffsetX = ev.rawX
                    dragOffsetY = ev.rawY
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - dragOffsetX
                    val dy = ev.rawY - dragOffsetY
                    if (!moved && dx * dx + dy * dy > 100) {
                        moved = true
                        dragging = true
                        return true
                    }
                }
            }
            return false
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_MOVE -> {
                    if (dragging) {
                        val dx = event.rawX - dragOffsetX
                        val dy = event.rawY - dragOffsetY
                        val wm = context.getSystemService(WINDOW_SERVICE) as WindowManager
                        val params = layoutParams as WindowManager.LayoutParams
                        params.x += dx.toInt()
                        params.y += dy.toInt()
                        try { wm.updateViewLayout(this, params) } catch (_: Exception) {}
                        dragOffsetX = event.rawX
                        dragOffsetY = event.rawY
                    }
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    dragging = false
                    moved = false
                    return true
                }
            }
            return super.onTouchEvent(event)
        }
    }

    /**
     * Canvas host view: draws scene, drag whole window, tap buttons → VM events.
     */
    class OverlayHostView(
        context: Context,
        private val overlayId: String,
        private val scene: SceneGraph,
        private val vm: VM?,
        private val onClose: ((String) -> Unit)? = null
    ) : View(context) {

        private var dragOffsetX = 0f
        private var dragOffsetY = 0f
        private var dragging = false
        private var downNode: SceneNode? = null
        private val renderer = OverlayRenderer()

        init {
            scene.root?.let { r ->
                layoutParams = FrameLayout.LayoutParams(r.width.toInt(), r.height.toInt())
            }
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val w = scene.root?.width?.toInt() ?: 240
            val h = scene.root?.height?.toInt() ?: 140
            setMeasuredDimension(w, h)
        }

        override fun onDraw(canvas: android.graphics.Canvas) {
            scene.root?.let { renderer.draw(canvas, it) }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downNode = scene.root?.hitTest(event.x, event.y)
                    dragging = false
                    dragOffsetX = event.rawX
                    dragOffsetY = event.rawY
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - dragOffsetX
                    val dy = event.rawY - dragOffsetY
                    if (!dragging && (dx * dx + dy * dy > 64)) dragging = true
                    if (dragging) {
                        val wm = context.getSystemService(WINDOW_SERVICE) as WindowManager
                        val params = layoutParams as WindowManager.LayoutParams
                        params.x += dx.toInt()
                        params.y += dy.toInt()
                        try {
                            wm.updateViewLayout(this, params)
                        } catch (_: Exception) {}
                        dragOffsetX = event.rawX
                        dragOffsetY = event.rawY
                    }
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragging) {
                        val n = downNode
                        if (n != null && n.type == NodeType.BUTTON && n.id != null) {
                            val eventName = "${n.id}.clicked"
                            vm?.runEvent(eventName)
                            invalidate()
                        }
                    }
                    downNode = null
                    dragging = false
                    return true
                }
            }
            return super.onTouchEvent(event)
        }
    }

    class OverlayRenderer {
        private val fill = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        private val text = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
        }
        private val stroke = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = 2f
            color = 0x55FFFFFF
        }

        fun draw(canvas: android.graphics.Canvas, node: SceneNode) {
            if (!node.visible) return
            val l = node.absoluteX()
            val t = node.absoluteY()
            val r = l + node.width
            val b = t + node.height
            val rect = android.graphics.RectF(l, t, r, b)

            when (node.type) {
                NodeType.WIDGET, NodeType.RECT, NodeType.BUTTON -> {
                    fill.color = node.background.argb
                    val rad = node.radius
                    if (rad > 0) canvas.drawRoundRect(rect, rad, rad, fill)
                    else canvas.drawRect(rect, fill)
                    if (node.type == NodeType.BUTTON) {
                        canvas.drawRoundRect(rect, rad.coerceAtLeast(8f), rad.coerceAtLeast(8f), stroke)
                    }
                }
                NodeType.CIRCLE -> {
                    fill.color = node.background.argb
                    canvas.drawCircle(
                        l + node.width / 2, t + node.height / 2,
                        minOf(node.width, node.height) / 2, fill
                    )
                }
                else -> {}
            }
            if (node.text.isNotEmpty()) {
                text.textSize = node.textSize
                text.color = node.textColor.argb
                canvas.drawText(node.text, l + 8f, t + node.textSize + 4f, text)
            }
            node.children.forEach { draw(canvas, it) }
        }
    }

    companion object {
        const val ACTION_START = "com.owsowd.ide.START_OVERLAY"
        const val ACTION_STOP = "com.owsowd.ide.STOP_OVERLAY"
        const val ACTION_STOP_ALL = "com.owsowd.ide.STOP_ALL_OVERLAYS"
        const val EXTRA_OWS = "ows"
        const val EXTRA_OWD = "owd"
        const val EXTRA_OVERLAY_ID = "overlay_id"
        private const val CHANNEL_ID = "ows_overlay"
        private const val NOTIFICATION_ID = 42
    }
}
