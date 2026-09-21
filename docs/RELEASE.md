# 签名与发布

正式发布优先走 **GitHub Actions 标签签名**：推送版本标签后，CI 用仓库 Secrets 解码上传密钥、签名 Release APK，并挂到对应 GitHub Release。不要把真实密钥、密码或 keystore 字节写进文档、提交到 git，或打印到日志。

## CI 标签签名（首选）

推送任意标签（例如 `v0.1.1-beta.2`）会跑既有质量门槛（单测、Lint、`assembleDebug`）。四个 Secrets **都已配置**时，再解码密钥并执行 `:app:assembleRelease`。缺任一 Secret 时签名作业会明确失败，**不会**静默发布未签名的 “release” APK。

仓库 Secrets（只写名称，不要填写真实值）：

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

CI 在仓库根目录写入 gitignore 的 `keystore.properties`，并与现有 Gradle 约定一致：

```
storeFile=secrets/upload.jks
storePassword=...
keyAlias=...
keyPassword=...
```

`ANDROID_KEYSTORE_BASE64` 解码到 `secrets/upload.jks`（该路径已被 gitignore）。当前上传密钥别名为 `upload`。

标签去掉前导 `v` 作为 `VERSION`（`v0.1.1-beta.2` → `0.1.1-beta.2`）。CI 会创建或更新该标签的 GitHub Release，并附上：

- 签名 APK：`any-listen-android-${VERSION}.apk`
- `SHA256SUMS.txt`（至少包含 APK 校验；若该 Release 已有其它附件，会合并既有校验行）
- `any-listen-android-${VERSION}-source.zip`（`git archive`）

版本名含 `beta` / `rc` / `alpha` 时发布为 **Pre-release**，否则为正式 Release。发布说明优先使用 `docs/RELEASE_NOTES_${VERSION}.md`。

## 本地构建（可选）

本地 `keystore.properties` 与 `python tools/release/ensure_local_keystore.py` 只用于本机 Release 构建，不是 CI 发布的前置条件。复制 `keystore.properties.example` 到仓库根目录并指向本地 `.jks`（均已 gitignore），或运行该脚本生成**私人**上传密钥，然后：

```bash
./gradlew assembleRelease
```

产物在 `app/build/outputs/apk/release/`。对 APK 计算 SHA-256。没有 `keystore.properties` 时 release 仍可编译，但不会按上传密钥签名，也不得当作可分发正式包。

发布物：

- APK
- SHA-256
- `versionName` / `versionCode`
- 兼容服务端版本：`0.11.0-beta.1`
- 真机结果见 `docs/TEST_EVIDENCE.md`

## 轮换上传密钥

更换上传密钥（包括放弃旧 keystore、改用新 `upload` 别名）后，**无法**覆盖安装旧签名的应用。v0.1.0 以及任何仍使用旧签名或未签名安装包的用户必须先卸载再安装；应用私有下载与登录状态会一并清除。这与软件许可证无关：更新 LICENSE 不必换签名密钥，但换密钥一定会影响应用更新路径。

## 源码公开与分发声明

发布名称及说明应保留“非官方客户端”身份，链接本项目仓库，并使用“基于 AGPL v3.0、附加非商业条款的自定义许可证”的准确许可名称。不要标记为标准 AGPL、MIT 或 Apache-2.0。

每个 APK 发布版本还应附带：

- 对应的源码标签/提交以及完整可构建源码下载，包含构建脚本；仅提供最新默认分支链接不够。
- 根目录 `LICENSE`、`NOTICE`、`docs/THIRD_PARTY.md` 和引用资源的上游许可及来源。
- 根据该次 releaseRuntimeClasspath 及 APK 实际内容整理的依赖许可与必要 NOTICE；第三方索引不等于完整的二进制分发许可清单。

保持源代码和说明可从 APK 下载页面免费获得，并按许可证要求持续提供。发布者需遵守完整许可证的源码、通知和其他分发要求；本文件不替代许可证。签名证书与软件许可证是两回事，更新许可文档无需更换应用签名密钥。

## 首版 v0.1.0

- versionName: 0.1.0；versionCode: 3；正式包名 io.github.nutea.anylisten。
- 使用当时的本地上传密钥签名；该密钥现已弃用，不能再用于覆盖安装后续版本。
- 先执行 `:core:model:test :core:data:testDebugUnitTest :app:lintRelease :app:assembleRelease`，再执行模拟器界面回归，并安装正式签名 APK 检查冷启动。
- 依赖清单：`./gradlew :app:writeReleaseDependencies --no-configuration-cache`，随后 `python tools/release/collect_licenses.py`。
- 源码提交和 CI 通过后创建 v0.1.0 标签；通过 GitHub Release 分发正式 APK、对应 git archive 源码、LICENSE/NOTICE/third_party 许可包与 SHA256SUMS.txt。使用草稿上传，检查附件齐全后发布。
- 调试版与正式版包名不同，数据不会自动迁移。发布正文见 RELEASE_NOTES_0.1.0.md。

## 测试版 v0.1.1-beta.1

- versionName: 0.1.1-beta.1；versionCode: 4；正式包名不变。
- 聚焦网络切换后的断连、卡住加载与「音频不可用」恢复，见 [RELEASE_NOTES_0.1.1-beta.1.md](RELEASE_NOTES_0.1.1-beta.1.md)。
- 原计划沿用 v0.1.0 上传密钥以保证覆盖安装；仓库发布环境当时没有 `keystore.properties`，GitHub Pre-release 只附源码与许可包，**没有**签名 APK。
- 不要移动或重打 `v0.1.1-beta.1` 标签。后续签名发布走新的 CI 密钥与新的测试版标签。

## 测试版 v0.1.1-beta.2

- versionName: 0.1.1-beta.2；versionCode: 5；正式包名不变。
- 启用 CI 标签签名；使用新的上传密钥（别名 `upload`）。旧上传密钥已弃用。
- 仍包含 v0.1.1-beta.1 的网络重连修复。
- 从 v0.1.0 或任何旧签名 / 未签名安装升级时，须卸载后重装。见 [RELEASE_NOTES_0.1.1-beta.2.md](RELEASE_NOTES_0.1.1-beta.2.md)。
- 兼容服务端仍为 Any Listen Web Server `0.11.0-beta.1`。

## 测试版 v0.1.1-beta.3

- versionName: 0.1.1-beta.3；versionCode: 6。按冷启动 EncryptedSharedPreferences 失败做了 session store fallback；**未能**解决「连上服务器后崩溃」。见 [RELEASE_NOTES_0.1.1-beta.3.md](RELEASE_NOTES_0.1.1-beta.3.md)。
- 不要移动或重打 `v0.1.1-beta.3` 标签。

## 测试版 v0.1.1-beta.4

- versionName: 0.1.1-beta.4；versionCode: 7；正式包名不变。
- 撤回 beta.3 无效的 SecureSessionStore hardening；修复登录/恢复成功后误拆 IPC 导致的闪退。见 [RELEASE_NOTES_0.1.1-beta.4.md](RELEASE_NOTES_0.1.1-beta.4.md)。
- 不要移动或重打 `v0.1.1-beta.3` 标签。未轮换签名密钥。

## 测试版 v0.1.1-beta.5

- versionName: 0.1.1-beta.5；versionCode: 8；正式包名不变。
- 重构服务器连接 / 会话 / 重连：单一 `SessionConnectionManager`、稳定网络身份、IPC 完成幂等。针对「播放未缓存歌曲时切换网络闪退」。见 [RELEASE_NOTES_0.1.1-beta.5.md](RELEASE_NOTES_0.1.1-beta.5.md)。
- 不要移动或重打 `v0.1.1-beta.4` 标签。未轮换签名密钥。
- 保留 beta.1 网络重连修复与 CI 标签签名。不要轮换上传密钥。

## 正式版 v0.1.2

- versionName: 0.1.2；versionCode: 9；正式包名不变。版本名不含 beta / rc / alpha。
- 相对 v0.1.0：纳入网络重连（PR #1 一脉）、CI 标签签名（beta.2）、连接 / 会话重构（PR #7 / beta.5）。beta.3 的 SecureSessionStore hardening 已在 beta.4 撤回。登录闪退与播放未缓存歌曲时切换网络闪退均已修复。见 [RELEASE_NOTES_0.1.2.md](RELEASE_NOTES_0.1.2.md)。
- 合并后由协调者打标签 `v0.1.2`，现有 CI 签名并发布正式 Release（非 Pre-release）。不要在本变更中打标签，也不要移动或重打既有测试版标签。未轮换签名密钥。

## 测试版 v0.1.4-beta.1

- versionName: 0.1.4-beta.1；versionCode: 11；正式包名及现有签名密钥不变。
- 歌单管理、多种歌词、评论、歌手与专辑页面，见 [发布说明](RELEASE_NOTES_0.1.4-beta.1.md)。
- 发布前审查与验证见 [审查记录](REVIEW_0.1.4-beta.1.md)；本轮设备测试使用 Android 16 ARM64 模拟器。

## 测试版 v0.1.4-beta.2

- versionName: 0.1.4-beta.2；versionCode: 12；沿用现有签名密钥。
- beta.1 因 CI 异步测试时序断言失败未发布 APK，保留标签不重打。
- 修复歌词刷新测试的同步条件，功能范围与 beta.1 相同；见 [发布说明](RELEASE_NOTES_0.1.4-beta.2.md) 和 [审查记录](REVIEW_0.1.4-beta.1.md)。
