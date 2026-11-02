package roro.stellar.server

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import rikka.hidden.compat.ActivityManagerApis
import rikka.hidden.compat.PackageManagerApis
import rikka.hidden.compat.UserManagerApis
import roro.stellar.StellarApiConstants.PERMISSIONS
import roro.stellar.StellarApiConstants.PERMISSION_KEY
import roro.stellar.StellarApiConstants.PERMISSION_STELLAR
import roro.stellar.server.StellarConfig.PackageEntry
import roro.stellar.server.api.IContentProviderUtils
import roro.stellar.server.ktx.workerHandler
import roro.stellar.server.shizuku.ShizukuApiConstants
import roro.stellar.server.util.Logger
import roro.stellar.server.util.PackageManagerCompat
import roro.stellar.server.util.ProviderDiscovery
import roro.stellar.server.util.UserHandleCompat

class ConfigManager {

    private val mWriteRunner: Runnable = Runnable { saveToManager(config) }

    private val config: StellarConfig
    private val configLoadedFromManager: Boolean
    val packages: MutableMap<Int, PackageEntry>
        get() = LinkedHashMap(config.packages)

    init {
        val loadResult = loadFromManagerWithStatus()
        this.config = loadResult.first
        this.configLoadedFromManager = loadResult.second

        var changed = false

        for (entry in LinkedHashMap(config.packages)) {

            val packages = PackageManagerApis.getPackagesForUidNoThrow(entry.key)
            if (packages.isEmpty()) {
                LOGGER.i("remove config for uid %d since it has gone", entry.key)
                config.packages.remove(entry.key)
                changed = true
                continue
            }

            var needRemoving = true

            for (packageName in entry.value.packages) {
                if (packages.contains(packageName)) {
                    needRemoving = false
                    break
                }
            }

            val rawSize = entry.value.packages.size
            val s = LinkedHashSet(entry.value.packages)
            entry.value.packages.clear()
            entry.value.packages.addAll(s)
            val shrunkSize = entry.value.packages.size
            if (shrunkSize < rawSize) {
                LOGGER.w("entry.packages has duplicate! Shrunk. (%d -> %d)", rawSize, shrunkSize)
            }

            if (needRemoving) {
                LOGGER.i("remove config for uid %d since the packages for it changed", entry.key)
                config.packages.remove(entry.key)
                changed = true
            }
        }

        for (userId in UserManagerApis.getUserIdsNoThrow()) {
            for (pi in PackageManagerCompat.getInstalledPackagesNoThrow(
                (PackageManager.MATCH_ALL or PackageManager.GET_META_DATA or PackageManager.GET_PROVIDERS).toLong(),
                userId
            )) {
                if (
                    pi == null ||
                    pi.applicationInfo == null ||
                    pi.packageName == ServerConstants.MANAGER_APPLICATION_ID
                ) {
                    continue
                }

                val uid = pi.applicationInfo!!.uid

                val packages = ArrayList<String>()
                packages.add(pi.packageName)

                // 2026-11-02（用户需求）：不再按 metadata / Provider 过滤。
                // 所有已安装应用都预授权，避免“应用没被管理器检测到”导致无法授权。
                updateLocked(uid, packages)
                changed = true
            }
        }

        // 2026-11-02（用户需求）：所有已安装应用的所有 Stellar 权限直接设为
        // FLAG_GRANTED；同时不再按 metadata 删除权限，防止预授权被下一轮裁剪掉。
        for (entry in LinkedHashMap(config.packages)) {
            val packageEntry = findLocked(entry.key) ?: continue
            for (permission in PERMISSIONS) {
                if (packageEntry.permissions[permission] != FLAG_GRANTED) {
                    packageEntry.permissions[permission] = FLAG_GRANTED
                    changed = true
                }
            }
            // 清掉旧版本标记的 ASK/DENIED 权限（包括未声明的历史权限）。
            val keys = ArrayList(packageEntry.permissions.keys)
            for (permission in keys) {
                if (packageEntry.permissions[permission] != FLAG_GRANTED) {
                    packageEntry.permissions[permission] = FLAG_GRANTED
                    changed = true
                }
            }
        }

        if (changed && configLoadedFromManager) {
            scheduleWriteLocked()
        }
    }

    private fun scheduleWriteLocked() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (workerHandler.hasCallbacks(mWriteRunner)) {
                return
            }
        } else {
            workerHandler.removeCallbacks(mWriteRunner)
        }
        workerHandler.postDelayed(mWriteRunner, WRITE_DELAY)
    }

    private fun findLocked(uid: Int): PackageEntry? {
        return config.packages[uid]
    }

    fun find(uid: Int): PackageEntry? {
        synchronized(this) {
            return findLocked(uid)
        }
    }

    fun getPermissionFlag(uid: Int, permission: String): Int {
        synchronized(this) {
            return findLocked(uid)?.permissions?.get(permission) ?: FLAG_ASK
        }
    }

    fun findOldConfigByPackageName(currentUid: Int, packageName: String): Pair<Int, PackageEntry>? {
        synchronized(this) {
            for ((uid, entry) in config.packages) {
                if (uid == currentUid) {
                    continue
                }
                if (entry.packages.contains(packageName)) {
                    return Pair(uid, entry)
                }
            }
            return null
        }
    }

    fun createConfigWithAllPermissions(uid: Int, packageName: String) {
        synchronized(this) {
            val entry = PackageEntry()
            entry.packages.add(packageName)
            // 2026-11-02（用户需求）：所有应用直接预授权全部 Stellar 权限，
            // 不再根据应用 metadata 决定是否弹窗确认。
            for (permission in PERMISSIONS) {
                entry.permissions[permission] = FLAG_GRANTED
            }
            if (entry.permissions.isEmpty()) {
                entry.permissions[PERMISSION_STELLAR] = FLAG_GRANTED
            }
            config.packages[uid] = entry
            scheduleWriteLocked()
            LOGGER.i("已创建全授权配置: uid=%d, package=%s, permissions=%s",
                uid, packageName, entry.permissions.toString())
        }
    }

    private fun updateLocked(
        uid: Int,
        packages: MutableList<String>?
    ) {
        var entry = findLocked(uid)
        if (entry == null) {
            entry = PackageEntry()
            for (permission in PERMISSIONS) {
                entry.permissions[permission] = FLAG_GRANTED
            }
            entry.permissions[PERMISSION_STELLAR] = FLAG_GRANTED
            config.packages[uid] = entry
        }
        if (packages != null) {
            for (packageName in packages) {
                if (entry.packages.contains(packageName)) {
                    continue
                }
                entry.packages.add(packageName)
            }
        }
        scheduleWriteLocked()
    }

    fun update(
        uid: Int,
        packages: MutableList<String>?
    ) {
        synchronized(this) {
            updateLocked(uid, packages)
        }
    }

    private fun updatePermissionLocked(uid: Int, permission: String, newFlag: Int) {
        var entry = findLocked(uid)
        if (entry == null) {
            entry = PackageEntry()
            val packages = PackageManagerApis.getPackagesForUidNoThrow(uid)
            entry.packages.addAll(packages)
            config.packages[uid] = entry
            LOGGER.i("为 uid=%d 创建新配置以保存权限 %s", uid, permission)
        } else if (entry.packages.isEmpty()) {
            entry.packages.addAll(PackageManagerApis.getPackagesForUidNoThrow(uid))
            LOGGER.i("为 uid=%d 的权限配置补全包名: %s", uid, entry.packages.toString())
        }
        entry.permissions[permission] = newFlag
        scheduleWriteLocked()
    }

    fun updatePermission(uid: Int, permission: String, newFlag: Int) {
        synchronized(this) {
            updatePermissionLocked(uid, permission, newFlag)
        }
    }

    private fun removeLocked(uid: Int) {
        config.packages.remove(uid)
        scheduleWriteLocked()
    }

    fun remove(uid: Int) {
        synchronized(this) {
            removeLocked(uid)
        }
    }

    fun isShizukuCompatEnabled(): Boolean {
        // 2026-11-01（用户需求）：Shizuku 兼容层恒开，设置页已隐藏开关。
        return true
    }

    fun setShizukuCompatEnabled(enabled: Boolean) {
        synchronized(this) {
            // 恒开：忽略关闭请求，只做一次持久化修正。
            if (!enabled) {
                LOGGER.i("Shizuku 兼容层已固定为开启，忽略禁用请求")
            }
            if (!config.shizukuCompatEnabled) {
                config.shizukuCompatEnabled = true
                LOGGER.i("Shizuku 兼容层状态已固定为: 启用")
                scheduleWriteLocked()
            }
        }
    }

    /**
     * 是否启用「无障碍自启」。
     *
     * ⚠️ 本工程改动（2026-10-01）：**恒定返回 false**。
     *
     * 原因：上游用这个开关驱动 ManagerGrantHelper.grantAccessibilityService()，
     * 把 manager 自己的 StellarAccessibilityService 写进
     * Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES，作为「开机自启的无障碍跳板」。
     *
     * 但本工程已把该无障碍服务从清单里**删除**（见
     * embedded/manager/src/main/AndroidManifest.xml 内的修改声明）。原因是
     * 它在系统「无障碍」列表里会和宿主自己的 AdSkipService 一样显示为
     * 「游龙安全护盾」，实测出现两个同名条目，用户无法分辨该开哪个；
     * 而宿主的开机自启本来就由自己的 com.youlong.hd.BootReceiver 负责，
     * 不需要这个跳板。
     *
     * 组件已不存在，再往 ENABLED_ACCESSIBILITY_SERVICES 里写它毫无意义，
     * 只会留下无效条目，因此这里直接短路为 false。
     */
    fun isAccessibilityAutoStartEnabled(): Boolean {
        return false
    }

    fun isDaemonEnabled(): Boolean {
        synchronized(this) {
            return config.daemonEnabled
        }
    }

    fun setDaemonEnabled(enabled: Boolean) {
        synchronized(this) {
            if (config.daemonEnabled != enabled) {
                config.daemonEnabled = enabled
                LOGGER.i("进程守护状态已更改: %s", if (enabled) "启用" else "禁用")
                scheduleWriteLocked()
            }
        }
    }

    companion object {
        private val LOGGER: Logger = Logger("ConfigManager")

        const val FLAG_ASK: Int = 0
        const val FLAG_GRANTED: Int = 1
        const val FLAG_DENIED: Int = 2

        private val GSON_IN: Gson = GsonBuilder().create()
        private val GSON_OUT: Gson = GsonBuilder()
            .setVersion(StellarConfig.LATEST_VERSION.toDouble())
            .create()

        private const val WRITE_DELAY = 1000L
        private const val MANAGER_PROVIDER = "${ServerConstants.MANAGER_APPLICATION_ID}.stellar"

        private fun callProvider(method: String, extras: Bundle?): Bundle? {
            val token: IBinder? = null
            var provider: android.content.IContentProvider? = null
            return try {
                provider = ActivityManagerApis.getContentProviderExternal(MANAGER_PROVIDER, 0, token, MANAGER_PROVIDER)
                    ?: return null
                IContentProviderUtils.callCompat(provider, null, MANAGER_PROVIDER, method, null, extras ?: Bundle())
            } catch (tr: Throwable) {
                LOGGER.e(tr, "ContentProvider 调用失败: %s", method)
                null
            } finally {
                if (provider != null) {
                    try { ActivityManagerApis.removeContentProviderExternal(MANAGER_PROVIDER, token) } catch (_: Throwable) {}
                }
            }
        }

        fun loadFromManagerWithStatus(): Pair<StellarConfig, Boolean> {
            return try {
                val reply = callProvider("loadConfig", null)
                if (reply == null) {
                    LOGGER.w("从 manager 加载配置失败: 返回 null，使用默认值并跳过初始化写入")
                    return Pair(StellarConfig(), false)
                }
                val json = reply.getString("configJson")
                if (json != null) {
                    val config = GSON_IN.fromJson(json, StellarConfig::class.java) ?: StellarConfig()
                    Pair(config, true)
                } else {
                    LOGGER.i("manager 无配置，使用默认值")
                    Pair(StellarConfig(), true)
                }
            } catch (tr: Throwable) {
                LOGGER.w(tr, "从 manager 加载配置失败，使用默认值，跳过初始化写入")
                Pair(StellarConfig(), false)
            }
        }

        fun loadFromManager(): StellarConfig {
            return loadFromManagerWithStatus().first
        }

        fun saveToManager(config: StellarConfig) {
            try {
                val json = GSON_OUT.toJson(config)
                val extras = Bundle().apply { putString("configJson", json) }
                val result = callProvider("saveConfig", extras)
                if (result != null) {
                    LOGGER.v("配置已保存到 manager")
                } else {
                    LOGGER.w("保存配置到 manager 失败: 返回 null")
                }
            } catch (tr: Throwable) {
                LOGGER.e(tr, "保存配置到 manager 失败")
            }
        }
    }
}
