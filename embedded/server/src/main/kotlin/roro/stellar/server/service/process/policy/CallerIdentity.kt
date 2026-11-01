// ==========================================================================
// 游龙安全护盾 —— 新增文件（非上游 Stellar 文件）
// --------------------------------------------------------------------------
// 调用方身份（uid → 包名 + 签名证书 SHA-256 + 信任等级）。
//
// 为什么必须按「证书」识别：恶意样本会给自己的克隆体随机化包名，但签名证书
// 不变（见《游龙 Shizuku 防御方案》附录 A）。按证书拉黑才拦得住改包名。
//
// 与 MPL-2.0 的关系：本文件是本工程新增，不是上游派生文件。
// ==========================================================================

package roro.stellar.server.service.process.policy

import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import rikka.hidden.compat.PackageManagerApis
import roro.stellar.server.ClientManager
import roro.stellar.server.util.Logger
import roro.stellar.server.util.PackageManagerCompat
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * 一次 shell 调用的调用方身份。
 */
data class CallerIdentity(
    val uid: Int,
    val userId: Int,
    val packageName: String?,
    val certSha256: String?,
    /** 2026-10-25：应用显示名称（名称库黑名单匹配用；拿不到为 null）。 */
    val appLabel: String?,
    /** 是否就是本应用（管理器）——完全放行。 */
    val isManager: Boolean,
    /** 是否受信应用（预授权全部能力，但仍受目标保护约束）。 */
    val isTrusted: Boolean
) {
    /** 给人看的名字（拿不到包名时用 uid）。 */
    val displayName: String
        get() = packageName ?: "uid=$uid"

    /**
     * 2026-10-30（用户需求）：游龙工具是配套应用。
     * 它执行任何命令都不做拦截、不弹确认、不写审计。
     */
    val isYulongTool: Boolean
        get() = packageName == "com.youlong.tool"

    /** 游龙安全护盾自身。 */
    val isYulongSelf: Boolean
        get() = packageName == "com.youlong.hd"

    /** 自身和配套工具都免指令审查。 */
    val isReviewExempt: Boolean
        get() = isYulongSelf || isYulongTool
}

object CallerResolver {

    private val LOGGER = Logger("StellarCaller")

    private val certCache = ConcurrentHashMap<String, Pair<Long, String?>>()

    /** 2026-10-25：应用名称缓存（名称库匹配用，60 秒）。 */
    private val labelCache = ConcurrentHashMap<String, Pair<Long, String?>>()

    private const val CERT_CACHE_TTL_MS = 60_000L

    /**
     * 解析调用方。
     *
     * @param uid           Binder.getCallingUid()
     * @param clientManager 服务端的客户端记录（里面存着应用自报的包名，最准）
     */
    fun resolve(uid: Int, clientManager: ClientManager): CallerIdentity {
        val userId = uid / 100000
        val packageName = resolvePackageName(uid, clientManager)
        val cert = packageName?.let { certSha256(it, userId) }
        // 2026-10-25：名称库黑名单需要应用显示名称
        val label = packageName?.let { appLabel(it, userId) }
        return CallerIdentity(
            uid = uid,
            userId = userId,
            packageName = packageName,
            certSha256 = cert,
            appLabel = label,
            isManager = packageName == PolicyConfig.MANAGER_PACKAGE,
            isTrusted = packageName != null && PolicyConfig.isTrusted(packageName)
        )
    }

    /**
     * 由 uid 反查调用方包名。
     *
     * 2026-10-24 调整顺序：**先**用 PackageManager 按 uid 查（系统权威数据，
     * 应用无法伪造），再用客户端记录兜底（极少数场景 PM 查不到时）。
     * 若先用客户端自报包名，恶意应用可以谎报成受信应用来绕过黑名单/能力判定。
     */
    private fun resolvePackageName(uid: Int, clientManager: ClientManager): String? {
        val pmPackages = runCatching {
            PackageManagerApis.getPackagesForUidNoThrow(uid)
        }.getOrNull()
        val clientPackage = runCatching {
            clientManager.findClients(uid).firstOrNull()?.packageName
        }.getOrNull()
        // 共享 uid 时 PM 会返回多个包：优先采用「客户端自报且确实属于该 uid」的那个，
        // 否则取 PM 的第一个（系统权威数据）。
        if (!pmPackages.isNullOrEmpty()) {
            if (clientPackage != null && pmPackages.contains(clientPackage)) return clientPackage
            return pmPackages.first()
        }
        return clientPackage
    }

    /**
     * 取包名对应签名证书的 SHA-256（十六进制小写）。
     *
     * API 28+ 走 signingInfo.apkContentsSigners；更老的系统退回 signatures。
     */
    fun certSha256(packageName: String, userId: Int): String? {
        val key = "$packageName@$userId"
        val now = System.currentTimeMillis()
        val cached = certCache[key]
        if (cached != null && now - cached.first < CERT_CACHE_TTL_MS) return cached.second

        val value = try {
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                PackageManager.GET_SIGNING_CERTIFICATES.toLong()
            } else {
                @Suppress("DEPRECATION")
                PackageManager.GET_SIGNATURES.toLong()
            }
            val pi = PackageManagerCompat.getPackageInfo(packageName, flags, userId)
            val signers: Array<Signature>? = if (pi == null) {
                null
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pi.signingInfo?.apkContentsSigners
            } else {
                @Suppress("DEPRECATION")
                pi.signatures
            }
            signers?.firstOrNull()?.let { sha256Hex(it.toByteArray()) }
        } catch (t: Throwable) {
            LOGGER.w(t, "读取 %s 签名失败", packageName)
            null
        }
        certCache[key] = now to value
        return value
    }

    /**
     * 2026-10-25：应用显示名称（名称库黑名单用），带 60 秒缓存。
     */
    private fun appLabel(packageName: String, userId: Int): String? {
        val key = "$packageName@$userId"
        val now = System.currentTimeMillis()
        val cached = labelCache[key]
        if (cached != null && now - cached.first < CERT_CACHE_TTL_MS) return cached.second
        val value = PackageManagerCompat.getApplicationLabel(packageName, userId)
        labelCache[key] = now to value
        return value
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) {
            val v = b.toInt() and 0xFF
            if (v < 0x10) sb.append('0')
            sb.append(Integer.toHexString(v))
        }
        return sb.toString()
    }
}
