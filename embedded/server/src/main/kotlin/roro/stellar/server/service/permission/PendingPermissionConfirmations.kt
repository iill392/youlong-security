package roro.stellar.server.service.permission

/**
 * 待确认授权请求登记表（服务端自持）。
 *
 * <p><b>为什么需要它</b>：授权弹窗由管理器进程呈现，用户的选择通过
 * `dispatchPermissionConfirmationResult(requestUid, requestPid, requestCode, data)`
 * 回传给服务端。但该方法收到的 `requestUid/requestPid/requestCode/permission`
 * **全部来自调用方**（管理器导出组件可被任意应用拉起并携带伪造值），属于不可信输入。
 * 若直接采信，任何应用只要能让管理器进程代为回传一个 Bundle，就能为任意 uid 授予权限。
 *
 * <p><b>做法</b>：服务端只承认「自己刚刚发起过、且尚未答复」的那一次请求。
 * 发起授权弹窗前调用 [register] 登记，收到结果时用
 * [consume] 按 (uid, pid, requestCode, permission) 精确比对：
 * 完全一致才消费掉并放行，否则一律丢弃。
 */
internal object PendingPermissionConfirmations {

    /** 单条待确认请求的有效期：超时未答复即作废，避免陈旧条目被重放。 */
    private const val TTL_MS: Long = 5 * 60 * 1000L

    private data class Pending(
        val uid: Int,
        val pid: Int,
        val permission: String,
        val expireAt: Long
    )

    private val pendingMap = java.util.concurrent.ConcurrentHashMap<Int, Pending>()

    /** 登记一次待确认请求（key 为 requestCode）。 */
    fun register(requestCode: Int, uid: Int, pid: Int, permission: String) {
        pendingMap[requestCode] = Pending(uid, pid, permission, System.currentTimeMillis() + TTL_MS)
    }

    /**
     * 校验并消费一次待确认请求。
     *
     * @return 仅当存在未过期、且 uid/pid/permission 全部匹配的请求时返回 true
     */
    fun consume(requestCode: Int, uid: Int, pid: Int, permission: String): Boolean {
        val item = pendingMap[requestCode] ?: return false
        // 过期：顺手清掉，之后按未登记处理
        if (System.currentTimeMillis() > item.expireAt) {
            pendingMap.remove(requestCode, item)
            return false
        }
        // 不匹配时保留条目：可能只是调用方参数写错，真正的合法回传还在路上
        if (item.uid != uid || item.pid != pid || item.permission != permission) {
            return false
        }
        pendingMap.remove(requestCode, item)
        return true
    }
}
