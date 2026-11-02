// 版本号 v2：真实来源是 app/build.gradle 的 defaultConfig（不是 root ext）
const fs = require('fs');

const A = 'E:/anquan/app/build.gradle';
let a = fs.readFileSync(A, 'utf8');

if (a.includes('versionCode 1234567899')) {
  a = a.replace('versionCode 1234567899', 'versionCode 1234567900');
  console.log('OK app/build.gradle: versionCode -> 1234567900');
} else {
  const m = a.match(/versionCode\s+(\d+)/);
  console.log('!! versionCode 现状: ' + (m ? m[1] : '未找到'));
}

if (a.includes('versionName "9.0.1"')) {
  a = a.replace('versionName "9.0.1"', 'versionName "9.1.0"');
  console.log('OK app/build.gradle: versionName -> 9.1.0');
} else {
  const m = a.match(/versionName\s+"([^"]+)"/);
  console.log('!! versionName 现状: ' + (m ? m[1] : '未找到'));
}

fs.writeFileSync(A, a, 'utf8');

// 复查三处
for (const [f, re, label] of [
  [A, /versionCode\s+(\d+)[\s\S]{0,80}?versionName\s+"([^"]+)"/, 'app/build.gradle'],
  ['E:/anquan/build.gradle', /versionCode\s*=\s*(\d+)[\s\S]{0,80}?versionName\s*=\s*"([^"]+)"/, 'root build.gradle'],
]) {
  const t = fs.readFileSync(f, 'utf8');
  const m = t.match(re);
  console.log(label + ': versionCode=' + (m ? m[1] : '?') + ' versionName=' + (m ? m[2] : '?'));
}
