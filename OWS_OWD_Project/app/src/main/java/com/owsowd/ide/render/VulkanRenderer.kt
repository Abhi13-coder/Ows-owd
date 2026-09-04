package com.owsowd.ide.render

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.owsowd.core.render.SceneRenderer
import com.owsowd.core.scene.SceneGraph
import com.owsowd.core.scene.SceneNode

/**
 * Vulkan-capable renderer entry point.
 *
 * Full Vulkan swapchain + command buffers require NDK (libvulkan).
 * This class:
 *  1. Detects Vulkan hardware (API 29+ / FEATURE_VULKAN_*)
 *  2. Uses a **Vulkan-preferred path flag** for hosts
 *  3. Draws via **OpenGL ES 2.0** as the production backend until a native
 *     Vulkan module is linked (same SceneGraph API — drop-in later)
 *
 * When you add `libows_vulkan.so` (NDK), wire [nativeDraw] here; the IDE and
 * overlays keep calling the same [SceneRenderer] interface.
 */
class VulkanRenderer(
    private val context: Context? = null
) : SceneRenderer {

    override val backend = SceneRenderer.Backend.VULKAN

    private val gles = GlesRenderer()
    @Volatile private var useNativeVulkan = false

    val vulkanHardwareAvailable: Boolean
        get() = isVulkanAvailable(context)

    init {
        // Native Vulkan optional — probe without crashing
        useNativeVulkan = tryLoadNativeVulkan()
    }

    fun onSurfaceCreated() {
        if (useNativeVulkan) {
            try {
                nativeInit()
            } catch (t: Throwable) {
                useNativeVulkan = false
                gles.onSurfaceCreated()
            }
        } else {
            gles.onSurfaceCreated()
        }
    }

    override fun resize(width: Int, height: Int) {
        if (useNativeVulkan) {
            try { nativeResize(width, height) } catch (_: Throwable) {
                useNativeVulkan = false
                gles.resize(width, height)
            }
        } else gles.resize(width, height)
    }

    override fun draw(scene: SceneGraph) {
        // Scene draw uses GLES until native Vulkan scene encoder is shipped
        gles.draw(scene)
    }

    override fun drawNode(node: SceneNode) {
        gles.drawNode(node)
    }

    override fun release() {
        if (useNativeVulkan) {
            try { nativeRelease() } catch (_: Throwable) {}
        }
        gles.release()
    }

    private fun tryLoadNativeVulkan(): Boolean {
        return try {
            System.loadLibrary("ows_vulkan")
            true
        } catch (_: UnsatisfiedLinkError) {
            false
        } catch (_: Exception) {
            false
        }
    }

    // JNI stubs — implemented in optional NDK module libows_vulkan
    private external fun nativeInit()
    private external fun nativeResize(w: Int, h: Int)
    private external fun nativeRelease()

    companion object {
        /**
         * True if the device advertises Vulkan hardware level / version.
         */
        fun isVulkanAvailable(context: Context?): Boolean {
            if (context == null) return Build.VERSION.SDK_INT >= 29
            val pm = context.packageManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                if (pm.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_LEVEL)) return true
                if (pm.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_VERSION)) return true
            }
            return false
        }

        fun describe(context: Context?): String {
            val hw = isVulkanAvailable(context)
            val native = try {
                System.loadLibrary("ows_vulkan")
                true
            } catch (_: Throwable) {
                false
            }
            return buildString {
                append("Vulkan hardware: ").append(if (hw) "yes" else "no")
                append(" | native libows_vulkan: ").append(if (native) "loaded" else "not present (GLES fallback)")
            }
        }
    }
}
