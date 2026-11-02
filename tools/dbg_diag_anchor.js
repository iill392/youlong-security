const fs = require('fs');
const P = 'E:/anquan/app/src/main/java/com/youlong/hd/MainActivity.java';
const t = fs.readFileSync(P, 'utf8');
const i = t.indexOf('getEnvGoodHint() {');
console.log('indexOf getEnvGoodHint() { @ ' + i);
if (i >= 0) {
  const seg = t.slice(i - 120, i + 220);
  console.log(JSON.stringify(seg));
}
console.log('');
const j = t.indexOf('public String getEnvironmentWarning()');
console.log('indexOf getEnvironmentWarning @ ' + j);
if (j >= 0) console.log(JSON.stringify(t.slice(j - 80, j + 60)));
