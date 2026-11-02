// ============================================================
// 游龙哨兵守护 - Native fork 子进程（双进程守护的 Native 层补充）
// ============================================================
// 职责：
//   fork() 出一个独立 C 子进程（无 Java 环境），实时监视主进程与哨兵进程
//   （guard）的存活状态。发现兄弟进程死亡 → 立即写入标记文件；
//   Java 层（ForegroundService / ProtectService）轮询标记文件核实后拉起。
//
// 安全说明（fork 后子进程铁律）：
//   - 只使用纯 POSIX 系统调用：kill / sleep / open / write / close /
//     unlink / setsid / _exit，绝不调用 malloc / JNI / Java / Android API
//     （fork 时其他线程可能持有 libc 锁，调用会死锁）
//   - 退出必须用 _exit()，跳过 atexit / Java 清理钩子
//   - 子进程不自启 Java 服务（无 VM 线程），只负责"死亡检测 + 标记"
// ============================================================
#include <jni.h>
#include <sys/types.h>
#include <unistd.h>
#include <signal.h>
#include <fcntl.h>
#include <errno.h>
#include <string.h>

// 标记文件名（与 Java 层约定）
#define SENTINEL_MAIN_DEAD   "sentinel_main_dead"
#define SENTINEL_GUARD_DEAD  "sentinel_guard_dead"

// 哨兵子进程 PID（父进程 guard 内维护）
static volatile pid_t s_child_pid = 0;

// 进程存活检测：kill(pid,0)==0 或 EPERM（存在但无权限）视为存活
static int pid_alive(pid_t pid) {
    if (pid <= 1) return 0;
    if (kill(pid, 0) == 0) return 1;
    return (errno == EPERM) ? 1 : 0;
}

// 拼接路径 dir + '/' + name（栈缓冲区，无 malloc）
static void join_path(char* out, size_t outsz, const char* dir, const char* name) {
    size_t i = 0, j = 0;
    if (dir) { while (dir[j] && i < outsz - 1) out[i++] = dir[j++]; }
    if (i > 0 && out[i - 1] != '/' && i < outsz - 1) out[i++] = '/';
    j = 0;
    if (name) { while (name[j] && i < outsz - 1) out[i++] = name[j++]; }
    out[i] = '\0';
}

// 写标记文件（存在 = 死亡信号）
static void touch_file(const char* path) {
    int fd = open(path, O_WRONLY | O_CREAT | O_TRUNC, 0600);
    if (fd >= 0) { (void)write(fd, "1", 1); close(fd); }
}

// 清除标记文件
static void clear_file(const char* path) {
    (void)unlink(path);
}

// ===== JNI：启动哨兵子进程 =====
JNIEXPORT jint JNICALL gs_startSentinel(JNIEnv* env, jobject thiz,
                                        jint mainPid, jint guardPid, jstring signalDir) {
    (void)thiz;
    const char* dir = (signalDir != NULL) ? env->GetStringUTFChars(signalDir, NULL) : NULL;
    char dirBuf[512];
    dirBuf[0] = '\0';
    if (dir != NULL) {
        strncpy(dirBuf, dir, sizeof(dirBuf) - 1);
        dirBuf[sizeof(dirBuf) - 1] = '\0';
        env->ReleaseStringUTFChars(signalDir, dir);
    }
    char mainPath[1024], guardPath[1024];
    join_path(mainPath, sizeof(mainPath), dirBuf, SENTINEL_MAIN_DEAD);
    join_path(guardPath, sizeof(guardPath), dirBuf, SENTINEL_GUARD_DEAD);

    pid_t pid = fork();
    if (pid < 0) return -1;

    if (pid == 0) {
        // ===== 子进程：独立哨兵（纯 POSIX，无任何 Java/Android 调用）=====
        setsid();
        // 尝试降低自身被杀优先级（失败忽略，不影响主逻辑）
        int oomfd = open("/proc/self/oom_score_adj", O_WRONLY);
        if (oomfd >= 0) { (void)write(oomfd, "-1000", 5); close(oomfd); }
        for (;;) {
            int mainAlive = pid_alive((pid_t)mainPid);
            int guardAlive = pid_alive((pid_t)guardPid);
            if (!mainAlive) touch_file(mainPath); else clear_file(mainPath);
            if (!guardAlive) touch_file(guardPath); else clear_file(guardPath);
            // 两个兄弟都死 → 哨兵失去意义，退出
            if (!mainAlive && !guardAlive) break;
            sleep(2);
        }
        _exit(0);
    }

    // ===== 父进程（guard）：记录新哨兵，清理旧哨兵 =====
    if (s_child_pid > 0 && s_child_pid != pid) {
        kill(s_child_pid, SIGKILL);
    }
    s_child_pid = pid;
    return (jint)pid;
}

// ===== JNI：停止哨兵 =====
JNIEXPORT void JNICALL gs_stopSentinel(JNIEnv* env, jobject thiz) {
    (void)env; (void)thiz;
    if (s_child_pid > 0) {
        kill(s_child_pid, SIGKILL);
        s_child_pid = 0;
    }
}

static const JNINativeMethod kSentinelMethods[] = {
    { "nativeStartSentinel", "(IILjava/lang/String;)I", (void*)gs_startSentinel },
    { "nativeStopSentinel",  "()V",                     (void*)gs_stopSentinel },
};

// 由 NativeCrypto.cpp 的 JNI_OnLoad 调用（同一 SO）
extern "C" int register_guard_sentinel(JNIEnv* env) {
    jclass cls = env->FindClass("com/youlong/hd/GuardNative");
    if (cls == NULL) {
        env->ExceptionClear();
        return -1;
    }
    int rc = env->RegisterNatives(cls, kSentinelMethods,
                                  sizeof(kSentinelMethods) / sizeof(kSentinelMethods[0]));
    env->DeleteLocalRef(cls);
    return rc;
}
