// 修：openAppInfoPage() 在内部类里，外层 handleEntryPermissionResult 用不到 → 加外层版本
const fs = require('fs');
const P = 'E:/anquan/app/src/main/java/com/youlong/hd/MainActivity.java';
let t = fs.readFileSync(P, 'utf8').replace(/\r\n/g, '\n');
const orig = t.length;

const from = `    private void handleEntryPermissionResult(String deniedPermission) {`;
const to = `    /** 打开本应用「应用信息」页（外层版本，供权限兜底使用） */
    private void openAppInfoPageOuter() {
        try {
            Intent it = new Intent(
                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            it.setData(android.net.Uri.parse("package:" + getPackageName()));
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(it);
        } catch (Throwable tr) {
            Log.w("MainActivity", "打开应用信息页失败", tr);
        }
    }

    private void handleEntryPermissionResult(String deniedPermission) {`;

const n = t.split(from).length - 1;
if (n !== 1) { console.log('!! 锚点匹配 ' + n + ' 次'); process.exit(1); }
t = t.replace(from, to);
console.log('OK 新增 openAppInfoPageOuter()');

// 把外层调用改成外层版本
const c1 = t.split('                openAppInfoPage();\n            }\n            // 通知权限被拒').length - 1;
t = t.replace('                openAppInfoPage();\n            }\n            // 通知权限被拒',
              '                openAppInfoPageOuter();\n            }\n            // 通知权限被拒');
console.log('OK 外层调用改为 openAppInfoPageOuter()（替换 ' + c1 + ' 处）');

fs.writeFileSync(P, t, 'utf8');
console.log('\n' + orig + ' -> ' + t.length + ' bytes');
