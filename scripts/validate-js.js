// 验证混淆后 HTML 内联 JS 的语法正确性(用 V8 引擎解析)
'use strict';
const fs = require('fs');
const vm = require('vm');

const file = process.argv[2] || 'build-out/obfuscated-assets/index.html';
const c = fs.readFileSync(file, 'utf8');

// 提取所有内联 script
const re = /<script\b([^>]*)>([\s\S]*?)<\/script>/gi;
let m, idx = 0, okCount = 0, failCount = 0;

while ((m = re.exec(c)) !== null) {
    idx++;
    const attrs = m[1];
    const body = m[2];
    if (/\bsrc\s*=/.test(attrs)) continue; // 跳过外部脚本
    if (!body.trim()) continue;

    try {
        // 用 vm 解析(不执行,仅语法检查) - 但 selfDefending 需要在执行时才触发
        new vm.Script(body, { filename: file + '#script' + idx });
        okCount++;
        console.log(`script ${idx}: 语法OK (${body.length} chars)`);
    } catch (e) {
        failCount++;
        console.error(`script ${idx}: 语法错误 -> ${e.message}`);
    }
}

console.log(`\n结果: ${okCount} 个通过, ${failCount} 个失败`);
process.exit(failCount > 0 ? 1 : 0);
