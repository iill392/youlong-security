package roro.stellar.server

import com.google.gson.annotations.SerializedName

class StellarConfig {

    @SerializedName("version")
    var version: Int = LATEST_VERSION
    @SerializedName("packages")
    var packages: MutableMap<Int, PackageEntry> = mutableMapOf()
    // ⚠️ 本工程改动（原默认值 true）：Shizuku 兼容层默认**关闭**。
    //    原因：兼容层会把 Shizuku Binder 投递给任何声明了对应 meta-data 的应用，
    //    默认开启等于给未授权应用多开一条提权面；需要时由用户在管理器里显式打开。
    @SerializedName("shizukuCompatEnabled")
    var shizukuCompatEnabled: Boolean = false
    @SerializedName("accessibilityAutoStart")
    var accessibilityAutoStart: Boolean = false
    @SerializedName("daemonEnabled")
    var daemonEnabled: Boolean = false

    class PackageEntry {
        @SerializedName("packages")
        var packages: MutableList<String> = ArrayList()
        @SerializedName("permissions")
        var permissions: MutableMap<String, Int> = mutableMapOf()
    }

    companion object {
        const val LATEST_VERSION: Int = 1
    }
}