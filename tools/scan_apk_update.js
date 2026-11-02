// 在已编译的 APK 里搜"更新模块"特征串（二进制安全）
const fs = require('fs');
const path = require('path');
const os = require('os');
const { execSync } = require('child_process');

const APK = 'E:/anquan/游龙安全护盾-9.1.0-release.apk';
const work = path.join(os.tmpdir(), 'apk_scan_update');
fs.rmSync(work, { recursive: true, force: true });
fs.mkdirSync(work, { recursive: true });

// 用 .NET 解压（Node 无内置 zip），改走 PowerShell 已经做过；
// 这里直接读 APK 内的 dex 需要先解包，所以退一步：用 unzip 式读取
// 简单起见调用 powershell Expand-Archive（APK 是 zip）
execSync(
  `powershell -NoProfile -Command "Copy-Item '${APK}' '${work}/a.zip' -Force; Expand-Archive '${work}/a.zip' -DestinationPath '${work}' -Force"`,
  { stdio: 'ignore' }
);

const needles = [
  'UpdateUtils', 'AppUpdate', 'ApkDownloader', 'UpdateSource', 'DownloadState',
  'api.github.com', 'gitee.com/api', 'releases/latest',
  'checkUpdate', 'new_version_title', 'select_update_source',
  'go_to_download', 'no_download_available', 'already_latest_version',
  'check_update_failed', 'download_failed', 'stellar_',
];

// 扫描所有 dex + 资源表
const files = fs.readdirSync(work).filter(f => /\.dex$/.test(f));
console.log('dex 文件: ' + files.join(', '));
console.log('');

const hits = {};
for (const f of files) {
  const buf = fs.readFileSync(path.join(work, f));
  // dex 里字符串是 MUTF-8，直接按 latin1 扫足够
  const s = buf.toString('latin1');
  for (const n of needles) {
    if (s.includes(n)) {
      hits[n] = hits[n] || [];
      hits[n].push(f);
    }
  }
}

console.log('=== dex 命中情况 ===');
if (Object.keys(hits).length === 0) {
  console.log('  （无任何更新模块特征串命中）');
} else {
  for (const [k, v] of Object.entries(hits)) {
    console.log('  ' + k + '  ->  ' + v.join(', '));
  }
}

// 顺带看资源表里还有没有更新相关的字符串资源
const resFiles = fs.readdirSync(path.join(work, 'res'));
console.log('');
console.log('res/ 下文件数: ' + resFiles.length);
