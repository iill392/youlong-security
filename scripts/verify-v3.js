// 验证 v3 混淆产物:函数保留 + while(true) 上下文
const fs = require('fs');
const s = fs.readFileSync(process.argv[2], 'utf8');
console.log('function showHome:', s.includes('function showHome'));
console.log('function showFavorites:', s.includes('function showFavorites'));
console.log('function showProfile:', s.includes('function showProfile'));
console.log('showHome 出现次数:', (s.match(/showHome/g) || []).length);
// 找 while(true) 出现次数和上下文
const matches = [...s.matchAll(/while\(!!\[\]\)/g)];
console.log('while(!![]) 出现次数:', matches.length);
matches.forEach((m, i) => {
    const start = Math.max(0, m.index - 80);
    const ctx = s.slice(start, m.index + 120).replace(/\n/g, ' ');
    console.log(`  #${i}: ...${ctx}...`);
});
// rotate 特征: 检查是否有 "\\n"+ 或 shift 调用
console.log('含 .shift():', s.includes('.shift()'));
console.log('含 push( 循环:', /while\(!!\[\]\).*push/.test(s));
