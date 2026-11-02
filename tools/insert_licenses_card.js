const fs = require('fs');
const p = 'E:/anquan/embedded/manager/src/main/kotlin/roro/stellar/manager/ui/features/settings/SettingsScreen.kt';
let t = fs.readFileSync(p, 'utf8');
const lines = t.split('\n');

// 找到 Text("Stellar" 那一行
let idx = -1;
for (let i = 0; i < lines.length; i++) {
  if (lines[i].includes('Text("Stellar"')) { idx = i; break; }
}
if (idx < 0) { console.log('ANCHOR NOT FOUND'); process.exit(1); }
console.log('anchor line ' + (idx + 1) + ': ' + lines[idx].trim());

// 打印锚点之后的 10 行便于确认
for (let i = idx; i < Math.min(idx + 10, lines.length); i++) {
  console.log((i + 1) + ': ' + lines[i]);
}

const block = `
                     // ⚠️ 本工程新增（第六轮）：「开源许可」入口。
                     //    合规依据：Apache-2.0 第 4(a) 条要求向接收者提供许可证
                     //    副本、第 4(b) 条要求保留 NOTICE；MPL-2.0 第 3.1/3.2 条
                     //    要求以 Source Code Form 提供被覆盖的源码。
                     //    用户拿到的是 APK，所以许可证全文必须能在应用内查看 ——
                     //    这里拉起 OpenSourceLicensesActivity，它直接读取
                     //    res/raw/open_source_licenses.txt
                     //    （Apache-2.0 全文 + MPL-2.0 全文 + NOTICE）。
                     Spacer(modifier = Modifier.height(12.dp))

                     SettingsClickableCard(
                         icon = Icons.Default.Description,
                         title = stringResource(R.string.open_source_licenses),
                         subtitle = stringResource(R.string.open_source_licenses_card_subtitle),
                         onClick = {
                             try {
                                 context.startActivity(
                                     Intent(context, OpenSourceLicensesActivity::class.java)
                                 )
                             } catch (_: Exception) {
                                 Toast.makeText(
                                     context,
                                     context.getString(R.string.licenses_read_failed),
                                     Toast.LENGTH_SHORT
                                 ).show()
                             }
                         }
                     )`;

// 在 Text("Stellar" 那一行之后的第 2 行（即 } } 之后的两处闭合）之前插入：
// 结构是:
//   <anchor>
//   }
//   }
// 我们把它改成:
//   <anchor>
//   }
//   <block>
//   }
lines.splice(idx + 2, 0, block);
fs.writeFileSync(p, lines.join('\n'), 'utf8');
console.log('INSERTED at line ' + (idx + 3));
