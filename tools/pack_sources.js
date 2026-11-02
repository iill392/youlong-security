// 打包源码为 zip —— 用 Node 收集 + tar 压缩（UTF-8 友好，避开 PowerShell 编码坑）
const fs = require('fs');
const path = require('path');
const { execFileSync } = require('child_process');

const ROOT = 'E:/anquan';
const stamp = new Date().toISOString().replace(/[-:T]/g, '').slice(0, 14);   // 去掉末尾的点
const ZIP = path.join(ROOT, `游龙安全护盾-源码-${stamp}.zip`);
const STAGE_ROOT = path.join(process.env.TEMP, `yl_src_${stamp}`);
const STAGE = path.join(STAGE_ROOT, '游龙安全护盾-源码');

const EXCLUDE_DIRS = new Set([
  'build_out', 'build', '.gradle', '.kotlin', '.idea', 'prefab',
  '__pycache__', 'node_modules', '.git', 'captures', '.cxx', '.fleet',
  '_vzip', '_vzip7z', '_verify_extract',
]);
const EXCLUDE_ROOT_FILES = new Set([
  'classes.dex', 'classes_dex_dump.txt', 'build_virusdb2.log', 'shizuku_removed_2026.zip',
  '_verify_out.txt',
]);
const EXCLUDE_EXT = new Set(['.apk', '.aar', '.zip', '.log', '.dex', '.hprof', '.pyc']);

let copied = 0, skipped = 0, bytes = 0;

function copyTree(src, dst, depth) {
  fs.mkdirSync(dst, { recursive: true });
  for (const e of fs.readdirSync(src, { withFileTypes: true })) {
    const s = path.join(src, e.name);
    const d = path.join(dst, e.name);
    if (e.isDirectory()) {
      if (EXCLUDE_DIRS.has(e.name) || e.name.endsWith('.prefab')) { skipped++; continue; }
      copyTree(s, d, depth + 1);
      continue;
    }
    if (depth === 0 && EXCLUDE_ROOT_FILES.has(e.name)) { skipped++; continue; }
    if (EXCLUDE_EXT.has(path.extname(e.name).toLowerCase())) { skipped++; continue; }
    fs.copyFileSync(s, d);
    copied++;
    bytes += fs.statSync(d).size;
  }
}

fs.rmSync(STAGE_ROOT, { recursive: true, force: true });
console.log('收集中（只收源码与文档，跳过构建产物）...');
copyTree(ROOT, STAGE, 0);
console.log(`文件 ${copied} 个，跳过 ${skipped} 项，原始 ${(bytes / 1048576).toFixed(1)} MB`);

if (fs.existsSync(ZIP)) fs.rmSync(ZIP, { force: true });

// 用自研 ZIP 写入器（强制 UTF-8 文件名 + 0x800 标志位）
// 旧的 `tar -a` 会把中文名按 GBK 写入且不置标志，导致手机/7-Zip/WinRAR 上全是乱码
const { makeZip } = require('./make_zip.js');
const r = makeZip(ZIP, STAGE, '游龙安全护盾-源码');
console.log(`ZIP 写入完成: 文件 ${r.files} 个，目录 ${r.dirs} 个（UTF-8 文件名）`);

fs.rmSync(STAGE_ROOT, { recursive: true, force: true });

if (!fs.existsSync(ZIP)) { console.log('!! 压缩产物不存在'); process.exit(1); }
const z = fs.statSync(ZIP);
console.log('');
console.log('ZIP : ' + ZIP);
console.log('大小: ' + (z.size / 1048576).toFixed(1) + ' MB');
