/**
 * 病毒库 URL 自检（防回归）
 * =======================================================
 * 背景：StrX 里的域名/路径是 XOR 字节常量，拼错一个字节就静默失效。
 *       曾因 VDB_HOST 里误带 "https://" 前缀 + VirusDb 又拼一次 → 变成
 *       "https://https://yl-tool-data.pages.dev/safe1.json" → 全部列表拉取失败。
 * 本脚本直接解析 StrX.java（不依赖 Java 运行），复现 VirusDb 的拼装逻辑并断言 URL 合法。
 * 用法：node scripts/verify-virusdb-url.js
 */
'use strict';

const fs = require('fs');
const path = require('path');

const STRX = path.join(__dirname, '..', 'app', 'src', 'main', 'java', 'com', 'youlong', 'hd', 'StrX.java');
const VIRUSDB = path.join(__dirname, '..', 'app', 'src', 'main', 'java', 'com', 'youlong', 'hd', 'VirusDb.java');
const K = 0x5A;

const src = fs.readFileSync(STRX, 'utf8');

/** 从 StrX.java 中取出某个 byte[] 常量并按 XOR 0x5A 解码 */
function decode(name) {
    const i = src.indexOf(name + ' =');
    if (i < 0) throw new Error('StrX 中找不到常量: ' + name);
    const a = src.indexOf('{', i);
    const b = src.indexOf('}', a);
    const bytes = src.substring(a + 1, b).split(',')
        .map(s => s.trim()).filter(Boolean).map(s => parseInt(s, 16));
    if (bytes.some(isNaN)) throw new Error(name + ' 含非法字节字面量');
    return bytes.map(x => String.fromCharCode(x ^ K)).join('');
}

/** 复现 VirusDb.stripScheme */
function stripScheme(h) {
    const s = (h || '').trim();
    if (/^https:\/\//i.test(s)) return s.substring(8);
    if (/^http:\/\//i.test(s)) return s.substring(7);
    return s;
}

const host = stripScheme(decode('VDB_HOST'));
const base = 'https://' + host;

const PATHS = [
    ['① 100% 病毒包名', 'VDB_PATH_CERTAIN_PKG'],
    ['② 可能病毒包名', 'VDB_PATH_SUSPECT_PKG'],
    ['③ 100% 病毒名称', 'VDB_PATH_CERTAIN_NAME'],
    ['④ 可能病毒名称', 'VDB_PATH_SUSPECT_NAME'],
    ['⑤ 可能病毒关键字', 'VDB_PATH_KEYS'],
    // 2026-10：名称库同时合并旧文件（服务器上 mingcheng1 只有 25 条，mian1 有 139 条）
    ['③ 100% 病毒名称（旧）', 'VDB_PATH_CERTAIN_NAME_LEGACY'],
    ['④ 可能病毒名称（旧）', 'VDB_PATH_SUSPECT_NAME_LEGACY'],
];

let fail = 0;
console.log('VDB_HOST 解码 = [' + host + ']');
console.log('SERVER_BASE  = [' + base + ']');
console.log('');

// 断言 1：域名不得含协议头（否则会被拼成 https://https://...）
if (/^https?:\/\//i.test(decode('VDB_HOST'))) {
    console.log('❌ VDB_HOST 原始字节里含协议头 —— VirusDb 会再拼一次 https:// 导致 URL 非法');
    fail++;
}
// 断言 2：域名形态
if (!/^[a-z0-9.-]+\.[a-z]{2,}$/i.test(host)) {
    console.log('❌ 域名形态不合法: [' + host + ']');
    fail++;
}
// 断言 3：VirusDb 里确实用了 stripScheme 兜底
const vdb = fs.readFileSync(VIRUSDB, 'utf8');
if (!vdb.includes('stripScheme(StrX.d(StrX.VDB_HOST))')) {
    console.log('⚠️  VirusDb 未使用 stripScheme() 兜底，建议补上以防字节常量误带协议头');
    fail++;
}

PATHS.forEach(function (p) {
    const url = base + decode(p[1]);
    const ok = /^https:\/\/[a-z0-9.-]+\.[a-z]{2,}\/[A-Za-z0-9._\-\/]+$/.test(url)
        && url.indexOf('https://', 8) < 0;   // 不得出现第二个 https://
    console.log((ok ? '✅ ' : '❌ ') + p[0] + '  ' + url);
    if (!ok) fail++;
});

console.log('');
if (fail === 0) {
    console.log('结果: 全部通过（' + PATHS.length + ' 个接口）');
} else {
    console.log('结果: ' + fail + ' 项失败');
}
process.exit(fail > 0 ? 1 : 0);
