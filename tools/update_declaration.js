// 更新「项目声明」文案：明确署名 Shizuku / Stellar 及其许可证
const fs = require('fs');
const path = require('path');
const RES = 'E:/anquan/embedded/manager/src/main/res';

const texts = {
  'values': {
    title: 'Acknowledgement',
    // 注意：' 需转义为 \'
    body: 'This product embeds the open-source project Shizuku (Apache License 2.0, Copyright RikkaApps) together with its fork Stellar (Mozilla Public License 2.0, Copyright roro2239 / RoRo Studio), which provide the ability to call system APIs via ADB or Root.\\n\\nThe corresponding source code, the full license texts of Apache-2.0 and MPL-2.0, and the list of every file we modified are shipped with this product under LICENSES/ and NOTICE, and can be viewed in-app via "Open Source Licenses".',
  },
  'values-zh-rCN': {
    title: '项目声明',
    body: '本产品内置了开源项目 Shizuku（Apache License 2.0，版权归 RikkaApps）及其分支 Stellar（Mozilla Public License 2.0，版权归 roro2239 / RoRo Studio），它们提供了通过 ADB 或 Root 调用系统 API 的能力。\\n\\n对应的源代码、Apache-2.0 与 MPL-2.0 许可证全文，以及本产品对每一个被修改文件的改动说明，均随本产品以源码形式分发（见 LICENSES/ 与 NOTICE），并可在应用内「开源许可」中查看。',
  },
  'values-zh-rHK': {
    title: '項目聲明',
    body: '本產品內置了開源項目 Shizuku（Apache License 2.0，版權歸 RikkaApps）及其分支 Stellar（Mozilla Public License 2.0，版權歸 roro2239 / RoRo Studio），它們提供了透過 ADB 或 Root 調用系統 API 的能力。\\n\\n對應的源代碼、Apache-2.0 與 MPL-2.0 許可證全文，以及本產品對每一個被修改文件的改動說明，均隨本產品以源碼形式分發（見 LICENSES/ 與 NOTICE），並可在應用內「開源許可」中查看。',
  },
  'values-zh-rTW': {
    title: '專案聲明',
    body: '本產品內嵌了開源專案 Shizuku（Apache License 2.0，版權歸 RikkaApps）及其分支 Stellar（Mozilla Public License 2.0，版權歸 roro2239 / RoRo Studio），它們提供了透過 ADB 或 Root 呼叫系統 API 的能力。\\n\\n對應的原始碼、Apache-2.0 與 MPL-2.0 授權條款全文，以及本產品對每一個被修改檔案的改動說明，均隨本產品以原始碼形式分發（見 LICENSES/ 與 NOTICE），並可在應用內「開源授權」中查看。',
  },
};

for (const [locale, v] of Object.entries(texts)) {
  const f = path.join(RES, locale, 'strings.xml');
  if (!fs.existsSync(f)) { console.log('SKIP ' + locale); continue; }
  let t = fs.readFileSync(f, 'utf8');
  const reTitle = /<string name="project_declaration">[^<]*<\/string>/;
  const reBody = /<string name="project_declaration_content">[^<]*<\/string>/;
  if (!reTitle.test(t) || !reBody.test(t)) { console.log('PATTERN MISS ' + locale); continue; }
  t = t.replace(reTitle, '<string name="project_declaration">' + v.title + '</string>');
  t = t.replace(reBody, '<string name="project_declaration_content">' + v.body + '</string>');
  fs.writeFileSync(f, t, 'utf8');
  console.log('OK ' + locale);
}
