# 第 20 轮改动汇总（2026-10-18）

本轮：删除「超级拦截模式」，恢复「极强拦截模式」，版本升到 9.2.1。

---

## 一、极强拦截模式回归

原生 `ProtectService` / `ShieldWarnActivity` 里本来就保留了完整的 `extreme` 实现
（`pm list packages -3` → 逐个 `am force-stop`，5 秒倒计时，自动跳过游龙安全护盾），
只是前几轮把 UI 入口删了。这轮恢复入口：

- [index.html](<E:/11/anquan/anquan/app/src/main/assets/index.html>)
  - 模式列表新增 `#modeExtreme` / `#radioExtreme`「极强拦截模式」；
  - 点击需先连接内置特权：`requireShizuku(() => setMode('extreme'))`；
  - `updateModeUI()` 支持 `basic / daily / extreme / final` 四种模式；
  - 内置特权断开时，`daily / extreme / final` 自动降级为基础模式，与原生一致。
- 原生 [ProtectService.java](<E:/11/anquan/anquan/app/src/main/java/com/youlong/hd/ProtectService.java>)
  - `extreme` 分支保留：覆盖层标题「极强拦截 · 全面停止」、5 秒倒计时、
    `pm list packages -3` 逐个 `am force-stop`、跳过白名单/自家应用；
  - 音量键/摇一摇救援的严格模式判断包含 `extreme`；
  - Shizuku 断连自动降级逻辑包含 `extreme`。

## 二、删除超级拦截模式

- 移除 `#modeSuper` / `#radioSuper` 及对应点击、UI 更新、`privilegedMode` 判断；
- 模式只保留 `basic / daily / extreme / final`；
- 老用户本地若还存着 `"super"`：
  - 页面启动时识别为非法模式，回落 `basic` 并写回原生；
  - `ProtectService.onCreate()` 再做一次兜底迁移，避免后台仍按已删除模式执行。

原生 `super` 分支代码未删除（只是没有入口/会被迁移），防止历史配置在极端情况下
产生未定义行为；实际用户已无法选择或保持超级拦截模式。

## 三、版本

- `versionName`：`9.2.0` → **`9.2.1`**
- `versionCode`：`1234567943` → **`1234567944`**
- 病毒库请求 User-Agent：`YulongShield/9.2.1`

## 四、产物

- APK：[游龙安全护盾-9.2.1-release-20261017.apk](<E:/11/anquan/anquan/游龙安全护盾-9.2.1-release-20261017.apk>)
- SHA-256：`AAB03DD63CD524B41C87E00285C1590A6FE8072F137D31538F008B783E343F98`
- HTML 内联 JS 已通过 `node --check`；release 构建通过。
- 设备仍不在线，未做真机模式切换验证。
