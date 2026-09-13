# 测试记录

未测项保持「未验证」，不写「通过」。

设备：Xiaomi 14（`23127PN0CC` / `houji`），Android 16，包名 `io.github.nutea.anylisten.debug`。

| 场景 | 状态 | 日期 | 设备 / API | 备注 |
|---|---|---|---|---|
| 错误密码 / 证书错误 | 部分 | 2026-09-13 | 探针 / 现场服务 | 错密返回 401 `Auth failed`，随后正确密码可登录。证书错误真机 UI 未测 |
| 会话过期 + 离线库 | 未验证 | | | |
| 锁屏连续切歌 | 未验证 | | | |
| 音频焦点 / 拔耳机 | 部分 | 2026-09-13 | Xiaomi 14 / 36 | `cmd media_session dispatch` 可使本应用 PAUSED→PLAYING；通知权限已授予。拔耳机、来电未测 |
| Wi-Fi → 蜂窝 → 断网 | 未验证 | | | |
| 飞行模式冷启动 | 未验证 | | | 本机已有完成下载文件；未关网冷启动 |
| 下载中断 / 进程回收 | 未验证 | | | 状态机单测已有 |
| Range 被忽略 / 源改变 | 部分 | 2026-09-13 | 单测 | `FileDownloaderTest`：206 续传、200 整段重下。真机源改变未测 |
| 磁盘不足 / 损坏文件 | 未验证 | | | |
| 删除下载不影响服务器 | 未验证 | | | |
| 双端歌单修改 | 未验证 | | | 不调用 overwrite |
| 升级覆盖安装 | 部分 | 2026-09-13 | Xiaomi 14 / 36 | debug `adb install -r` 后 Room 库与下载文件仍在。正式包覆盖未测 |

服务端现场（2026-09-13）：登录、会话、WS、列表、`getMusicUrl`、Range `206 audio/mpeg`、隔离歌单 add/remove 通过。本地音频为 `al-ps-host:` → `/public/medias/`。详见 `docs/api/COMPATIBILITY.md`。

真机播放（2026-09-13）：MediaSession 为媒体按键会话，可 pause / play。

本地签名 APK：`app/build/outputs/apk/release/app-release.apk`，`versionName=0.1.0-dev`，SHA-256 `1F462B188300E6EA79F48D58EC3B3A7A03C904A0E6D6A4D13F9AF208A2F16124`。密钥在 gitignore 的 `keystore.properties` / `secrets/upload.jks`。
