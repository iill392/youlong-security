#!/system/bin/sh
# 游龙安全护盾 - 自保 watchdog（2026-10-25）
# 由 SelfDefense 通过 Stellar 的 manager 权限启动。
#
# 能力：
#   1. 自己被 disable -> pm enable
#   2. 组件被 disable -> 逐个恢复
#   3. 无障碍被挤掉 -> 恢复 enabled_accessibility_services
#   4. App 进程被强停/回收 -> 自动 am start 拉起（不再只依赖 logcat 证据）
#   5. stellar_server 进程被强停 -> 自动用 base.apk 的 libstellar.so 重启
#   6. 有嫌疑应用 -> 直接加入持久冻结列表，并每 2 秒反复执行冻结
#   7. 冻结管理：App 从列表删除某包 -> 停止复冻并 pm enable 解冻
#   8. 已知恶意包 -> force-stop / uninstall
#   9. 异常关闭/冻结检测：采集证据归因；只要归因出嫌疑就冻结，系统/用户操作只恢复。
#
# 无 root 边界：系统日志通常只记录“谁被关”，不记录“谁发起”；
# 归因是证据链推断，不是系统级审计。

ME=com.youlong.hd
A11Y=com.youlong.hd/com.youlong.hd.AdSkipService
BASE=/sdcard/Android/data/com.youlong.hd/files
ENABLEFLAG="$BASE/self_defense_enabled"
BLIST="$BASE/self_defense_blacklist.txt"
WLIST=/data/local/tmp/stellar_guard/command_whitelist.txt
FROZEN=/data/local/tmp/youlong_frozen_list.txt
FROZEN_PREV=/data/local/tmp/youlong_frozen_prev.txt
PIDFILE=/data/local/tmp/youlong_selfdefense.pid
STATEFILE=/data/local/tmp/youlong_selfdefense.state
EVIDENCE=/data/local/tmp/youlong_selfdefense_evidence.txt
ALERTFILE=/data/local/tmp/youlong_selfdefense_alert.txt
LOG=/data/local/tmp/youlong_selfdefense.log
HIST=/data/local/tmp/youlong_adb_clients.hist
ACTIVE=/data/local/tmp/youlong_adb_clients.active
UIDCACHE=/data/local/tmp/youlong_uidcache
SYSPKGS=/data/local/tmp/youlong_system_packages.txt
OUR_UID=$(dumpsys package "$ME" 2>/dev/null | sed -n 's/.*userId=\([0-9]*\).*/\1/p' | head -n 1)
APP_RESTART_LAST=0
STELLAR_RESTART_LAST=0

# 2026-11-01：系统应用名单。系统 App 不全是 UID 1000，
# 用 pm list packages -s 建一份名单，任何自保归因/冻结都排除它们。
pm list packages -s 2>/dev/null | sed 's/^package://' >"$SYSPKGS.tmp" 2>/dev/null
mv "$SYSPKGS.tmp" "$SYSPKGS" 2>/dev/null

# 2026-10-31：旧版本可能把游龙自身/配套写进了冻结列表。
# 启动时先剔除，保证后续 enforce_frozen 不会再次处理自身。
if [ -f "$FROZEN" ]; then
    grep -vxF -e "$ME" -e "com.youlong.tool" -e "com.youlong.zoo" "$FROZEN" >"$FROZEN.tmp" 2>/dev/null
    mv "$FROZEN.tmp" "$FROZEN" 2>/dev/null
    # 2026-10-31：剔除 “OK” 这类非包名脏数据；Android 包名至少要有两段。
    grep -E '^[A-Za-z0-9_]+(\.[A-Za-z0-9_]+)+$' "$FROZEN" >"$FROZEN.tmp" 2>/dev/null
    mv "$FROZEN.tmp" "$FROZEN" 2>/dev/null
fi

log_msg() {
    echo "$(date '+%m-%d %H:%M:%S') $*" >>"$LOG" 2>/dev/null
}

# V30：只有用户开启安全守护（App 写 flag=1）才允许 watchdog 运行；
# 未开启安全守护时只使用 App 的闹钟保活。
guard_enabled() {
    [ "$(cat "$ENABLEFLAG" 2>/dev/null)" = "1" ]
}

top_pkg() {
    p=$(dumpsys activity activities 2>/dev/null | grep -E 'topResumedActivity|mResumedActivity' | head -n 1 | sed -n 's/.* \([a-zA-Z0-9_.]*\)\/.*/\1/p')
    if [ -z "$p" ]; then
        p=$(dumpsys window 2>/dev/null | grep -m 1 'mCurrentFocus' | sed -n 's/.* \([a-zA-Z0-9_.]*\)\/.*/\1/p')
    fi
    echo "$p"
}

is_system_pkg() {
    case "$1" in
        ""|android|com.android.*|com.google.android.*|com.vivo.*|com.bbk.*|com.iqoo.*|com.mediatek.*|com.qualcomm.*|com.huawei.*|com.hihonor.*|com.miui.*|com.xiaomi.*|com.oppo.*|com.coloros.*|com.oplus.*|com.oneplus.*|com.samsung.*|com.sec.*|com.realme.*|com.meizu.*|com.transsion.*|*launcher*|*home*|*inputmethod*|*keyboard*|*ime*) return 0;;
    esac
    # pm list packages -s 覆盖所有预装系统应用（含厂商独立 UID 的系统组件）。
    if [ -s "$SYSPKGS" ] && grep -qxF "$1" "$SYSPKGS" 2>/dev/null; then return 0; fi
    return 1
}

# V23：桌面、SystemUI、设置、安装器绝不能冻结，否则手机直接不可用。
is_critical_system_pkg() {
    case "$1" in
        ""|com.bbk.launcher2|com.android.systemui|com.android.settings|com.android.packageinstaller|com.google.android.packageinstaller|com.vivo.safecenter|*launcher*|*home*|*inputmethod*|*keyboard*|*ime*) return 0;;
        *) return 1;;
    esac
}

# V25：PID 会被系统复用。只 kill -0 会把“别的进程用了旧 PID”
# 误判成 watchdog 还活着，导致新脚本直接退出、自保彻底失效。
is_our_script_pid() {
    pid="$1"
    [ -n "$pid" ] || return 1
    [ -r "/proc/$pid/cmdline" ] || return 1
    tr '\0' ' ' <"/proc/$pid/cmdline" 2>/dev/null | grep -q 'self_defense.sh'
}

is_blacklisted() {
    p="$1"
    [ -z "$p" ] && return 1
    if [ -f "$BLIST" ] && grep -qx "$p" "$BLIST" 2>/dev/null; then return 0; fi
    [ "$p" = "mio.TerminalDeity.Ultra" ] && return 0
    return 1
}

# 白名单 / 内置工具 / 自己 / 系统应用一律不冻结。
is_whitelisted() {
    p="$1"
    [ -z "$p" ] && return 1
    [ "$p" = "$ME" ] && return 0
    [ "$p" = "com.youlong.tool" ] && return 0
    [ "$p" = "com.youlong.zoo" ] && return 0
    is_system_pkg "$p" && return 0
    if [ -f "$WLIST" ] && grep -qxF "$p" "$WLIST" 2>/dev/null; then return 0; fi
    return 1
}

is_valid_pkg() {
    case "$1" in
        ""|.*|*.|*[!A-Za-z0-9_.]*) return 1;;
        *.*) return 0;;
        *) return 1;;
    esac
}

# 真正执行冻结（会被 enforce_frozen 每 2 秒反复调用）。
apply_freeze() {
    p="$1"
    # 2026-10-31 修复：任何路径都绝不冻结自己/配套工具/用户白名单，
    # 避免自保脚本误把游龙自身或游龙工具冻结。
    [ "$p" = "$ME" ] && return
    [ "$p" = "com.youlong.tool" ] && return
    [ "$p" = "com.youlong.zoo" ] && return
    is_whitelisted "$p" && return
    am force-stop --user 0 "$p" >/dev/null 2>&1
    pm disable-user --user 0 "$p" >/dev/null 2>&1
}

# 加入持久冻结列表，并立即执行一次。
add_frozen() {
    p="$1"
    is_valid_pkg "$p" || return
    [ "$p" = "$ME" ] && return
    [ "$p" = "com.youlong.tool" ] && return
    [ "$p" = "com.youlong.zoo" ] && return
    is_whitelisted "$p" && return
    is_critical_system_pkg "$p" && return
    : >>"$FROZEN" 2>/dev/null
    grep -qxF "$p" "$FROZEN" 2>/dev/null || echo "$p" >>"$FROZEN" 2>/dev/null
    apply_freeze "$p"
}

# V10：反复执行冻结；列表中被用户删掉的应用 → 停止冻结并解冻。
enforce_frozen() {
    : >>"$FROZEN" 2>/dev/null
    if [ -f "$FROZEN_PREV" ]; then
        while IFS= read -r p; do
            [ -z "$p" ] && continue
            if ! grep -qxF "$p" "$FROZEN" 2>/dev/null; then
                log_msg "user unfreeze: $p"
                pm enable --user 0 "$p" >/dev/null 2>&1
                pm unsuspend --user 0 "$p" >/dev/null 2>&1
            fi
        done <"$FROZEN_PREV"
    fi
    if [ -s "$FROZEN" ]; then
        while IFS= read -r p; do
            [ -z "$p" ] && continue
            is_valid_pkg "$p" || continue
            [ "$p" = "$ME" ] && continue
            is_whitelisted "$p" && continue
            is_critical_system_pkg "$p" && continue
            apply_freeze "$p"
        done <"$FROZEN"
    fi
    cp "$FROZEN" "$FROZEN_PREV" 2>/dev/null
}

uid_to_pkg() {
    uid="$1"
    [ -z "$uid" ] && return 1
    p=$(grep "^$uid|" "$UIDCACHE" 2>/dev/null | head -n 1 | cut -d'|' -f2)
    if [ -z "$p" ]; then
        p=$(pm list packages -U 2>/dev/null | grep "uid:$uid" | head -n 1 | sed 's/^package://; s/ uid:.*//')
        [ -n "$p" ] && echo "$uid|$p" >>"$UIDCACHE" 2>/dev/null
    fi
    [ -n "$p" ] && echo "$p"
}

# 内核 socket 归因：App 自带 ADB 客户端必须连系统 adbd 的 TCP 端口，
# /proc/net/tcp 的 uid 字段就是该 App 的 UID，不依赖它自己公开日志。
snapshot_adb_clients() {
    port=$(getprop service.adb.tcp.port 2>/dev/null)
    [ -z "$port" ] && return
    hex=$(printf '%04X' "$port" 2>/dev/null)
    [ -z "$hex" ] && return
    now=$(date +%s)
    : >"$ACTIVE.tmp" 2>/dev/null
    for f in /proc/net/tcp /proc/net/tcp6; do
        [ -r "$f" ] || continue
        for uid in $(awk -v hp=":$hex" 'NR>1 && index($3,hp)>0 {print $8}' "$f" 2>/dev/null | sort -u); do
            [ -z "$uid" ] && continue
            [ "$uid" = "0" ] && continue
            [ "$uid" = "1000" ] && continue
            [ "$uid" = "2000" ] && continue
            [ -n "$OUR_UID" ] && [ "$uid" = "$OUR_UID" ] && continue
            pkg=$(uid_to_pkg "$uid")
            [ -z "$pkg" ] && continue
            [ "$pkg" = "$ME" ] && continue
            is_whitelisted "$pkg" && continue
            is_critical_system_pkg "$pkg" && continue
            echo "$now|$uid|$pkg" >>"$HIST" 2>/dev/null
            echo "$uid|$pkg" >>"$ACTIVE.tmp" 2>/dev/null
        done
    done
    if [ -f "$HIST" ]; then
        tail -n 300 "$HIST" >"$HIST.tmp" 2>/dev/null && mv "$HIST.tmp" "$HIST" 2>/dev/null
    fi
    [ -f "$ACTIVE.tmp" ] && mv "$ACTIVE.tmp" "$ACTIVE" 2>/dev/null
}

# 候选列表：优先用“异常发生时仍活跃”的连接；否则用最近 15 秒的连接。
# 返回包名，可能多行；多候选时绝不能盲选一个冻结。
recent_adb_candidates() {
    if [ -f "$ACTIVE" ] && [ -s "$ACTIVE" ]; then
        awk -F'|' '{print $2}' "$ACTIVE" 2>/dev/null | sort -u
        return
    fi
    [ -f "$HIST" ] || return 1
    now=$(date +%s)
    awk -F'|' -v n="$now" '$1 >= n-15 {print $3}' "$HIST" 2>/dev/null | sort -u
}

read_components_disabled() {
    dumpsys package "$ME" 2>/dev/null | sed -n 's/.*disabledComponents=//p' | head -n 1
}

read_a11y_ok() {
    cur=$(settings get secure enabled_accessibility_services 2>/dev/null)
    case ":$cur:" in *":$A11Y:"*) echo 1;; *) echo 0;; esac
}

# V7：PID 存在但进程状态为 T/t（SIGSTOP/冻结）时返回 0。
is_frozen_pid() {
    [ -n "$1" ] || return 1
    st=$(awk '{print $3}' "/proc/$1/stat" 2>/dev/null)
    [ "$st" = "T" ] || [ "$st" = "t" ]
}

# V6：App 进程被强停/回收后无条件拉起（限频 5 秒，避免刷屏）
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

# V6：内置特权进程被强停后，用当前安装包里的 libstellar.so 重启。
# watchdog 本身是 nohup setsid 的 shell 进程，Stellar 被杀后仍应继续运行。
restart_stellar() {
    # A/B 两个 watchdog 共享锁，避免同时拉起两个 stellar_server。
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
        # 兜底：直接用 app_process 加载 APK 里的服务端（与 libstellar.so 等价）。
        log_msg "restart stellar via app_process"
        nohup setsid env CLASSPATH="$apk" app_process /system/bin \
            --nice-name=stellar_server roro.stellar.server.StellarService \
            >/dev/null 2>&1 &
    fi
}

capture_evidence() {
    top=$(top_pkg)
    focus=$(dumpsys window 2>/dev/null | grep -m 1 'mCurrentFocus')
    events=$(logcat -d -b events -t 500 2>/dev/null | grep -E 'am_force_stop|am_kill|am_proc_died|am_crash|am_anr' | tail -n 30)
    syslog=$(logcat -d -b system -t 800 2>/dev/null | grep -iE 'force stop|disable|kill|youlong|package' | tail -n 30)
    force=$(logcat -d -b main -t 1200 2>/dev/null | grep -iE 'force stopping|force-stop|am_force_stop' | tail -n 30)
    exitinfo=$(dumpsys activity exit-info "$ME" 2>/dev/null | head -n 80)
    shizuku=$(logcat -d -b main -t 800 2>/dev/null | grep -i 'shizuku' | tail -n 30)
    {
        echo "time=$(date '+%F %T')"
        echo "top=$top"
        echo "focus=$focus"
        echo "--- adb client sockets (kernel UID evidence) ---"
        tail -n 20 "$HIST" 2>/dev/null
        echo "--- events ---"
        echo "$events"
        echo "--- system ---"
        echo "$syslog"
        echo "--- force stop logs ---"
        echo "$force"
        echo "--- exit info ---"
        echo "$exitinfo"
        echo "--- shizuku ---"
        echo "$shizuku"
    } >"$EVIDENCE" 2>/dev/null
    log_msg "evidence top=$top"
}

# 2026-11-01：从系统 force-stop 日志中找真正的调用者，不依赖前台应用。
# AOSP 会打印：Force stopping <pkg> ... from pid <callingPid>。
# callingPid 反查 /proc/<pid>/status 的 Uid -> 包名；只有找到了具体的
# 第三方调用者才返回 0，供上层冻结。
attribute_force_stop_caller() {
    [ -f "$EVIDENCE" ] || return 1
    # 2026-11-01：只有退出原因确实是 force-stop / 用户请求停止时才做调用者归因。
    # 低内存回收、系统杀进程、崩溃、ANR 直接跳过，避免拿历史 force-stop 日志误伤。
    if ! grep -qE 'REASON_FORCE_STOPPED|REASON_USER_REQUESTED|FORCE_STOPPED' "$EVIDENCE" 2>/dev/null; then
        return 1
    fi
    lines=$(grep -iE "force stopping.*${ME}|force-stop.*${ME}" "$EVIDENCE" 2>/dev/null | tail -n 20)
    [ -z "$lines" ] && return 1

    for pid in $(printf '%s\n' "$lines" | sed -n 's/.*from pid \([0-9][0-9]*\).*/\1/p' | sort -u); do
        [ -z "$pid" ] && continue
        [ "$pid" -le 1 ] 2>/dev/null && continue
        uid=$(awk '/^Uid:/{print $2; exit}' "/proc/$pid/status" 2>/dev/null)
        [ -z "$uid" ] && continue
        [ "$uid" = "0" ] && continue
        [ "$uid" = "1000" ] && continue
        [ "$uid" = "2000" ] && continue
        pkg=$(uid_to_pkg "$uid")
        [ -z "$pkg" ] && continue
        [ "$pkg" = "$ME" ] && continue
        [ "$pkg" = "com.youlong.tool" ] && continue
        [ "$pkg" = "com.youlong.zoo" ] && continue
        is_whitelisted "$pkg" && continue
        is_critical_system_pkg "$pkg" && continue
        is_valid_pkg "$pkg" || continue
        ATTACKER="$pkg"
        CONF="high"
        REASON="系统 force-stop 日志调用者 pid=$pid -> $pkg"
        log_msg "force-stop caller: pid=$pid pkg=$pkg"
        return 0
    done

    # 如果调用者是 shell/Shizuku，尝试从 Shizuku 日志里找明确调用包。
    # 这里只接受完整包名并命中黑名单，避免扩大误伤。
    sz=$(grep -i shizuku "$EVIDENCE" 2>/dev/null)
    if [ -n "$sz" ]; then
        for p in $(printf '%s\n' "$sz" | grep -oE '(caller|callingPackage|package|pkg)[=: ]+[A-Za-z0-9_.]+' \
                | sed -E 's/.*[=: ]+//' | sort -u); do
            [ "$p" = "$ME" ] && continue
            is_whitelisted "$p" && continue
            is_critical_system_pkg "$p" && continue
            is_valid_pkg "$p" || continue
            if is_blacklisted "$p"; then
                ATTACKER="$p"
                CONF="high"
                REASON="Shizuku 日志 force-stop 调用包命中黑名单：$p"
                return 0
            fi
        done
    fi
    return 1
}

classify() {
    ATTACKER=""
    CONF="unknown"
    REASON="未定位可疑应用"
    # 2026-10-31：完全不读取前台应用，归因只依赖内核 ADB 客户端 UID 证据和黑名单。

    # 2026-10-31（用户反馈）：不再用“当前前台应用”做任何归因。
    # 系统杀进程、用户操作、桌面切换都可能让前台 App 背锅；只保留可验证证据。

    # 第一优先：内核 socket 候选集。自带 ADB 客户端的 App 必须连系统 adbd；
    # 多个候选时不盲选，只有唯一候选或候选中的黑名单才自动冻结。
    snapshot_adb_clients
    cands=$(recent_adb_candidates | while IFS= read -r p; do
        [ -z "$p" ] && continue
        [ "$p" = "$ME" ] && continue
        is_whitelisted "$p" && continue
        printf '%s\n' "$p"
    done)
    count=$(printf '%s\n' "$cands" | grep -v '^$' | wc -l | tr -d ' ')
    if [ "$count" -gt 1 ]; then
        bl=""
        for p in $cands; do
            if is_blacklisted "$p" && ! is_whitelisted "$p"; then bl="$p"; break; fi
        done
        if [ -n "$bl" ]; then
            ATTACKER="$bl"
            CONF="high"
            REASON="多个 ADB 客户端中命中黑名单：$bl"
            return
        fi
        # 多个普通候选无法唯一定位：宁可不冻结，也不能靠前台 App 猜。
        CONF="unknown"
        REASON="多个 ADB 客户端候选，证据不足，不冻结"
        return
    fi
    if [ "$count" -eq 1 ] && [ -n "$cands" ] && [ "$cands" != "$ME" ] && ! is_whitelisted "$cands"; then
        ATTACKER="$cands"
        CONF="high"
        if is_blacklisted "$cands"; then
            REASON="内核 socket: 黑名单 App 在异常前连接过 adbd（UID 归属）"
        else
            REASON="内核 socket: 唯一第三方 App 在异常前连接过 adbd，属于独立 ADB 客户端"
        fi
        return
    fi

    # 2026-11-01：优先从系统 force-stop 日志中解析调用者 PID/包名。
    # 这是“系统杀进程”和“第三方 force-stop”之间的可靠区分点，不使用前台应用。
    if attribute_force_stop_caller; then
        return
    fi

    # 系统退出原因明确是内存回收 / 系统杀进程 / 崩溃 / ANR：不反击。
    if grep -qE 'REASON_LOW_MEMORY|REASON_KILLED_BY_SYSTEM|REASON_APP_CRASH|REASON_ANR|REASON_SIGNALED' "$EVIDENCE" 2>/dev/null; then
        CONF="system"
        REASON="ApplicationExitInfo 显示系统回收/崩溃/ANR，非第三方攻击"
        return
    fi

    # 2026-10-31：不再拿前台包名与 Shizuku 日志做时间吻合推断。

    # Shizuku 日志里如果出现黑名单包名，视为高置信
    if [ -f "$EVIDENCE" ]; then
        for p in $(grep -i shizuku "$EVIDENCE" 2>/dev/null | grep -oE '(package|pkg)=[a-zA-Z0-9_.]+' | cut -d= -f2 | sort -u); do
            if is_blacklisted "$p"; then
                ATTACKER="$p"
                CONF="high"
                REASON="Shizuku 调用日志命中黑名单"
                return
            fi
        done
    fi

    # 2026-10-31：Shizuku 日志仅使用明确包名且命中黑名单的证据；不结合前台应用。

    # 2026-10-31：删除设置/安装器前台判断；前台状态不再参与归因。
    # 没有可靠证据时保持 ATTACKER 为空，由上层只记录/提示，不冻结。
}

freeze_attacker() {
    # ATTACKER 可能是逗号分隔的多个嫌疑包；全部加入持久冻结列表。
    list=$(printf '%s' "$1" | tr ',' ' ')
    for p in $list; do
        [ -z "$p" ] && continue
        [ "$p" = "$ME" ] && continue
        [ "$p" = "com.youlong.tool" ] && continue
        [ "$p" = "com.youlong.zoo" ] && continue
        is_whitelisted "$p" && continue
        is_critical_system_pkg "$p" && continue
        is_valid_pkg "$p" || continue
        log_msg "freeze suspect: $p"
        add_frozen "$p"
    done
}

show_alert() {
    action="$1"
    printf '%s|%s|%s\n' "$ATTACKER" "$CONF" "$REASON" >"$ALERTFILE" 2>/dev/null
    am start -n com.youlong.hd/.MainActivity \
        --activity-single-top \
        --es self_defense_attacker "$ATTACKER" \
        --es self_defense_confidence "$CONF" \
        --es self_defense_reason "$REASON" \
        --es self_defense_evidence "$EVIDENCE" \
        --es self_defense_action "$action" >/dev/null 2>&1
}

handle_abnormal() {
    kind="$1"
    now=$(date +%s)
    last=0
    [ -f "$ALERTFILE" ] && last=$(stat -c %Y "$ALERTFILE" 2>/dev/null || echo 0)
    [ $((now - last)) -lt 30 ] && return

    log_msg "abnormal detect: $kind"
    capture_evidence
    classify
    log_msg "classify: attacker=$ATTACKER conf=$CONF reason=$REASON"

    # 先恢复自己，否则 App 被 disable 时 am start 弹窗会失败。
    restore_self

    if [ "$CONF" = "system" ]; then
        # 系统/用户操作：恢复即可，不冻结、不弹窗
        return
    fi

    # V10：只要归因出嫌疑应用就冻结（不再区分高/中/证据不足）。
    if [ -n "$ATTACKER" ]; then
        freeze_attacker "$ATTACKER"
        show_alert "freeze"
    else
        # 没定位到具体包，无法冻结；不再输出“证据不足”文案。
        log_msg "no suspect package located"
        show_alert "warn"
    fi
}

restore_self() {
    pm enable --user 0 "$ME" >/dev/null 2>&1
    pm unsuspend --user 0 "$ME" >/dev/null 2>&1
    for c in \
        com.youlong.hd/.MainActivity \
        com.youlong.hd/.ForegroundService \
        com.youlong.hd/.ScreenFilterService \
        com.youlong.hd/.PerformanceService \
        com.youlong.hd/.FpsMonitorService \
        com.youlong.hd/.AdSkipService \
        com.youlong.hd/.ProtectService \
        com.youlong.hd/.AdbGuardService \
        com.youlong.hd/.RescueWindowService \
        com.youlong.hd/.DeviceAdminReceiver \
        com.youlong.hd/.BootReceiver \
        com.youlong.hd/roro.stellar.manager.authorization.RequestPermissionActivity \
        com.youlong.hd/roro.stellar.manager.authorization.CommandGuardActivity \
        com.youlong.hd/roro.stellar.manager.authorization.AdbGuardActivity \
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

# ===== V7：双 watchdog 互保 =====
# A、B 两个独立 shell 进程互为备份：任意一个被 kill，另一个会把它拉回来；
# 两者都监控 App 和 stellar_server。
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
        HIST=/data/local/tmp/youlong_adb_clients_B.hist
        ACTIVE=/data/local/tmp/youlong_adb_clients_B.active
        UIDCACHE=/data/local/tmp/youlong_uidcache_B
        EVIDENCE=/data/local/tmp/youlong_selfdefense_evidence_B.txt
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
        HIST=/data/local/tmp/youlong_adb_clients_A.hist
        ACTIVE=/data/local/tmp/youlong_adb_clients_A.active
        UIDCACHE=/data/local/tmp/youlong_uidcache_A
        EVIDENCE=/data/local/tmp/youlong_selfdefense_evidence_A.txt
        ;;
esac
SELF="$0"
[ -f "$SELF" ] || SELF="$BASE/self_defense.sh"

start_other() {
    # PID 还在但进程状态是 T/t → 被 SIGSTOP/冻结，杀掉重拉。
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
        # PID 被系统复用但不是本脚本：清掉旧状态，继续拉起。
        rm -f "$OTHER_PID" 2>/dev/null
        rm -rf "$OTHER_LOCK" 2>/dev/null
    fi
    # 对方可能正在启动：先等 1 秒看 PID 是否写出来，避免双方同时拉起两份。
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

# 防止重复启动（mkdir 是原子锁；LOCKDIR/PIDFILE/STATEFILE/LOG 已按角色设置）
# V30：安全守护关闭时，旧 watchdog 也必须退出，不能继续保活/拉起 App。
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

# 2026-11-01（用户需求）：自保启动时，把 pm list packages -s 获取到的
# 系统应用包名自动合并进用户/指令白名单，防止任何系统应用被误冻结。
# 只在 watchdog 真正启动时执行，保留原有用户白名单并去重。
if [ -s "$SYSPKGS" ]; then
    mkdir -p "$(dirname "$WLIST")" 2>/dev/null
    [ -f "$WLIST" ] || : >"$WLIST" 2>/dev/null
    # 2026-11-01：原白名单末尾可能没有换行，直接 cat 会把最后一个用户条目
    # 和第一个系统包名粘成一条（如 com.bbk.accountcom.android.camera）。
    # 合并前强制补一个换行，再和系统包名一起去重。
    { cat "$WLIST" 2>/dev/null; printf '\n'; cat "$SYSPKGS" 2>/dev/null; } \
        | sed '/^[[:space:]]*$/d' | sort -u >"$WLIST.tmp.$$" 2>/dev/null
    mv "$WLIST.tmp.$$" "$WLIST" 2>/dev/null
    log_msg "system packages merged into whitelist: $(wc -l < "$WLIST" 2>/dev/null)"
fi

start_other

# 初始化状态
if [ ! -f "$STATEFILE" ]; then
    d=0
    pm list packages -d 2>/dev/null | grep -qx "package:$ME" && d=1
    r=0
    pidof "$ME" >/dev/null 2>&1 && r=1
    printf '%s|%s|%s|%s\n' "$d" "$(read_components_disabled)" "$(read_a11y_ok)" "$r" >"$STATEFILE" 2>/dev/null
    # 脚本启动时如果已经被冻结，也立即处理一次
    if [ "$d" = "1" ]; then
        handle_abnormal "package-disabled-initial"
    fi
fi

while true; do
    if ! guard_enabled; then
        log_msg "安全守护已关闭，watchdog 退出"
        exit 0
    fi
    start_other
    enforce_frozen
    snapshot_adb_clients

    prev=$(cat "$STATEFILE" 2>/dev/null)
    prev_d=$(echo "$prev" | cut -d'|' -f1)
    prev_c=$(echo "$prev" | cut -d'|' -f2)
    prev_a=$(echo "$prev" | cut -d'|' -f3)
    prev_r=$(echo "$prev" | cut -d'|' -f4)

    # 包是否被卸载
    if ! pm list packages 2>/dev/null | grep -qx "package:$ME"; then
        log_msg "our package disappeared (uninstalled?)"
        printf '%s|%s|%s|%s\n' "$prev_d" "$prev_c" "$prev_a" "$prev_r" >"$STATEFILE" 2>/dev/null
        # 没有 App 可弹窗，用 shell 通知尽力提示
        cmd notification post -S bigtext -t "游龙自保" "selfdefense" "检测到游龙被卸载，请重新安装并启用设备管理器" >/dev/null 2>&1
        exit 0
    fi

    # V6：内置特权进程被强停/杀死 → 自动重启（watchdog 不在 Stellar 进程树里）。
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
    c=$(read_components_disabled)
    a=$(read_a11y_ok)
    r=0
    pidof "$ME" >/dev/null 2>&1 && r=1
    apid=$(pidof "$ME" 2>/dev/null | awk '{print $1}')
    if [ -n "$apid" ] && is_frozen_pid "$apid"; then
        log_msg "app process frozen; kill and restart"
        kill -9 "$apid" 2>/dev/null
        r=0
    fi

    if [ "$d" = "1" ] && [ "$prev_d" != "1" ]; then
        handle_abnormal "package-disabled"
    fi
    if [ -n "$c" ] && [ "$c" != "$prev_c" ]; then
        handle_abnormal "component-disabled"
    fi
    if [ "$a" = "0" ] && [ "$prev_a" = "1" ]; then
        handle_abnormal "accessibility-removed"
    fi
    # V6：App 进程被强停/回收后无条件拉起，不再只依赖 logcat 里的 force-stop 证据。
    # 如果检测到明确 force-stop，再额外走一次证据归因/提示。
    if [ "$r" = "0" ] && [ "$d" = "0" ]; then
        if [ "$prev_r" = "1" ] && logcat -d -b events -t 150 2>/dev/null | grep -qE "am_force_stop.*$ME|am_kill.*$ME.*(force-stop|shell)"; then
            handle_abnormal "process-killed"
        fi
        restart_app
    fi

    # 只在真的需要时恢复，避免每 2 秒 pm enable 造成 PackageManager 卡顿/Binder 超时。
    if [ "$d" = "1" ] || [ -n "$c" ] || [ "$a" = "0" ]; then
        restore_self
    fi

    # 已知恶意包：force-stop + 卸载
    for p in $(cat "$BLIST" 2>/dev/null) mio.TerminalDeity.Ultra; do
        [ -z "$p" ] && continue
        [ "$p" = "$ME" ] && continue
        case "$p" in \#*) continue;; esac
        if pm list packages 2>/dev/null | grep -qx "package:$p"; then
            log_msg "known malicious: $p"
            am force-stop --user 0 "$p" >/dev/null 2>&1
            pm uninstall --user 0 "$p" >/dev/null 2>&1
        fi
    done

    # 记录的是恢复之后的状态，否则下次 disable 时 prev_d 还是 1，会漏检。
    d=0
    pm list packages -d 2>/dev/null | grep -qx "package:$ME" && d=1
    c=$(read_components_disabled)
    a=$(read_a11y_ok)
    r=0
    pidof "$ME" >/dev/null 2>&1 && r=1
    printf '%s|%s|%s|%s\n' "$d" "$c" "$a" "$r" >"$STATEFILE" 2>/dev/null
    sleep 2
done
