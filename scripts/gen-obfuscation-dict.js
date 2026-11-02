/**
 * 游龙 混淆字典生成器 v1.0
 * ========================================================
 * 生成 R8 / ProGuard 使用的三个混淆字典：
 *   - class-dict.txt    类名候选
 *   - package-dict.txt  包名候选
 *   - member-dict.txt   字段/方法名候选
 *
 * 设计原理：
 *   默认 R8 生成 a / b / c / a1 / b2 … 这类"字典序可读"的名字，
 *   逆向者能把反编译结果和原类名做启发式对照，也便于人工阅读调用链。
 *   使用「形近字符」（l / I / 1 / O / 0）作为标识符字母表后，
 *   反编译结果变成 IlllIl1I 这类人类无法分辨的名字，
 *   阅读成本急剧上升，且任何"抄名字去搜索"的手段全部失效。
 *
 * 安全性（为什么不担心破坏编译）：
 *   * 只使用合法 Java 标识符字符（l I 1 O 0），首字符必为字母 l/I/O，
 *     数字绝不出现在首位 —— 永远不会生成关键字或非法标识符。
 *   * 字典只决定「改名成什么」，不决定「哪些被改名」。
 *     因此它不会让原本能通过的反射/keep 规则失效。
 *   * 名字长度 8~14，冲突率极低，且生成器内部做去重。
 *
 * 用法：node gen-obfuscation-dict.js <输出目录>
 */
'use strict';

const fs = require('fs');
const path = require('path');

const outDir = process.argv[2];
if (!outDir) {
    console.error('用法: node gen-obfuscation-dict.js <输出目录>');
    process.exit(1);
}
fs.mkdirSync(outDir, { recursive: true });

// 形近字母表：每个字符在小号字体下几乎无法区分
const HEAD = ['l', 'I', 'O'];                    // 首字符必须是字母
const BODY = ['l', 'I', '1', 'O', '0'];          // 后续字符可含数字

// 固定随机种子（xorshift32），保证每次生成的字典完全一致 —— 这样
// 重复构建的映射表稳定，出问题时能对上号
let seed = 0x594F554C; // "YOUL"
function rnd() {
    seed ^= seed << 13; seed >>>= 0;
    seed ^= seed >>> 17;
    seed ^= seed << 5;  seed >>>= 0;
    return seed / 0xFFFFFFFF;
}

function pick(arr) {
    return arr[Math.floor(rnd() * arr.length) % arr.length];
}

// 生成 n 个互不相同的形近标识符
function genNames(n, minLen, maxLen) {
    const set = new Set();
    let guard = 0;
    while (set.size < n && guard < n * 200) {
        guard++;
        const len = minLen + Math.floor(rnd() * (maxLen - minLen + 1));
        let s = pick(HEAD);
        for (let i = 1; i < len; i++) s += pick(BODY);
        set.add(s);
    }
    return Array.from(set);
}

function writeFile(name, names) {
    const p = path.join(outDir, name);
    fs.writeFileSync(p, names.join('\n') + '\n', 'utf8');
    console.log(`  ${name}  →  ${names.length} 条`);
}

// 类名：需要更多的候选量（一个 APK 往往上千个类）
writeFile('class-dict.txt', genNames(6000, 8, 13));
// 包名字段较少（本项目还会被 -repackageclasses 合并到单一包）
writeFile('package-dict.txt', genNames(500, 6, 10));
// 方法与字段数量最多
writeFile('member-dict.txt', genNames(9000, 7, 12));

console.log('[dict] 生成完成: ' + outDir);
