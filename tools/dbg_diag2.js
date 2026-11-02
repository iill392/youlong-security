const fs = require('fs');
const P = 'E:/anquan/app/src/main/java/com/youlong/hd/MainActivity.java';
const lines = fs.readFileSync(P, 'utf8').split('\r\n');
const i = lines.findIndex(l => l.includes('getEnvGoodHint() {'));
console.log('getEnvGoodHint 行号(0-based)=' + i);
for (let k = i - 2; k <= i + 8; k++) {
  if (k < 0 || k >= lines.length) continue;
  console.log((k + 1) + ': ' + JSON.stringify(lines[k]));
}
