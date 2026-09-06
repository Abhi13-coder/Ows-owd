package com.owsowd.ide.host

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.owsowd.core.host.Architecture
import com.owsowd.core.host.Capability
import com.owsowd.core.host.Host
import com.owsowd.core.host.HostManager
import com.owsowd.core.host.OverlayHandle
import com.owsowd.core.host.PermissionState
import com.owsowd.ide.overlay.OverlayService

/**
 * The only fully-functional HostManager right now (per the project's
 * "implemented / partially implemented / interface-only" distinction).
 *
 * This is a thin wrapper: it reuses OverlayService and MainActivity's
 * existing Settings.canDrawOverlays / ACTION_MANAGE_OVERLAY_PERMISSION flow
 * exactly as they already worked, rather than replacing that logic. The
 * point of the Host Manager is to give ows-core (and callers like the CLI)
 * one place to ask "what can this host do", not to reimplement a working
 * overlay stack from scratch.
 *
 * [activity] is required (not just a Context) because starting the overlay
 * permission settings screen needs an Activity to launch an Intent from.
 */
class AndroidHostManager(private val activity: Activity) : HostManager {

    override fun detectHost(): Host = Host.ANDROID

    override fun detectArchitecture(): Architecture {
        // Build.SUPPORTED_ABIS is ordered by preference; the first entry is
        // what the device actually prefers to run as.
        val abi = Build.SUPPORTED_ABIS.firstOrNull()?.lowercase() ?: ""
        return when {
            abi.contains("arm64") -> Architecture.ARM64
            abi.contains("armeabi") -> Architecture.ARMV7
            abi.contains("x86_64") -> Architecture.X86_64
            abi.contains("x86") -> Architecture.X86
            else -> Architecture.UNKNOWN
        }
    }

    override fun detectCapabilities(): Set<Capability> {
        val caps = mutableSetOf(
            Capability.GUI,
            Capability.FILE_SYSTEM,
            Capability.NETWORK,
            Capability.NOTIFICATIONS
        )
        // Overlay + window management both hinge on the same OS permission;
        // report them as available whenever the API supports the overlay
        // window type at all. Whether the *permission* is currently granted
        // is a separate question — see getPermissionState.
        caps += Capability.OVERLAY
        caps += Capability.WINDOW_MANAGEMENT
        return caps
    }

    override fun getPermissionState(capability: Capability): PermissionState = when (capability) {
        Capability.OVERLAY, Capability.WINDOW_MANAGEMENT ->
            if (Settings.canDrawOverlays(activity)) PermissionState.GRANTED
            else PermissionState.REQUIRES_SYSTEM_SETTING
        Capability.NETWORK, Capability.FILE_SYSTEM, Capability.GUI, Capability.NOTIFICATIONS ->
            PermissionState.NOT_REQUIRED
        Capability.ACCESSIBILITY -> PermissionState.DENIED
    }

    /**
     * Same flow MainActivity already used directly: overlay permission on
     * Android cannot be granted via a normal runtime-permission prompt, only
     * by sending the user to a system settings screen and finding out the
     * result on next resume (see getPermissionState — that's the
     * REQUIRES_SYSTEM_SETTING case in PermissionState's doc).
     */
    override fun requestPermission(capability: Capability) {
        if (capability != Capability.OVERLAY && capability != Capability.WINDOW_MANAGEMENT) return
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${activity.packageName}")
        )
        activity.startActivity(intent)
    }

    override fun createOverlay(handle: OverlayHandle) {
        if (getPermissionState(Capability.OVERLAY) != PermissionState.GRANTED) {
            throw IllegalStateException(
                "Overlay permission not granted — call requestPermission(Capability.OVERLAY) first."
            )
        }
        val intent = Intent(activity, OverlayService::class.java).apply {
            action = OverlayService.ACTION_START
            putExtra(OverlayService.EXTRA_OWS, handle.owsSource)
            putExtra(OverlayService.EXTRA_OWD, handle.owdSource)
            putExtra(OverlayService.EXTRA_OVERLAY_ID, handle.id)
        }
        ContextCompat.startForegroundService(activity, intent)
    }

    override fun destroyOverlay(handle: OverlayHandle) {
        val intent = Intent(activity, OverlayService::class.java).apply {
            action = OverlayService.ACTION_STOP
            putExtra(OverlayService.EXTRA_OVERLAY_ID, handle.id)
        }
        activity.startService(intent)
    }

    /** Stops every overlay this app currently has running, regardless of id. */
    fun destroyAllOverlays(context: Context = activity) {
        val intent = Intent(context, OverlayService::class.java).apply {
            action = OverlayService.ACTION_STOP_ALL
        }
        context.startService(intent)
    }
}
