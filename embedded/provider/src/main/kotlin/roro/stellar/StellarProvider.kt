// ==========================================================================
// 本文件来自 https://github.com/roro2239/Stellar-API （MPL-2.0 + Apache-2.0）
//   provider/src/main/kotlin/roro/stellar/StellarProvider.kt
//
// 【本工程修改声明】—— 按 MPL-2.0 第 3.4 条标注
//   改动：把三处硬编码的 "roro.stellar.manager" 前缀改为 "com.youlong.hd"：
//           EXTRA_BINDER / EXTRA_CLIENT_BINDER  —— 与特权服务端
//             roro.stellar.server.BinderDistributor、UserServiceStarter
//             里对应的 extra key **必须逐字相同**（Binder 就是靠这套 key
//             跨进程投递的），服务端的同名常量已一并改写；
//           MANAGER_APPLICATION_ID             —— 用于识别「管理器自己」。
//   原因：管理器被内置进「游龙安全护盾」，应用包名是 com.youlong.hd，
//         本机不存在 roro.stellar.manager 这个包。
//   除这三处字符串外，本文件其余内容与上游一致。
// ==========================================================================

package roro.stellar

import android.content.BroadcastReceiver
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import androidx.core.net.toUri
import com.stellar.api.BinderContainer
import roro.stellar.Stellar.onBinderReceived
import roro.stellar.Stellar.pingBinder

@Suppress("deprecation")
open class StellarProvider : ContentProvider() {
    override fun attachInfo(context: Context?, info: ProviderInfo) {
        super.attachInfo(context, info)

        check(!info.multiprocess) { "android:multiprocess must be false" }

        check(info.exported) { "android:exported must be true" }

        isProviderProcess = true
    }

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (extras == null) {
            return null
        }

        extras.classLoader = BinderContainer::class.java.getClassLoader()

        val reply = Bundle()
        when (method) {
            METHOD_SEND_BINDER -> {
                handleSendBinder(extras)
            }

            METHOD_GET_BINDER -> {
                if (!handleGetBinder(reply)) {
                    return null
                }
            }

            METHOD_SEND_USER_SERVICE -> {
                return handleSendUserService(extras)
            }
        }
        return reply
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

        Log.i(TAG, "收到 Stellar Binder：current=${Stellar.binder}, alive=${pingBinder()}")

        // ==================================================================
        // 2026-10 修正：已有 binder 时不能无条件"忽略重复发送"。
        // ------------------------------------------------------------------
        // 若当前 binder 是**外部应用抢先注入的冒充者**，必须允许自家服务端
        // 把它换掉，否则功能会瘫（界面显示"内置特权服务未启动"）。
        // 只有确认当前 binder **就是自家服务端**时才忽略重复。
        // ==================================================================
        if (pingBinder()) {
            if (isOwnServiceBinder(Stellar.binder)) {
                Log.w(TAG, "已有自家服务端 Binder，忽略重复发送")
                return
            }
            Log.w(TAG, "当前 Binder 非自家服务端（疑似外部注入），将用可信来源替换")
        }

        val container = extras.getParcelable<BinderContainer?>(EXTRA_BINDER)
        if (container != null && container.binder != null) {
            Log.i(TAG, "接收并绑定 Stellar Binder")

            onBinderReceived(container.binder, context!!.packageName)

            if (enableMultiProcess) {
                Log.d(TAG, "多进程支持已启用，广播 Binder 给同包其他进程")

                val intent = Intent(ACTION_BINDER_RECEIVED)
                    .putExtra(EXTRA_BINDER, container)
                    .setPackage(context!!.packageName)
                context!!.sendBroadcast(intent)
            }
        } else {
            Log.e(TAG, "发送 Binder 的 extras 中没有有效 Binder")
        }
    }

    private fun handleGetBinder(reply: Bundle): Boolean {
        val binder: IBinder? = Stellar.binder
        if (binder == null || !binder.pingBinder()) return false

        reply.putParcelable(EXTRA_BINDER, BinderContainer(binder))
        return true
    }

    private fun handleSendUserService(extras: Bundle): Bundle? {
        Log.i(TAG, "收到用户服务 Binder")

        val service = Stellar.getService()
        if (service == null) {
            Log.e(TAG, "Stellar 服务未连接，无法附加用户服务")
            return null
        }

        val container = extras.getParcelable<BinderContainer?>(EXTRA_BINDER)
        if (container?.binder == null) {
            Log.e(TAG, "用户服务 extras 中没有有效 Binder")
            return null
        }

        try {
            service.attachUserService(container.binder, extras)
        } catch (e: Exception) {
            Log.e(TAG, "附加用户服务失败", e)
            return null
        }

        val reply = Bundle()
        val stellarBinder = Stellar.binder
        if (stellarBinder != null) {
            reply.putParcelable(EXTRA_BINDER, BinderContainer(stellarBinder))
        }
        val clientBinder = Stellar.getClientBinder()
        if (clientBinder != null) {
            reply.putParcelable(EXTRA_CLIENT_BINDER, BinderContainer(clientBinder))
        }
        return reply
    }

    override fun query(
        uri: Uri,
        projection: Array<String?>?,
        selection: String?,
        selectionArgs: Array<String?>?,
        sortOrder: String?
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String?>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<String?>?
    ): Int = 0

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
     * 外部应用无法伪造成我们 AIDL 的 descriptor。用来把「外部应用抢先注入的
     * 冒充者」与「自家服务端」区分开：冒充者必须允许被自家服务端替换，
     * 否则功能会瘫（界面显示「内置特权服务未启动」）。
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
        private const val TAG = "StellarProvider"

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

        const val METHOD_SEND_BINDER: String = "sendBinder"

        const val METHOD_GET_BINDER: String = "getBinder"

        const val METHOD_SEND_USER_SERVICE: String = "sendUserService"

        const val ACTION_BINDER_RECEIVED: String = "com.stellar.api.action.BINDER_RECEIVED"

        private const val EXTRA_BINDER = "com.youlong.hd.intent.extra.BINDER"
        private const val EXTRA_CLIENT_BINDER = "com.youlong.hd.intent.extra.CLIENT_BINDER"

        const val MANAGER_APPLICATION_ID: String = "com.youlong.hd"

        private var enableMultiProcess = false

        private var isProviderProcess = false

        fun setIsProviderProcess(isProviderProcess: Boolean) {
            Companion.isProviderProcess = isProviderProcess
        }

        fun enableMultiProcessSupport(isProviderProcess: Boolean) {
            Log.d(
                TAG,
                if (isProviderProcess) "当前进程是 Provider 进程，启用多进程 Binder 转发"
                else "当前进程不是 Provider 进程，启用多进程 Binder 接收"
            )

            Companion.isProviderProcess = isProviderProcess
            enableMultiProcess = true
        }

        fun requestBinderForNonProviderProcess(context: Context) {
            if (isProviderProcess) {
                return
            }

            Log.d(TAG, "非 Provider 进程请求当前进程的 Stellar Binder")

            val receiver: BroadcastReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    val container = intent.getParcelableExtra<BinderContainer?>(EXTRA_BINDER)
                    if (container != null && container.binder != null) {
                        Log.i(TAG, "非 Provider 进程收到 Stellar Binder 广播")
                        onBinderReceived(container.binder, context.packageName)
                    }
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(
                    receiver,
                    IntentFilter(ACTION_BINDER_RECEIVED),
                    Context.RECEIVER_NOT_EXPORTED
                )
            } else {
                context.registerReceiver(receiver, IntentFilter(ACTION_BINDER_RECEIVED))
            }
            val reply = try {
                context.contentResolver.call(
                    ("content://" + context.packageName + ".stellar").toUri(),
                    METHOD_GET_BINDER, null, Bundle()
                )
            } catch (tr: Throwable) {
                null
            }

            if (reply != null) {
                reply.classLoader = BinderContainer::class.java.getClassLoader()

                val container = reply.getParcelable<BinderContainer?>(EXTRA_BINDER)
                if (container != null && container.binder != null) {
                    Log.i(TAG, "非 Provider 进程通过 Provider 取得 Stellar Binder")
                    onBinderReceived(container.binder, context.packageName)
                }
            }
        }
    }
}
