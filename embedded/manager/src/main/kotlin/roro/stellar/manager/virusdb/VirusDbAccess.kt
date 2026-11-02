// ==========================================================================
// 游龙安全护盾 —— 新增文件（非上游 Stellar 文件）
// --------------------------------------------------------------------------
// 内置特权管理器 ↔ 宿主 App 的「病毒库同步」桥（2026-10-24）。
//
// 方向：manager 模块（本文件）只定义数据与回调；宿主 App（:app）在
// YouLongApp.onCreate() 里通过 setProvider() 注入实现（读状态 / 触发同步）。
// 这样 manager 不需要编译期依赖 app 模块（依赖方向仍是 app → manager）。
//
// 与 MPL-2.0 的关系：本文件是本工程新增，不是上游派生文件。
// ==========================================================================

package roro.stellar.manager.virusdb

/**
 * 病毒库同步状态（展示用，字段都可空/默认）。
 *
 * @param ready         特征库是否已就绪（有解析结果）
 * @param version       特征库版本（如 2026.10.24.1）
 * @param lastSyncAt    最近一次同步成功时间（毫秒；0 = 从未成功）
 * @param lastAttemptAt 最近一次发起同步时间（毫秒；0 = 从未发起）
 * @param syncing       是否正在同步
 * @param error         最近一次失败原因（空 = 无错误）
 * @param periodic      是否已开启每 3 分钟自动同步
 * @param pollIntervalMs 自动同步周期（毫秒）
 * @param snapshotPushed 是否已把快照成功推送到内置特权（未推送时服务端不会按这份库拦截）
 * @param blacklistCount 黑名单应用数量（safe1 + virus-db packages）
 * @param ruleCount      拦截指令（行为规则）数量
 */
data class VirusDbSyncStatus(
    val ready: Boolean = false,
    val version: String = "",
    val lastSyncAt: Long = 0L,
    val lastAttemptAt: Long = 0L,
    val syncing: Boolean = false,
    val error: String = "",
    val periodic: Boolean = false,
    val pollIntervalMs: Long = 180_000L,
    val snapshotPushed: Boolean = false,
    val blacklistCount: Int = 0,
    val ruleCount: Int = 0
)

/**
 * 宿主 App 注入的两个能力。
 *
 * 用普通 Java 接口（而不是 Kotlin 函数类型），宿主侧可以直接用 lambda / 匿名类实现。
 */
object VirusDbAccess {

    /** 读取当前同步状态（必须轻量、不能做网络请求）。 */
    fun interface StatusProvider {
        fun status(): VirusDbSyncStatus?
    }

    /** 触发一次同步；返回 true 表示已启动后台同步。 */
    fun interface SyncTrigger {
        fun sync(force: Boolean): Boolean
    }

    @Volatile
    private var statusProvider: StatusProvider? = null

    @Volatile
    private var syncTrigger: SyncTrigger? = null

    /** 宿主 App 启动时注入（重复注入以最后一次为准）。 */
    @JvmStatic
    fun setProvider(provider: StatusProvider?, trigger: SyncTrigger?) {
        statusProvider = provider
        syncTrigger = trigger
    }

    /** 是否已注入（未注入说明宿主还没初始化）。 */
    @JvmStatic
    fun isAvailable(): Boolean = statusProvider != null && syncTrigger != null

    /** 当前同步状态；未注入或异常时返回 null。 */
    fun status(): VirusDbSyncStatus? = try {
        statusProvider?.status()
    } catch (_: Throwable) {
        null
    }

    /** 请求同步（默认强制，忽略 30 秒节流）。 */
    fun requestSync(force: Boolean = true): Boolean = try {
        syncTrigger?.sync(force) ?: false
    } catch (_: Throwable) {
        false
    }
}
