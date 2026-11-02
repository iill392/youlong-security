package roro.stellar.shizuku

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.util.Log

/**
 * Shizuku 兼容 Provider
 * 用于接收来自 Stellar 服务的 Shizuku 兼容 Binder
 */
open class ShizukuProvider : ContentProvider() {

    override fun attachInfo(context: Context?, info: ProviderInfo) {
        super.attachInfo(context, info)
        check(!info.multiprocess) { "android:multiprocess must be false" }
        check(info.exported) { "android:exported must be true" }
    }

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (extras == null) return null

        extras.classLoader = BinderContainer::class.java.classLoader

        return when (method) {
            METHOD_SEND_BINDER -> {
                handleSendBinder(extras)
                Bundle()
            }
            METHOD_GET_BINDER -> {
                handleGetBinder()
            }
            else -> null
        }
    }

    private fun handleSendBinder(extras: Bundle) {
        // ==================================================================
        // 2026-10 安全加固（用户需求）：只接受**内置特权服务端**投递的 Binder。
        // ------------------------------------------------------------------
        // 本 provider 是 exported 的，任何应用都能调 sendBinder。若不校验，
        // 外部 Stellar / Shizuku 应用就能把我们的 binder 换成它自己的 binder，
        // 表现为"外部软件也能授权"。
        //
        // 我们的服务端用 getContentProviderExternal + IContentProvider.call
        // 投递，在这里表现为服务端进程的 uid（root=0 / system=1000 / shell=2000）；
        // 普通第三方应用是 10xxx，一律拒绝。
        // ==================================================================
        if (!isTrustedBinderSender()) {
            val badUid = android.os.Binder.getCallingUid()
            recordRejectedInjection(badUid)
            android.util.Log.e(
                TAG,
                "拒绝来自不可信来源的 Binder 注入：callingUid=" + badUid
            )
            return
        }

        val container = extras.getParcelable<BinderContainer>(EXTRA_BINDER)
        if (container?.binder != null) {
            Log.i(TAG, "收到 Shizuku Binder")
            ShizukuCompat.onBinderReceived(container.binder, context!!.packageName)
        }
    }

    private fun handleGetBinder(): Bundle? {
        val binder = ShizukuCompat.binder
        if (binder == null || !binder.pingBinder()) return null

        val reply = Bundle()
        reply.putParcelable(EXTRA_BINDER, BinderContainer(binder))
        return reply
    }

    override fun query(
        uri: Uri, projection: Array<String?>?,
        selection: String?, selectionArgs: Array<String?>?,
        sortOrder: String?
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String?>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String?>?): Int = 0

    /**
     * 2026-10 新增：Binder 注入来源校验。
     *
     * <p>只允许内置特权服务端（root=0 / system=1000 / shell=2000）投递 Binder。
     * 普通应用（uid 10xxx）一律拒绝 —— 防止外部 Stellar / Shizuku 应用通过
     * exported 的 provider 把我们的 binder 换成它自己的，从而"看起来授权成功"。
     */
    /**
     * 2026-10 新增：判断一个 Binder 是不是**自家服务端**（IStellarService）。
     *
     * <p>依据是 Binder 的 interface descriptor —— 它由远端 Binder 自己声明，
     * 外部应用无法伪造成我们 AIDL 的 descriptor（那需要真的实现同一接口）。
     * 用来把"外部应用抢先注入的冒充者"和"自家服务端"区分开。
     */
    private fun isOwnServiceBinder(binder: android.os.IBinder?): Boolean {
        if (binder == null) return false
        return try {
            if (!binder.pingBinder()) return false
            binder.interfaceDescriptor == "com.stellar.server.IStellarService"
        } catch (tr: Throwable) {
            false
        }
    }

    private fun isTrustedBinderSender(): Boolean {
        return try {
            val uid = android.os.Binder.getCallingUid()
            uid == 0 || uid == 1000 || uid == 2000
        } catch (tr: Throwable) {
            false
        }
    }

    companion object {
        private const val TAG = "ShizukuProvider"

        /**
         * 2026-10 新增：被拒绝的 Binder 注入记录（排错/实证用）。
         *
         * <p>release 构建里 android.util.Log 会被 R8 裁掉，光靠 logcat 看不到
         * "拒绝外部注入"这件事，所以在这里留一份可读记录。
         */
        @JvmStatic
        val rejectedInjectionInfo: String
            get() = rejectedInfo

        private var rejectedCount = 0
        private var rejectedInfo = "无"

        private fun recordRejectedInjection(uid: Int) {
            rejectedCount++
            rejectedInfo = "已拒绝 " + rejectedCount + " 次，最近一次来源 uid=" + uid
        }
        private const val METHOD_SEND_BINDER = "sendBinder"
        private const val METHOD_GET_BINDER = "getBinder"
        private const val EXTRA_BINDER = "moe.shizuku.privileged.api.intent.extra.BINDER"
    }
}
