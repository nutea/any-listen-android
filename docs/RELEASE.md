# 签名与发布

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

## 源码公开与分发声明

发布名称及说明应保留“非官方客户端”身份，链接本项目仓库，并使用“基于 AGPL v3.0、附加非商业条款的自定义许可证”的准确许可名称。不要标记为标准 AGPL、MIT 或 Apache-2.0。

每个 APK 发布版本还应附带：

- 对应的源码标签/提交以及完整可构建源码下载，包含构建脚本；仅提供最新默认分支链接不够。
- 根目录 `LICENSE`、`NOTICE`、`docs/THIRD_PARTY.md` 和引用资源的上游许可及来源。
- 根据该次 releaseRuntimeClasspath 及 APK 实际内容整理的依赖许可与必要 NOTICE；第三方索引不等于完整的二进制分发许可清单。

保持源代码和说明可从 APK 下载页面免费获得，并按许可证要求持续提供。发布者需遵守完整许可证的源码、通知和其他分发要求；本文件不替代许可证。签名证书与软件许可证是两回事，更新许可文档无需更换应用签名密钥。

## 首版 v0.1.0

- versionName: 0.1.0；versionCode: 3；正式包名 io.github.nutea.anylisten。
- 使用已有本地签名密钥，不重新生成或更换；密钥与密码仅在被忽略的本地文件中保存。
- 先执行 `:core:model:test :core:data:testDebugUnitTest :app:lintRelease :app:assembleRelease`，再执行模拟器界面回归，并安装正式签名 APK 检查冷启动。
- 依赖清单：`./gradlew :app:writeReleaseDependencies --no-configuration-cache`，随后 `python tools/release/collect_licenses.py`。
- 源码提交和 CI 通过后创建 v0.1.0 标签；通过 GitHub Release 分发正式 APK、对应 git archive 源码、LICENSE/NOTICE/third_party 许可包与 SHA256SUMS.txt。使用草稿上传，检查附件齐全后发布。
- 调试版与正式版包名不同，数据不会自动迁移。发布正文见 RELEASE_NOTES_0.1.0.md。
