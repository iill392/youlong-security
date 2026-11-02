// 用行索引把 riskKind 一路补齐（避免字符串锚点的缩进/换行差异）
const fs = require('fs');

function editByLines(path, fn) {
  const lines = fs.readFileSync(path, 'utf8').split('\r\n');
  fn(lines);
  fs.writeFileSync(path, lines.join('\r\n'), 'utf8');
}

// ---------------------------------------------------------------- 1) CommandGuard
const CG = 'E:/anquan/embedded/server/src/main/kotlin/roro/stellar/server/service/process/CommandGuard.kt';
editByLines(CG, (L) => {
  const find = (re, from = 0) => {
    for (let i = from; i < L.length; i++) if (re.test(L[i])) return i;
    return -1;
  };

  // 1a) askForConfirmation 签名加 riskKind
  let i = find(/fun askForConfirmation\(/);
  if (i >= 0 && !L.slice(i, i + 12).join('\n').includes('riskKind')) {
    // 找到 userId: Int 那一行并在其后加逗号 + riskKind
    for (let k = i; k < i + 10; k++) {
      if (/^\s*userId: Int\s*$/.test(L[k])) {
        L[k] = L[k].replace(/userId: Int\s*$/, 'userId: Int,');
        L.splice(k + 1, 0,
          '        // 2026-10 新增：命令分析得出的风险类型（system / bulk / admin / normal）',
          '        riskKind: String = "normal"');
        console.log('OK CG 1a) askForConfirmation 加 riskKind');
        break;
      }
    }
  } else {
    console.log('SKIP CG 1a)');
  }

  // 1b) writeRequest 签名加 riskKind（并把调用处补上参数）
  i = find(/private fun writeRequest\(/);
  if (i >= 0) {
    const seg = L.slice(i, i + 12).join('\n');
    if (!seg.includes('riskKind')) {
      for (let k = i; k < i + 10; k++) {
        if (/^\s*reason: String\s*$/.test(L[k])) {
          L[k] = L[k].replace(/reason: String\s*$/, 'reason: String,');
          L.splice(k + 1, 0,
            '        // 2026-10 新增：风险类型，写进请求文件供弹窗使用',
            '        riskKind: String = "normal"');
          console.log('OK CG 1b) writeRequest 加 riskKind');
          break;
        }
      }
    } else console.log('SKIP CG 1b)');
  }

  // 1c) writeRequest 的调用处补 riskKind 实参
  i = find(/val request = writeRequest\(/);
  if (i >= 0 && !L[i].includes('riskKind')) {
    L[i] = L[i].replace(/,\s*reason\s*\)/, ', reason, riskKind)');
    if (!L[i].includes('riskKind')) L[i] = L[i].replace(/\)\s*$/, ', riskKind)');
    console.log('OK CG 1c) writeRequest 调用处: ' + L[i].trim());
  }

  // 1d) launch 签名加 riskKind
  i = find(/private fun launch\(/);
  if (i >= 0 && !L.slice(i, i + 12).join('\n').includes('riskKind')) {
    for (let k = i; k < i + 10; k++) {
      if (/^\s*userId: Int\s*$/.test(L[k])) {
        L[k] = L[k].replace(/userId: Int\s*$/, 'userId: Int,');
        L.splice(k + 1, 0,
          '        // 2026-10 新增：风险类型，放进 Intent 给弹窗',
          '        riskKind: String = "normal"');
        console.log('OK CG 1d) launch 加 riskKind');
        break;
      }
    }
  } else console.log('SKIP CG 1d)');

  // 1e) Intent 里加 putExtra("riskKind", riskKind)
  i = find(/putExtra\("requestPath", requestPath\)/);
  if (i >= 0 && !L.slice(i, i + 3).join('\n').includes('riskKind')) {
    L.splice(i + 1, 0, '                .putExtra("riskKind", riskKind)');
    console.log('OK CG 1e) Intent 加 riskKind');
  } else console.log('SKIP CG 1e)');
});

// ---------------------------------------------------------------- 2) ProcessManager
const PM = 'E:/anquan/embedded/server/src/main/kotlin/roro/stellar/server/service/process/ProcessManager.kt';
editByLines(PM, (L) => {
  for (let i = 0; i < L.length; i++) {
    if (/callerPackage, flat, decision\.reason, userId/.test(L[i])) {
      L[i] = L[i].replace(/decision\.reason, userId/, 'decision.reason, userId, decision.kind');
      console.log('OK PM 传入 kind: ' + L[i].trim());
      break;
    }
  }
});

// 复查
const cg = fs.readFileSync(CG, 'utf8');
const pm = fs.readFileSync(PM, 'utf8');
console.log('');
console.log('CommandGuard riskKind 出现次数: ' + (cg.split('riskKind').length - 1));
console.log('ProcessManager decision.kind: ' + (pm.split('decision.kind').length - 1));
