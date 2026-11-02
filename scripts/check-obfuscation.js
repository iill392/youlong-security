// 检查混淆后 HTML 的完整性:结构、残留 JS、可读性
'use strict';
const fs = require('fs');

const file = process.argv[2] || 'build-out/obfuscated-assets/index.html';
const c = fs.readFileSync(file, 'utf8');

const out = [];
out.push('文件: ' + file);
out.push('大小: ' + (Buffer.byteLength(c, 'utf8') / 1024).toFixed(1) + ' KB');
out.push('script标签数: ' + (c.match(/<script\b/g) || []).length);
out.push('script闭合数: ' + (c.match(/<\/script>/g) || []).length);

// script 块外的内联 JS 残留(事件属性等)
const outside = c.replace(/<script[^>]*>[\s\S]*?<\/script>/g, '');
out.push('--- script块外残留 ---');
out.push('onclick: ' + (outside.match(/onclick=/g) || []).length);
out.push('onload: ' + (outside.match(/onload=/g) || []).length);
out.push('onerror: ' + (outside.match(/onerror=/g) || []).length);
out.push('onchange: ' + (outside.match(/onchange=/g) || []).length);
out.push('oninput: ' + (outside.match(/oninput=/g) || []).length);
out.push('onsubmit: ' + (outside.match(/onsubmit=/g) || []).length);
out.push('onclick=js表达式: ' + (outside.match(/onclick\s*=\s*["']?[^"'\s>]{20,}/g) || []).length);
out.push('script外function(: ' + (outside.match(/function\s*\(/g) || []).length);
out.push('script外const: ' + (outside.match(/\bconst\s/g) || []).length);
out.push('script外var: ' + (outside.match(/\bvar\s/g) || []).length);
out.push('script外let: ' + (outside.match(/\blet\s/g) || []).length);

// 混淆特征
out.push('--- 混淆特征 ---');
out.push('_0x字符串数组: ' + (c.match(/_0x/g) || []).length);
out.push('包含base64: ' + c.includes('base64'));
out.push('HTML标签完整性(html闭合): ' + (c.match(/<\/html>/g) || []).length);

console.log(out.join('\n'));
