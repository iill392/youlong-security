// ==========================================================================
// 合规补丁：为「已改动但缺少 MPL-2.0 修改声明」的文件补上声明
// ==========================================================================
const fs = require('fs');
const path = require('path');

let fixed = 0, failed = 0;

function patchKt(file, decl) {
  if (!fs.existsSync(file)) { console.log('MISSING ' + file); failed++; return; }
  let t = fs.readFileSync(file, 'utf8');
  if (/本工程(修改声明|改动|新增|删除)/.test(t)) { console.log('SKIP(已有声明) ' + path.basename(file)); return; }
  fs.writeFileSync(file, decl + t, 'utf8');
  console.log('OK ' + file.replace(/\\/g, '/').replace('E:/anquan/embedded/', ''));
  fixed++;
}

function patchXml(file, decl) {
  if (!fs.existsSync(file)) { console.log('MISSING ' + file); failed++; return; }
  let t = fs.readFileSync(file, 'utf8');
  if (/本工程(修改声明|改动|新增|删除)/.test(t)) { console.log('SKIP(已有声明) ' + file); return; }
  const anchor = '<?xml version="1.0" encoding="utf-8"?>';
  if (!t.startsWith(anchor)) { console.log('!! 头部不符 ' + file); failed++; return; }
  t = anchor + '\n' + decl + t.slice(anchor.length);
  fs.writeFileSync(file, t, 'utf8');
  console.log('OK ' + file.replace(/\\/g, '/').replace('E:/anquan/embedded/', ''));
  fixed++;
}

const M = 'E:/anquan/embedded/manager/src/main/';
const S = 'E:/anquan/embedded/server/src/main/';

// ---------------------------------------------------------------- 1) Kotlin
patchKt(M + 'kotlin/roro/stellar/manager/startup/service/SelfStarterService.kt',
`// ==========================================================================
// 本文件来自 https://github.com/roro2239/Stellar 的
//   manager/src/main/kotlin/roro/stellar/manager/startup/service/SelfStarterService.kt
// （MPL-2.0；其中源自 Shizuku 的部分为 Apache-2.0）
//
// 【本工程修改声明】—— 按 MPL-2.0 第 3.4 条标注
//   改动：前台服务通知里引用的字符串资源 ID 由上游的
//         R.string.starting_stellar_service 改为
//         R.string.starting_adb_shield_service。
//   原因：本工程把界面品牌名由 "Stellar / ADB护盾版" 改为
//         "游龙安全ADB / YouLong SafeADB"，资源 ID 一并改名以免
//         继续出现上游项目名；文案内容同步改名（见各语言 strings.xml 的声明）。
//   除该资源 ID 外，本文件其余内容与上游一致。
// ==========================================================================

`);

patchKt(S + 'kotlin/roro/stellar/server/service/process/CommandGuard.kt',
`// ==========================================================================
// 说明：本文件是**本工程新增**（不是上游 Stellar 文件），因此不受 MPL-2.0
//       第 3.4 条「被修改文件需带修改声明」的约束；此处仅作来源与合规说明。
//
// 新增内容：命令安全拦截的弹窗投递与用户答复等待（详见文件内注释）。
// 合规相关：它属于「与 MPL 代码并置的独立文件」（MPL-2.0 第 3.3 条 Larger Work），
//           本产品以 Apache-2.0 发布本文件；它调用 MPL 覆盖的 Stellar 服务端代码
//           仅通过既有内部 API，未复制或修改 MPL 代码本身。
// ==========================================================================

`);

patchKt(M + 'kotlin/roro/stellar/manager/ui/theme/BrandColors.kt',
`// ==========================================================================
// 说明：本文件是**本工程新增**（不是上游 Stellar 文件），因此不受 MPL-2.0
//       第 3.4 条「被修改文件需带修改声明」的约束；此处仅作来源与合规说明。
//
// 新增内容：内置管理器的护盾品牌配色（替代上游的 Material3 默认色板）。
// 合规相关：本文件不包含任何取自 Stellar 的代码或素材，色值取自本产品
//           自己网页界面的用色统计。
// ==========================================================================

`);

patchKt(M + 'kotlin/roro/stellar/manager/ui/features/settings/OpenSourceLicensesActivity.kt',
`// ==========================================================================
// 说明：本文件是**本工程新增**（不是上游 Stellar 文件），因此不受 MPL-2.0
//       第 3.4 条「被修改文件需带修改声明」的约束；此处仅作来源与合规说明。
//
// 新增内容：应用内「开源许可」查看界面（Apache-2.0 第 4(a) 条 /
//           MPL-2.0 第 3.1-3.3 条要求的「向接收者提供许可证副本」入口）。
// 合规相关：本文件不包含任何取自 Stellar 的代码。
// ==========================================================================

`);

// ---------------------------------------------------------------- 2) strings.xml
const locales = ['ar', 'es', 'fr', 'ja', 'pt-rBR', 'ru', 'zh-rHK', 'zh-rTW'];
const localeComment = (loc) => `<!--
  ==========================================================================
  本文件来自 https://github.com/roro2239/Stellar 的 manager/src/main/res/ 对应语言目录
  （MPL-2.0；其中源自 Shizuku 的部分为 Apache-2.0）

  【本工程修改声明】—— 按 MPL-2.0 第 3.4 条标注
    改动 1：删除上游的 app_name 条目（它会把宿主应用的显示名顶掉）。
    改动 2：界面品牌名由 "ADB Shield / ADB护盾版 / ADB 護盾版" 改为
            "YouLong SafeADB / 游龙安全ADB / 遊龍安全ADB"。
    改动 3：三个品牌相关资源 ID 改名：
              stellar_apps                -> adb_shield_apps
              permission_use_stellar      -> permission_use_adb_shield
              starting_stellar_service    -> starting_adb_shield_service
            （调用方 SelfStarterService.kt / AppsScreen.kt /
              RequestPermissionActivity.kt 已同步修改，见各自文件的声明）
    改动 4：新增本工程自己的字符串（配色来源、命令安全拦截、开源许可等）。
    未改动：开机脚本路径 /data/adb/service.d/stellar.sh 保持上游原名
            （它不是品牌名，改名会导致脚本路径不一致）。

  本语言文件：values-${loc}
  ==========================================================================
-->
`;

for (const loc of locales) {
  patchXml(M + 'res/values-' + loc + '/strings.xml', localeComment(loc));
}

console.log('\n修复 ' + fixed + ' 个，失败 ' + failed + ' 个');
