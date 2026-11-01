// ==========================================================================
// 本文件来自 https://github.com/roro2239/Stellar 的
//   server/src/main/kotlin/roro/stellar/server/ServerConstants.kt
// （MPL-2.0；其中源自 Shizuku 的部分为 Apache-2.0）
//
// 【本工程修改声明】—— 按 MPL-2.0 第 3.4 条标注
//   改动 1：MANAGER_APPLICATION_ID 由上游的 "roro.stellar.manager"
//         改为 "com.youlong.hd"。
//   原因：上游的 manager 是独立 APK（applicationId = roro.stellar.manager）；
//         本工程把管理器内置进「游龙安全护盾」，应用自身的包名是 com.youlong.hd。
//         该常量被服务端用来判定「调用者是不是管理器」（并由此决定是否授予管理
//         权限），也是请求权限 Activity 的 action 前缀，必须与真实包名一致，
//         否则本应用自己会被服务端当成第三方客户端而拿不到管理器级权限。
//   改动 2：新增 COMMAND_GUARD_ACTION 常量（上游没有）。
//   原因：本工程新增了「命令安全拦截」功能：已授权应用通过内置 Stellar 下发
//         卸载/冻结/批量停止/针对自家应用的危险命令时，服务端需要拉起管理器
//         侧的一个对话框向用户告警或征求确认，该 action 即用于这个对话框。
//   除以上两处外，本文件其余内容与上游一致。
// ==========================================================================

package roro.stellar.server

object ServerConstants {
    const val MANAGER_APP_NOT_FOUND: Int = 50

    // ⚠️ 本工程改动（原值 "roro.stellar.manager"）：本应用包名为 com.youlong.hd。
    //    详见文件头部的修改声明。
    const val MANAGER_APPLICATION_ID: String = "com.youlong.hd"

    const val REQUEST_PERMISSION_ACTION: String =
        "$MANAGER_APPLICATION_ID.intent.action.REQUEST_PERMISSION"

    // ⚠️ 本工程新增（上游没有此常量）：命令安全拦截对话框的 action。
    //    对应的 Activity 是管理器侧的 CommandGuardActivity，见其 AndroidManifest 声明。
    const val COMMAND_GUARD_ACTION: String =
        "$MANAGER_APPLICATION_ID.intent.action.COMMAND_GUARD"

    const val BINDER_TRANSACTION_getApplications: Int = 10001
}
