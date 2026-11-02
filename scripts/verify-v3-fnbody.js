// 提取 showHome 函数定义，验证完整（有大括号闭合）
const fs = require('fs');
const s = fs.readFileSync(process.argv[2], 'utf8');
for (const fn of ['showHome', 'showFavorites', 'showProfile']) {
    const idx = s.indexOf('function ' + fn);
    if (idx === -1) { console.log(fn + ': NOT FOUND'); continue; }
    // 向后找函数体结束（匹配大括号）
    let depth = 0, i = s.indexOf('{', idx), end = -1;
    let strMode = null;
    for (; i < s.length; i++) {
        const c = s[i];
        if (strMode === '"') { if (c === '\\') i++; else if (c === '"') strMode = null; continue; }
        if (strMode === "'") { if (c === '\\') i++; else if (c === "'") strMode = null; continue; }
        if (c === '"' || c === "'") { strMode = c; continue; }
        if (c === '{') depth++;
        else if (c === '}') { depth--; if (depth === 0) { end = i; break; } }
    }
    const len = end - idx + 1;
    console.log(fn + ': 找到 @' + idx + ', 长度 ' + len + ' 字符, 大括号闭合=' + (end > 0));
    // 看函数体前 200 字符确认是定义
    console.log('  定义头: ' + s.slice(idx, idx + 120).replace(/\n/g, ' ').slice(0, 120));
}
