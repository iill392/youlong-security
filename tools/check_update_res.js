// 1) 更新相关字符串资源是否还被资源压缩器保留
// 2) dex 里 stellar_ 串的上下文
const fs = require('fs');
const path = require('path');
const os = require('os');
const { execSync } = require('child_process');

const APK = 'E:/anquan/游龙安全护盾-9.1.0-release.apk';
const aapt2 = 'D:/Android/sdk/build-tools/37.0.0/aapt2.exe';
const out = path.join(os.tmpdir(), 'res_dump.txt');

execSync(`"${aapt2}" dump resources "${APK}" > "${out}"`, { stdio: 'ignore', shell: 'cmd.exe' });
const t = fs.readFileSync(out, 'utf8');

const keys = ['new_version_title', 'select_update_source', 'go_to_download',
  'no_download_available', 'already_latest_version', 'check_update_failed',
  'download_failed', 'check_update', 'current_version', 'install', 'downloading'];

console.log('=== 更新相关字符串资源在 APK 资源表里的状态 ===');
for (const k of keys) {
  const re = new RegExp('string/' + k + '\\b');
  console.log('  ' + k.padEnd(24) + (re.test(t) ? '仍在资源表' : '已被裁掉 ✅'));
}

console.log('');
console.log('=== dex 里 "stellar_" 的上下文 ===');
const dex = path.join(os.tmpdir(), 'apk_scan_update', 'classes3.dex');
const s = fs.readFileSync(dex).toString('latin1');
let i = -1, n = 0;
while ((i = s.indexOf('stellar_', i + 1)) >= 0 && n < 8) {
  console.log('  ' + JSON.stringify(s.slice(Math.max(0, i - 40), i + 50)));
  n++;
}
if (n === 0) console.log('  （无）');
