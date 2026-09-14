# v0.1.1-beta.3 · 冷启动崩溃修复

Any Listen Android 非官方客户端的测试版本。修复 v0.1.1-beta.2 在部分设备上首次启动黑屏后闪退、之后持续崩溃的问题。

- 会话存储不再在 `Application.onCreate` 中因 EncryptedSharedPreferences / Android Keystore / Tink 初始化失败而杀死进程：失败时清除损坏的密钥再试一次，仍失败则回落到应用私有 SharedPreferences，保证能进入连接页。
- Release R8 保留 `androidx.security.crypto` 与 Tink 注册图，避免压缩后缺类导致同样的启动崩溃。
- 版本号 0.1.1-beta.3（versionCode 6）。请勿重新打 v0.1.1-beta.2 标签；合并后由现有 CI 用已配置的上传密钥签名。

## 安装

Android 8.0 及以上；当前适配 Any Listen Web Server v0.11.0-beta.1，需要手机可访问的 HTTPS 服务地址。本项目不提供音乐资源或公共服务器。

本版本为 **测试版**。正式包名仍为 `io.github.nutea.anylisten`，与调试版 `.debug` 独立安装。可覆盖安装 v0.1.1-beta.2（同一上传密钥）。

## 范围与反馈

主要作者自用，优先维护现有功能和本地音乐管理体验。Issues 重点处理 Bug，新功能不承诺采纳或排期。

## 下载与许可

`SHA256SUMS.txt` 提供附件校验值。本项目非官方、无官方背书，源码采用基于 AGPL v3.0、附加非商业条款的自定义许可证，详见源码中的 LICENSE、NOTICE 和 docs/THIRD_PARTY.md。
