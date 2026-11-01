// ==========================================================================
// 本文件来自 https://github.com/roro2239/Stellar （MPL-2.0 + Apache-2.0）
//   manager/src/main/kotlin/roro/stellar/manager/StellarApplication.kt
//
// 【本工程修改声明】—— 按 MPL-2.0 第 3.4 条标注
//   上游的 manager 是独立 APK，自己声明 android:name=".StellarApplication"，
//   系统启动时自动构造该类并调用 onCreate()。本工程把管理器内置进
//   「游龙安全护盾」，宿主 Application 是 com.youlong.hd.YouLongApp（清单合并
//   以主模块为准），StellarApplication 因此**不会**被系统实例化，
//   它原来在 onCreate() 里做的初始化也就不会发生。
//
//   本文件的改动（共三处，其余与上游逐字一致）：
//     1. `application` 的类型由 StellarApplication 放宽为 Application。
//        管理器内部（startup/command/Starter.kt、Chid.kt）只用到
//        application.applicationInfo.nativeLibraryDir / sourceDir，
//        并不需要它真的是 StellarApplication 类型。
//     2. `init(context)` 由 private 改为公开，供宿主 YouLongApp 显式调用。
//     3. 新增 attachApplication(Application)，把宿主的 Application 实例交给
//        管理器（等价于上游 onCreate 里的 `application = this`）。
//        宿主调用顺序见 YouLongApp.java 内的说明。
//   伴生对象的 init 块（Shell 默认构造器、HiddenApiBypass 豁免、加载 libadb）
//   保持不动：它在类首次被加载时执行，宿主首次触达本类的静态成员即会触发。
// ==========================================================================

package roro.stellar.manager

import android.app.Application
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatDelegate
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import org.lsposed.hiddenapibypass.HiddenApiBypass
import roro.stellar.Stellar
import roro.stellar.manager.compat.BuildUtils.atLeast30
import roro.stellar.manager.db.AppDatabase
import roro.stellar.manager.startup.notification.BootStartNotifications
import roro.stellar.manager.util.Logger.Companion.LOGGER

// ⚠️ 本工程改动：类型由 StellarApplication 放宽为 Application。
//    原因见文件头部的修改声明第 1 条。
lateinit var application: Application

class StellarApplication : Application() {

    @RequiresApi(Build.VERSION_CODES.P)
    companion object {

        init {
            LOGGER.d("init")

            @Suppress("DEPRECATION")
            Shell.setDefaultBuilder(Shell.Builder.create().setFlags(Shell.FLAG_REDIRECT_STDERR))

            HiddenApiBypass.setHiddenApiExemptions("")

            if (atLeast30) {
                System.loadLibrary("adb")
            }
        }

        /**
         * ⚠️ 本工程新增：把宿主的 Application 交给管理器。
         *
         * 上游这里是实例字段赋值（`application = this`，发生在 onCreate 中）。
         * 本工程中 StellarApplication 不会被系统实例化，因此改由宿主在
         * YouLongApp.onCreate() 里调用本方法完成同样的赋值。
         */
        fun attachApplication(app: Application) {
            application = app
        }

        /**
         * ⚠️ 本工程改动：可见性由 private 改为 public，供宿主显式调用。
         *
         * 初始化管理器的偏好存储与夜间模式。上游由 onCreate() 调用；
         * 本工程由 YouLongApp.onCreate() 调用。
         */
        fun init(context: Context) {
            StellarSettings.initialize(context)
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
        }
    }

    override fun onCreate() {
        super.onCreate()
        application = this
        init(this)
        BootStartNotifications.createChannel(this)
        Stellar.addServiceStartedListener(
            { executeFollowCommands() }
        )
    }

    private fun executeFollowCommands() {
        val context = this
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val commands = AppDatabase.get(context).commandDao().getAll()
                    .filter { it.mode == "FOLLOW_SERVICE" || (it.mode == "FOLLOW_SERVICE_ONCE" && it.enabled && it.executionCount < it.maxExecutions) }
                commands.forEach { cmd ->
                    try {
                        if (cmd.mode == "FOLLOW_SERVICE_ONCE" &&
                            AppDatabase.get(context).commandDao().claimExecution(cmd.id) == 0
                        ) {
                            return@forEach
                        }
                        LOGGER.d("执行跟随服务命令: title=${cmd.title}, command=${cmd.command}")
                        val process = Stellar.newProcess(arrayOf("sh", "-c", cmd.command), null, null)
                        val stdout = async(Dispatchers.IO) {
                            process.inputStream.bufferedReader().readText()
                        }
                        val stderr = async(Dispatchers.IO) {
                            process.errorStream.bufferedReader().readText()
                        }
                        val exitCode = process.waitFor()
                        val stdoutText = stdout.await()
                        val stderrText = stderr.await()
                        if (exitCode != 0) {
                            LOGGER.w("命令执行失败: title=${cmd.title}, 退出码=$exitCode, stdout=$stdoutText, stderr=$stderrText")
                            if (cmd.mode == "FOLLOW_SERVICE_ONCE") {
                                AppDatabase.get(context).commandDao().recordExecution(cmd.id, success = 0, failure = 1)
                            }
                        } else {
                            LOGGER.d("命令执行完成: ${cmd.title}, 退出码=$exitCode")
                            if (cmd.mode == "FOLLOW_SERVICE_ONCE") {
                                AppDatabase.get(context).commandDao().recordExecution(cmd.id, success = 1, failure = 0)
                            }
                        }
                    } catch (e: Exception) {
                        LOGGER.e("命令执行失败: ${cmd.title}", e)
                        if (cmd.mode == "FOLLOW_SERVICE_ONCE") {
                            AppDatabase.get(context).commandDao().recordExecution(cmd.id, success = 0, failure = 1)
                        }
                    }
                }
            } catch (e: Exception) {
                LOGGER.e("读取跟随服务命令失败", e)
            }
        }
    }
}
