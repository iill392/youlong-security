// 分析混淆 JS 产物的启动开销：stringArray 规模 + parse 耗时
const fs = require('fs');
const { performance } = require('perf_hooks');

function analyze(file) {
    const s = fs.readFileSync(file, 'utf8');
    // 提取所有内联 script
    const scripts = [...s.matchAll(/<script[^>]*>([\s\S]*?)<\/script>/gi)].map(m => m[1]).join('\n');
    console.log('=== ' + file + ' ===');
    console.log('HTML 大小 KB:', (s.length / 1024).toFixed(1));
    console.log('Script 总长 KB:', (scripts.length / 1024).toFixed(1));

    // 找 stringArray 定义: var _0xXXX = ['a','b',...]; (javascript-obfuscator 风格)
    const arrRe = /(?:var|const|let)\s+(_0x[0-9a-f]+)\s*=\s*(\[[\s\S]*?\]);/g;
    let m, totalItems = 0, biggestArr = 0, totalChars = 0;
    const arrs = [];
    while ((m = arrRe.exec(scripts)) !== null) {
        let items = 0, chars = 0;
        // 粗略统计数组内的字符串项（单引号字符串，允许转义）
        const body = m[2];
        const itemRe = /'((?:\\.|[^'\\])*)'/g;
        let im;
        while ((im = itemRe.exec(body)) !== null) { items++; chars += im[1].length; }
        totalItems += items;
        totalChars += chars;
        if (items > biggestArr) biggestArr = items;
        arrs.push({ name: m[1], items, charsKB: (chars / 1024).toFixed(1) });
    }
    console.log('stringArray 数组数:', arrs.length);
    arrs.sort((a, b) => b.items - a.items).slice(0, 5).forEach(a =>
        console.log('  数组', a.name, '项数:', a.items, '字符KB:', a.charsKB));
    console.log('总数组项数:', totalItems, '总字符KB:', (totalChars / 1024).toFixed(1));

    // 是否有 rotateStringArray 痕迹（while(true) + shift）
    console.log('含 shift() 调用:', scripts.includes('.shift()'));
    console.log('含 while(true):', scripts.includes('while(!![])'));

    // parse 耗时（new Function 只编译不执行）
    const t0 = performance.now();
    try {
        new Function(scripts);
        const t1 = performance.now();
        console.log('JS parse 耗时: ' + (t1 - t0).toFixed(1) + ' ms (Node/V8 参考)');
    } catch (e) {
        console.log('parse 失败(可能依赖浏览器API):', e.message.slice(0, 80));
    }
    console.log('');
}

analyze(process.argv[2]);
