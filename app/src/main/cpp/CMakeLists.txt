# 游龙 Native 加密核心 - CMake 构建脚本
cmake_minimum_required(VERSION 3.22.1)

project("nativecrypto")

# 纯 C/C++ 自实现加密核心（SHA-256 / AES-256 / GCM），零外部依赖
set(CMAKE_CXX_STANDARD 11)

add_library(nativecrypto SHARED
        NativeCrypto.cpp
        GuardSentinel.cpp
)

# 仅链接 Android 系统库（加密核心为自实现，不依赖 OpenSSL）
target_link_libraries(nativecrypto
        android
        log
)

# 优化选项：O2 + 隐藏符号 + 栈保护（提高逆向难度）
target_compile_options(nativecrypto PRIVATE
        -O2
        -fvisibility=hidden
        -fstack-protector-strong
        -ffunction-sections
        -fdata-sections
        -D_FORTIFY_SOURCE=2

        # ============================================================
        # 反逆向加固 v5.0
        # ------------------------------------------------------------
        # -fno-exceptions / -fno-rtti：
        #   本工程 C++ 全程不使用 throw/try/catch/dynamic_cast（已全局校验）。
        #   关闭后可消除 .gcc_except_table 与 .rodata 里的 RTTI 类型名
        #   （typeinfo 会带完整类名，属真实信息泄漏），同时缩小体积。
        # -fno-ident：不写入编译器版本指纹
        # -ffile-prefix-map：抹掉编译期绝对路径，否则 .rodata 里能直接
        #   搜到 E:\安全护盾\anquan\app\src\main\cpp\xxx.cpp
        #
        # ⚠️ 故意【不】使用 -fno-unwind-tables / -fno-asynchronous-unwind-tables：
        #   arm64 上 ART / libunwindstack 依赖 .eh_frame 做异步栈回溯
        #   （GC 线程扫描、ANR、tombstone 都走这条路）。去掉后 libunwindstack
        #   会退化到「帧指针链」回溯，而 -O2 默认省略帧指针 → 回退失败。
        #   收益（多藏几条函数边界，反汇编本来也能推）远小于风险（回溯异常）。
        # ============================================================
        -fno-exceptions
        -fno-rtti
        -fno-ident
        -ffile-prefix-map=${CMAKE_SOURCE_DIR}=.
)

# 链接期加固：
#   -s                剥离符号表（.symtab/.strtab）
#   --exclude-libs,ALL 让静态库符号也转为局部符号，不出现在 .dynsym
#   -z,relro -z,now   全量 RELRO，GOT 只读，阻断运行时地址改写
target_link_options(nativecrypto PRIVATE
        -Wl,-s
        -Wl,--exclude-libs,ALL
        -Wl,-z,relro
        -Wl,-z,now
)

# 隐藏未导出符号（JNI 符号显式导出）
set_target_properties(nativecrypto PROPERTIES
        CXX_VISIBILITY_PRESET hidden
        VISIBILITY_INLINES_HIDDEN ON
)

# ==========================================================================
# 内置 Stellar 特权内核的原生层（源码级融合）
# --------------------------------------------------------------------------
# 子目录 stellar/ 里是 Stellar（github.com/roro2239/Stellar，MPL-2.0 +
# Apache-2.0）manager/src/main/jni 的源码，产出：
#   libstellar.so —— 以 shell/root 身份通过 /system/bin/app_process 从**本应用
#                    自己的 APK** 中加载 roro.stellar.server.StellarService，
#                    从而让本应用自带特权服务，无需安装任何外部 Stellar 应用；
#   libchid.so    —— 降权工具（root → uid 2000）；
#   libadb.so     —— ADB 无线调试配对（SPAKE2），依赖 BoringSSL prefab 静态库；
#   librish.so    —— rish 终端原生层。
# AGP 每个模块只允许一个 CMake 入口，因此这里用 add_subdirectory 合并。
#
# ⚠️ 2026 年迁移：此前这里是 add_subdirectory(shizuku) 与 add_subdirectory(adb)，
#    两者都来自上游 Shizuku 的 manager JNI。改为 Stellar 后，原生组件统一收敛在
#    stellar/ 下（Stellar 已把 Shizuku 的 starter + chid + adb_pairing + rish
#    合并成同一组 JNI 源文件），因此 adb/ 子目录不再需要，已随 Shizuku 一并移除。
# ==========================================================================
add_subdirectory(stellar)
