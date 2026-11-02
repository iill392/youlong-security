# Conscrypt 编译期桩（compileOnly，不会进 APK）

## 为什么需要它

ADB 无线调试配对要算密码：

```
password = pairCode 的字节 ‖ RFC5705_TLS_exporter(socket, "adb-label\0", 64 字节)
```

那个 TLS exporter（RFC 5705 keying material）在 JSSE 里**没有公开 API**。
Android 上只有隐藏类 `com.android.org.conscrypt.Conscrypt` 提供：

```java
public static byte[] exportKeyingMaterial(SSLSocket socket, String label, byte[] context, int length)
```

Shizuku 上游就是直接调它。但 `android.jar`（API 34）里**不含**这个 `@hide` 类，
所以需要一份「只有签名、没有实现」的桩来让编译器闭嘴。

## 它不会进 APK

`app/build.gradle` 里是这样引用的：

```groovy
compileOnly files('libs/conscrypt-stub/conscrypt-stub.jar')
```

`compileOnly` 只出现在编译类路径上，不参与打包、也不在 R8 的 program path 上，
因此这个桩一定会被 R8 视为「缺失的库类」（配合 `-dontwarn com.android.org.conscrypt.**`），
运行期解析到的是系统自带的真实实现。

## 万一哪天需要重新生成

```powershell
$jdk = "D:\scoop\apps\temurin21-jdk\current\bin"
& "$jdk\javac.exe" -encoding UTF-8 -source 8 -target 8 -nowarn `
    -d conscrypt-stub/classes conscrypt-stub/src/com/android/org/conscrypt/Conscrypt.java
& "$jdk\jar.exe" --create --file conscrypt-stub/conscrypt-stub.jar -C conscrypt-stub/classes com
```

## 验证打包结果

```powershell
# 应该没有任何输出
Select-String -Path <dex 转出的 txt> -Pattern "com/android/org/conscrypt"
```
