#!/system/bin/sh
# 游龙安全护盾 - 自保 watchdog（2026-11-02 精简）
#
# 只负责“自己恢复自己”，不再归因、不再冻结、不再 force-stop 任何嫌疑软件：
#   1. 自己被 disable / suspend → pm enable / unsuspend
#   2. 关键组件被 disable → pm enable
#   3. 无障碍被挤掉 → 只把自己的服务加回去（保留其它服务）
#   4. App 进程被强停/回收 → 重新拉起
#   5. stellar_server 被强停/冻结 → 重新启动
#   6. A/B 双 watchdog 互保

ME=com.youlong.hd
A11Y=com.youlong.hd/com.youlong.hd.AdSkipService
BASE=/sdcard/Android/data/com.youlong.hd/files
ENABLEFLAG="$BASE/self_defense_enabled"
WLIST=/data/local/tmp/stellar_guard/command_whitelist.txt
SYSPKGS=/data/local/tmp/youlong_system_packages.txt
ALERTFILE=/data/local/tmp/youlong_selfdefense_alert.txt
PIDFILE=/data/local/tmp/youlong_selfdefense.pid
APP_RESTART_LAST=0
STELLAR_RESTART_LAST=0

log_msg() {
    echo "$(date '+%m-%d %H:%M:%S') $*" >>"$LOG" 2>/dev/null
}

guard_enabled() {
    [ "$(cat "$ENABLEFLAG" 2>/dev/null)" = "1" ]
}

is_our_script_pid() {
    pid="$1"
    [ -n "$pid" ] || return 1
    [ -r "/proc/$pid/cmdline" ] || return 1
    tr '\0' ' ' <"/proc/$pid/cmdline" 2>/dev/null | grep -q 'self_defense.sh'
}

is_frozen_pid() {
    [ -n "$1" ] || return 1
    st=$(awk '{print $3}' "/proc/$1/stat" 2>/dev/null)
    [ "$st" = "T" ] || [ "$st" = "t" ]
}

read_components_disabled() {
    dumpsys package "$ME" 2>/dev/null | sed -n 's/.*disabledComponents=//p' | head -n 1
}

COMPONENT_CACHE=""
COMPONENT_CHECKED=0
COMPONENT_TICK=0
read_components_disabled_cached() {
    COMPONENT_TICK=$((COMPONENT_TICK + 1))
    if [ "$COMPONENT_CHECKED" = "0" ] || [ $((COMPONENT_TICK % 3)) -eq 0 ]; then
        COMPONENT_CACHE=$(read_components_disabled)
        COMPONENT_CHECKED=1
    fi
    echo "$COMPONENT_CACHE"
}

read_a11y_ok() {
    cur=$(settings get secure enabled_accessibility_services 2>/dev/null)
    case ":$cur:" in *":$A11Y:"*) echo 1;; *) echo 0;; esac
}

# 只恢复本应用自己；不碰其它应用。
restore_self() {
    pm enable --user 0 "$ME" >/dev/null 2>&1
    pm unsuspend --user 0 "$ME" >/dev/null 2>&1
    for c in \
        com.youlong.hd/.MainActivity \
        com.youlong.hd/.ForegroundService \
        com.youlong.hd/.ScreenFilterService \
        com.youlong.hd/.AdSkipService \
        com.youlong.hd/.ProtectService \
        com.youlong.hd/.RescueWindowService \
        com.youlong.hd/.DeviceAdminReceiver \
        com.youlong.hd/.BootReceiver \
        com.youlong.hd/roro.stellar.manager.authorization.RequestPermissionActivity \
        com.youlong.hd/roro.stellar.manager.authorization.CommandGuardActivity \
        com.youlong.hd/roro.stellar.manager.receiver.BootCompleteReceiver \
        com.youlong.hd/roro.stellar.manager.receiver.StellarReceiver \
        com.youlong.hd/roro.stellar.manager.startup.notification.BootStartActionReceiver \
        com.youlong.hd/roro.stellar.StellarProvider \
        com.youlong.hd/com.youlong.hd.StellarManagerProvider \
        com.youlong.hd/androidx.core.content.FileProvider; do
        pm enable --user 0 "$c" >/dev/null 2>&1
    done

    settings put secure accessibility_enabled 1 >/dev/null 2>&1
    cur=$(settings get secure enabled_accessibility_services 2>/dev/null)
    if [ -z "$cur" ] || [ "$cur" = "null" ]; then
        settings put secure enabled_accessibility_services "$A11Y" >/dev/null 2>&1
    else
        case ":$cur:" in
            *":$A11Y:"*) ;;
            *) settings put secure enabled_accessibility_services "$cur:$A11Y" >/dev/null 2>&1;;
        esac
    fi
}

restart_app() {
    now=$(date +%s)
    [ $((now - APP_RESTART_LAST)) -lt 5 ] && return
    APP_RESTART_LAST=$now
    log_msg "restart app process"
    pm enable --user 0 "$ME" >/dev/null 2>&1
    am start -n "$ME/.MainActivity" --user 0 --activity-single-top >/dev/null 2>&1
    cmd activity start-activity -n "$ME/.MainActivity" --user 0 >/dev/null 2>&1
    pidof "$ME" >/dev/null 2>&1 || \
        monkey -p "$ME" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
}

restart_stellar() {
    SLOCK=/data/local/tmp/youlong_stellar_restart.lock
    if [ -d "$SLOCK" ]; then
        lpid=$(cat "$SLOCK/pid" 2>/dev/null)
        if is_our_script_pid "$lpid"; then
            return
        fi
        rm -rf "$SLOCK" 2>/dev/null
    fi
    mkdir "$SLOCK" 2>/dev/null || return
    echo $$ >"$SLOCK/pid" 2>/dev/null
    ( sleep 10; rm -rf "$SLOCK" 2>/dev/null ) &

    now=$(date +%s)
    [ $((now - STELLAR_RESTART_LAST)) -lt 5 ] && return
    STELLAR_RESTART_LAST=$now
    apk=$(pm path "$ME" 2>/dev/null | sed -n 's/^package://p' | head -n 1)
    [ -z "$apk" ] && return
    libdir=$(dirname "$apk")
    lib="$libdir/lib/arm64/libstellar.so"
    [ -x "$lib" ] || lib="$libdir/lib/arm/libstellar.so"
    if [ -x "$lib" ]; then
        log_msg "restart stellar via lib: $lib"
        nohup setsid "$lib" --apk="$apk" >/dev/null 2>&1 &
    else
        log_msg "restart stellar via app_process"
        nohup setsid env CLASSPATH="$apk" app_process /system/bin \
            --nice-name=stellar_server roro.stellar.server.StellarService \
            >/dev/null 2>&1 &
    fi
}

show_self_alert() {
    now=$(date +%s)
    last=0
    [ -f "$ALERTFILE" ] && last=$(stat -c %Y "$ALERTFILE" 2>/dev/null || echo 0)
    [ $((now - last)) -lt 30 ] && return
    printf '%s|%s|%s\n' "" "self" "检测到自保状态异常，已强制恢复" >"$ALERTFILE" 2>/dev/null
    am start -n com.youlong.hd/.MainActivity \
        --activity-single-top \
        --es self_defense_reason "检测到自保状态异常，已强制恢复" \
        --es self_defense_action "warn" >/dev/null 2>&1
}

# ===== A/B 双 watchdog 互保 =====
ROLE="${1:-A}"
case "$ROLE" in
    B|b)
        ROLE=B
        LOCKDIR=/data/local/tmp/youlong_selfdefense_b.lock
        PIDFILE=/data/local/tmp/youlong_selfdefense_b.pid
        STATEFILE=/data/local/tmp/youlong_selfdefense_b.state
        LOG=/data/local/tmp/youlong_selfdefense_b.log
        OTHER_ROLE=A
        OTHER_LOCK=/data/local/tmp/youlong_selfdefense_a.lock
        OTHER_PID=/data/local/tmp/youlong_selfdefense_a.pid
        ;;
    *)
        ROLE=A
        LOCKDIR=/data/local/tmp/youlong_selfdefense_a.lock
        PIDFILE=/data/local/tmp/youlong_selfdefense_a.pid
        STATEFILE=/data/local/tmp/youlong_selfdefense_a.state
        LOG=/data/local/tmp/youlong_selfdefense_a.log
        OTHER_ROLE=B
        OTHER_LOCK=/data/local/tmp/youlong_selfdefense_b.lock
        OTHER_PID=/data/local/tmp/youlong_selfdefense_b.pid
        ;;
esac
SELF="$0"
[ -f "$SELF" ] || SELF="$BASE/self_defense.sh"

start_other() {
    if [ -f "$OTHER_PID" ]; then
        opid=$(cat "$OTHER_PID" 2>/dev/null)
        if is_our_script_pid "$opid"; then
            is_frozen_pid "$opid" && {
                kill -9 "$opid" 2>/dev/null
                rm -rf "$OTHER_LOCK" 2>/dev/null
                log_msg "other watchdog frozen; killed role=$OTHER_ROLE"
            }
            is_our_script_pid "$opid" && return
        fi
        rm -f "$OTHER_PID" 2>/dev/null
        rm -rf "$OTHER_LOCK" 2>/dev/null
    fi
    if [ -d "$OTHER_LOCK" ]; then
        sleep 1
        if [ -f "$OTHER_PID" ]; then
            opid=$(cat "$OTHER_PID" 2>/dev/null)
            is_our_script_pid "$opid" && return
        fi
        rm -rf "$OTHER_LOCK" 2>/dev/null
    fi
    log_msg "restart other watchdog role=$OTHER_ROLE"
    nohup setsid sh "$SELF" "$OTHER_ROLE" >/dev/null 2>&1 &
}

guard_enabled || { log_msg "安全守护未开启，watchdog 不启动"; exit 0; }
if ! mkdir "$LOCKDIR" 2>/dev/null; then
    OLD=$(cat "$PIDFILE" 2>/dev/null)
    if is_our_script_pid "$OLD"; then exit 0; fi
    sleep 1
    OLD=$(cat "$PIDFILE" 2>/dev/null)
    if is_our_script_pid "$OLD"; then exit 0; fi
    rm -rf "$LOCKDIR"
    mkdir "$LOCKDIR" 2>/dev/null || exit 0
fi
trap 'rm -rf "$LOCKDIR"; rm -f "$PIDFILE"' EXIT
echo $$ >"$PIDFILE" 2>/dev/null
log_msg "watchdog start role=$ROLE pid=$$"

# 旧版自动写入的系统应用白名单清理（保留用户普通应用）。
pm list packages -s 2>/dev/null | sed 's/^package://' >"$SYSPKGS.tmp" 2>/dev/null
mv "$SYSPKGS.tmp" "$SYSPKGS" 2>/dev/null
if [ -s "$WLIST" ] && [ -s "$SYSPKGS" ]; then
    grep -vxF -f "$SYSPKGS" "$WLIST" >"$WLIST.tmp.$$" 2>/dev/null
    mv "$WLIST.tmp.$$" "$WLIST" 2>/dev/null
fi

start_other

while true; do
    if ! guard_enabled; then
        log_msg "安全守护已关闭，watchdog 退出"
        exit 0
    fi
    start_other

    prev=$(cat "$STATEFILE" 2>/dev/null)
    prev_d=$(echo "$prev" | cut -d'|' -f1)
    prev_c=$(echo "$prev" | cut -d'|' -f2)
    prev_a=$(echo "$prev" | cut -d'|' -f3)
    prev_r=$(echo "$prev" | cut -d'|' -f4)

    if ! pm path "$ME" >/dev/null 2>&1; then
        log_msg "our package disappeared (uninstalled?)"
        exit 0
    fi

    spid=$(pidof stellar_server 2>/dev/null | awk '{print $1}')
    if [ -z "$spid" ]; then
        restart_stellar
    elif is_frozen_pid "$spid"; then
        log_msg "stellar_server frozen; kill and restart"
        kill -9 "$spid" 2>/dev/null
        restart_stellar
    fi

    d=0
    pm list packages -d 2>/dev/null | grep -qx "package:$ME" && d=1
    c=$(read_components_disabled_cached)
    a=$(read_a11y_ok)
    r=0
    pidof "$ME" >/dev/null 2>&1 && r=1
    apid=$(pidof "$ME" 2>/dev/null | awk '{print $1}')
    if [ -n "$apid" ] && is_frozen_pid "$apid"; then
        log_msg "app process frozen; kill and restart"
        kill -9 "$apid" 2>/dev/null
        r=0
    fi

    need_alert=0
    if [ "$d" = "1" ] || { [ -n "$c" ] && [ "$c" != "$prev_c" ]; } \
            || { [ "$a" = "0" ] && [ "$prev_a" = "1" ]; } \
            || { [ "$r" = "0" ] && [ "$prev_r" = "1" ]; }; then
        need_alert=1
    fi

    if [ "$d" = "1" ] || [ -n "$c" ] || [ "$a" = "0" ]; then
        restore_self
    fi
    if [ "$r" = "0" ] && [ "$d" = "0" ]; then
        restart_app
    fi

    if [ "$need_alert" = "1" ]; then
        show_self_alert
    fi

    d=0
    pm list packages -d 2>/dev/null | grep -qx "package:$ME" && d=1
    c=$(read_components_disabled_cached)
    a=$(read_a11y_ok)
    r=0
    pidof "$ME" >/dev/null 2>&1 && r=1
    printf '%s|%s|%s|%s\n' "$d" "$c" "$a" "$r" >"$STATEFILE" 2>/dev/null
    sleep 10
done
