// ============================================================
// 游龙哨兵守护 - Native fork 子进程（双进程守护的 Native 层补充）
// ============================================================
// 职责：
//   fork() 出一个独立 C 子进程（无 Java 环境），实时监视主进程与哨兵进程
//   （guard）的存活状态。发现兄弟进程死亡 → 立即写入标记文件；
//   Java 层（ForegroundService / ProtectService）轮询标记文件核实后拉起。
//
// 安全说明（fork 后子进程铁律）：
//   - 只使用纯 POSIX 系统调用：kill / sleep / open / read / write / close /
//     unlink / setsid / getrlimit / _exit，绝不调用 malloc / JNI / Java /
//     Android API / stdio（snprintf 等）（fork 时其他线程可能持有 libc 锁，
//     调用会死锁）
//   - 退出必须用 _exit()，跳过 atexit / Java 清理钩子
//   - 子进程不自启 Java 服务（无 VM 线程），只负责"死亡检测 + 标记"
//
// 2026-10 加固（本工程改动）：
//   1) 存活判定不再只看 kill(pid,0)，改为比对 /proc/<pid>/stat 的 starttime
//      （第 22 字段）。原实现下，被监视进程死亡后其 PID 被系统复用给别的进程时，
//      哨兵会误判"兄弟仍活着"而永不写死亡标记，双进程互拉静默失效。
//   2) 父进程回收旧哨兵时先核对 starttime 再 SIGKILL，避免 PID 已被复用时
//      误杀同 uid 的其他进程。
//   3) 子进程 fork 后关闭继承来的多余 fd（应用进程持有大量 binder/socket fd），
//      避免应用进程死亡后这些 fd 仍被哨兵占用。注意需先关掉子进程的 fdsan 检查
//      （弱符号探测，API<29 上不可用则整段跳过），详见 close_inherited_fds()。
// ============================================================
#include <jni.h>
#include <sys/types.h>
#include <sys/resource.h>
#include <unistd.h>
#include <signal.h>
#include <fcntl.h>
#include <errno.h>
#include <string.h>

// 标记文件名（与 Java 层约定）
#define SENTINEL_MAIN_DEAD   "sentinel_main_dead"
#define SENTINEL_GUARD_DEAD  "sentinel_guard_dead"

// 哨兵子进程 PID（父进程 guard 内维护）+ 其启动时间（用于防 PID 复用）
static volatile pid_t s_child_pid = 0;
static volatile unsigned long long s_child_start = 0;

// ---- 无锁路径构造 "/proc/<pid>/stat" ----
// fork 之后禁止 stdio（其他线程可能持有 libc 锁），因此自己拼字符串。
static void build_stat_path(char* out, size_t outsz, pid_t pid) {
    if (outsz == 0) return;
    size_t i = 0;
    const char* prefix = "/proc/";
    for (int k = 0; prefix[k] != '\0' && i < outsz - 1; k++) out[i++] = prefix[k];

    long v = (long) pid;
    if (v < 0) v = -v;
    char digits[12];
    int n = 0;
    do {
        digits[n++] = (char) ('0' + (int) (v % 10));
        v /= 10;
    } while (v != 0 && n < (int) sizeof(digits));
    while (n > 0 && i < outsz - 1) out[i++] = digits[--n];

    const char* suffix = "/stat";
    for (int k = 0; suffix[k] != '\0' && i < outsz - 1; k++) out[i++] = suffix[k];
    out[i] = '\0';
}

// 读取 /proc/<pid>/stat 的第 22 个字段（starttime，单位 jiffies）。
// 读不到（进程不存在 / 无权限）返回 0。全程只用 open/read/close，无 malloc / stdio。
//
// 说明：comm 字段（第 2 个）可能含空格与括号，因此先定位最后一个 ')'，
//       从其后开始数 token：第 1 个是 state（第 3 字段），第 20 个即 starttime（第 22 字段）。
static unsigned long long proc_starttime(pid_t pid) {
    if (pid <= 1) return 0;

    char path[64];
    build_stat_path(path, sizeof(path), pid);

    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return 0;

    char buf[512];
    ssize_t n;
    do { n = read(fd, buf, sizeof(buf) - 1); } while (n < 0 && errno == EINTR);
    close(fd);
    if (n <= 0) return 0;
    buf[n] = '\0';

    size_t last = 0;
    int found = 0;
    for (size_t i = 0; i < (size_t) n; i++) {
        if (buf[i] == ')') { last = i; found = 1; }
    }
    if (!found) return 0;

    size_t i = last + 1;
    int field = 2;                       // 已处理 pid(1) 与 comm(2)
    while (field < 22) {
        while (i < (size_t) n && buf[i] == ' ') i++;
        if (i >= (size_t) n) return 0;
        size_t start = i;
        while (i < (size_t) n && buf[i] != ' ') i++;
        field++;
        if (field == 22) {
            unsigned long long v = 0;
            for (size_t k = start; k < i; k++) {
                char c = buf[k];
                if (c < '0' || c > '9') return 0;
                v = v * 10ULL + (unsigned long long) (c - '0');
            }
            return v;
        }
    }
    return 0;
}

// 进程存活检测：以 starttime 为准，避免 PID 复用造成的误判。
//   expect_start == 0 表示调用方未取到基准启动时间，此时退化为"进程存在即存活"。
static int pid_alive(pid_t pid, unsigned long long expect_start) {
    if (pid <= 1) return 0;
    unsigned long long st = proc_starttime(pid);
    if (st == 0) return 0;                                    // 读不到 /proc/<pid>/stat → 已死亡
    if (expect_start != 0 && st != expect_start) return 0;    // PID 已被复用
    return 1;
}

// fork 后关闭继承来的多余 fd（保留 0/1/2）。
//
// ⚠️ Android 特有陷阱：API 29+ 的应用默认启用 **fdsan**，父进程那些由 Java 侧
//    登记过的 fd（socket / ParcelFileDescriptor / binder …）在子进程里仍带着
//    owner tag，直接 close() 可能触发 fdsan 致命报错，把哨兵子进程自己搞崩 ——
//    那等于拿"守护可用性"去换一个低危的 fd 占用问题，得不偿失。
//    因此这里用**弱符号**探测：只有能把子进程的 fdsan 检查先关掉，才执行关闭；
//    探测不到该符号（API < 29）就整段跳过，保持与旧实现一致。
//
//    注：这里按 ABI（enum 即 int）自行声明而不 include <android/fdsan.h>，
//    避免与 NDK 头里的同名声明产生类型重定义；弱符号在 API<29 上解析为 nullptr。
extern "C" int android_fdsan_set_error_level(int) __attribute__((weak));
#define YL_FDSAN_ERROR_LEVEL_DISABLED 0

static void close_inherited_fds(void) {
    if (!android_fdsan_set_error_level) return;                 // API < 29：跳过
    (void) android_fdsan_set_error_level(YL_FDSAN_ERROR_LEVEL_DISABLED);

    long maxfd = 4096;
    struct rlimit rl;
    if (getrlimit(RLIMIT_NOFILE, &rl) == 0 && rl.rlim_cur != RLIM_INFINITY) {
        long cur = (long) rl.rlim_cur;
        if (cur > 0 && cur < 65536) maxfd = cur;   // 上限保护，避免极端 rlimit 下长时间循环
    }
    for (long fd = 3; fd < maxfd; fd++) {
        (void) close((int) fd);
    }
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

    // 在 fork 之前取好两个兄弟的启动时间：子进程直接继承（避免 fork 后再读，
    // 也避免兄弟进程在 fork 与首次检查之间被杀导致基准丢失）。
    unsigned long long mainStart = proc_starttime((pid_t) mainPid);
    unsigned long long guardStart = proc_starttime((pid_t) guardPid);

    pid_t pid = fork();
    if (pid < 0) return -1;

    if (pid == 0) {
        // ===== 子进程：独立哨兵（无 Java/ART、无 stdio；只用系统调用）=====
        // 先关掉从父进程继承来的一堆 fd（binder/socket/...），否则应用进程死亡后
        // 哨兵会替它一直占着这些 fd。
        close_inherited_fds();
        setsid();
        // 尝试降低自身被杀优先级（失败忽略，不影响主逻辑）
        int oomfd = open("/proc/self/oom_score_adj", O_WRONLY);
        if (oomfd >= 0) { (void)write(oomfd, "-1000", 5); close(oomfd); }
        for (;;) {
            int mainAlive = pid_alive((pid_t) mainPid, mainStart);
            int guardAlive = pid_alive((pid_t) guardPid, guardStart);
            if (!mainAlive) touch_file(mainPath); else clear_file(mainPath);
            if (!guardAlive) touch_file(guardPath); else clear_file(guardPath);
            // 两个兄弟都死 → 哨兵失去意义，退出
            if (!mainAlive && !guardAlive) break;
            sleep(2);
        }
        _exit(0);
    }

    // ===== 父进程（guard）：记录新哨兵，清理旧哨兵 =====
    // 回收旧哨兵前先核对 starttime：若它早已退出、PID 被系统复用给别的进程，
    // 直接 kill 会误杀无辜（同 uid 进程）。核对不上就跳过，交给它自己退出。
    if (s_child_pid > 0 && s_child_pid != pid && s_child_start != 0) {
        if (proc_starttime(s_child_pid) == s_child_start) {
            kill(s_child_pid, SIGKILL);
        }
    }
    s_child_pid = pid;
    s_child_start = proc_starttime(pid);
    return (jint)pid;
}

// ===== JNI：停止哨兵 =====
JNIEXPORT void JNICALL gs_stopSentinel(JNIEnv* env, jobject thiz) {
    (void)env; (void)thiz;
    const pid_t pid = s_child_pid;
    const unsigned long long start = s_child_start;
    s_child_pid = 0;
    s_child_start = 0;
    // 同样先核对 starttime：PID 若已被复用，绝不能 kill（会误杀别的进程）
    if (pid > 0 && start != 0 && proc_starttime(pid) == start) {
        kill(pid, SIGKILL);
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
