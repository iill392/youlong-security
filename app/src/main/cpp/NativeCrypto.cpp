// ============================================================
// 游龙 Native 加密核心 v1.0 - YouLong Native Crypto Core
// ============================================================
// 职责：
//   1. 密钥派生 KDF：SHA-256(种子密钥 + HMAC盐 + 签名证书指纹)
//   2. AES-256-GCM 解密（BoringSSL/OpenSSL 或系统 EVP）
//   3. 种子密钥以加密常量存储（非明文），运行时在 native 层解密
//   4. 反调试/反注入：TracerPid 只读检测 + maps 恶意注入扫描（供 Java 层调用）
//      ⚠️ 仅只读检测，**不修改自身 ptrace 状态**（PTRACE_TRACEME 已移除，见下）
//
// 安全设计：
//   - Java 层不再持有任何密钥字节，仅持有证书指纹（动态）
//   - 种子密钥在 SO 内以 XOR+位移混淆常量存储，grep/hexdump 不可见
//   - 解密后的明文立即用于上层，本层不缓存
//
// JNI 接口：
//   Java_com_youlong_hd_NativeCrypto_deriveKey(
//       byte[] signatureFingerprint /* 32B 证书指纹或空 */) -> byte[32] AES-256 密钥
//   Java_com_youlong_hd_NativeCrypto_decrypt(
//       byte[] iv, byte[] ciphertext, byte[] signatureFingerprint) -> byte[] 明文
//   Java_com_youlong_hd_NativeCrypto_isTracerAttached() -> boolean TracerPid 只读检测
//       （仅供环境信息记录，Java 层不据此报复崩溃，详见函数上方注释）
//   Java_com_youlong_hd_NativeCrypto_detectFrida() -> boolean 反注入
// ============================================================

#include <jni.h>
#include <stdio.h>
#include <string.h>
#include <stdlib.h>
#include <stdint.h>
#include <sys/types.h>
#include <unistd.h>

// ---- 纯 C 自实现加密核心（零外部依赖）----
//   SHA-256 / AES-256 / GCM(GHASH+CTR) 全部在本文件内实现
//   1) 不依赖预编译 OpenSSL（NDK 不内置，避免 ABI/版本坑）
//   2) 自研实现的逆向难度显著高于调用 OpenSSL（无符号/无特征）
//   3) 内核只暴露 3 个 JNI 入口，反编译面最小

// ---- Android 日志（仅加密核心错误时使用，检测路径不打日志）----
#include <android/log.h>
#define LOG_TAG "YLN"
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

// ---- 纯 C 自实现加密核心（零外部依赖，含单元测试可移植）----
//   SHA-256 / AES-256 / GCM / 混淆种子存储 / 密钥派生 全部在 crypto_core.h
//   1) 不依赖预编译 OpenSSL（NDK 不内置，避免 ABI/版本坑）
//   2) 自研实现的逆向难度显著高于调用 OpenSSL（无符号/无特征）
//   3) 内核只暴露 3 个 JNI 入口，反编译面最小
#include "crypto_core.h"

// ============================================================
// 反调试：TracerPid 只读检测（读取 /proc/self/status）
//
// ⚠️ 只读、无副作用，**仅用于环境信息记录**，Java 层不再据此报复崩溃：
//    TracerPid 非 0 在 Android 上多由厂商 ROM 加固 / 无障碍 / 性能工具 /
//    沙箱多开引起，无法区分真调试器，据此崩溃会误杀正常用户。
//    （本文件已不再调用 ptrace(PTRACE_TRACEME)，详见下方"已移除"注释块）
// ============================================================
static int check_tracer_pid() {
    FILE* f = fopen("/proc/self/status", "r");
    if (f == NULL) return 0;
    char line[256];
    int tracer = 0;
    while (fgets(line, sizeof(line), f)) {
        if (strncmp(line, "TracerPid:", 10) == 0) {
            tracer = atoi(line + 10);
            break;
        }
    }
    fclose(f);
    return (tracer != 0) ? 1 : 0;
}

// ============================================================
// 反注入：/proc/self/maps 扫描 Frida/Xposed 特征
// 特征串 XOR 0x7F 混淆存储、运行时解码 —— strings/grep 提取不到明文
// 静默检测：命中不打日志（避免被 hook 定位检测点）
// ============================================================
static int detect_injection() {
    FILE* f = fopen("/proc/self/maps", "r");
    if (f == NULL) return 0;
    char line[512];
    int suspicious = 0;
    // XOR 0x7F 编码的特征串（与 Java 层 StrX 的 0x5A 不同，双密钥）
    static const uint8_t enc[][10] = {
        {0x19,0x0D,0x16,0x1B,0x1E},                          // frida
        {0x18,0x1E,0x1B,0x18,0x1A,0x0B},                     // gadget
        {0x07,0x0F,0x10,0x0C,0x1A,0x1B},                     // xposed
        {0x0C,0x0A,0x1D,0x0C,0x0B,0x0D,0x1E,0x0B,0x1A},      // substrate
        {0x13,0x16,0x1D,0x14,0x1A,0x0D,0x11,0x1E,0x13},      // libkernal
        {0x13,0x16,0x1D,0x1B,0x09,0x12},                     // libdvm
    };
    static const int enc_len[] = {5, 6, 6, 9, 9, 6};
    char pat[16];
    while (fgets(line, sizeof(line), f)) {
        for (int i = 0; i < 6; i++) {
            for (int j = 0; j < enc_len[i]; j++) pat[j] = (char)(enc[i][j] ^ 0x7F);
            pat[enc_len[i]] = '\0';
            if (strstr(line, pat) != NULL) { suspicious = 1; break; }
        }
        if (suspicious) break;
    }
    fclose(f);
    memset(pat, 0, sizeof(pat));
    return suspicious;
}

// ============================================================
// JNI 导出 —— 全部改为 static + RegisterNatives 动态注册
// 目的：隐藏 Java_com_youlong_hd_* 导出符号，
//       .dynsym 仅保留 JNI_OnLoad，攻击者 nm/readelf 看不到接口名
// ============================================================

// isNativeLoaded() -> boolean （SO 加载成功后恒为 true）
static jboolean nc_isNativeLoaded(JNIEnv* env, jobject thiz) {
    (void)env; (void)thiz;
    return JNI_TRUE;
}

// deriveSeedForFallback() -> byte[16]（Java 兜底用，返回混淆存储的种子）
static jbyteArray nc_deriveSeedForFallback(JNIEnv* env, jobject thiz) {
    (void)thiz;
    const uint8_t* seed = get_seed_key();
    jbyteArray result = env->NewByteArray(16);
    if (result != NULL) {
        env->SetByteArrayRegion(result, 0, 16, (const jbyte*)seed);
    }
    return result;
}

// deriveSaltForFallback() -> byte[16]（Java 兜底用，返回混淆存储的盐）
static jbyteArray nc_deriveSaltForFallback(JNIEnv* env, jobject thiz) {
    (void)thiz;
    const uint8_t* salt = get_hmac_salt();
    jbyteArray result = env->NewByteArray(16);
    if (result != NULL) {
        env->SetByteArrayRegion(result, 0, 16, (const jbyte*)salt);
    }
    return result;
}

// deriveKey(byte[] fingerprint) -> byte[32]
static jbyteArray nc_deriveKey(JNIEnv* env, jobject thiz, jbyteArray fingerprint) {
    (void)thiz;
    jsize fpLen = 0;
    jbyte* fp = NULL;
    if (fingerprint != NULL) {
        fpLen = env->GetArrayLength(fingerprint);
        if (fpLen > 0) {
            fp = env->GetByteArrayElements(fingerprint, NULL);
        }
    }

    uint8_t key[32];
    derive_key((const uint8_t*)fp, (int)fpLen, key);

    if (fp != NULL) env->ReleaseByteArrayElements(fingerprint, fp, JNI_ABORT);

    jbyteArray result = env->NewByteArray(32);
    if (result != NULL) {
        env->SetByteArrayRegion(result, 0, 32, (const jbyte*)key);
    }
    secure_clear(key, sizeof(key));
    return result;
}

// decrypt(byte[] iv, byte[] ciphertext, byte[] fingerprint) -> byte[] 明文
static jbyteArray nc_decrypt(
        JNIEnv* env, jobject thiz,
        jbyteArray iv, jbyteArray ciphertext, jbyteArray fingerprint) {
    (void)thiz;
    if (iv == NULL || ciphertext == NULL) return NULL;

    jsize ivLen = env->GetArrayLength(iv);
    jsize ctLen = env->GetArrayLength(ciphertext);
    if (ivLen != 12 || ctLen <= 16) return NULL;

    jbyte* ivBuf = env->GetByteArrayElements(iv, NULL);
    jbyte* ctBuf = env->GetByteArrayElements(ciphertext, NULL);

    jsize fpLen = 0;
    jbyte* fp = NULL;
    if (fingerprint != NULL) {
        fpLen = env->GetArrayLength(fingerprint);
        if (fpLen > 0) {
            fp = env->GetByteArrayElements(fingerprint, NULL);
        }
    }

    uint8_t key[32];
    derive_key((const uint8_t*)fp, (int)fpLen, key);

    int ptLen = ctLen - 16;
    jbyteArray result = NULL;
    uint8_t* pt = (uint8_t*)malloc((size_t)ptLen);
    if (pt != NULL) {
        int decLen = aes_gcm_decrypt(key, (const uint8_t*)ivBuf,
                                     (const uint8_t*)ctBuf, (int)ctLen,
                                     pt, ptLen);
        if (decLen >= 0) {
            result = env->NewByteArray(decLen);
            if (result != NULL) {
                env->SetByteArrayRegion(result, 0, decLen, (const jbyte*)pt);
            }
        }
        secure_clear(pt, (size_t)ptLen);
        free(pt);
    }

    env->ReleaseByteArrayElements(iv, ivBuf, JNI_ABORT);
    env->ReleaseByteArrayElements(ciphertext, ctBuf, JNI_ABORT);
    if (fp != NULL) env->ReleaseByteArrayElements(fingerprint, fp, JNI_ABORT);
    secure_clear(key, sizeof(key));
    return result;
}

// isTracerAttached() -> boolean
static jboolean nc_isTracerAttached(JNIEnv* env, jobject thiz) {
    (void)env; (void)thiz;
    return check_tracer_pid() ? JNI_TRUE : JNI_FALSE;
}

// detectFrida() -> boolean
static jboolean nc_detectFrida(JNIEnv* env, jobject thiz) {
    (void)env; (void)thiz;
    return detect_injection() ? JNI_TRUE : JNI_FALSE;
}

// ============================================================
// ⛔ 已移除：ptrace(PTRACE_TRACEME)「自占坑」反调试
//
// v9.0 及之前在 JNI_OnLoad 里执行 ptrace(PTRACE_TRACEME)，期望"占坑"后
// 调试器 attach 失败。这在 Android 上是**反模式**，会造成「启动即无响应」：
//
//   1) TRACEME = 请求"父进程跟踪我"，而 App 进程的父进程是 zygote。
//      zygote 只负责 fork，从不履行 tracer 的 wait/continue 职责 →
//      进程一旦进入 ptrace-stop 就是**永久冻结**（SIGCONT 亦无效）；
//      主线程被停 → ANR「应用无响应」，:guard 前台服务超时被系统清理。
//   2) TRACEME 成功后 /proc/self/status 的 TracerPid 变成 zygote 的 pid，
//      check_tracer_pid() 会把"TracerPid != 0"判定为"被调试"，
//      而 YouLongShield.init() 据此调用 scheduleRetaliation(3,15) 随机崩溃。
//      两者叠加 = 用户看到的「3~15 秒卡死 + 应用无响应」。
//   3) 进程处于"已被追踪"状态时 debuggerd 无法抓现场 →
//      此类崩溃在崩溃统计里是**隐形的**，极难自查。
//
// 真机案例：realme Neo7 Turbo (RMX5062 / ColorOS / Android 15)，
//           TracerPid = 1144 (= zygote64)；去掉本调用后症状完全消失。
//
// 现行反调试策略 = **纯只读检测**（不修改自身 ptrace 状态）：
//   - check_tracer_pid()  读 /proc/self/status 的 TracerPid（仅记录，不报复）
//   - detect_injection()  扫 /proc/self/maps 的 Frida/Xposed 特征
//   - Java 层 Debug.isDebuggerConnected() / Frida 端口与 D-Bus 握手
// ============================================================

// ============================================================
// JNI_OnLoad：动态注册全部 native 方法（无任何反调试副作用钩子）
// ============================================================
static const JNINativeMethod kNativeMethods[] = {
    { "isNativeLoaded",          "()Z",    (void*)nc_isNativeLoaded },
    { "deriveSeedForFallback",   "()[B",   (void*)nc_deriveSeedForFallback },
    { "deriveSaltForFallback",   "()[B",   (void*)nc_deriveSaltForFallback },
    { "deriveKey",               "([B)[B", (void*)nc_deriveKey },
    { "decrypt",                 "([B[B[B)[B", (void*)nc_decrypt },
    { "isTracerAttached",        "()Z",    (void*)nc_isTracerAttached },
    { "detectFrida",             "()Z",    (void*)nc_detectFrida },
};

// 哨兵守护（GuardSentinel.cpp）：fork 子进程监视兄弟进程存活
extern "C" int register_guard_sentinel(JNIEnv* env);

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    (void)reserved;
    // ⛔ 严禁在此调用 ptrace(PTRACE_TRACEME) 之类"自占坑"反调试：
    //    在 Android 上它会让进程被 zygote 名义追踪，收到信号后永久 ptrace-stop
    //    → ANR「应用无响应」+ 服务被系统清理。机理详见上方注释块。

    JNIEnv* env = NULL;
    if (vm->GetEnv((void**)&env, JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }
    jclass cls = env->FindClass("com/youlong/hd/NativeCrypto");
    if (cls == NULL) {
        return JNI_ERR;
    }
    if (env->RegisterNatives(cls, kNativeMethods,
                             sizeof(kNativeMethods) / sizeof(kNativeMethods[0])) != JNI_OK) {
        env->DeleteLocalRef(cls);
        return JNI_ERR;
    }
    env->DeleteLocalRef(cls);

    // 注册哨兵守护 native 方法（失败不影响主加密核心）
    register_guard_sentinel(env);
    return JNI_VERSION_1_6;
}

// ============================================================
// 以下自实现加密核心已迁移至 crypto_core.h（上方 #include）
// 此处仅留档，编译期被 #if 0 排除，避免重复定义
// ============================================================
#if 0
// ============================================================
// ================ 自实现加密核心（纯 C） =====================
// ============================================================

// ------------------------------------------------------------
// SHA-256（FIPS 180-4 标准实现）
// ------------------------------------------------------------
static const uint32_t SHA256_K[64] = {
    0x428a2f98,0x71374491,0xb5c0fbcf,0xe9b5dba5,0x3956c25b,0x59f111f1,0x923f82a4,0xab1c5ed5,
    0xd807aa98,0x12835b01,0x243185be,0x550c7dc3,0x72be5d74,0x80deb1fe,0x9bdc06a7,0xc19bf174,
    0xe49b69c1,0xefbe4786,0x0fc19dc6,0x240ca1cc,0x2de92c6f,0x4a7484aa,0x5cb0a9dc,0x76f988da,
    0x983e5152,0xa831c66d,0xb00327c8,0xbf597fc7,0xc6e00bf3,0xd5a79147,0x06ca6351,0x14292967,
    0x27b70a85,0x2e1b2138,0x4d2c6dfc,0x53380d13,0x650a7354,0x766a0abb,0x81c2c92e,0x92722c85,
    0xa2bfe8a1,0xa81a664b,0xc24b8b70,0xc76c51a3,0xd192e819,0xd6990624,0xf40e3585,0x106aa070,
    0x19a4c116,0x1e376c08,0x2748774c,0x34b0bcb5,0x391c0cb3,0x4ed8aa4a,0x5b9cca4f,0x682e6ff3,
    0x748f82ee,0x78a5636f,0x84c87814,0x8cc70208,0x90befffa,0xa4506ceb,0xbef9a3f7,0xc67178f2
};

static uint32_t rotr32(uint32_t x, int n) { return (x >> n) | (x << (32 - n)); }

static void sha256_transform(sha256_context* ctx, const uint8_t* block) {
    uint32_t w[64];
    for (int i = 0; i < 16; i++) {
        w[i] = ((uint32_t)block[i * 4] << 24) | ((uint32_t)block[i * 4 + 1] << 16) |
               ((uint32_t)block[i * 4 + 2] << 8) | (uint32_t)block[i * 4 + 3];
    }
    for (int i = 16; i < 64; i++) {
        uint32_t s0 = rotr32(w[i - 15], 7) ^ rotr32(w[i - 15], 18) ^ (w[i - 15] >> 3);
        uint32_t s1 = rotr32(w[i - 2], 17) ^ rotr32(w[i - 2], 19) ^ (w[i - 2] >> 10);
        w[i] = w[i - 16] + s0 + w[i - 7] + s1;
    }
    uint32_t a = ctx->state[0], b = ctx->state[1], c = ctx->state[2], d = ctx->state[3];
    uint32_t e = ctx->state[4], f = ctx->state[5], g = ctx->state[6], h = ctx->state[7];
    for (int i = 0; i < 64; i++) {
        uint32_t S1 = rotr32(e, 6) ^ rotr32(e, 11) ^ rotr32(e, 25);
        uint32_t ch = (e & f) ^ (~e & g);
        uint32_t t1 = h + S1 + ch + SHA256_K[i] + w[i];
        uint32_t S0 = rotr32(a, 2) ^ rotr32(a, 13) ^ rotr32(a, 22);
        uint32_t maj = (a & b) ^ (a & c) ^ (b & c);
        uint32_t t2 = S0 + maj;
        h = g; g = f; f = e; e = d + t1; d = c; c = b; b = a; a = t1 + t2;
    }
    ctx->state[0] += a; ctx->state[1] += b; ctx->state[2] += c; ctx->state[3] += d;
    ctx->state[4] += e; ctx->state[5] += f; ctx->state[6] += g; ctx->state[7] += h;
}

static void sha256_init(sha256_context* ctx) {
    ctx->state[0] = 0x6a09e667; ctx->state[1] = 0xbb67ae85;
    ctx->state[2] = 0x3c6ef372; ctx->state[3] = 0xa54ff53a;
    ctx->state[4] = 0x510e527f; ctx->state[5] = 0x9b05688c;
    ctx->state[6] = 0x1f83d9ab; ctx->state[7] = 0x5be0cd19;
    ctx->bitlen = 0; ctx->buflen = 0;
}

static void sha256_update(sha256_context* ctx, const uint8_t* data, size_t len) {
    for (size_t i = 0; i < len; i++) {
        ctx->buffer[ctx->buflen++] = data[i];
        if (ctx->buflen == 64) {
            sha256_transform(ctx, ctx->buffer);
            ctx->bitlen += 512;
            ctx->buflen = 0;
        }
    }
}

static void sha256_final(sha256_context* ctx, uint8_t out[32]) {
    uint64_t bitlen = ctx->bitlen + (uint64_t)ctx->buflen * 8;
    ctx->buffer[ctx->buflen++] = 0x80;
    if (ctx->buflen > 56) {
        while (ctx->buflen < 64) ctx->buffer[ctx->buflen++] = 0;
        sha256_transform(ctx, ctx->buffer);
        ctx->buflen = 0;
    }
    while (ctx->buflen < 56) ctx->buffer[ctx->buflen++] = 0;
    for (int i = 0; i < 8; i++) {
        ctx->buffer[63 - i] = (uint8_t)(bitlen >> (8 * i));
    }
    sha256_transform(ctx, ctx->buffer);
    for (int i = 0; i < 8; i++) {
        out[i * 4]     = (uint8_t)(ctx->state[i] >> 24);
        out[i * 4 + 1] = (uint8_t)(ctx->state[i] >> 16);
        out[i * 4 + 2] = (uint8_t)(ctx->state[i] >> 8);
        out[i * 4 + 3] = (uint8_t)(ctx->state[i]);
    }
}

// ------------------------------------------------------------
// AES-256（Rijndael，Nk=8 / Nr=14；仅需加密方向）
// ------------------------------------------------------------
static const uint8_t SBOX[256] = {
    0x63,0x7c,0x77,0x7b,0xf2,0x6b,0x6f,0xc5,0x30,0x01,0x67,0x2b,0xfe,0xd7,0xab,0x76,
    0xca,0x82,0xc9,0x7d,0xfa,0x59,0x47,0xf0,0xad,0xd4,0xa2,0xaf,0x9c,0xa4,0x72,0xc0,
    0xb7,0xfd,0x93,0x26,0x36,0x3f,0xf7,0xcc,0x34,0xa5,0xe5,0xf1,0x71,0xd8,0x31,0x15,
    0x04,0xc7,0x23,0xc3,0x18,0x96,0x05,0x9a,0x07,0x12,0x80,0xe2,0xeb,0x27,0xb2,0x75,
    0x09,0x83,0x2c,0x1a,0x1b,0x6e,0x5a,0xa0,0x52,0x3b,0xd6,0xb3,0x29,0xe3,0x2f,0x84,
    0x53,0xd1,0x00,0xed,0x20,0xfc,0xb1,0x5b,0x6a,0xcb,0xbe,0x39,0x4a,0x4c,0x58,0xcf,
    0xd0,0xef,0xaa,0xfb,0x43,0x4d,0x33,0x85,0x45,0xf9,0x02,0x7f,0x50,0x3c,0x9f,0xa8,
    0x51,0xa3,0x40,0x8f,0x92,0x9d,0x38,0xf5,0xbc,0xb6,0xda,0x21,0x10,0xff,0xf3,0xd2,
    0xcd,0x0c,0x13,0xec,0x5f,0x97,0x44,0x17,0xc4,0xa7,0x7e,0x3d,0x64,0x5d,0x19,0x73,
    0x60,0x81,0x4f,0xdc,0x22,0x2a,0x90,0x88,0x46,0xee,0xb8,0x14,0xde,0x5e,0x0b,0xdb,
    0xe0,0x32,0x3a,0x0a,0x49,0x06,0x24,0x5c,0xc2,0xd3,0xac,0x62,0x91,0x95,0xe4,0x79,
    0xe7,0xc8,0x37,0x6d,0x8d,0xd5,0x4e,0xa9,0x6c,0x56,0xf4,0xea,0x65,0x7a,0xae,0x08,
    0xba,0x78,0x25,0x2e,0x1c,0xa6,0xb4,0xc6,0xe8,0xdd,0x74,0x1f,0x4b,0xbd,0x8b,0x8a,
    0x70,0x3e,0xb5,0x66,0x48,0x03,0xf6,0x0e,0x61,0x35,0x57,0xb9,0x86,0xc1,0x1d,0x9e,
    0xe1,0xf8,0x98,0x11,0x69,0xd9,0x8e,0x94,0x9b,0x1e,0x87,0xe9,0xce,0x55,0x28,0xdf,
    0x8c,0xa1,0x89,0x0d,0xbf,0xe6,0x42,0x68,0x41,0x99,0x2d,0x0f,0xb0,0x54,0xbb,0x16
};

static uint8_t xtime(uint8_t x) {
    return (uint8_t)((x << 1) ^ ((x & 0x80) ? 0x1b : 0x00));
}

static void aes256_key_expansion(const uint8_t* key, uint8_t* rk /* 240B */) {
    for (int i = 0; i < 8; i++) {
        rk[i * 4] = key[i * 4]; rk[i * 4 + 1] = key[i * 4 + 1];
        rk[i * 4 + 2] = key[i * 4 + 2]; rk[i * 4 + 3] = key[i * 4 + 3];
    }
    uint8_t rcon = 1;
    for (int i = 8; i < 60; i++) {
        uint8_t t[4];
        for (int j = 0; j < 4; j++) t[j] = rk[(i - 1) * 4 + j];
        if (i % 8 == 0) {
            uint8_t tmp = t[0]; t[0] = t[1]; t[1] = t[2]; t[2] = t[3]; t[3] = tmp; // RotWord
            for (int j = 0; j < 4; j++) t[j] = SBOX[t[j]];                          // SubWord
            t[0] ^= rcon;
            rcon = xtime(rcon);
        } else if (i % 8 == 4) {
            for (int j = 0; j < 4; j++) t[j] = SBOX[t[j]];
        }
        for (int j = 0; j < 4; j++) rk[i * 4 + j] = (uint8_t)(rk[(i - 8) * 4 + j] ^ t[j]);
    }
}

static void aes_add_round_key(uint8_t* state, const uint8_t* rk) {
    for (int i = 0; i < 16; i++) state[i] ^= rk[i];
}

static void aes_sub_bytes(uint8_t* state) {
    for (int i = 0; i < 16; i++) state[i] = SBOX[state[i]];
}

static void aes_shift_rows(uint8_t* s) {
    uint8_t t;
    t = s[1];  s[1] = s[5];  s[5] = s[9];  s[9] = s[13];  s[13] = t;  // 行1 左移1
    t = s[2];  s[2] = s[10]; s[10] = t;                                 // 行2 左移2
    t = s[6];  s[6] = s[14]; s[14] = t;
    t = s[15]; s[15] = s[11]; s[11] = s[7]; s[7] = s[3]; s[3] = t;      // 行3 左移3
}

static void aes_mix_columns(uint8_t* s) {
    for (int c = 0; c < 4; c++) {
        uint8_t* p = s + c * 4;
        uint8_t a0 = p[0], a1 = p[1], a2 = p[2], a3 = p[3];
        uint8_t t = (uint8_t)(a0 ^ a1 ^ a2 ^ a3);
        p[0] = (uint8_t)(a0 ^ t ^ xtime((uint8_t)(a0 ^ a1)));
        p[1] = (uint8_t)(a1 ^ t ^ xtime((uint8_t)(a1 ^ a2)));
        p[2] = (uint8_t)(a2 ^ t ^ xtime((uint8_t)(a2 ^ a3)));
        p[3] = (uint8_t)(a3 ^ t ^ xtime((uint8_t)(a3 ^ a0)));
    }
}

static void aes256_encrypt_block(const uint8_t key[32], const uint8_t in[16], uint8_t out[16]) {
    uint8_t rk[240];
    aes256_key_expansion(key, rk);
    uint8_t state[16];
    memcpy(state, in, 16);
    aes_add_round_key(state, rk);
    for (int round = 1; round <= 13; round++) {
        aes_sub_bytes(state);
        aes_shift_rows(state);
        aes_mix_columns(state);
        aes_add_round_key(state, rk + round * 16);
    }
    aes_sub_bytes(state);
    aes_shift_rows(state);
    aes_add_round_key(state, rk + 14 * 16);
    memcpy(out, state, 16);
    secure_clear(rk, sizeof(rk));
    secure_clear(state, sizeof(state));
}

// ------------------------------------------------------------
// GCM 辅助：GF(2^128) 乘法（NIST SP 800-38D, MSB-first）
// ------------------------------------------------------------
static void gcm_shift_left(uint8_t* v) {
    uint8_t carry = 0;
    for (int i = 15; i >= 0; i--) {
        uint8_t next_carry = (uint8_t)((v[i] >> 7) & 1);
        v[i] = (uint8_t)((v[i] << 1) | carry);
        carry = next_carry;
    }
}

static void gcm_mul(uint8_t z[16], const uint8_t x[16], const uint8_t y[16]) {
    uint8_t v[16];
    memcpy(v, x, 16);
    memset(z, 0, 16);
    for (int i = 0; i < 128; i++) {
        int ybit = (y[i >> 3] >> (7 - (i & 7))) & 1;
        if (ybit) {
            for (int j = 0; j < 16; j++) z[j] ^= v[j];
        }
        uint8_t top = (uint8_t)((v[0] >> 7) & 1);
        gcm_shift_left(v);
        if (top) v[0] ^= 0xE1; // 规约多项式 R = 0xE1 << 120
    }
    secure_clear(v, sizeof(v));
}
#endif // 0 - 加密核心已迁移至 crypto_core.h
