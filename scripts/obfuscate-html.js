/**
 * 游龙 HTML/JS 极致混淆流水线 v1.0
 * =======================================================
 * 作用：对 assets 下所有 HTML 的内联 <script> 进行 javascript-obfuscator 混淆
 *       - 核心文件（index.html 等）→ 最高强度（控制流平坦化+字符串数组+死代码注入+自防御）
 *       - 其他文件 → 中强度（控体积控性能）
 * 安全机制：
 *       - 混淆失败自动回退原文，绝不阻塞构建
 *       - 禁用 renameGlobals（避免破坏 window 全局接口 / JS Bridge）
 *       - 禁用 debugProtection（WebView 上可能触发异常，用 selfDefending 代替）
 * 用法：node obfuscate-html.js <源assets目录> <输出目录>
 */
'use strict';

const fs = require('fs');
const path = require('path');
const JavaScriptObfuscator = require('javascript-obfuscator');

// ============ 核心文件清单（最高强度混淆） ============
// index.html 是主界面核心技术，其余为功能页核心
const CORE_FILES = new Set([
    'index.html',
    'termux.html',
    'VIVO.html',
    'OPPO.html',
    'xiaomi.html',
    'shizuku.html',
    'rongyao.html',
    'aiagent.html',
]);

// ============ 高强度混淆配置（核心文件） ============
// 性能注意：deadCodeInjection / 高 controlFlowFlattening / 高
// stringArrayThreshold 会让 JS 解析+执行膨胀数倍（首屏慢）。
// 已在「能接受的开销」内拉到最高：字符串数组几乎全量隐藏 + 数组轮转 +
// 字符串切片 + 数字转表达式 + 控制流平坦化 + selfDefending。
// 仍关闭的项及原因：
//   - deadCodeInjection：体积翻数倍，对逆向提升有限
//   - transformObjectKeys：页面大量使用 obj[动态变量] 访问（JS Bridge /
//     配置表），转换后会静默失效
//   - renameGlobals：会破坏 Android evaluateJavascript 调用的全局函数
const HIGH_OBFUSCATION = {
    compact: true,
    controlFlowFlattening: true,
    controlFlowFlatteningThreshold: 0.5,
    deadCodeInjection: false,
    numbersToExpressions: true,
    simplify: true,
    stringArray: true,
    stringArrayEncoding: ['base64'],
    stringArrayThreshold: 0.75,
    stringArrayIndexesType: ['hexadecimal-number'],
    stringArrayWrappersChainedCalls: true,
    stringArrayWrappersCount: 3,
    stringArrayWrappersType: 'function',
    rotateStringArray: true,
    splitStrings: true,
    splitStringsChunkLength: 10,    // 拆分后不再能用 strings 直接 grep 出原句
    selfDefending: true,
    identifierNamesGenerator: 'hexadecimal',
    identifiersPrefix: 'lI1',       // 给所有标识符加形近前缀，与 R8 字典呼应
    renameGlobals: false,          // 保护全局接口
    reservedNames: [
        '^showHome$', '^showFavorites$', '^showProfile$',
        '^init$', '^refreshUI$', '^renderFavorites$',
        '^loadAdsFromPages$', '^fetchFromPages$', '^fetchFromNewPages$',
        '^openShizukuManager$', '^openShizukuGuide$', '^refreshShizukuGuide$',
    ],                             // 保留 Android evaluateJavascript 调用的全局函数名
    transformObjectKeys: false,    // 保护对象键名
    unicodeEscapeSequence: false,
    disableConsoleOutput: false,   // 保留 console（页面可能依赖）
    ignoreImports: true,
    // 排除包含关键保留字的代码（防误伤）
    reservedStrings: [],
};

// ============ 中等强度混淆配置（普通页面） ============
const MID_OBFUSCATION = {
    compact: true,
    controlFlowFlattening: true,
    controlFlowFlatteningThreshold: 0.4,
    deadCodeInjection: false,
    numbersToExpressions: true,
    simplify: true,
    stringArray: true,
    stringArrayEncoding: ['base64'],
    stringArrayThreshold: 0.6,
    stringArrayIndexesType: ['hexadecimal-number'],
    stringArrayWrappersChainedCalls: true,
    stringArrayWrappersCount: 2,
    stringArrayWrappersType: 'function',
    rotateStringArray: true,
    splitStrings: true,
    splitStringsChunkLength: 10,
    selfDefending: true,
    identifierNamesGenerator: 'hexadecimal',
    identifiersPrefix: 'lI1',
    renameGlobals: false,
    reservedNames: [
        '^showHome$', '^showFavorites$', '^showProfile$',
        '^init$', '^refreshUI$', '^renderFavorites$',
        '^loadAdsFromPages$', '^fetchFromPages$', '^fetchFromNewPages$',
        '^openShizukuManager$', '^openShizukuGuide$', '^refreshShizukuGuide$',
    ],
    transformObjectKeys: false,
    unicodeEscapeSequence: false,
    disableConsoleOutput: false,
    ignoreImports: true,
};

// ============ 从 HTML 提取内联 <script> 块并混淆 ============
// 只处理无 src 的内联脚本；外部 <script src> 不处理（本工程 JS 全内联）
function obfuscateInlineScripts(html, config) {
    let modified = html;
    // 匹配 <script>...</script>（无属性，或仅含 type 属性），贪婪但安全
    const scriptRe = /<script\b([^>]*)>([\s\S]*?)<\/script>/gi;
    modified = modified.replace(scriptRe, (fullMatch, attrs, body) => {
        // 跳过有 src 的脚本
        if (/\bsrc\s*=/.test(attrs)) {
            return fullMatch;
        }
        // 跳过空脚本
        if (!body.trim()) {
            return fullMatch;
        }
        try {
            const result = JavaScriptObfuscator.obfuscate(body, config);
            const obfuscated = result.getObfuscatedCode();
            // 校验混淆结果非空
            if (!obfuscated || obfuscated.trim().length < 10) {
                console.error('[obfuscate] 混淆结果异常，回退原文');
                return fullMatch;
            }
            return `<script${attrs}>${obfuscated}</script>`;
        } catch (e) {
            console.error('[obfuscate] 混淆失败(回退原文): ' + e.message);
            return fullMatch; // 失败回退，不阻塞构建
        }
    });
    return modified;
}

// 复制文件（带重试，Windows 文件锁导致 EPERM 时跳过，不阻塞构建）
function copyFileSafe(src, dest) {
    const maxRetry = 3;
    for (let i = 0; i < maxRetry; i++) {
        try {
            fs.copyFileSync(src, dest);
            return true;
        } catch (e) {
            if (i === maxRetry - 1) {
                console.error(`[obfuscate] 复制失败(跳过): ${dest} -> ${e.code}`);
                return false;
            }
            // 同步等待后重试（避开 Defender 扫描锁）
            const waitMs = 300 * (i + 1);
            Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, waitMs);
        }
    }
    return false;
}

// ============ 递归处理目录 ============
function processDir(srcDir, outDir) {
    if (!fs.existsSync(srcDir)) {
        console.error('[obfuscate] 源目录不存在: ' + srcDir);
        process.exit(1);
    }
    fs.mkdirSync(outDir, { recursive: true });

    let htmlCount = 0;
    let failCount = 0;

    const walk = (dir, rel) => {
        const entries = fs.readdirSync(dir, { withFileTypes: true });
        for (const entry of entries) {
            const srcPath = path.join(dir, entry.name);
            const relPath = rel ? rel + '/' + entry.name : entry.name;
            if (entry.isDirectory()) {
                fs.mkdirSync(path.join(outDir, relPath), { recursive: true });
                walk(srcPath, relPath);
            } else {
                const outPath = path.join(outDir, relPath);
                if (entry.name.toLowerCase().endsWith('.html')) {
                    htmlCount++;
                    const raw = fs.readFileSync(srcPath, 'utf8');
                    const config = CORE_FILES.has(entry.name) ? HIGH_OBFUSCATION : MID_OBFUSCATION;
                    const obfuscated = obfuscateInlineScripts(raw, config);
                    if (obfuscated === raw) failCount++;
                    fs.writeFileSync(outPath, obfuscated, 'utf8');
                    const sizeMB = (Buffer.byteLength(obfuscated, 'utf8') / 1024 / 1024).toFixed(2);
                    const tag = CORE_FILES.has(entry.name) ? '[HIGH]' : '[mid ]';
                    console.log(`  ${tag} ${relPath}  →  ${sizeMB} MB`);
                } else {
                    // 非 HTML 原样复制（图片/音视频/js 等，加密阶段统一处理）
                    copyFileSafe(srcPath, outPath);
                }
            }
        }
    };
    walk(srcDir, '');
    console.log(`[obfuscate] 完成: ${htmlCount} 个 HTML 处理完成, ${failCount} 个回退原文`);
    return { htmlCount, failCount };
}

// ============ 主入口 ============
const srcDir = process.argv[2];
const outDir = process.argv[3];
if (!srcDir || !outDir) {
    console.error('用法: node obfuscate-html.js <源assets目录> <输出目录>');
    process.exit(1);
}
console.log('[obfuscate] 源目录: ' + srcDir);
console.log('[obfuscate] 输出目录: ' + outDir);
processDir(srcDir, outDir);
