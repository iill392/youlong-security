// 更新 MIGRATION_STELLAR.md 里已失效的免责弹窗排错提示
const fs = require('fs');
const P = 'E:/anquan/MIGRATION_STELLAR.md';
let t = fs.readFileSync(P, 'utf8');

const oldBlock = `### 排错提示：首页有个免责声明弹窗会挡住点击

自动化测试时如果点击「没反应」，先确认屏幕上没有那个
「提示 … 我知道了」的遮罩弹窗（首次启动会出现）。
先 \`input tap\` 点掉它，再去点其它元素。
\`uiautomator dump\` 会显示 \`disclaimerBtn\`（文本「我知道了」），
它的 \`bounds\` 就是可点击区域。`;

const newBlock = `### ~~排错提示：首页有个免责声明弹窗会挡住点击~~（已失效，2026-10 第 12 轮删除）

> ⚠️ 本条已作废。原来每次进入应用都会弹一个「提示 / 目前**格机脚本 sh 文件**防不了，
> 建议大家日常尽量不要开……概不负责」的遮罩弹窗（\`showDisclaimer()\`，
> \`uiautomator\` 里是 \`disclaimerBtn\`），用户要求去掉，已在第 12 轮
> **整块删除**（函数体 + \`init()\` 里的调用）。
> 现在进入应用不会再弹任何遮挡层，自动化脚本也不需要先点掉它了。
> 详见 \`ROUND12_改动汇总.md\`。

（以下为历史记录，仅供参考）

自动化测试时如果点击「没反应」，先确认屏幕上没有那个
「提示 … 我知道了」的遮罩弹窗（首次启动会出现）。
先 \`input tap\` 点掉它，再去点其它元素。
\`uiautomator dump\` 会显示 \`disclaimerBtn\`（文本「我知道了」），
它的 \`bounds\` 就是可点击区域。`;

if (!t.includes(oldBlock)) {
  console.log('!! 未找到目标段落，改为按行替换');
  const lines = t.split('\n');
  let i = lines.findIndex(l => l.includes('排错提示：首页有个免责声明弹窗会挡住点击'));
  if (i < 0) { console.log('!! 也找不到标题行'); process.exit(1); }
  // 替换标题行及其后 6 行
  const head = lines.slice(0, i);
  const tail = lines.slice(i + 7);
  t = head.concat(newBlock.split('\n')).concat(tail).join('\n');
} else {
  t = t.replace(oldBlock, newBlock);
}

fs.writeFileSync(P, t, 'utf8');
console.log('OK MIGRATION_STELLAR.md 已更新，' + t.length + ' bytes');
