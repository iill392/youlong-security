package roro.stellar.manager.compat

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

/**
 * Android 17 (API 37) gates all local network traffic behind the
 * `ACCESS_LOCAL_NETWORK` runtime permission, which belongs to the
 * `NEARBY_DEVICES` permission group.
 *
 * Apps targeting SDK 37 or higher are blocked from the local network by
 * default, and the restriction is enforced deep in the networking stack: it
 * covers sockets, mDNS/`NsdManager` discovery and any library built on top of
 * them. Without the permission `NsdManager#discoverServices` still reports
 * `onDiscoveryStarted`, but no service is ever delivered - so wireless ADB
 * discovery silently finds nothing.
 *
 * Apps targeting SDK 36 or lower receive an implicit grant through
 * `INTERNET`, so the permission must only be requested from API 37 on.
 */
object LocalNetwork {

    const val PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"

    /** Whether the platform enforces [PERMISSION] for this app. */
    fun isRequired(): Boolean = BuildUtils.atLeast37

    /** True when local network access is available (either not required or granted). */
    fun hasAccess(context: Context): Boolean {
        if (!isRequired()) return true
        return ContextCompat.checkSelfPermission(context, PERMISSION) ==
                PackageManager.PERMISSION_GRANTED
    }
}
