// 清掉内部类里已经用不到的旧实现（suBinaryExists + 我加的占位方法）
const fs = require('fs');
const P = 'E:/anquan/app/src/main/java/com/youlong/hd/MainActivity.java';
let t = fs.readFileSync(P, 'utf8');
const orig = t.length;
const crlf = s => s.replace(/\r?\n/g, '\r\n');

function rep(from, to, label) {
  const f = crlf(from), g = crlf(to);
  const n = t.split(f).length - 1;
  if (n !== 1) { console.log('!! ' + label + ' 匹配 ' + n + ' 次，跳过'); return false; }
  t = t.replace(f, g);
  console.log('OK ' + label);
  return true;
}

rep(
`        /**
         * 2026-10 修正：原来这里还有一份 isPackageInstalled() 与 suBinaryExists()，
         * 与外层实现重复且 root 判定不可靠。现已统一到外层
         * （deviceIsRooted / deviceHasDhizuku / isInstalledPkg / suGrantsRoot），
         * 本内部类不再持有独立的 root 判定实现。
         */
        @SuppressWarnings("unused")
        private boolean isPackageInstalledUnusedPlaceholder() {
            return false;
        }

        /** 探测常见 su 二进制路径是否存在（只读探测，不执行） */
        private boolean suBinaryExists() {
            String[] paths = {
                    "/system/bin/su",
                    "/system/xbin/su",
                    "/sbin/su",
                    "/su/bin/su",
                    "/system/sbin/su",
                    "/vendor/bin/su",
                    "/debug_ramdisk/su",
                    "/data/adb/ksu/bin/su",
                    "/data/adb/ap/bin/su",
                    "/data/adb/magisk/busybox"
            };
            for (String p : paths) {
                try {
                    if (new java.io.File(p).exists()) return true;
                } catch (Throwable ignored) {
                }
            }
            return false;
        }

        /** 查询隐藏后台开关状态 */`,
`        // 2026-10 修正（用户反馈：没 root 被误判有 root）：
        // 这里原本还有一份 isPackageInstalled() 与 suBinaryExists()，与外层重复，
        // 且用"su 文件存在"直接下 root 结论 —— 部分 ROM / 工程模式 / 残留文件
        // 会有同名文件但设备并没有 root，于是误判。
        // 现在 root 判定统一到外层的 deviceIsRooted()（实证：su -c id 返回 uid=0），
        // 本内部类不再持有独立的判定实现。

        /** 查询隐藏后台开关状态 */`,
'清掉内部类里用不到的旧 root 判定实现');

fs.writeFileSync(P, t, 'utf8');
console.log('\n' + orig + ' -> ' + t.length + ' bytes');

// 复查
const t2 = fs.readFileSync(P, 'utf8');
console.log('复查 suBinaryExists 残留: ' + (t2.split('suBinaryExists').length - 1) + '（应只剩注释）');
console.log('复查 isPackageInstalled 出现处: ' + (t2.split('isPackageInstalled').length - 1));
