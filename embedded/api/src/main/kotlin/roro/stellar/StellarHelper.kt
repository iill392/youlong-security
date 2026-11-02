// ==========================================================================
// 本文件来自 https://github.com/roro2239/Stellar-API （MPL-2.0 + Apache-2.0）
//   api/src/main/kotlin/roro/stellar/StellarHelper.kt
//
// 【本工程修改声明】—— 按 MPL-2.0 第 3.4 条标注
//   改动：STELLAR_MANAGER_PACKAGE_NAME 由上游的 "roro.stellar.manager"
//         改为 "com.youlong.hd"。
//   原因：管理器已被内置进「游龙安全护盾」，本机并不存在 roro.stellar.manager
//         这个包；写成上游包名会让 isManagerInstalled() 恒为 false、
//         openManager() 永远打不开界面。
//   除这一处常量的取值外，本文件其余内容与上游一致。
// ==========================================================================

package roro.stellar

import android.content.Context
import roro.stellar.Stellar.pingBinder
import roro.stellar.Stellar.sELinuxContext
import roro.stellar.Stellar.uid
import roro.stellar.Stellar.version

object StellarHelper {
    private const val SHIZUKU_PACKAGE_NAME = "moe.shizuku.privileged.api"
    private const val STELLAR_MANAGER_PACKAGE_NAME = "com.youlong.hd"

    fun isManagerInstalled(context: Context): Boolean {
        try {
            context.packageManager.getPackageInfo(STELLAR_MANAGER_PACKAGE_NAME, 0)
            return true
        } catch (_: Exception) {
            try {
                context.packageManager.getPackageInfo(SHIZUKU_PACKAGE_NAME, 0)
                return true
            } catch (_: Exception) {
                return false
            }
        }
    }

    fun openManager(context: Context): Boolean {
        return try {
            val intent = context.packageManager.getLaunchIntentForPackage(STELLAR_MANAGER_PACKAGE_NAME)
                ?: context.packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE_NAME)
            intent?.let {
                context.startActivity(it)
                true
            } ?: false
        } catch (_: Exception) {
            false
        }
    }

    val serviceInfo: ServiceInfo?
        get() = if (!pingBinder()) null else try {
            ServiceInfo(uid, version, sELinuxContext)
        } catch (_: Exception) {
            null
        }

    class ServiceInfo(
        val uid: Int,
        val version: Int,
        val seLinuxContext: String?
    ) {
        val isRoot: Boolean
            get() = uid == 0

        val isAdb: Boolean
            get() = uid == 2000

        override fun toString(): String =
            "ServiceInfo{uid=$uid, version=$version, seLinuxContext='$seLinuxContext'}"
    }
}
