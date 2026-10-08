// ============================================================

// ============================================================




//







//









// ============================================================
#include <jni.h>
#include <sys/types.h>
#include <sys/resource.h>
#include <sys/wait.h>
#include <unistd.h>
#include <signal.h>
#include <fcntl.h>
#include <errno.h>
#include <string.h>


#define SENTINEL_MAIN_DEAD   "sentinel_main_dead"
#define SENTINEL_GUARD_DEAD  "sentinel_guard_dead"


// 本目标以 -DANDROID_STL=none 编译（无 C++ STL 头），不能使用 <atomic>；
// 改用 GCC/Clang 内建 __atomic_* 提供跨线程原子读写（无需头文件）。
static volatile pid_t s_child_pid = 0;
static volatile unsigned long long s_child_start = 0;

static pid_t sentinel_load_pid() { return __atomic_load_n(&s_child_pid, __ATOMIC_SEQ_CST); }
static void sentinel_store_pid(pid_t v) { __atomic_store_n(&s_child_pid, v, __ATOMIC_SEQ_CST); }
static unsigned long long sentinel_load_start() { return __atomic_load_n(&s_child_start, __ATOMIC_SEQ_CST); }
static void sentinel_store_start(unsigned long long v) { __atomic_store_n(&s_child_start, v, __ATOMIC_SEQ_CST); }



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



//


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
    int field = 2;                       
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



static int pid_alive(pid_t pid, unsigned long long expect_start) {
    if (pid <= 1) return 0;
    unsigned long long st = proc_starttime(pid);
    if (st == 0) return 0;                                    
    if (expect_start != 0 && st != expect_start) return 0;    
    return 1;
}


//






//


extern "C" int android_fdsan_set_error_level(int) __attribute__((weak));
#define YL_FDSAN_ERROR_LEVEL_DISABLED 0

static void close_inherited_fds(void) {
    // 关 fd 是主职责；fdsan 只在 API 29+ 可用时附带禁用，不能决定关 fd 是否执行
    if (android_fdsan_set_error_level) {
        (void) android_fdsan_set_error_level(YL_FDSAN_ERROR_LEVEL_DISABLED);
    }

    long maxfd = 4096;
    struct rlimit rl;
    if (getrlimit(RLIMIT_NOFILE, &rl) == 0 && rl.rlim_cur != RLIM_INFINITY) {
        long cur = (long) rl.rlim_cur;
        if (cur > 0 && cur < 65536) maxfd = cur;   
    }
    for (long fd = 3; fd < maxfd; fd++) {
        (void) close((int) fd);
    }
}


static void join_path(char* out, size_t outsz, const char* dir, const char* name) {
    size_t i = 0, j = 0;
    if (dir) { while (dir[j] && i < outsz - 1) out[i++] = dir[j++]; }
    if (i > 0 && out[i - 1] != '/' && i < outsz - 1) out[i++] = '/';
    j = 0;
    if (name) { while (name[j] && i < outsz - 1) out[i++] = name[j++]; }
    out[i] = '\0';
}


static void touch_file(const char* path) {
    int fd = open(path, O_WRONLY | O_CREAT | O_TRUNC, 0600);
    if (fd >= 0) { (void)write(fd, "1", 1); close(fd); }
}


static void clear_file(const char* path) {
    (void)unlink(path);
}


extern "C" JNIEXPORT jint JNICALL gs_startSentinel(JNIEnv* env, jobject thiz,
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

    
    
    unsigned long long mainStart = proc_starttime((pid_t) mainPid);
    unsigned long long guardStart = proc_starttime((pid_t) guardPid);

    pid_t pid = fork();
    if (pid < 0) return -1;

    if (pid == 0) {
        
        
        
        close_inherited_fds();
        setsid();
        
        int oomfd = open("/proc/self/oom_score_adj", O_WRONLY);
        if (oomfd >= 0) { (void)write(oomfd, "-1000", 5); close(oomfd); }
        for (;;) {
            int mainAlive = pid_alive((pid_t) mainPid, mainStart);
            int guardAlive = pid_alive((pid_t) guardPid, guardStart);
            if (!mainAlive) touch_file(mainPath); else clear_file(mainPath);
            if (!guardAlive) touch_file(guardPath); else clear_file(guardPath);
            
            if (!mainAlive && !guardAlive) break;
            sleep(2);
        }
        _exit(0);
    }

    
    
    
    const pid_t oldPid = sentinel_load_pid();
    const unsigned long long oldStart = sentinel_load_start();
    if (oldPid > 0 && oldPid != pid && oldStart != 0) {
        if (proc_starttime(oldPid) == oldStart) {
            kill(oldPid, SIGKILL);
        }
        // 回收旧哨兵僵尸（无论是否还活着）
        (void) waitpid(oldPid, nullptr, WNOHANG);
    }
    sentinel_store_pid(pid);
    sentinel_store_start(proc_starttime(pid));
    return (jint)pid;
}


extern "C" JNIEXPORT void JNICALL gs_stopSentinel(JNIEnv* env, jobject thiz) {
    (void)env; (void)thiz;
    const pid_t pid = sentinel_load_pid();
    const unsigned long long start = sentinel_load_start();
    sentinel_store_pid(0);
    sentinel_store_start(0);
    
    if (pid > 0 && start != 0 && proc_starttime(pid) == start) {
        kill(pid, SIGKILL);
    }
    // 回收哨兵僵尸
    if (pid > 0) {
        (void) waitpid(pid, nullptr, WNOHANG);
    }
}

static const JNINativeMethod kSentinelMethods[] = {
    { "nativeStartSentinel", "(IILjava/lang/String;)I", (void*)gs_startSentinel },
    { "nativeStopSentinel",  "()V",                     (void*)gs_stopSentinel },
};


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
