#include <jni.h>
#include <unistd.h>
#include <pty.h>
#include <sys/wait.h>
#include <sys/ioctl.h>
#include <termios.h>
#include <stdlib.h>
#include <string.h>

static char **unpackArgs(const jbyte *block, jint count, jint blockLen) {
    if (block == nullptr || count < 0 || count > 1024 || blockLen <= 0) {
        return nullptr;
    }
    char **arr = new char *[count + 1];
    const char *p = reinterpret_cast<const char *>(block);
    const char *end = p + blockLen;
    for (int i = 0; i < count; i++) {
        if (p >= end) {
            // 块内字符串不足 count 个：拒绝，避免越界读
            delete[] arr;
            return nullptr;
        }
        size_t len = strnlen(p, static_cast<size_t>(end - p));
        if (len == static_cast<size_t>(end - p)) {
            // 无终止符，块损坏：拒绝
            delete[] arr;
            return nullptr;
        }
        arr[i] = const_cast<char *>(p);
        p += len + 1;
    }
    arr[count] = nullptr;
    return arr;
}

extern "C" JNIEXPORT jintArray JNICALL
Java_rikka_rish_RishHost_start(JNIEnv *env, jclass,
                               jbyteArray argBlock, jint argc,
                               jbyteArray envBlock, jint envc,
                               jbyteArray dirBlock,
                               jbyte tty, jint in, jint out, jint err) {
    if (argBlock == nullptr || argc < 0 || dirBlock == nullptr
            || (envc >= 0 && envBlock == nullptr)) {
        return nullptr;
    }
    jbyte *args = env->GetByteArrayElements(argBlock, nullptr);
    if (args == nullptr) return nullptr;
    jbyte *envs = envc >= 0 ? env->GetByteArrayElements(envBlock, nullptr) : nullptr;
    if (envc >= 0 && envs == nullptr) {
        env->ReleaseByteArrayElements(argBlock, args, JNI_ABORT);
        return nullptr;
    }
    jbyte *dir = env->GetByteArrayElements(dirBlock, nullptr);
    if (dir == nullptr) {
        env->ReleaseByteArrayElements(argBlock, args, JNI_ABORT);
        if (envs) env->ReleaseByteArrayElements(envBlock, envs, JNI_ABORT);
        return nullptr;
    }

    jsize argLen = env->GetArrayLength(argBlock);
    jsize envLen = envc >= 0 ? env->GetArrayLength(envBlock) : 0;

    char **argv = unpackArgs(args, argc, argLen);
    if (argv == nullptr) {
        env->ReleaseByteArrayElements(argBlock, args, JNI_ABORT);
        if (envs) env->ReleaseByteArrayElements(envBlock, envs, JNI_ABORT);
        env->ReleaseByteArrayElements(dirBlock, dir, JNI_ABORT);
        return nullptr;
    }
    char **envp = envc >= 0 ? unpackArgs(envs, envc, envLen) : nullptr;
    if (envc >= 0 && envp == nullptr) {
        env->ReleaseByteArrayElements(argBlock, args, JNI_ABORT);
        env->ReleaseByteArrayElements(envBlock, envs, JNI_ABORT);
        env->ReleaseByteArrayElements(dirBlock, dir, JNI_ABORT);
        delete[] argv;
        return nullptr;
    }

    int ptmx = -1;
    pid_t pid;

    if (tty) {
        pid = forkpty(&ptmx, nullptr, nullptr, nullptr);
    } else {
        pid = fork();
    }

    if (pid < 0) {
        // fork 失败：释放资源并返回错误
        env->ReleaseByteArrayElements(argBlock, args, JNI_ABORT);
        if (envs) env->ReleaseByteArrayElements(envBlock, envs, JNI_ABORT);
        env->ReleaseByteArrayElements(dirBlock, dir, JNI_ABORT);
        delete[] argv;
        delete[] envp;
        return nullptr;
    }

    if (pid == 0) {
        if (!tty) {
            if (in >= 0) dup2(in, STDIN_FILENO);
            if (out >= 0) dup2(out, STDOUT_FILENO);
            if (err >= 0) dup2(err, STDERR_FILENO);
        }
        if (dir && reinterpret_cast<const char *>(dir)[0]) {
            if (chdir(reinterpret_cast<const char *>(dir)) != 0) {
                _exit(126);
            }
        }
        if (envp) {
            execvpe(argv[0], argv, envp);
        } else {
            execvp(argv[0], argv);
        }
        _exit(127);
    }

    env->ReleaseByteArrayElements(argBlock, args, JNI_ABORT);
    if (envs) env->ReleaseByteArrayElements(envBlock, envs, JNI_ABORT);
    env->ReleaseByteArrayElements(dirBlock, dir, JNI_ABORT);
    delete[] argv;
    delete[] envp;

    jintArray result = env->NewIntArray(2);
    if (result == nullptr) return nullptr;
    jint buf[2] = {pid, ptmx};
    env->SetIntArrayRegion(result, 0, 2, buf);
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_rikka_rish_RishHost_setWindowSize(JNIEnv *, jclass, jint fd, jlong size) {
    struct winsize ws;
    ws.ws_col = (unsigned short) (size & 0xffff);
    ws.ws_row = (unsigned short) ((size >> 16) & 0xffff);
    ws.ws_xpixel = 0;
    ws.ws_ypixel = 0;
    ioctl(fd, TIOCSWINSZ, &ws);
}

extern "C" JNIEXPORT jint JNICALL
Java_rikka_rish_RishHost_waitFor(JNIEnv *, jclass, jint pid) {
    if (pid <= 0) return -1;
    int status = 0;
    if (waitpid(pid, &status, 0) < 0) return -1;
    return WIFEXITED(status) ? WEXITSTATUS(status) : -1;
}
