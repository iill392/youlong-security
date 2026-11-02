// 校验源码 zip：与工程目录逐文件 SHA256 对比 + 文件名编码检查 + 乱码扫描
// 用法: node tools/verify_source_zip.js <zip路径>
const fs = require('fs');
const path = require('path');
const zlib = require('zlib');
const crypto = require('crypto');

const ROOT = 'E:/anquan';
const ZIP = process.argv[2] || 'E:/anquan/游龙安全护盾-源码-20261002075931.zip';
const TOP = '游龙安全护盾-源码/';

// 与 pack_sources.js 完全一致的收集规则
const EXCLUDE_DIRS = new Set([
  'build_out', 'build', '.gradle', '.kotlin', '.idea', 'prefab',
  '__pycache__', 'node_modules', '.git', 'captures', '.cxx', '.fleet',
]);
const EXCLUDE_ROOT_FILES = new Set([
  'classes.dex', 'classes_dex_dump.txt', 'build_virusdb2.log', 'shizuku_removed_2026.zip',
]);
const EXCLUDE_EXT = new Set(['.apk', '.aar', '.zip', '.log', '.dex', '.hprof', '.pyc']);
// 校验脚本自身与本次校验产生的临时目录也要排除
const EXCLUDE_EXTRA_NAMES = new Set();
const EXCLUDE_EXTRA_ROOT_DIRS = new Set(['_vzip', '_verify_extract', '_log']);

/* ---------------- CRC32 ---------------- */
const CRC_TABLE = (() => {
  const t = new Int32Array(256);
  for (let n = 0; n < 256; n++) {
    let c = n;
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    t[n] = c;
  }
  return t;
})();
function crc32(buf) {
  let c = 0 ^ -1;
  for (let i = 0; i < buf.length; i++) c = (c >>> 8) ^ CRC_TABLE[(c ^ buf[i]) & 0xff];
  return (c ^ -1) >>> 0;
}

/* ---------------- 解析 ZIP ---------------- */
const buf = fs.readFileSync(ZIP);
let eocd = -1;
for (let i = buf.length - 22; i >= Math.max(0, buf.length - 66000); i--) {
  if (buf.readUInt32LE(i) === 0x06054b50) { eocd = i; break; }
}
if (eocd < 0) { console.log('!! 找不到 EOCD，zip 结构损坏'); process.exit(1); }
const total = buf.readUInt16LE(eocd + 10);
const cdSize = buf.readUInt32LE(eocd + 12);
const cdOff = buf.readUInt32LE(eocd + 16);
console.log(`EOCD: 条目=${total} 目录=${cdSize}B @${cdOff}`);

const entries = [];
let p = cdOff;
for (let i = 0; i < total; i++) {
  if (buf.readUInt32LE(p) !== 0x02014b50) { console.log(`!! 第${i}条中央目录签名错误 @${p}`); break; }
  const flags = buf.readUInt16LE(p + 8);
  const method = buf.readUInt16LE(p + 10);
  const crc = buf.readUInt32LE(p + 16);
  const csize = buf.readUInt32LE(p + 20);
  const usize = buf.readUInt32LE(p + 24);
  const nlen = buf.readUInt16LE(p + 28);
  const elen = buf.readUInt16LE(p + 30);
  const clen = buf.readUInt16LE(p + 32);
  const loff = buf.readUInt32LE(p + 42);
  const nameBytes = buf.subarray(p + 46, p + 46 + nlen);
  const utf8Flag = (flags & 0x800) !== 0;
  const nameUtf8 = nameBytes.toString('utf8');
  const roundTrip = Buffer.from(nameUtf8, 'utf8').equals(nameBytes);
  entries.push({ name: nameUtf8, nameBytes, utf8Flag, roundTrip, method, crc, csize, usize, loff, flags });
  p += 46 + nlen + elen + clen;
}

/* ---------------- 文件名编码检查 ---------------- */
const noFlag = entries.filter(e => !e.utf8Flag);
const badRound = entries.filter(e => !e.roundTrip);
const badChar = entries.filter(e => /[\uFFFD]/.test(e.name));
console.log('');
console.log('--- 文件名编码 ---');
console.log(`条目标志位11(UTF-8)已置位: ${entries.length - noFlag.length}/${entries.length}`);
console.log(`UTF-8 往返失败(真乱码): ${badRound.length}`);
console.log(`含替换字符 U+FFFD: ${badChar.length}`);
if (noFlag.length) {
  console.log('  未置 UTF-8 标志的条目（在中文 Windows 资源管理器里可能显示为乱码）示例:');
  for (const e of noFlag.slice(0, 8)) console.log('   ' + e.name);
}
badRound.slice(0, 10).forEach(e => console.log('  往返失败: ' + e.name + '  bytes=' + e.nameBytes.toString('hex')));

/* ---------------- 解压 + CRC 校验 ---------------- */
function extract(e) {
  const lp = e.loff;
  if (buf.readUInt32LE(lp) !== 0x04034b50) throw new Error('本地头签名错误: ' + e.name);
  const nlen = buf.readUInt16LE(lp + 26);
  const elen = buf.readUInt16LE(lp + 28);
  const start = lp + 30 + nlen + elen;
  const raw = buf.subarray(start, start + e.csize);
  if (e.method === 0) return Buffer.from(raw);
  if (e.method === 8) return zlib.inflateRawSync(raw);
  throw new Error('不支持压缩方法 ' + e.method + ': ' + e.name);
}

const sha = b => crypto.createHash('sha256').update(b).digest('hex');
const zipFiles = new Map();
let dirs = 0, crcBad = 0, sizeBad = 0, readErr = 0;
for (const e of entries) {
  if (e.name.endsWith('/')) { dirs++; continue; }
  try {
    const data = extract(e);
    if (data.length !== e.usize) { sizeBad++; console.log('  尺寸不符: ' + e.name); }
    if (crc32(data) !== e.crc) { crcBad++; console.log('  CRC 不符: ' + e.name); }
    zipFiles.set(e.name, { sha: sha(data), size: data.length, data });
  } catch (err) {
    readErr++;
    console.log('  解压失败: ' + e.name + ' -> ' + err.message);
  }
}
console.log('');
console.log('--- zip 内容完整性 ---');
console.log(`目录条目 ${dirs}，文件条目 ${zipFiles.size}，CRC 错误 ${crcBad}，尺寸错误 ${sizeBad}，解压失败 ${readErr}`);

/* ---------------- 与工程目录逐文件比对 ---------------- */
function walk(dir, rel, out) {
  for (const ent of fs.readdirSync(dir, { withFileTypes: true })) {
    const s = path.join(dir, ent.name);
    const r = rel ? rel + '/' + ent.name : ent.name;
    if (ent.isDirectory()) {
      if (EXCLUDE_DIRS.has(ent.name) || ent.name.endsWith('.prefab')) continue;
      if (!rel && EXCLUDE_EXTRA_ROOT_DIRS.has(ent.name)) continue;
      walk(s, r, out);
      continue;
    }
    if (!rel && EXCLUDE_ROOT_FILES.has(ent.name)) continue;
    if (EXCLUDE_EXT.has(path.extname(ent.name).toLowerCase())) continue;
    if (EXCLUDE_EXTRA_NAMES.has(ent.name)) continue;
    out.push(r);
  }
  return out;
}
const treeRels = walk(ROOT, '', []).sort();

let missing = 0, mismatch = 0, matched = 0;
const treeSet = new Set(treeRels);
for (const r of treeRels) {
  const z = zipFiles.get(TOP + r);
  if (!z) { missing++; if (missing <= 15) console.log('  缺失: ' + r); continue; }
  const local = fs.readFileSync(path.join(ROOT, r));
  if (sha(local) === z.sha) matched++;
  else { mismatch++; if (mismatch <= 15) console.log('  内容不一致: ' + r); }
}
const extra = [...zipFiles.keys()].filter(k => !treeSet.has(k.slice(TOP.length)));
console.log('');
console.log('--- 与工程目录比对 ---');
console.log(`工程应打包文件: ${treeRels.length}`);
console.log(`SHA256 完全一致 : ${matched}`);
console.log(`缺失            : ${missing}`);
console.log(`内容不一致      : ${mismatch}`);
console.log(`zip 中多出的文件: ${extra.length}`);
extra.slice(0, 15).forEach(e => console.log('  多余: ' + e));

/* ---------------- 乱码扫描 ---------------- */
const TEXT_EXT = new Set(['.java', '.kt', '.xml', '.js', '.html', '.htm', '.css', '.md', '.txt', '.gradle', '.properties', '.json', '.ps1', '.py', '.sh', '.pro', '.aidl', '.bat', '.yml', '.yaml', '.kts']);
// 乱码特征（用转义写，避免扫描脚本自身命中自己）
const MOJI = [
  '\uFFFD',                     // 替换字符
  '\u951F\u65A4\u62F7',         // GBK 双重误转
  '\u00EF\u00BF\u00BD',         // UTF-8 被当 Latin-1
  '\u00C3\u00A4', '\u00C3\u00A5', '\u00E2\u20AC',
  '\u00E5\u00AE\u2030', '\u00E6\u00B8\u00B8', '\u00E7\u00B3\u00BB',
  '\u00E3\u20AC', '\u00E4\u00B8',
];
let scanned = 0, notUtf8 = 0, withBom = 0;
const sus = [];
for (const [name, info] of zipFiles) {
  const ext = path.extname(name).toLowerCase();
  if (!TEXT_EXT.has(ext)) continue;
  scanned++;
  const text = info.data.toString('utf8');
  if (!Buffer.from(text, 'utf8').equals(info.data)) {
    notUtf8++;
    if (notUtf8 <= 10) console.log('  非 UTF-8 编码(可能是 GBK/ANSI): ' + name);
  }
  if (info.data.length >= 3 && info.data[0] === 0xef && info.data[1] === 0xbb && info.data[2] === 0xbf) {
    withBom++;
    if (withBom <= 10) console.log('  含 UTF-8 BOM: ' + name);
  }
  const hits = MOJI.filter(m => text.includes(m));
  if (hits.length) sus.push({ name, hits });
}
console.log('');
console.log('--- 文件内容编码 ---');
console.log(`扫描文本文件 ${scanned} 个；非 UTF-8 ${notUtf8} 个；含 BOM ${withBom} 个；命中乱码特征 ${sus.length} 个`);
sus.slice(0, 20).forEach(s => console.log('  ' + s.name + '  <- ' + s.hits.join(',')));

/* ---------------- 关键文件抽查 ---------------- */
console.log('');
console.log('--- 关键文件是否在包内 ---');
const KEY = [
  'settings.gradle', 'build.gradle', 'gradle.properties', 'gradlew', 'gradlew.bat',
  'app/build.gradle', 'app/src/main/AndroidManifest.xml',
  'app/src/main/assets/index.html',
  'app/src/main/java/com/youlong/hd/MainActivity.java',
  'app/src/main/java/com/youlong/hd/ProtectService.java',
  'embedded/server/src/main/kotlin/roro/stellar/server/service/process/CommandInterceptor.kt',
  'embedded/server/src/main/kotlin/roro/stellar/server/service/process/CommandGuard.kt',
  'embedded/manager/src/main/kotlin/roro/stellar/manager/authorization/CommandGuardActivity.kt',
  'LICENSE', 'NOTICE', 'LICENSES/Apache-2.0.txt', 'LICENSES/MPL-2.0.txt', 'THIRD_PARTY_NOTICES.md',
  'MIGRATION_STELLAR.md', 'LICENSE_COMPLIANCE_AUDIT.md',
];
for (const k of KEY) {
  const inZip = zipFiles.has(TOP + k);
  const inTree = fs.existsSync(path.join(ROOT, k));
  console.log(`  ${inZip && inTree ? 'OK  ' : '!!  '} ${k}  zip=${inZip} tree=${inTree}`);
}

console.log('');
const ok = missing === 0 && mismatch === 0 && crcBad === 0 && sizeBad === 0 && readErr === 0
  && badRound.length === 0 && badChar.length === 0 && sus.length === 0
  && notUtf8 === 0 && noFlag.length === 0 && extra.length === 0;
if (withBom) console.log('提示: ' + withBom + ' 个文件带 UTF-8 BOM（不是乱码，可正常编译，保留原样）');
console.log(ok ? '==> 结论: 源码包完整、无乱码、与工程目录完全一致' : '==> 结论: 存在问题，见上面输出');
