package roro.stellar.server.service.permission

import android.os.Bundle
import rikka.hidden.compat.PackageManagerApis
import roro.stellar.StellarApiConstants
import roro.stellar.server.ClientManager
import roro.stellar.server.ClientRecord
import roro.stellar.server.ConfigManager
import roro.stellar.server.util.Logger

class PermissionRequester(
    private val clientManager: ClientManager,
    private val configManager: ConfigManager,
    private val confirmation: PermissionConfirmation
) {
    companion object {
        private val LOGGER = Logger("PermissionRequester")
    }
    fun requestPermission(
        uid: Int,
        pid: Int,
        userId: Int,
        permission: String,
        requestCode: Int
    ) {
        val clientRecord = clientManager.requireClient(uid, pid)

        if (!StellarApiConstants.PERMISSIONS.contains(permission)) {
            clientRecord.dispatchRequestPermissionResult(
                requestCode,
                allowed = false,
                onetime = false,
                permission
            )
            return
        }
        when (configManager.find(uid)?.permissions?.get(permission)) {
            ConfigManager.FLAG_GRANTED -> {
                clientRecord.dispatchRequestPermissionResult(
                    requestCode,
                    allowed = true,
                    onetime = false,
                    permission
                )
            }
            ConfigManager.FLAG_DENIED -> {
                clientRecord.dispatchRequestPermissionResult(
                    requestCode,
                    allowed = false,
                    onetime = false,
                    permission
                )
            }
            else -> {
                // 登记「本次请求待用户确认」：只有登记过的请求，其结果回传才会被采信
                PendingPermissionConfirmations.register(requestCode, uid, pid, permission)
                confirmation.showPermissionConfirmation(
                    requestCode,
                    clientRecord,
                    uid,
                    pid,
                    userId,
                    permission
                )
            }
        }
    }

    fun dispatchPermissionResult(
        requestUid: Int,
        requestPid: Int,
        requestCode: Int,
        data: Bundle
    ) {
        val allowed = data.getBoolean(StellarApiConstants.REQUEST_PERMISSION_REPLY_ALLOWED)
        val onetime = data.getBoolean(StellarApiConstants.REQUEST_PERMISSION_REPLY_IS_ONETIME)
        val permission = data.getString(
            StellarApiConstants.REQUEST_PERMISSION_REPLY_PERMISSION,
            StellarApiConstants.PERMISSION_STELLAR
        )

        LOGGER.i(
            "dispatchPermissionResult: uid=$requestUid, pid=$requestPid, " +
                    "requestCode=$requestCode, allowed=$allowed, onetime=$onetime, permission=$permission"
        )

        // 结果回传中的 uid/pid/requestCode 全部来自调用方，属不可信输入：
        // 必须与服务端自己登记过、且尚未答复的那次请求完全一致才采信，否则丢弃。
        if (!consumePendingConfirmation(requestCode, requestUid, requestPid, permission)) {
            LOGGER.w(
                "dispatchPermissionResult: 未找到匹配的待确认请求，已忽略 " +
                        "(uid=$requestUid, pid=$requestPid, code=$requestCode, permission=$permission)"
            )
            return
        }

        val records = clientManager.findClients(requestUid)
        val packages = ArrayList<String>()

        if (records.isEmpty()) {
            LOGGER.w("dispatchPermissionResult: 未找到 uid $requestUid 的客户端")
            packages.addAll(PackageManagerApis.getPackagesForUidNoThrow(requestUid))
        } else {
            for (record in records) {
                packages.add(record.packageName)
                if (StellarApiConstants.isRuntimePermission(permission)) {
                    record.allowedMap[permission] = allowed
                }
                if (record.pid == requestPid) {
                    record.dispatchRequestPermissionResult(
                        requestCode,
                        allowed,
                        onetime,
                        permission
                    )
                }
            }
        }

        configManager.update(requestUid, packages)
        configManager.updatePermission(
            requestUid,
            permission,
            when {
                onetime -> ConfigManager.FLAG_ASK
                allowed -> ConfigManager.FLAG_GRANTED
                else -> ConfigManager.FLAG_DENIED
            }
        )
    }

    /** 校验并消费一次待确认请求（供 shizuku 兼容层的结果回传路径复用）。 */
    fun consumePendingConfirmation(
        requestCode: Int,
        uid: Int,
        pid: Int,
        permission: String
    ): Boolean = PendingPermissionConfirmations.consume(requestCode, uid, pid, permission)
}
