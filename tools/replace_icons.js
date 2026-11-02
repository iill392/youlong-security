// 批量替换：Stellar 图标 -> 宿主使用的系统通知图标 / 宿主启动图标
// 用法: node replace_icons.js
const fs = require('fs');
const path = require('path');

const M = 'E:/anquan/embedded/manager/src/main/kotlin/roro/stellar/manager/';

const edits = [
  // 通知小图标 -> 与宿主其它前台服务一致的系统图标
  { file: M + 'adb/AdbPairingService.kt',
    from: 'R.drawable.ic_stellar', to: 'android.R.drawable.ic_menu_manage' },
  { file: M + 'startup/notification/BootStartNotifications.kt',
    from: 'R.drawable.ic_stellar', to: 'android.R.drawable.ic_menu_manage' },
  // 快捷方式 -> 宿主自己的启动图标
  { file: M + 'shortcut/CommandShortcutManager.kt',
    from: 'R.drawable.ic_stellar', to: 'R.mipmap.ic_launcher' },
  // 权限确认弹窗 -> 宿主自己的启动图标
  { file: M + 'authorization/RequestPermissionActivity.kt',
    from: 'R.drawable.ic_stellar', to: 'R.mipmap.ic_launcher' },
];

for (const e of edits) {
  if (!fs.existsSync(e.file)) { console.log('SKIP (missing): ' + e.file); continue; }
  let t = fs.readFileSync(e.file, 'utf8');
  const n = t.split(e.from).length - 1;
  if (n === 0) { console.log('NO-OP: ' + path.basename(e.file)); continue; }
  t = t.split(e.from).join(e.to);
  fs.writeFileSync(e.file, t, 'utf8');
  console.log('OK ' + path.basename(e.file) + ' -> ' + n + ' occurrence(s) replaced with ' + e.to);
}
