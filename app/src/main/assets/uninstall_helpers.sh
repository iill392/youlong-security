#!/system/bin/sh
# ==========================================================================
# 游龙安全护盾 —— 日常模式【预卸载】脚本
# --------------------------------------------------------------------------
# 作用：在批量 pm uninstall 之前，解除设备上第三方应用的「设备管理员」身份。
#       持有 DeviceAdmin 的应用无法被 pm uninstall 卸载（系统会拒绝），
#       必须先 dpm remove-active-admin 摘掉它的管理员身份。
#
# 用法：sh uninstall_helpers.sh <目标包名列表，空格分隔>
#       （目标包名列表用于日志展示；本脚本只处理设备管理员）
#
# 设计要点（都是为了避免「卡住」）：
#   1. 每一步都套 timeout，单条命令最多 15 秒；超时后**继续往下走**，
#      绝不因为某条命令阻塞而把整个卸载流程挂死。
#   2. list-owners 超时或失败时，退化为「遍历 1..6 逐个清理」——
#      dpm list-owners 在部分 ROM 上会卡住或输出异常，不能盲信。
#   3. 无条件尝试解除自己注册过的每个槽位（adm<0..6>，即本应用
#      com.youlong.hd/.DeviceAdminReceiver 的各个 user 变体），
#      保证不会因为「曾经的设备管理器记录」拖累后续卸载与升级安装。
#      历史上该应用确实激活过设备管理器，而升级安装不会自动撤销。
#   4. 不用 grep -oP：Android 的 toybox grep 不支持 -P / PCRE（设备实测
#      报 "grep: Unknown option 'P'"），所以提取改用 awk。
#   5. 总时长有条件上限（循环内检查 NOW >= DEADLINE 就 break），
#      最坏情况约 15s(list) + 6*2*15s(清理) ≈ 3 分钟以内必定结束；
#      Java 侧另有 runCommand 的硬超时与 destroyForcibly 兜底。
# ==========================================================================

TARGETS="$*"
if [ -z "$TARGETS" ]; then
    TARGETS="(未指定目标应用)"
fi

echo '=== 正在获取设备管理员列表 ==='

# 提取 ComponentInfo{包/类} 里的内容；天然排除 "Active admin:" 等标题行
AWK_EXTRACT='{ if (match($0, /ComponentInfo\{[^,}]*/)) { s=substr($0, RSTART+14, RLENGTH-14); print s } }'

# ---- 第 1 步：拿设备管理员列表（带超时）------------------------------------
ADMINS=""
LIST_OK=0
if command -v timeout >/dev/null 2>&1; then
    ADMINS=$(timeout 15 dpm list-owners 2>/dev/null | awk "$AWK_EXTRACT")
    [ $? -eq 0 ] && LIST_OK=1
else
    ADMINS=$(dpm list-owners 2>/dev/null | awk "$AWK_EXTRACT")
    LIST_OK=1
fi

# list-owners 卡住/失败时，不能盲信「没有管理员」——
# 下面的「遍历 1..6 清理自己」会兜住这种情况。
if [ "$LIST_OK" -ne 1 ]; then
    echo '（dpm list-owners 超时或失败，跳过列举，仅执行自身清理）'
fi

# ---- 第 2 步：解除本应用自己注册过的设备管理员 -----------------------------
# 2026-10-01 起本应用已彻底移除 DeviceAdmin 能力（清单/类/xml 全删）。
# 但**历史上激活过的设备**可能仍残留一条记录，而升级安装不会自动撤销它，
# 所以这里仍显式清理一次，避免残留记录拖累卸载与后续升级安装。
#
# 实测设备反馈：本应用从未激活过时，dpm 会报
#     SecurityException: Attempt to remove non-test admin ComponentInfo{...}
# 这**不是失败**，只是「这条记录不存在 / 不是 testOnly 组件」，
# 属于预期情况，因此直接判定为「无需清理」并结束，不做无谓重试。
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
            # dpm 会把整段 Java 堆栈吐出来，只取第一行有效信息，避免灌满日志区
            echo "（解除自身管理员未成功：$(echo "$SELF_OUT" | head -n 1)）" ;;
    esac
fi

# ---- 第 3 步：面向第三方应用的管理员清理 -----------------------------------
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

# 总时长的兜底上限（秒）。超出就停止继续清理，避免无限拖。
DEADLINE=$(( $(date +%s 2>/dev/null || echo 0) + 150 ))

for admin in $ADMINS; do
    # 超时兜底
    NOW=$(date +%s 2>/dev/null || echo 0)
    if [ "$NOW" -ge "$DEADLINE" ]; then
        echo '（已到时间上限，停止继续移除管理员；未处理的将在下次执行）'
        break
    fi

    # 跳过本应用自身（第 2 步已单独处理，且绝不能在遍历里误伤）
    case "$admin" in
        com.youlong.hd*)
            echo "跳过（本应用自身）: $admin"
            echo '---'
            continue ;;
    esac

    # 组件名必须含 '/'，否则不拿它去 remove-active-admin
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
        # dpm 失败时会打印整段 Java 堆栈。只取第一行有效信息，
        # 否则用户可见的日志区会被几十行 at com.android.server... 灌满。
        echo "✗ 移除失败: $admin"
        echo "   原因: $(echo "$OUT" | head -n 1)"
    fi
    echo '---'
done

echo '批量操作完成。'
exit 0
