package com.owsowd.core.host

/**
 * Which OS the runtime is actually executing on.
 *
 * Deliberately NOT inferred from CPU architecture. A Linux userland running
 * inside Termux/PRoot on an Android phone is still, from this runtime's
 * point of view, a LINUX host — it does not get Android's WindowManager,
 * permission model, or lifecycle just because the underlying kernel happens
 * to be an Android kernel. Architecture and host are independent axes; see
 * [Architecture] below.
 */
enum class Host {
    ANDROID,
    LINUX,
    WINDOWS,
    MACOS,
    UNKNOWN
}

/**
 * CPU architecture. Independent of [Host] — an ARM64 Android phone and an
 * ARM64 Linux server are different hosts on the same architecture; an x86_64
 * Windows box and an x86_64 Linux box are different hosts too. Nothing in
 * this runtime should ever branch on architecture to decide platform
 * behavior — only [Host] governs that.
 */
enum class Architecture {
    ARMV7,
    ARM64,
    X86,
    X86_64,
    UNKNOWN
}

/**
 * A capability is something a host may or may not be able to do. The VM and
 * OWD renderer ask "can I do X" instead of "am I on Android" — this is what
 * keeps ows-core free of platform `when(host) { ... }` branches scattered
 * through the language runtime. Add new capabilities here only when an
 * actual host implementation backs them; see the "no fake support" note in
 * [HostManager].
 */
enum class Capability {
    /** Can render a scene into some kind of visible surface at all. */
    GUI,

    /** Can draw a scene as a floating overlay above other apps/windows. */
    OVERLAY,

    /** Can move/resize/close windows it created (including overlays). */
    WINDOW_MANAGEMENT,

    /** Can read/write arbitrary files outside the app sandbox. */
    FILE_SYSTEM,

    /** Can make outbound network requests (http.get/http.post/...). */
    NETWORK,

    /** Can post system notifications. */
    NOTIFICATIONS,

    /** Can query accessibility services / act as one. */
    ACCESSIBILITY
}

/** Result of a permission check or request. Kept as an enum, not a boolean,
 *  because Android in particular has a real third state: "denied, and the
 *  user must be sent to a system settings screen to fix it" (SYSTEM_SETTING),
 *  distinct from a normal in-app prompt (PROMPTABLE) or an outright refusal
 *  the app cannot recover from in-session (DENIED). */
enum class PermissionState {
    GRANTED,
    DENIED,
    NOT_REQUIRED,
    REQUIRES_SYSTEM_SETTING
}

/**
 * Platform-independent contract the OWS/OWD runtime talks to instead of
 * scattering `if (Build.VERSION...)` / `System.getProperty("os.name")`
 * checks through the compiler, VM, or renderer.
 *
 * IMPORTANT — per the project's own "do not overengineer" rule: this
 * interface only describes what a host CAN be asked. It does not promise
 * every host implements every method meaningfully. [AndroidHostManager]
 * (in the :app module) is the only fully functional implementation right
 * now. A desktop implementation only needs to answer detectHost/
 * detectArchitecture/detectCapabilities honestly and report OVERLAY as
 * unsupported — it must NOT pretend to create a real overlay.
 */
interface HostManager {
    fun detectHost(): Host
    fun detectArchitecture(): Architecture
    fun detectCapabilities(): Set<Capability>

    fun supports(capability: Capability): Boolean = capability in detectCapabilities()

    fun getPermissionState(capability: Capability): PermissionState

    /**
     * Ask the host to grant [capability]. This may show a system dialog and
     * return before the user has answered — callers should not assume
     * synchronous completion; poll getPermissionState or use a host-specific
     * callback (Android's overlay permission flow is the concrete example:
     * it hands off to Settings.ACTION_MANAGE_OVERLAY_PERMISSION and the app
     * finds out the result on next resume, not synchronously).
     */
    fun requestPermission(capability: Capability)

    /**
     * Start an overlay for [handle]. Hosts that don't support
     * Capability.OVERLAY must throw UnsupportedOperationException rather
     * than silently no-op — a silent no-op is exactly the "fake support"
     * this interface is trying to avoid.
     */
    fun createOverlay(handle: OverlayHandle)

    fun destroyOverlay(handle: OverlayHandle)
}

/**
 * Everything a host needs to start one overlay instance. Plain data only —
 * no platform types — so this stays definable in ows-core. [id] lets a
 * caller replace/target a specific running overlay (mirrors
 * OverlayService.EXTRA_OVERLAY_ID on the Android host).
 */
data class OverlayHandle(val id: String, val owsSource: String, val owdSource: String?)

/**
 * Reads OS/arch off the running JVM. Correct for any plain JVM host
 * (Linux, Windows, macOS) but NOT for Android — Android's JVM reports
 * "Linux" for os.name too, which is exactly the kind of host/architecture
 * conflation this module exists to avoid. The Android app module supplies
 * its own [HostManager] (AndroidHostManager) that overrides host detection
 * with a real Android check (android.os.Build) instead of this fallback.
 */
open class JvmHostManager : HostManager {
    override fun detectHost(): Host {
        val osName = System.getProperty("os.name")?.lowercase() ?: ""
        return when {
            osName.contains("win") -> Host.WINDOWS
            osName.contains("mac") || osName.contains("darwin") -> Host.MACOS
            osName.contains("nux") || osName.contains("nix") -> Host.LINUX
            else -> Host.UNKNOWN
        }
    }

    override fun detectArchitecture(): Architecture {
        val arch = System.getProperty("os.arch")?.lowercase() ?: ""
        return when {
            arch.contains("aarch64") || arch.contains("arm64") -> Architecture.ARM64
            arch.contains("arm") -> Architecture.ARMV7
            arch.contains("amd64") || arch.contains("x86_64") -> Architecture.X86_64
            arch.contains("x86") || arch.contains("i386") || arch.contains("i686") -> Architecture.X86
            else -> Architecture.UNKNOWN
        }
    }

    /**
     * Deliberately conservative: a plain JVM process has no windowing
     * toolkit on the classpath here (no AWT/Swing dependency in ows-core),
     * so GUI/OVERLAY/WINDOW_MANAGEMENT are NOT claimed even though a desktop
     * JVM *could* support them with more work. FILE_SYSTEM and NETWORK are
     * real and already used by the VM's http.* builtins.
     */
    override fun detectCapabilities(): Set<Capability> =
        setOf(Capability.FILE_SYSTEM, Capability.NETWORK)

    override fun getPermissionState(capability: Capability): PermissionState =
        if (capability in detectCapabilities()) PermissionState.NOT_REQUIRED else PermissionState.DENIED

    override fun requestPermission(capability: Capability) {
        // No desktop permission model modeled yet; nothing to request.
    }

    override fun createOverlay(handle: OverlayHandle) {
        throw UnsupportedOperationException(
            "Overlay is not implemented on ${detectHost()}. " +
                "Only the Android host (see :app AndroidHostManager) can create a real overlay right now."
        )
    }

    override fun destroyOverlay(handle: OverlayHandle) {
        throw UnsupportedOperationException("No overlay was created on ${detectHost()}.")
    }
}
