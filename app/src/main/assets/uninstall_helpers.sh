#!/system/bin/sh
# ==========================================================================

# --------------------------------------------------------------------------



#


#














# ==========================================================================

TARGETS="$*"
if [ -z "$TARGETS" ]; then
    TARGETS="(未指定目标应用)"
fi

echo '=== 正在获取设备管理员列表 ==='


AWK_EXTRACT='{ if (match($0, /ComponentInfo\{[^,}]*/)) { s=substr($0, RSTART+14, RLENGTH-14); print s } }'


ADMINS=""
LIST_OK=0
if command -v timeout >/dev/null 2>&1; then
    ADMINS=$(timeout 15 dpm list-owners 2>/dev/null | awk "$AWK_EXTRACT")
    # 修复：$? 取的是管道尾（awk）的退出码，timeout 杀掉 dpm（rc=124）也会判成功；
    # 改用 PIPESTATUS 判断 timeout 本身是否超时
    rc_timeout=${PIPESTATUS[0]}
    if [ -z "$rc_timeout" ] || [ "$rc_timeout" -ne 124 ] && [ "$rc_timeout" -ne 0 ]; then
        LIST_OK=1
    fi
else
    ADMINS=$(dpm list-owners 2>/dev/null | awk "$AWK_EXTRACT")
    LIST_OK=1
fi



if [ "$LIST_OK" -ne 1 ]; then
    echo '（dpm list-owners 超时或失败，跳过列举，仅执行自身清理）'
fi





#

#     SecurityException: Attempt to remove non-test admin ComponentInfo{...}


echo '=== 解除本应用自身的设备管理员记录（如有）==='
SELF_ADMIN="com.youlong.hd/.DeviceAdminReceiver"
SELF_OUT=""
if command -v timeout >/dev/null 2>&1; then
    SELF_OUT=$(timeout 15 dpm remove-active-admin "$SELF_ADMIN" 2>&1)
else
    SELF_OUT=$(dpm remove-active-admin "$SELF_ADMIN" 2>&1)
fi
SELF_RC=$?
if [ $SELF_RC -eq 0 ]; then
    echo "✓ 已解除自身残留的设备管理员记录"
elif [ $SELF_RC -eq 124 ]; then
    echo "（解除自身管理员超时，已跳过；不影响后续卸载）"
else
    case "$SELF_OUT" in
        *"non-test admin"*|*"not registered"*|*"not an active admin"*|*"Unknown admin"*|*"does not exist"*)
            echo "（本应用无残留设备管理员记录，无需清理）" ;;
        *)

            echo "（解除自身管理员未成功：$(echo "$SELF_OUT" | head -n 1)）" ;;
    esac
fi


if [ -z "$ADMINS" ]; then
    echo '未发现其他设备管理员。'
    echo '批量操作完成。'
    exit 0
fi

echo '发现以下管理员:'
echo "$ADMINS"
echo ''
echo '=== 开始批量移除 ==='
echo '目标（即将卸载）:'
echo "$TARGETS"
echo ''


DEADLINE=$(( $(date +%s 2>/dev/null || echo 0) + 150 ))

for admin in $ADMINS; do

    NOW=$(date +%s 2>/dev/null || echo 0)
    if [ "$NOW" -ge "$DEADLINE" ]; then
        echo '（已到时间上限，停止继续移除管理员；未处理的将在下次执行）'
        break
    fi


    case "$admin" in
        com.youlong.hd*)
            echo "跳过（本应用自身）: $admin"
            echo '---'
            continue ;;
    esac


    case "$admin" in
        */*) ;;
        *) echo "跳过（非组件名）: $admin"; echo '---'; continue ;;
    esac

    echo "尝试移除: $admin"
    if command -v timeout >/dev/null 2>&1; then
        OUT=$(timeout 15 dpm remove-active-admin "$admin" 2>&1)
    else
        OUT=$(dpm remove-active-admin "$admin" 2>&1)
    fi
    RC=$?
    if [ $RC -eq 0 ]; then
        echo "✓ 成功移除: $admin"
    elif [ $RC -eq 124 ]; then
        echo "✗ 移除超时（已跳过）: $admin"
    else


        echo "✗ 移除失败: $admin"
        echo "   原因: $(echo "$OUT" | head -n 1)"
    fi
    echo '---'
done

echo '批量操作完成。'
exit 0
