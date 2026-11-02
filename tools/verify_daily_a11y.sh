#!/bin/sh
# ==========================================================================
# 日常模式无障碍收窄脚本的正确性验证（POSIX sh）
#
# 验证目标：ProtectService.runDailyAccessibilityHarden() 拼出来的脚本
#           是否能把 enabled_accessibility_services 收窄到
#           **只保留本应用自己的服务**。
#
# 这里刻意用 "tr ':' '\n'"（单引号里的真实换行）——
# 也就是 Android shell 里那个转义序列被解释之后的效果。
# ==========================================================================
set -u

T=com.youlong.hd

# 与 Java 侧完全一致的过滤表达式
filter() {
    C="$1"
    printf '%s' "$C" | tr ':' '\n' | grep "^$T/" | tr '\n' ':' | sed 's/:$//'
}

pass=0
fail=0
check() {
    desc="$1"; input="$2"; want="$3"
    got=$(filter "$input")
    if [ "$got" = "$want" ]; then
        pass=$((pass + 1))
        printf 'PASS  %-38s got=[%s]\n' "$desc" "$got"
    else
        fail=$((fail + 1))
        printf 'FAIL  %-38s got=[%s] want=[%s]\n' "$desc" "$got" "$want"
    fi
}

check "自身服务 + 两个第三方" \
      "com.youlong.hd/com.youlong.hd.AdSkipService:com.evil.ransom/com.evil.ransom.A11y:com.other.app/com.other.app.Svc" \
      "com.youlong.hd/com.youlong.hd.AdSkipService"

check "settings 返回 null（未启用过）" \
      "null" \
      ""

check "只有第三方服务" \
      "com.evil.ransom/com.evil.ransom.A11y:com.other.app/com.other.app.Svc" \
      ""

check "自身服务在中间" \
      "com.a/b:com.youlong.hd/com.youlong.hd.AdSkipService:com.c/d" \
      "com.youlong.hd/com.youlong.hd.AdSkipService"

check "自身有多个服务（多进程）" \
      "com.youlong.hd/com.youlong.hd.AdSkipService:com.evil/x:com.youlong.hd/com.youlong.hd.OtherSvc" \
      "com.youlong.hd/com.youlong.hd.AdSkipService:com.youlong.hd/com.youlong.hd.OtherSvc"

check "前缀相似但不属于本应用" \
      "com.youlong.hd2/com.youlong.hd2.A11y:com.youlong.hd/com.youlong.hd.AdSkipService" \
      "com.youlong.hd/com.youlong.hd.AdSkipService"

check "空串输入" \
      "" \
      ""

printf '\nTOTAL: PASS=%d FAIL=%d\n' "$pass" "$fail"
[ "$fail" -eq 0 ]
