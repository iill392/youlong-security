// 打包「整个工程」：不做任何排除，逐流写盘，内存占用恒定
// 用法: node make_full_zip.js <源目录> <输出.zip> [包内根目录名]
// 说明:
//   - 大文件(>8MB) 用流式 deflate + 数据描述符(标志位 0x0808)，不会把整个文件读进内存
//   - 小文件走内存快路径
//   - 文件名强制 UTF-8 并置 0x800 标志位
//   - 跳过符号链接/目录联接，防止循环
const fs = require('fs');
const path = require('path');
const zlib = require('zlib');

const ROOT = process.argv[2];
const OUT = process.argv[3];
const ROOTNAME = process.argv[4] || '';
if (!ROOT || !OUT) { console.log('用法: node make_full_zip.js <源目录> <输出.zip> [包内根名]'); process.exit(1); }

const BIG = 8 * 1024 * 1024;
const BIG_LEVEL = 6;   // 大文件用 6 提速，压缩率差别很小

const CRC_TABLE = (() => {
  const t = new Int32Array(256);
  for (let n = 0; n < 256; n++) { let c = n; for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1; t[n] = c; }
  return t;
})();
function crcUpdate(crc, buf) { let c = crc; for (let i = 0; i < buf.length; i++) c = (c >>> 8) ^ CRC_TABLE[(c ^ buf[i]) & 0xff]; return c; }
function dosTime(d) {
  return {
    time: (d.getHours() << 11) | (d.getMinutes() << 5) | (d.getSeconds() >> 1),
    date: ((d.getFullYear() - 1980) << 9) | ((d.getMonth() + 1) << 5) | d.getDate(),
  };
}
const mb = b => (b / 1048576).toFixed(1) + ' MB';

(async () => {
  /* ---------- 1. 枚举 ---------- */
  console.log('枚举文件...');
  const items = [];
  const links = [], unreadable = [], visited = new Set();
  let rawTotal = 0;

  function walk(dir, rel) {
    let real;
    try { real = fs.realpathSync(dir); } catch (e) { unreadable.push(rel + '  (目录不可达)'); return; }
    if (visited.has(real)) { console.log('  跳过循环链接: ' + rel); return; }
    visited.add(real);
    let ents;
    try { ents = fs.readdirSync(dir, { withFileTypes: true }); }
    catch (e) { unreadable.push(rel + '  (无法列出: ' + e.code + ')'); return; }
    for (const e of ents) {
      const abs = path.join(dir, e.name);
      const r = rel ? rel + '/' + e.name : e.name;
      let st;
      try { st = fs.lstatSync(abs); } catch (err) { unreadable.push(r + '  (lstat: ' + err.code + ')'); continue; }
      if (st.isSymbolicLink()) { links.push(r); continue; }
      if (st.isDirectory()) { items.push({ rel: r + '/', abs, isDir: true, mtime: st.mtime }); walk(abs, r); }
      else if (st.isFile()) { items.push({ rel: r, abs, isDir: false, mtime: st.mtime, size: st.size }); rawTotal += st.size; }
      else { links.push(r + '  (特殊类型)'); }
    }
  }
  walk(ROOT, '');
  const nFiles = items.filter(i => !i.isDir).length;
  console.log(`枚举完成: 文件 ${nFiles} 个，目录 ${items.length - nFiles} 个，原始 ${mb(rawTotal)}`);
  if (links.length) console.log(`跳过链接/特殊文件 ${links.length} 个`);
  if (unreadable.length) { console.log(`无法访问 ${unreadable.length} 个:`); unreadable.slice(0, 20).forEach(u => console.log('  ' + u)); }

  /* ---------- 2. 写 zip ---------- */
  const t0 = Date.now();
  const fd = fs.openSync(OUT, 'w');
  let offset = 0;
  const push = buf => { fs.writeSync(fd, buf, 0, buf.length, offset); offset += buf.length; };
  const central = [];
  let done = 0, stored = 0, deflated = 0, big = 0, skipped = 0;

  const header = (nameBuf, method, time, date, crc, csize, usize, extraFlags = 0) => {
    const b = Buffer.alloc(30);
    b.writeUInt32LE(0x04034b50, 0);
    b.writeUInt16LE(20, 4);
    b.writeUInt16LE(0x0800 | extraFlags, 6);
    b.writeUInt16LE(method, 8);
    b.writeUInt16LE(time, 10);
    b.writeUInt16LE(date, 12);
    b.writeUInt32LE(crc, 14);
    b.writeUInt32LE(csize, 18);
    b.writeUInt32LE(usize, 22);
    b.writeUInt16LE(nameBuf.length, 26);
    b.writeUInt16LE(0, 28);
    return b;
  };
  const centralHeader = (nameBuf, method, time, date, crc, csize, usize, loff, isDir, extraFlags = 0) => {
    const b = Buffer.alloc(46);
    b.writeUInt32LE(0x02014b50, 0);
    b.writeUInt16LE(20, 4);
    b.writeUInt16LE(20, 6);
    b.writeUInt16LE(0x0800 | extraFlags, 8);
    b.writeUInt16LE(method, 10);
    b.writeUInt16LE(time, 12);
    b.writeUInt16LE(date, 14);
    b.writeUInt32LE(crc, 16);
    b.writeUInt32LE(csize, 20);
    b.writeUInt32LE(usize, 24);
    b.writeUInt16LE(nameBuf.length, 28);
    b.writeUInt16LE(0, 30);
    b.writeUInt16LE(0, 32);
    b.writeUInt16LE(0, 34);
    b.writeUInt16LE(0, 36);
    b.writeUInt32LE(isDir ? 0x10 : 0x20, 38);
    b.writeUInt32LE(loff, 42);
    return b;
  };

  for (const it of items) {
    const fullName = ROOTNAME ? ROOTNAME + '/' + it.rel : it.rel;
    const nameBuf = Buffer.from(fullName, 'utf8');
    const { time, date } = dosTime(it.mtime);
    const loff = offset;
    done++;

    if (it.isDir) {
      push(header(nameBuf, 0, time, date, 0, 0, 0));
      push(nameBuf);
      central.push(Buffer.concat([centralHeader(nameBuf, 0, time, date, 0, 0, 0, loff, true), nameBuf]));
      continue;
    }

    if (it.size <= BIG) {
      let raw;
      try { raw = fs.readFileSync(it.abs); }
      catch (e) { unreadable.push(it.rel + '  (读取失败: ' + e.code + ')'); skipped++; continue; }
      const usize = raw.length;
      const crc = (crcUpdate(-1, raw) ^ -1) >>> 0;
      const def = zlib.deflateRawSync(raw, { level: 9 });
      let method, data;
      if (def.length < raw.length) { method = 8; data = def; deflated++; }
      else { method = 0; data = raw; stored++; }
      push(header(nameBuf, method, time, date, crc, data.length, usize));
      push(nameBuf);
      if (data.length) push(data);
      central.push(Buffer.concat([centralHeader(nameBuf, method, time, date, crc, data.length, usize, loff, false), nameBuf]));
    } else {
      // 大文件：先写占位头，流式压缩，末尾补数据描述符
      big++;
      push(header(nameBuf, 8, time, date, 0, 0, 0, 0x0008));
      push(nameBuf);
      let crc = -1, usize = 0, csize = 0;
      try {
        await new Promise((resolve, reject) => {
          const rs = fs.createReadStream(it.abs, { highWaterMark: 1 << 20 });
          const def = zlib.createDeflateRaw({ level: BIG_LEVEL });
          rs.on('error', reject);
          def.on('error', reject);
          rs.on('data', c => { usize += c.length; crc = crcUpdate(crc, c); });
          // 必须用带偏移的写；不带偏移的 write 会用 fd 自己的文件指针，与 pwrite 混用会从文件头覆盖
          def.on('data', c => { push(c); csize += c.length; });
          def.on('end', resolve);
          rs.pipe(def);
        });
      } catch (e) {
        console.log('!! 大文件读取失败: ' + it.rel + ' -> ' + e.message);
        unreadable.push(it.rel + '  (流式读取失败)'); skipped++;
        // 回滚：把已写入的头部和部分数据截断
        offset = loff; fs.ftruncateSync(fd, offset);
        continue;
      }
      crc = (crc ^ -1) >>> 0;
      const dd = Buffer.alloc(16);
      dd.writeUInt32LE(0x08074b50, 0);
      dd.writeUInt32LE(crc, 4);
      dd.writeUInt32LE(csize, 8);
      dd.writeUInt32LE(usize, 12);
      push(dd);
      central.push(Buffer.concat([centralHeader(nameBuf, 8, time, date, crc, csize, usize, loff, false, 0x0008), nameBuf]));
    }

    if (done % 1000 === 0) {
      console.log(`  ${done}/${items.length}  ${mb(offset)} 已写入  (${((Date.now() - t0) / 1000).toFixed(0)}s)`);
    }
  }

  /* ---------- 3. 中央目录 + EOCD ---------- */
  const cdOff = offset;
  for (const c of central) push(c);
  const cdSize = offset - cdOff;
  const eocd = Buffer.alloc(22);
  eocd.writeUInt32LE(0x06054b50, 0);
  eocd.writeUInt16LE(0, 4);
  eocd.writeUInt16LE(0, 6);
  eocd.writeUInt16LE(Math.min(central.length, 0xffff), 8);
  eocd.writeUInt16LE(Math.min(central.length, 0xffff), 10);
  eocd.writeUInt32LE(cdSize, 12);
  eocd.writeUInt32LE(cdOff, 16);
  eocd.writeUInt16LE(0, 20);
  push(eocd);
  fs.closeSync(fd);

  /* ---------- 4. 自检：结构必须能读回来 ---------- */
  const check = fs.openSync(OUT, 'r');
  const head = Buffer.alloc(4), tail = Buffer.alloc(22);
  fs.readSync(check, head, 0, 4, 0);
  fs.readSync(check, tail, 0, 22, fs.statSync(OUT).size - 22);
  fs.closeSync(check);
  const headOk = head.readUInt32LE(0) === 0x04034b50;
  const tailOk = tail.readUInt32LE(0) === 0x06054b50;
  const cntOk = tail.readUInt16LE(10) === Math.min(central.length, 0xffff);
  console.log('');
  console.log('===== 自检 =====');
  console.log(`  文件头 PK\\x03\\x04 : ${headOk ? 'OK' : '错误'}`);
  console.log(`  末尾 EOCD        : ${tailOk ? 'OK' : '错误'}`);
  console.log(`  EOCD 条目数      : ${cntOk ? 'OK' : '错误'} (${tail.readUInt16LE(10)} / ${central.length})`);
  if (!headOk || !tailOk || !cntOk) {
    console.log('  !! 结构异常，产物不可用');
    process.exitCode = 3;
    return;
  }

  const zipSize = fs.statSync(OUT).size;
  console.log('');
  console.log('===== 打包完成 =====');
  console.log('输出    : ' + OUT);
  console.log('zip 大小: ' + mb(zipSize));
  console.log('原始大小: ' + mb(rawTotal));
  console.log('条目    : ' + central.length + ' (文件 ' + (central.length - (items.length - nFiles)) + '，目录 ' + (items.length - nFiles) + ')');
  console.log('压缩方式: deflate ' + deflated + '，store ' + stored + '，大文件流式 ' + big);
  console.log('跳过    : ' + skipped + '，链接 ' + links.length);
  console.log('耗时    : ' + ((Date.now() - t0) / 1000).toFixed(1) + ' 秒');

  // 附带一份清单，便于核对完整性
  const LIST = OUT.replace(/\.zip$/i, '') + '_文件清单.txt';
  const lines = [`# ${path.basename(OUT)} 文件清单`, `# 生成时间: ${new Date().toLocaleString()}`,
    `# 文件 ${nFiles} 个，目录 ${items.length - nFiles} 个，原始 ${mb(rawTotal)}`];
  for (const it of items) lines.push((it.isDir ? 'DIR  ' : String(it.size).padStart(12) + '  ') + (ROOTNAME ? ROOTNAME + '/' : '') + it.rel);
  if (links.length) { lines.push('', '# 跳过的链接/特殊文件:'); links.forEach(l => lines.push('SKIP ' + l)); }
  if (unreadable.length) { lines.push('', '# 无法访问:'); unreadable.forEach(l => lines.push('ERR  ' + l)); }
  fs.writeFileSync(LIST, lines.join('\r\n'), 'utf8');
  console.log('清单    : ' + LIST);

  if (unreadable.length) { console.log(''); console.log('注意: 有 ' + unreadable.length + ' 项无法访问，详情见清单'); process.exitCode = 2; }
})();
