# 签名与私有发布

## 密钥

- 在用户控制的安全位置生成上传密钥，例如 `keytool -genkeypair -keystore any-listen.jks -keyalg RSA -keysize 2048 -validity 10000`。
- 不要把 `.jks` / 密码提交到 git。CI 用仓库 Secrets 注入。
- 本仓库只记录操作，不保存密钥。

## 正式构建

复制 `keystore.properties.example` 为仓库根目录的 `keystore.properties`，并指向本地 `.jks`（均已 gitignore）。也可运行 `python tools/release/ensure_local_keystore.py` 生成私人上传密钥，然后：

```bash
./gradlew assembleRelease
```

产物在 `app/build/outputs/apk/release/`。对 APK 计算 SHA-256。没有 `keystore.properties` 时 release 仍可编译，但不会按上传密钥签名。

发布物：

- APK
- SHA-256
- `versionName` / `versionCode`
- 兼容服务端版本：`0.11.0-beta.1`
- 真机结果见 `docs/TEST_EVIDENCE.md`
