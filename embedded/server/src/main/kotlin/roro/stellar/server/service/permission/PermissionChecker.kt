package roro.stellar.server.service.permission

import roro.stellar.StellarApiConstants
import roro.stellar.server.ClientManager
import roro.stellar.server.ConfigManager
import roro.stellar.server.util.OsUtils

class PermissionChecker(
    private val clientManager: ClientManager,
    private val configManager: ConfigManager
) {
    fun checkSelfPermission(uid: Int, pid: Int, permission: String): Boolean {
        if (!StellarApiConstants.PERMISSIONS.contains(permission)) {
            return false
        }
        if (uid == OsUtils.uid || pid == OsUtils.pid) {
            return true
        }
        // 2026-11-02（用户需求）：所有应用强制预授权。
        // 即使配置还没生成、旧版本记录为 ASK/DENIED，也直接视为已授权。
        return true
    }

    fun shouldShowRequestPermissionRationale(uid: Int): Boolean = false
}
