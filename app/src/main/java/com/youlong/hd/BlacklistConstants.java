package com.youlong.hd;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * 软件级黑名单常量（硬编码，所有检测模块共用）
 * 安装了这些包名 → 无条件弹窗拦截
 */
public final class BlacklistConstants {

    private BlacklistConstants() {}

    public static final Set<String> HARDCODED_BLACKLIST = new HashSet<>(Arrays.asList(
        "com.cange.wd",
        "com.mycompany.application",
        "com.liangcheng.mini",
        "c18.dhmgbup.pgmt.tnecnet.moc.abef971.m0",
        "com.huge.p34366425",
        "com.i6b401775ea8ced552",
        "com.i9236f23a6f538c44",
        "com.i838d322f2a6b750a",
        "com.Tsc",
        "com.my.evil.tim",
        "com.zz166666556",
        "com.Qinjian",
        "com.qq2973357235.wzasj",
        "com.nancisancongsuoji",
        "com.Mixo.XiaoBao",
        "com.Tong.nb"
    ));
}
