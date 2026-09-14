# v0.1.1-beta.2 · CI 签名与重连修复

Any Listen Android 非官方客户端的测试版本。本版由 CI 使用新的上传密钥签名，并继续包含切换网络后的播放恢复修复。

- 标签推送后由 GitHub Actions 解码仓库 Secrets、签名 Release APK，并挂到本 Pre-release。
- 新上传密钥别名为 `upload`；旧上传密钥已弃用，不再用于分发。
- 仍包含 v0.1.1-beta.1 的网络重连修复：Wi‑Fi / 移动数据切换、飞行模式与接入点漫游后恢复 IPC 与播放，无需重启应用。

## 安装

Android 8.0 及以上；当前适配 Any Listen Web Server v0.11.0-beta.1，需要手机可访问的 HTTPS 服务地址。本项目不提供音乐资源或公共服务器。

本版本为 **测试版**。正式包名仍为 `io.github.nutea.anylisten`，与调试版 `.debug` 独立安装。

**签名已更换。** 本 APK 的签名与 v0.1.0 不同，也与任何本地未签名 / 旧密钥的 v0.1.1-beta.1 安装包不同，无法覆盖更新。请先卸载再安装 `any-listen-android-0.1.1-beta.2.apk`。卸载会删除应用私有下载与登录状态。

## 范围与反馈

主要作者自用，优先维护现有功能和本地音乐管理体验。Issues 重点处理 Bug，新功能不承诺采纳或排期。测试版未覆盖所有设备和服务端部署组合；不同厂商后台限制需在系统设置中按需允许后台播放。

## 下载与许可

`SHA256SUMS.txt` 提供附件校验值；`any-listen-android-0.1.1-beta.2-source.zip` 为此标签对应的源码。

本项目非官方、无官方背书，源码采用基于 AGPL v3.0、附加非商业条款的自定义许可证，详见源码中的 LICENSE、NOTICE 和 docs/THIRD_PARTY.md。第三方组件保留各自许可；音乐、封面和歌词授权不包含在软件许可中。
