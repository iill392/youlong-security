#ifndef MISC_H
#define MISC_H

int switch_mnt_ns(int pid);
int get_proc_name(int pid, char *name, size_t _size);

using foreach_proc_function = void(pid_t);
void foreach_proc(foreach_proc_function *func);

char *trim(char *str);

#endif
