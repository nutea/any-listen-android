# v0.1.1-beta.4 · 连接后闪退修复

Any Listen Android 非官方客户端的测试版本。v0.1.1-beta.3 把闪退当成冷启动 EncryptedSharedPreferences 失败来修，但用户反馈 **连上服务器之后** 仍会崩溃。本版本撤回那套无效 hardening，并修复 0.1.1 重连路径在「已经连上」时误拆 IPC 会话的问题。

- **撤回** PR #5 对 `SecureSessionStore` 的 wipe / fallback / Tink consumer-rules。那条路径只在 `Application.onCreate` 跑，解释不了「连接成功后崩溃」。
- **对比 v0.1.0（可用）与 0.1.1-beta.1 起的重连改动：** 登录或会话恢复成功后，`ConnectivityManager` 的第一次回调仍会立刻 `session.restore()`，把刚建好的 WebSocket 拆掉再建。`Message2Call.destroy()` 与 `onMessage` 在 OkHttp 线程上双次 resume，会以未捕获异常杀死进程。
- **实际修复：** 健康且尚未发生网络切换的会话不再重建；只有套接字已死或默认网络真的变了才重连（半开连接仍按 PR #1 恢复）。`Message2Call` 对已完成的 continuation 不再抛 `Already resumed`，同一异常实例也不会复用到多个 pending call。
- 版本号 0.1.1-beta.4（versionCode 7）。请勿重打 beta.3 标签；合并后由现有 CI 用已配置的上传密钥签名。未轮换签名密钥。

## 安装

Android 8.0 及以上；当前适配 Any Listen Web Server v0.11.0-beta.1，需要手机可访问的 HTTPS 服务地址。本项目不提供音乐资源或公共服务器。

本版本为 **测试版**。正式包名仍为 `io.github.nutea.anylisten`，与调试版 `.debug` 独立安装。可覆盖安装 v0.1.1-beta.3（同一上传密钥）。从 v0.1.0 或旧签名包升级须先卸载。

## 如何验证

1. 全新安装或清除应用数据后打开客户端，应能进入连接页（与 v0.1.0 相同）。
2. 填写自建 Any Listen Web Server 地址并登录。预期：连接成功后进入曲库，进程保持存活，不会在登录成功后立刻闪退。
3. 杀进程再开：应自动恢复会话并停留在曲库，而不是连上后崩溃。
4. 对照：播放未缓存歌曲时切换 Wi‑Fi ↔ 移动数据，仍应能在短暂缓冲后恢复（PR #1 行为保留）。

## 范围与反馈

主要作者自用，优先维护现有功能和本地音乐管理体验。Issues 重点处理 Bug，新功能不承诺采纳或排期。

## 下载与许可

`SHA256SUMS.txt` 提供附件校验值。本项目非官方、无官方背书，源码采用基于 AGPL v3.0、附加非商业条款的自定义许可证，详见源码中的 LICENSE、NOTICE 和 docs/THIRD_PARTY.md。
