# ==========================================================================
# 内置 Stellar 的原生组件（源码级融合）
# --------------------------------------------------------------------------
# 源文件来自 https://github.com/roro2239/Stellar 的 manager/src/main/jni/
#                        （MPL-2.0；其中源自 Shizuku 的部分为 Apache-2.0）
#
# 产出四个 native 组件：
#   libstellar.so  可执行文件 —— 以 shell(uid 2000) 或 root(uid 0) 身份执行
#                  /system/bin/app_process，用 CLASSPATH 指向**本应用自己的
#                  APK**，加载其中的 roro.stellar.server.StellarService，
#                  从而在本机拉起一个属于本应用的特权服务进程。
#                  这就是「不依赖任何外部 Stellar/Shizuku 应用」的关键实现。
#   libchid.so     可执行文件 —— 降权工具：root → uid 2000 后再拉起 libstellar.so
#   libadb.so      动态库 —— ADB 无线调试配对（SPAKE2 + curve25519 + AES-GCM），
#                  依赖 BoringSSL 静态库
#   librish.so     动态库 —— Stellar 内置的 rish 终端原生层
#
# --------------------------------------------------------------------------
# 相对上游 manager/src/main/jni/CMakeLists.txt 的改动（本工程适配版）：
#
#   1. 去掉 cmake_minimum_required / project()：本目录被 :app 的主 CMake
#      以 add_subdirectory 方式包含（AGP 每个模块只允许一个 CMake 入口点）。
#
#   2. 把上游的全局 add_compile_options / add_link_options / add_definitions
#      改为**逐目标**设置 —— 上游那份是模块级 CMake 工程的唯一入口，全局设置
#      没问题；这里被 :app 包含，全局设置会污染同级的 nativecrypto 目标。
#
#   3. ⚠️【关键】不再使用 find_package(boringssl/cxx)。
#      这两个包上游用 **prefab** 引入：
#          io.github.vvb2060.ndk:boringssl   → boringssl::crypto_static
#          org.lsposed.libcxx:libcxx         → cxx::cxx
#      但 AGP 调起 prefab CLI 的方式是「生成 .bat 批处理 → cmd.exe 执行」，
#      批处理里是 Gradle 缓存的**绝对路径**，其中含当前 Windows 用户名
#      （本机为中文「游龙」）。cmd.exe 按系统 ANSI 代码页读这个 .bat，
#      中文路径被误解码，于是报：
#          Error while executing process ...\prefab_command.bat
#          系统找不到指定的路径。
#          '-path' 不是内部或外部命令，也不是可运行的程序或批处理文件。
#      这是 AGP + prefab 在「Windows 用户名含非 ASCII 字符」环境下的固有缺陷，
#      与工程代码无关，也没有 CMake / Gradle 参数可以绕过。
#
#      解决办法：关掉 AGP 的 prefab（app/build.gradle 中
#      buildFeatures.prefab = false），改由 :app 的 Gradle 任务
#      extractPrefabPackages 直接把这两个 AAR 解压到
#          <buildDir>/prefab/<包名>/
#      并通过 -D 把路径传进来，下面手工构造等价的 imported target。
#
#   4. 刻意**不**编译 helper.cpp：上游的 CMakeLists 同样没有编译它，但那份里
#      JNI_OnLoad 注册的是 "moe/stellar/server/utils/NativeHelper"，而这个类在
#      上游与本案的全部 Kotlin/Java 源码中都不存在（FindClass 会失败并让
#      JNI_OnLoad 返回 -1）。保持「不编译」即与上游行为一致，不引入回归。
#
#   5. 保留上游各目标的链接关系，不做替换。
# ==========================================================================

set(STELLAR_JNI_DIR ${CMAKE_CURRENT_SOURCE_DIR})

# --------------------------------------------------------------------------
# 公用编译/链接选项（等价于上游的全局开关，这里显式展开到每个目标）
# --------------------------------------------------------------------------
# ⚠️ 本工程改动（2026-10 加固）：
#   · 去掉上游的 -fno-threadsafe-statics —— 它会关闭 C++11 对静态局部变量初始化的
#     线程安全保证；libadb.so / librish.so 是加载进应用进程的 JNI 库，存在多线程
#     首次访问静态局部对象的风险；
#   · 新增 -fstack-protector-strong —— 与同工程的 nativecrypto 目标保持一致
#     （原先只有 nativecrypto 开了栈保护，这 4 个目标漏了）。
set(STELLAR_COMMON_COMPILE_OPTIONS
        -fdata-sections
        -ffunction-sections
        -fno-exceptions
        -fno-rtti
        -fstack-protector-strong)

# 发布构建额外选项：体积 + 隐藏符号（与上游一致）+ 加固
set(STELLAR_RELEASE_COMPILE_OPTIONS
        -Os
        -flto
        -fvisibility=hidden
        -fvisibility-inlines-hidden
        # ⚠️ 本工程改动（2026-10）：补上 FORTIFY（需配合 -O，故只放在发布选项里）
        -D_FORTIFY_SOURCE=2)

set(STELLAR_RELEASE_LINK_OPTIONS
        -flto
        -Wl,--exclude-libs,ALL
        -Wl,--gc-sections
        -Wl,--strip-all
        # ⚠️ 本工程改动（2026-10）：全量 RELRO，GOT 只读（nativecrypto 目标本来就有）
        -Wl,-z,relro
        -Wl,-z,now)

# ==========================================================================
# cxx::cxx —— 来自 prefab 包 org.lsposed.libcxx:libcxx（NDK libc++ 预编译）
# --------------------------------------------------------------------------
# prefab 正常工作时生成的 imported target 名为 cxx::cxx，链接
#   <pkg>/modules/cxx/libs/android.<abi>/libcxx.a
# 头文件目录为 <pkg>/modules/cxx/include。
#
# ⚠️ 目录缺失时**只告警、不 FATAL_ERROR**：AGP 的 clean 流程
#    （:app:externalNativeBuildCleanDebug）会在 buildDir 被清掉后重新配置
#    一次 CMake 工程，此时 prefab 目录必然还不存在；若在这里 FATAL_ERROR，
#    `gradlew clean` 会直接失败。真正需要库的构建目标在下面才对缺失报错。
# ==========================================================================
if (NOT DEFINED STELLAR_LIBCXX_PREFAB_DIR OR NOT EXISTS "${STELLAR_LIBCXX_PREFAB_DIR}")
    message(WARNING
            "libcxx prefab 目录不存在（STELLAR_LIBCXX_PREFAB_DIR=${STELLAR_LIBCXX_PREFAB_DIR}）。"
            "若正在执行 clean 可忽略；否则请检查 :app:extractPrefabPackages 任务。")
endif ()

set(_cxx_lib "${STELLAR_LIBCXX_PREFAB_DIR}/modules/cxx/libs/android.${ANDROID_ABI}/libcxx.a")
if (NOT EXISTS "${_cxx_lib}")
    # 只有真正要编译时才报错（clean 阶段不编，见上方说明）
    if (NOT CMAKE_BUILD_TYPE STREQUAL "Clean")
        message(FATAL_ERROR "未找到 libcxx 静态库：${_cxx_lib}")
    endif ()
endif ()

add_library(cxx::cxx STATIC IMPORTED GLOBAL)
set_target_properties(cxx::cxx PROPERTIES
        IMPORTED_LOCATION "${_cxx_lib}"
        INTERFACE_INCLUDE_DIRECTORIES "${STELLAR_LIBCXX_PREFAB_DIR}/modules/cxx/include")

# ==========================================================================
# boringssl::crypto_static —— 来自 prefab 包 io.github.vvb2060.ndk:boringssl
# ==========================================================================
if (NOT DEFINED STELLAR_BORINGSSL_PREFAB_DIR OR NOT EXISTS "${STELLAR_BORINGSSL_PREFAB_DIR}")
    message(WARNING
            "boringssl prefab 目录不存在（STELLAR_BORINGSSL_PREFAB_DIR=${STELLAR_BORINGSSL_PREFAB_DIR}）。"
            "若正在执行 clean 可忽略；否则请检查 :app:extractPrefabPackages 任务。")
endif ()

set(_boring_lib
        "${STELLAR_BORINGSSL_PREFAB_DIR}/modules/crypto_static/libs/android.${ANDROID_ABI}/libcrypto_static.a")
if (NOT EXISTS "${_boring_lib}")
    if (NOT CMAKE_BUILD_TYPE STREQUAL "Clean")
        message(FATAL_ERROR "未找到 BoringSSL 静态库：${_boring_lib}")
    endif ()
endif ()

add_library(boringssl::crypto_static STATIC IMPORTED GLOBAL)
set_target_properties(boringssl::crypto_static PROPERTIES
        IMPORTED_LOCATION "${_boring_lib}"
        INTERFACE_INCLUDE_DIRECTORIES
        "${STELLAR_BORINGSSL_PREFAB_DIR}/modules/crypto_static/include")

# ==========================================================================
# libstellar.so —— 特权服务启动器（本质是可执行文件）
# --------------------------------------------------------------------------
# ⚠️ 刻意取名 libstellar.so：Android 只允许从应用的 nativeLibraryDir
#    （即 APK 内 lib/<abi>/ 解压后的目录）执行原生程序，其它目录会被 SELinux 拦。
#    因此必须同时开启 useLegacyPackaging（见 app/build.gradle），
#    否则 AGP 会把 .so 压缩进 APK 而不解压到 nativeLibraryDir，导致无法执行。
# ==========================================================================
add_executable(libstellar.so
        ${STELLAR_JNI_DIR}/starter.cpp
        ${STELLAR_JNI_DIR}/misc.cpp
        ${STELLAR_JNI_DIR}/selinux.cpp
        ${STELLAR_JNI_DIR}/cgroup.cpp
        ${STELLAR_JNI_DIR}/android.cpp)

set_target_properties(libstellar.so PROPERTIES
        CXX_STANDARD 17
        CXX_STANDARD_REQUIRED ON)

target_include_directories(libstellar.so PRIVATE ${STELLAR_JNI_DIR})
target_compile_options(libstellar.so PRIVATE ${STELLAR_COMMON_COMPILE_OPTIONS})
target_link_libraries(libstellar.so PRIVATE log cxx::cxx)

# ==========================================================================
# libchid.so —— 降权工具（root → uid 2000）
# ==========================================================================
add_executable(libchid.so ${STELLAR_JNI_DIR}/chid.cpp)

set_target_properties(libchid.so PROPERTIES
        CXX_STANDARD 17
        CXX_STANDARD_REQUIRED ON)

target_include_directories(libchid.so PRIVATE ${STELLAR_JNI_DIR})
target_compile_options(libchid.so PRIVATE ${STELLAR_COMMON_COMPILE_OPTIONS})
target_link_libraries(libchid.so PRIVATE log)

# ==========================================================================
# libadb.so —— ADB 无线调试配对
# ==========================================================================
add_library(adb SHARED
        ${STELLAR_JNI_DIR}/adb_pairing.cpp
        ${STELLAR_JNI_DIR}/misc.cpp)

set_target_properties(adb PROPERTIES
        CXX_STANDARD 17
        CXX_STANDARD_REQUIRED ON
        OUTPUT_NAME adb)

target_include_directories(adb PRIVATE ${STELLAR_JNI_DIR})
target_compile_options(adb PRIVATE ${STELLAR_COMMON_COMPILE_OPTIONS})
target_link_libraries(adb PRIVATE log boringssl::crypto_static cxx::cxx)

# ==========================================================================
# librish.so —— rish 终端原生层
# ==========================================================================
add_library(rish SHARED ${STELLAR_JNI_DIR}/rish.cpp)

set_target_properties(rish PROPERTIES
        CXX_STANDARD 17
        CXX_STANDARD_REQUIRED ON
        OUTPUT_NAME rish)

target_include_directories(rish PRIVATE ${STELLAR_JNI_DIR})
target_compile_options(rish PRIVATE ${STELLAR_COMMON_COMPILE_OPTIONS})
target_link_libraries(rish PRIVATE log cxx::cxx)

# ==========================================================================
# 发布构建的额外处理（逐目标，避免污染同级 nativecrypto）
# ==========================================================================
if (NOT CMAKE_BUILD_TYPE STREQUAL "Debug")
    foreach (_target libstellar.so libchid.so adb rish)
        target_compile_options(${_target} PRIVATE ${STELLAR_RELEASE_COMPILE_OPTIONS})
        target_link_options(${_target} PRIVATE ${STELLAR_RELEASE_LINK_OPTIONS})
    endforeach ()

    find_program(STELLAR_STRIP NAMES llvm-strip strip)
    if (STELLAR_STRIP)
        # ⚠️ 这里必须用 **CMake 目标名**，不能用产物文件名：
        #    adb / rish 两个目标通过 OUTPUT_NAME 把产物改名成 libadb.so /
        #    librish.so，但它们的目标名仍是 adb / rish。
        #    add_custom_command(TARGET ...) 只认目标名，写成 libadb.so 会报
        #        CMake Error at stellar/CMakeLists.txt: No TARGET 'libadb.so'
        #        has been created in this directory.
        #    （Debug 构建不进入本分支，所以之前没暴露；Release 才踩到。）
        #    实际路径用生成器表达式 $<TARGET_FILE:...> 取，天然正确。
        foreach (_target libstellar.so libchid.so adb rish)
            add_custom_command(TARGET ${_target} POST_BUILD
                    COMMAND ${STELLAR_STRIP} --remove-section=.comment
                    "$<TARGET_FILE:${_target}>"
                    COMMENT "剥离 $<TARGET_FILE_NAME:${_target}> 的 .comment 段（与上游一致）")
        endforeach ()
    endif ()
else ()
    target_compile_definitions(libstellar.so PRIVATE DEBUG)
    target_compile_definitions(libchid.so PRIVATE DEBUG)
    target_compile_definitions(adb PRIVATE DEBUG)
    target_compile_definitions(rish PRIVATE DEBUG)
endif ()
