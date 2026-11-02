// 加一个诊断桥接：getRootDiagnostics()（仅诊断用，不参与界面判定）
const fs = require('fs');
const P = 'E:/anquan/app/src/main/java/com/youlong/hd/MainActivity.java';
let t = fs.readFileSync(P, 'utf8');
const orig = t.length;
const crlf = s => s.replace(/\r?\n/g, '\r\n');

function rep(from, to, label) {
  const f = crlf(from), g = crlf(to);
  const n = t.split(f).length - 1;
  if (n !== 1) { console.log('!! ' + label + ' 匹配 ' + n + ' 次，跳过'); return false; }
  t = t.replace(f, g);
  console.log('OK ' + label);
  return true;
}

rep(
`    /**
     * 设备是否 root（2026-10 改为"实证"判定，修掉没 root 被误判的问题）。`,
`    /**
     * root 判定诊断信息（**仅供排错**，不参与界面判定）。
     *
     * <p>如果仍有用户反馈"没 root 却被判定有 root"，把这段 JSON 发回来即可定位
     * 是哪一条命中：装了哪个包、哪个 su 路径存在、su 实际返回了什么。
     */
    private String buildRootDiagnosticsJson() {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\\"rooted\\":").append(deviceIsRooted());
        sb.append(",\\"dhizuku\\":").append(deviceHasDhizuku());

        // 命中/未命中的 root 管理器包
        sb.append(",\\"packages\\":{");
        String[] pkgs = {
                "com.topjohnwu.magisk", "io.github.huskydg.magisk",
                "me.weishu.kernelsu", "com.rifsxd.ksunext",
                "me.bmax.apatch", "eu.chainfire.supersu",
                "com.koushikdutta.superuser"
        };
        for (int i = 0; i < pkgs.length; i++) {
            if (i > 0) sb.append(',');
            sb.append('\\"').append(pkgs[i]).append("\\":").append(isInstalledPkg(pkgs[i]));
        }
        sb.append("}");

        // 各 su 路径是否存在
        sb.append(",\\"suPaths\\":{");
        String[] paths = {
                "/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su",
                "/system/sbin/su", "/vendor/bin/su", "/debug_ramdisk/su",
                "/data/adb/ksu/bin/su", "/data/adb/ap/bin/su"
        };
        for (int i = 0; i < paths.length; i++) {
            if (i > 0) sb.append(',');
            boolean exists = false;
            try { exists = new File(paths[i]).exists(); } catch (Throwable ignored) {}
            sb.append('\\"').append(paths[i]).append("\\":").append(exists);
        }
        sb.append("}");

        // su -c id 的实际输出
        String suOut = "";
        try {
            Process p = new ProcessBuilder("su", "-c", "id").redirectErrorStream(true).start();
            final StringBuilder o = new StringBuilder();
            Thread r = new Thread(() -> {
                try (java.io.BufferedReader br = new java.io.BufferedReader(
                        new java.io.InputStreamReader(p.getInputStream()))) {
                    String l;
                    while ((l = br.readLine()) != null) o.append(l).append(' ');
                } catch (Throwable ignored) {}
            });
            r.setDaemon(true);
            r.start();
            if (!p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) {
                try { p.destroyForcibly(); } catch (Throwable ignored) {}
                suOut = "(timeout)";
            } else {
                r.join(500);
                suOut = o.toString().trim();
            }
        } catch (Throwable tr) {
            suOut = "(unavailable: " + tr.getClass().getSimpleName() + ")";
        }
        sb.append(",\\"suIdOutput\\":\\"").append(suOut.replace("\\"", "'")).append("\\"");
        sb.append("}");
        return sb.toString();
    }

    /**
     * 设备是否 root（2026-10 改为"实证"判定，修掉没 root 被误判的问题）。`,
'1) 新增 root 诊断方法');

rep(
`        @JavascriptInterface
        public String getEnvGoodHint() {
            return MainActivity.this.buildEnvGoodHintJson();
        }`,
`        @JavascriptInterface
        public String getEnvGoodHint() {
            return MainActivity.this.buildEnvGoodHintJson();
        }

        /**
         * root 判定诊断（2026-10 新增，**仅供排错**）。
         * 调用后返回 JSON，包含：最终结论、各 root 管理器包是否安装、
         * 各 su 路径是否存在、以及 su -c id 的真实输出。
         * 用户在网页控制台执行 Android.getRootDiagnostics() 即可拿到。
         */
        @JavascriptInterface
        public String getRootDiagnostics() {
            return MainActivity.this.buildRootDiagnosticsJson();
        }`,
'2) 暴露诊断桥接');

fs.writeFileSync(P, t, 'utf8');
console.log('\n' + orig + ' -> ' + t.length + ' bytes');
