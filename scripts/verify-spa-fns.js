// 验证混淆产物中的 SPA 函数保留情况
const fs = require('fs');
const s = fs.readFileSync('build-out/obfuscated-assets/index.html', 'utf8');
console.log('file size KB:', (s.length / 1024).toFixed(1));
console.log('function showHome:', s.includes('function showHome'));
console.log('function showFavorites:', s.includes('function showFavorites'));
console.log('function showProfile:', s.includes('function showProfile'));
console.log('window.showHome=:', /window\.showHome\s*=/.test(s));
console.log('showHome 出现次数:', (s.match(/showHome/g) || []).length);
console.log('showFavorites 出现次数:', (s.match(/showFavorites/g) || []).length);
console.log('showProfile 出现次数:', (s.match(/showProfile/g) || []).length);
// 检查调用点（URL 参数驱动的调用）
console.log("'PAGES1' 驱动调用:", /page === 'PAGES1'/.test(s) || /page==='PAGES1'/.test(s));
console.log("'PAGES2' 驱动调用:", /page === 'PAGES2'/.test(s) || /page==='PAGES2'/.test(s));
