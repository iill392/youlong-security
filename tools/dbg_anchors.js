const fs = require('fs');
const P = 'E:/anquan/app/src/main/java/com/youlong/hd/ProtectService.java';
const t = fs.readFileSync(P, 'utf8');

console.log('CRLF 数: ' + (t.match(/\r\n/g) || []).length);
console.log('裸 LF 数: ' + (t.match(/(?<!\r)\n/g) || []).length);
console.log('');

for (const needle of [
  '病毒库检测异常',
  '直接覆盖层弹窗已显示',
  'isAccessibilityServiceEnabled() {',
  'fallback通知',
]) {
  const i = t.indexOf(needle);
  console.log('=== "' + needle + '" @ ' + i + ' ===');
  if (i >= 0) {
    const seg = t.slice(Math.max(0, i - 260), i + 200);
    console.log(JSON.stringify(seg));
    console.log('');
  }
}
