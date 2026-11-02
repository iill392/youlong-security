package com.youlong.hd;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * 接收内置特权管理器发来的「进入内置特权界面」等事件，统一写运行日志。
 * 只在同应用内使用（exported=false）。
 */
public class RuntimeEventReceiver extends BroadcastReceiver {

    public static final String ACTION = "com.youlong.hd.action.RUNTIME_EVENT";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String event = intent.getStringExtra("event");
        String detail = intent.getStringExtra("detail");
        if (event == null || event.isEmpty()) event = "事件";
        RuntimeEventLog.log(context, "UI", event + (detail == null || detail.isEmpty() ? "" : "（" + detail + "）"));
    }
}
