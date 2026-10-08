#include <sys/types.h>
#include <sys/stat.h>
#include <zconf.h>
#include <dirent.h>
#include <fcntl.h>
#include <cstdio>
#include <cstring>
#include <cstdlib>
#include <sched.h>
#include <cctype>
#include <cerrno>
#include "misc.h"

ssize_t fdgets(char *buf, const size_t size, int fd) {
    buf[0] = '\0';
    ssize_t ret;
    do {
        ret = read(fd, buf, size - 1);
    } while (ret < 0 && errno == EINTR);
    if (ret < 0)
        return -1;
    buf[ret] = '\0';
    return ret;
}

int get_proc_name(int pid, char *name, size_t size) {
    int fd;
    char buf[PATH_MAX];
    snprintf(buf, sizeof(buf), "/proc/%d/cmdline", pid);
    if ((fd = open(buf, O_RDONLY)) == -1)
        return 1;
    fdgets(name, size, fd);
    close(fd);
    return 0;
}

int is_num(const char *s) {
    size_t len = strlen(s);
    for (size_t i = 0; i < len; ++i)
        if (s[i] < '0' || s[i] > '9')
            return 0;
    return 1;
}

int switch_mnt_ns(int pid) {
    char mnt[32];
    snprintf(mnt, sizeof(mnt), "/proc/%d/ns/mnt", pid);
    if (access(mnt, R_OK) == -1) return -1;

    int fd = open(mnt, O_RDONLY);
    if (fd < 0) return -1;

    int res = setns(fd, 0);
    close(fd);
    return res;
}

void foreach_proc(foreach_proc_function *func) {
    DIR *dir;
    struct dirent *entry;

    if (!(dir = opendir("/proc")))
        return;

    while ((entry = readdir(dir))) {
        if (entry->d_type != DT_DIR) continue;
        if (!is_num(entry->d_name)) continue;
        pid_t pid = atoi(entry->d_name);
        func(pid);
    }

    closedir(dir);
}

char *trim(char *str) {
    size_t len = 0;
    char *frontp = str;
    char *endp = nullptr;

    if (str == nullptr) { return nullptr; }
    if (str[0] == '\0') { return str; }

    len = strlen(str);
    endp = str + len;

    while (isspace((unsigned char) *frontp)) { ++frontp; }
    if (endp != frontp) {
        while (isspace((unsigned char) *(--endp)) && endp != frontp) {}
    }

    if (endp == frontp) {
        // 全空白输入：直接清空，避免 *(endp + 1) 越界写
        *str = '\0';
        return str;
    }

    if (str + len - 1 != endp)
        *(endp + 1) = '\0';

    endp = str;
    if (frontp != str) {
        while (*frontp) { *endp++ = *frontp++; }
        *endp = '\0';
    }

    return str;
}
