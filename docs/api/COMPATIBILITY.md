# 能力矩阵：Any Listen Web Server v0.11.0-beta.1

状态：源码审查 + **2026-09-13 现场只读探测**（凭据仅在本地 `.env`，未入仓库）。
日期：2026-09-13。

## 版本定位

| 项 | 值 | 证据 |
|---|---|---|
| 宣称版本 | v0.11.0-beta.1 | [web-server Release](https://github.com/any-listen/any-listen-web-server/releases/tag/v0.11.0-beta.1) |
| 发布日期 | 2026-08-31 | GitHub Release `published_at` |
| 发布 zip | `any-listen-web-server_v0.11.0-beta.1.zip` | asset digest `sha256:daa59fff4b03d4f4ea18034a275b600236d9612c866d989dd7f1adefb28abe4f` |
| 匹配源码标签 | `webserver-v0.11.0-beta.1` | [any-listen@e4ef53a](https://github.com/any-listen/any-listen/commit/e4ef53a5094473687e2d142fb7b63a530436b982) |
| 现场 `getCurrentVersionInfo.version` | `0.11.0-beta.1` | 探针 `version` 通过 |
| 公开镜像 | `lyswhut/any-listen-web-server:0.11.0-beta.1` | Docker Hub 标签存在 |
| 用户部署摘要 | HTTPS 公网；无额外 API 前缀 | hello 在 `/api/ipc/hello`；TLS 系统校验通过 |
| 反代前缀/额外认证 | 未见额外路径或第二层登录 | 未提供反代配置；当前路径与源码一致 |

现场验证命令：`python tools/probe/probe.py`（凭据只走环境变量 / `.env`）。
脱敏报告只写在 `captures/private/`（gitignore）。

## 能力表

逐项状态只能是：通过 / 失败 / 源码推断 / 未验证。源码推断不能代替现场探测。

| 能力 | 状态 | 日期 | 源码 | 脱敏证据 | 复现 | 影响与处理 |
|---|---|---|---|---|---|---|
| 登录 | 通过 | 2026-09-13 | `ipc/{index,auth}.ts` | hello 常量；错密 401 `Auth failed`；正确密码发 token | `probe.py auth` | `/api/ipc/ah`；禁止 trust-all |
| 认证恢复 | 通过 | 2026-09-13 | `auth.ts` | 只带头 `m=token` 恢复成功 | `probe.py session` | 客户端存 token 与密码；token 失效时静默重登；错密或手动退出才清会话 |
| WebSocket | 通过 | 2026-09-13 | `websocket.ts` | 升级 101；`inited` 返回 | `probe.py ws` | `/api/ipc/socket?m=&t=main` |
| 列表与曲目 | 通过 | 2026-09-13 | `list_ipc.d.ts` | 内置 `default`/`love`/`last_played` + 1 个用户列表；样本 8 首；同会话 `id` 稳定 | `getAllUserLists`/`getListMusics` | `id` 作 remoteTrackId，`meta.musicId` 作指纹 |
| 音频解析 | 通过 | 2026-09-13 | `fileSystem/index.ts` `tools.ts` | 本地曲目 `al-ps-host:` → `/public/medias/` | `getMusicUrl` | 客户端必须改写，见 `UrlNormalizer` |
| Range | 通过 | 2026-09-13 | `stream-file.ts` | `206` `audio/mpeg` `Accept-Ranges: bytes` 1024B | `probe.py range` | 下载可续传；遇 200 仍整段重下 |
| 封面/歌词 | 通过 | 2026-09-13 | `getMusicPic` `getMusicLyric` | 封面有 url；歌词 `info.lyric` 有内容；另有 `awlyric` | IPC | 缺失不阻断播放；封面同样要改写虚拟协议 |
| 收藏/歌单 | 通过 | 2026-09-13 | `listAction` | 隔离歌单 add 0→1，remove 恢复 0；未调用 overwrite | `probe.py --write-playlist` | 只对独立用户歌单写；内置列表拒绝 |
| 文件变更 | 未验证 | 2026-09-13 | 无生产实验 | — | 只用测试副本 | 旧下载标孤立 |
| 反向代理 | 通过（当前实例） | 2026-09-13 | `getIP` | 无额外前缀即可登录 | 现场 hello | 若以后加前缀，只改 `baseUrl` |

## 缺口处理

1. 本地媒体 URL 是 `al-ps-host:/public/medias/<name>`，不是直链。适配层已按源码改写。
2. Range：改写后必须复测；未 206 则下载整文件重下，不宣称续传。
3. 地址栏 `?id=` 可能与 IPC `userList[].id` 不完全相同；写入以 `getAllUserLists` 为准。
4. 生产补丁：默认不改生产服务。当前缺口可用客户端改写消化。
