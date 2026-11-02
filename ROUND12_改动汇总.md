# 第 12 轮改动汇总（2026-10-01）

## 去掉每次进入都弹的「提示」弹窗

### 定位

用户描述是「每次进入都有那个什么 sh 文件不能防的弹窗」。
在 `app/src/main/assets/index.html` 里找到出处 —— **免责声明弹窗** `showDisclaimer()`：

```js
'<div class="modal-title">提示</div>'+
'<div class="modal-body">目前<strong>格机脚本 sh 文件</strong>防不了，建议大家
 <strong>日常尽量不要开</strong>，因为可能会<strong>误伤</strong>，开启后
 <strong>一切由自己负责</strong>，我们<strong>概不负责</strong>。</div>'+
'<div class="modal-footer"><button class="modal-btn" id="disclaimerBtn">我知道了</button></div>'
```

它由 `init()` 第一个调用，所以**每次进入应用都会弹**，
而且是一个 `modal-overlay` 遮罩层，会盖住下面的所有点击。

### 改动

1. **删除函数体** `showDisclaimer()`（整块，含 `disclaimerBtn` 及其点击处理）；
2. **删除 `init()` 里的调用**，并在原位置留注释说明；
3. 复查：`disclaimerBtn` 残留 0、`<div class="modal-title">提示</div>` 残留 0、
   「格机脚本」只出现在注释里（1 处）。

### 真机验证（安装后强停再启动）

```
=== 免责弹窗是否已消失 ===
  格机脚本   ✅ 已消失
  防不了     ✅ 已消失
  概不负责   ✅ 已消失
  我知道了   ✅ 已消失
  提示       ✅ 已消失
```

进入后页面文本直接是首页内容，没有任何遮挡层：

```
游龙安全护盾 / 首页 / 自动拦截 / 需授权 无障碍权限 / …
紧急逃生操作 以 猛摇 力度摇晃手机 立即触发紧急拦截救援
开启实时守护 … 已守护 1h51m
拦截模式 / 日常模式2.0（100%拦截） 推荐
全网首发，病毒拦截率为100%，误伤率基本没有
```

无崩溃（`adb logcat -b crash` 无 `com.youlong.hd` 记录）。

### 连带修正的文档

`MIGRATION_STELLAR.md` 里有一条「排错提示：首页有个免责声明弹窗会挡住点击」，
它指导自动化测试先点掉 `disclaimerBtn` —— 该弹窗已删除，这条提示随之作废。
已在文档里标注作废并保留原文作为历史记录。

---

## 构建与安装

```
JAVA_HOME=D:\scoop\apps\temurin21-jdk\21.0.12-8.0
gradlew :app:assembleRelease  → BUILD SUCCESSFUL in 11m 4s
游龙安全护盾-9.1.0-release.apk  37.9 MB  (app-release-20261001-130928.apk)
pm install -r -d  → Success  (lastUpdateTime=2026-10-01 13:24:03, versionName=9.1.0)
```
