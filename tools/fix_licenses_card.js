const fs = require('fs');
const p = 'E:/anquan/embedded/manager/src/main/kotlin/roro/stellar/manager/ui/features/settings/SettingsScreen.kt';
const lines = fs.readFileSync(p, 'utf8').split('\n');

// 目标结构（1-based）：
//   848  Text("Stellar", ...)
//   849  }
//   850  (空行)
//   851..878  新插入的开源许可块
//   879  }        <-- 多余的右花括号，删掉
//   880  }        <-- Row 的右花括号
// 目标：
//   848  Text("Stellar", ...)
//   849  }         关闭 Row
//                   接着插入开源许可块
//   }            关闭 Column

// 先取出开源许可块（851..878 -> 索引 850..877）
const block = lines.slice(850, 878);
// 删掉 850..879（索引 849..878）：空行 + 块 + 多余花括号
lines.splice(849, 30);
// 现在索引 848 是原来的 849 行 "}"（关闭 Row）
// 在它之后插入块 + Column 的闭合
lines.splice(849, 0, ...block, '                 }');

fs.writeFileSync(p, lines.join('\n'), 'utf8');
console.log('OK, 新行数: ' + lines.length);
for (let i = 840; i < Math.min(890, lines.length); i++) {
  console.log((i + 1) + ': ' + lines[i]);
}
