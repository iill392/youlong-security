// 自研 ZIP 写入器：强制 UTF-8 文件名 + 置 0x800 标志位，兼容所有解压工具
// 原因：Windows 自带 bsdtar(tar -a) 会把中文文件名按系统 ANSI(GBK) 写入且不置 UTF-8 标志，
//       在手机 / 7-Zip / WinRAR / macOS / Linux 上全部显示为乱码。
const fs = require('fs');
const path = require('path');
const zlib = require('zlib');

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
  let c = -1;
  for (let i = 0; i < buf.length; i++) c = (c >>> 8) ^ CRC_TABLE[(c ^ buf[i]) & 0xff];
  return (c ^ -1) >>> 0;
}
function dosTime(d) {
  const time = (d.getHours() << 11) | (d.getMinutes() << 5) | (d.getSeconds() >> 1);
  const date = ((d.getFullYear() - 1980) << 9) | ((d.getMonth() + 1) << 5) | d.getDate();
  return { time, date };
}

/**
 * @param {string} zipPath 输出 zip
 * @param {string} baseDir 待打包目录
 * @param {string} rootName zip 内的根目录名（可为空字符串表示不套一层）
 */
function makeZip(zipPath, baseDir, rootName) {
  const items = [];
  function walk(dir, rel) {
    const names = fs.readdirSync(dir).sort((a, b) => (a < b ? -1 : a > b ? 1 : 0));
    for (const n of names) {
      const abs = path.join(dir, n);
      const r = rel ? rel + '/' + n : n;
      const st = fs.statSync(abs);
      if (st.isDirectory()) {
        items.push({ rel: r + '/', abs, isDir: true, mtime: st.mtime });
        walk(abs, r);
      } else if (st.isFile()) {
        items.push({ rel: r, abs, isDir: false, mtime: st.mtime, size: st.size });
      }
    }
  }
  walk(baseDir, '');

  const chunks = [];
  const central = [];
  let offset = 0;
  const push = b => { chunks.push(b); offset += b.length; };

  for (const it of items) {
    const fullName = rootName ? rootName + '/' + it.rel : it.rel;
    const nameBuf = Buffer.from(fullName, 'utf8');
    const { time, date } = dosTime(it.mtime);
    let method = 0, data = Buffer.alloc(0), csize = 0, usize = 0, crc = 0;

    if (!it.isDir) {
      const raw = fs.readFileSync(it.abs);
      usize = raw.length;
      crc = crc32(raw);
      const def = zlib.deflateRawSync(raw, { level: 9 });
      if (def.length < raw.length) { method = 8; data = def; csize = def.length; }
      else { method = 0; data = raw; csize = raw.length; }
    }

    const localOff = offset;
    const lh = Buffer.alloc(30);
    lh.writeUInt32LE(0x04034b50, 0);
    lh.writeUInt16LE(20, 4);            // version needed
    lh.writeUInt16LE(0x0800, 6);        // flags: UTF-8 名称
    lh.writeUInt16LE(method, 8);
    lh.writeUInt16LE(time, 10);
    lh.writeUInt16LE(date, 12);
    lh.writeUInt32LE(crc, 14);
    lh.writeUInt32LE(csize, 18);
    lh.writeUInt32LE(usize, 22);
    lh.writeUInt16LE(nameBuf.length, 26);
    lh.writeUInt16LE(0, 28);            // extra len
    push(lh); push(nameBuf); if (csize) push(data);

    const ch = Buffer.alloc(46);
    ch.writeUInt32LE(0x02014b50, 0);
    ch.writeUInt16LE(20, 4);            // version made by
    ch.writeUInt16LE(20, 6);            // version needed
    ch.writeUInt16LE(0x0800, 8);
    ch.writeUInt16LE(method, 10);
    ch.writeUInt16LE(time, 12);
    ch.writeUInt16LE(date, 14);
    ch.writeUInt32LE(crc, 16);
    ch.writeUInt32LE(csize, 20);
    ch.writeUInt32LE(usize, 24);
    ch.writeUInt16LE(nameBuf.length, 28);
    ch.writeUInt16LE(0, 30);            // extra
    ch.writeUInt16LE(0, 32);            // comment
    ch.writeUInt16LE(0, 34);            // disk
    ch.writeUInt16LE(0, 36);            // internal attrs
    ch.writeUInt32LE(it.isDir ? 0x10 : 0x20, 38); // external attrs
    ch.writeUInt32LE(localOff, 42);
    central.push(Buffer.concat([ch, nameBuf]));
  }

  const cdOff = offset;
  for (const c of central) push(c);
  const cdSize = offset - cdOff;

  const eocd = Buffer.alloc(22);
  eocd.writeUInt32LE(0x06054b50, 0);
  eocd.writeUInt16LE(0, 4);
  eocd.writeUInt16LE(0, 6);
  eocd.writeUInt16LE(items.length, 8);
  eocd.writeUInt16LE(items.length, 10);
  eocd.writeUInt32LE(cdSize, 12);
  eocd.writeUInt32LE(cdOff, 16);
  eocd.writeUInt16LE(0, 20);
  push(eocd);

  fs.mkdirSync(path.dirname(zipPath), { recursive: true });
  if (fs.existsSync(zipPath)) fs.rmSync(zipPath, { force: true });
  fs.writeFileSync(zipPath, Buffer.concat(chunks));
  return { files: items.filter(i => !i.isDir).length, dirs: items.filter(i => i.isDir).length };
}

module.exports = { makeZip, crc32 };

if (require.main === module) {
  const [zipPath, baseDir, rootName] = process.argv.slice(2);
  if (!zipPath || !baseDir) { console.log('用法: node make_zip.js <输出.zip> <源目录> [根目录名]'); process.exit(1); }
  const r = makeZip(zipPath, baseDir, rootName || '');
  console.log(`OK: ${zipPath}  文件 ${r.files} 个，目录 ${r.dirs} 个`);
}
