// 版本 9.1.0 -> 9.1.1（两处：app/build.gradle 是 APK 实际取值，root ext 供 manager BuildConfig）
const fs = require('fs');

const targets = [
  { f: 'E:/anquan/app/build.gradle',   code: /versionCode\s+1234567900/, name: /versionName\s+"9\.1\.0"/ },
  { f: 'E:/anquan/build.gradle',       code: /versionCode\s*=\s*1234567900/, name: /versionName\s*=\s*"9\.1\.0"/ },
];

for (const t of targets) {
  let s = fs.readFileSync(t.f, 'utf8');
  const before = s.length;
  let hits = 0;

  if (t.code.test(s)) {
    s = s.replace(t.code, (m) => m.replace('1234567900', '1234567901'));
    hits++;
    console.log('OK ' + t.f + ' versionCode 1234567900 -> 1234567901');
  } else {
    const m = s.match(/versionCode\s*=?\s*(\d+)/);
    console.log('!! ' + t.f + ' 未匹配 versionCode（现状 ' + (m ? m[1] : '?') + '）');
  }

  if (t.name.test(s)) {
    s = s.replace(t.name, (m) => m.replace('9.1.0', '9.1.1'));
    hits++;
    console.log('OK ' + t.f + ' versionName 9.1.0 -> 9.1.1');
  } else {
    const m = s.match(/versionName\s*=?\s*"([^"]+)"/);
    console.log('!! ' + t.f + ' 未匹配 versionName（现状 ' + (m ? m[1] : '?') + '）');
  }

  fs.writeFileSync(t.f, s, 'utf8');
  console.log('   ' + before + ' -> ' + s.length + ' bytes, 改 ' + hits + ' 处');
}

// VirusDb 的 User-Agent 也一并跟上
const D = 'E:/anquan/app/src/main/java/com/youlong/hd/VirusDb.java';
let d = fs.readFileSync(D, 'utf8');
if (d.includes('YulongShield/9.1.0')) {
  d = d.replace('YulongShield/9.1.0', 'YulongShield/9.1.1');
  fs.writeFileSync(D, d, 'utf8');
  console.log('OK VirusDb.java User-Agent -> YulongShield/9.1.1');
} else {
  const m = d.match(/YulongShield\/([\d.]+)/);
  console.log((m ? '现状 ' + m[0] : '未找到 User-Agent'));
}

// 复查
console.log('');
for (const f of ['E:/anquan/app/build.gradle', 'E:/anquan/build.gradle']) {
  const s = fs.readFileSync(f, 'utf8');
  const c = s.match(/versionCode\s*=?\s*(\d+)/);
  const n = s.match(/versionName\s*=?\s*"([^"]+)"/);
  console.log(f.replace('E:/anquan/', '') + ': versionCode=' + (c ? c[1] : '?') + ' versionName=' + (n ? n[1] : '?'));
}
