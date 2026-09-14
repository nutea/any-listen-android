# 协议说明（v0.11.0-beta.1 源码）

本文件记录 **any-listen@e4ef53a / tag `webserver-v0.11.0-beta.1`** 的实际路径与语义。
现场响应以 `tools/probe` 脱敏输出为准；与源码冲突时以现场为准并改本文件。
2026-09-13 已对用户部署实例完成登录、WS、列表、音频解析探测（Range 见 `COMPATIBILITY.md`）。

客户端抽象名（`AnyListenGateway`）≠ HTTP 路径。下列路径来自该版本源码，不是猜测。

## 传输与前缀

- HTTP API 前缀：`/api`（`API_PREFIX`）。
- 反代可能再加一层前缀，必须实测后写入 `baseUrl`。
- TLS：系统校验。禁止明文（非 localhost）和 trust-all。
- 跨主机重定向默认不转发 Cookie / 认证头。媒体域名例外只能在现场确认后加入允许表。

## HTTP：发现与登录

| 方法 | 路径 | 含义 |
|---|---|---|
| GET | `/api/ipc/hello` | 正文 `Hello~::^-^::~v1~` |
| GET | `/api/ipc/id` | 正文 `OjppZDo6-` + serverId |
| POST | `/api/ipc/ah` | 登录或恢复会话 |
| GET | `/api/proxyUrlToken?m=<token>` | 设置媒体代理 Cookie |
| GET | `/api/p_static/:name` | 静态/媒体代理；**不**校验 `p_urlkey` |
| GET | `/api/p_url/:url` | URL 代理；生产环境校验 Cookie `p_urlkey` |

### POST `/api/ipc/ah`

密码登录：

- 头 `s`：随机盐，长度 < 4096。
- 头 `m`：`SHA-256(password + salt)` 的十六进制小写。
- 成功：HTTP 200，正文第一行 hello 常量，第二行 `encodeURIComponent(serverName)`，响应头 `token` 为 JWT。
- 失败：401 `Auth failed`；同一 IP 失败累计 ≥10 次后 403 `Blocked IP`。

会话恢复：只发头 `m=<token>`，无 `s`。服务端校验 JWT 且 clientId 仍在本地表中。

JWT 由服务端密钥签发，payload 为 `{ clientId, timestamp }`。源码未设置 `expiresIn`。失效以现场 401/校验失败为准。

退出：WebSocket 关闭码 `4001`，服务端删除该 client 会话。

## WebSocket

- 升级 URL：`/api/ipc/socket?m=<urlencode(token)>&t=main`（`t` 只能是 `main` 或 `desktopLyric`）。
- 握手失败：原始套接字写 `HTTP/1.1 401 Unauthorized`。
- 应用层当前 **不加密**（`encryptMsg`/`decryptMsg` 原样返回）。帧为 JSON 文本。
- 心跳：服务端空闲 >15s 发 `ping`；>45s `terminate`。客户端 46s 无消息则断开。
- 关闭码：`1000` 正常，`4001` 登出，`4100` 失败。

连接成功后客户端必须调用 IPC `inited()`，否则收不到列表变更广播。

现场：升级返回 `101`，`inited` 可调用，`getCurrentVersionInfo.version` 为 `0.11.0-beta.1`。

## message2call 帧

依赖 `message2call` v2 数组格式（MIT，lyswhut/message2call）：

```
REQUEST  = [0, eventName, pathname[], args[], callbackIndex[]]
RESPONSE = [1, eventName, null, result] | [1, eventName, {message, stack?}]
```

`eventName` 形如 `getAllUserLists_<id>`。
服务端 expose 对象是扁平方法表，`pathname` 为 `["getAllUserLists"]`、`["getMusicUrl"]` 等。
`list` / `player` 等 remote group 只做客户端排队，不进入 pathname。

本仓库在 `core:data` 重实现 REQUEST/RESPONSE，不复制上游源码。

## 客户端使用的 IPC

| 方法 | 参数 | 结果 | 用途 |
|---|---|---|---|
| `inited` | 无 | void | 标记套接字就绪 |
| `getCurrentVersionInfo` | 无 | `{ version, commit, ... }` | 显示服务端版本 |
| `getAllUserLists` | 无 | `{ defaultList, loveList, lastPlayList, userList[] }` | 库 |
| `getListMusics` | `listId` | `MusicInfo[]` | 列表曲目（全量，无游标） |
| `getMusicUrl` | `{ musicInfo, isRefresh?, quality? }` | `{ url, quality, isFromCache }` | 播放/下载 |
| `getMusicPic` | `{ musicInfo, isRefresh? }` | `{ url, isFromCache }` | 封面 |
| `getMusicLyric` | `{ musicInfo, isRefresh? }` | `{ info: { lyric, awlyric?, tlyric?, ... }, isFromCache }` | 基础 LRC |
| `getSetting` | 无 | 扁平 `AppSetting`（键如 `list.addMusicLocationType`） | 最近播放插入位置，默认 `top` |
| `listAction` | `{ action, data }` | void | 收藏/增删；最近播放另用 `list_music_update_position` |
| `checkListExistMusic` | `listId, musicId` | boolean | 写后确认 |

内置列表 ID：`default`、`love`、`last_played`。

### 最近播放（`last_played`）

Web 播放器在当前曲目变化时（`playerEvent` / `musicChanged`）由 **web-server** `updateLatestPlayList` 写入该列表，不是独立的 REST 接口。服务端逻辑（any-listen@e4ef53a `packages/web-server/src/app/modules/player/index.ts`）：

- 若当前队列来自 `last_played` 本身，则不写入（避免在最近播放里点播再把自己顶到最前）。
- 已存在：`list_music_update_position` 移到最新位置。
- 不存在：`list_music_add`，并在 `meta.createTime` 写入 `Date.now()`；超过 **1000** 首时 `list_music_remove` 丢掉最旧的一首。
- 最新位置由设置 `list.addMusicLocationType` 决定，**默认 `top`**（下标 0 为最新）。`createTime` 只在首次插入时设置，复播只改位置，因此 **列表顺序才是播放时间顺序**。

本客户端使用独立 Media3 队列，不调用 `playListAction` / `playerEvent`，以免覆盖 Web 正在使用的共享播放队列。写入路径与服务端相同：对 `last_played` 发上述 `listAction`。读取仍走 `getAllUserLists` + `getListMusics('last_played')`。界面默认按播放时间从新到旧；空列表有独立空态。用户不可从菜单手动增删该列表（`canMutateOnline = false`）。

### MusicInfo（字段名来自源码类型）

- `id`：列表内条目 ID（作 `remoteTrackId`）。
- `name` / `singer` / `interval`（如 `03:55`）/ `isLocal`。
- `meta.musicId`、`meta.albumName`、`meta.picUrl`、`meta.filePath`（本地）、`meta.ext`、`meta.sizeStr`。
- 现场还出现：`bitrateLabel`、`createTime`、`deviceId`、`discNo`、`posTime`、`trackNo`、`unparsed`、`updateTime`、`year`。忽略未知字段。

同会话内对同一列表连续两次 `getListMusics`，`id` 集合一致。路径改名后是否变 ID 仍未测；未证实前不按歌名合并。

### 媒体 URL（现场）

`getMusicUrl` 返回 `{ url, quality, isFromCache }`。现场本地曲目的 `url` 不是 `https://`，而是虚拟协议：

```
al-ps-host:/public/medias/<sha256(filePath)>.<ext>
```

来源：`createMediaPublicPath` → `buildVirtualPublicPath('/public/medias', name)`。
服务端在调用 `getMusicUrl` 时把 `name → 真实文件` 记入内存表，由 `GET /public/medias/:name` 流式输出（`sendFileStream`，支持 Range / 206）。**不在 `/api` 下，也不校验 `p_urlkey`。**

扩展/缓存代理仍可能返回：

```
al-ps-host:/api/p_static/<name>
al-ps-host:/api/p_url/<encoded-url>
```

Web 端用 `buildRealPublicPath(url, proxyServerHost)` 把 `al-ps-host:` 换成 `proxyHost`（生产值为 `.`）。

安卓只改写白名单前缀，拒绝 `//`、`..` 和多余路径段：

```
https://<base>/public/medias/<name>
https://<base>/api/p_static/<name>
https://<base>/api/p_url/<name>
```

`p_url` 才需要先 `GET /api/proxyUrlToken?m=<token>` 拿 Cookie `p_urlkey`。跨主机不转发该 Cookie。
内存映射在进程重启后失效，播放/下载遇 404 应重新 `getMusicUrl`。

### listAction（只用已标明的安全子集）

```
{ "action": "list_music_add", "data": { "id": "<listId>", "musicInfos": [MusicInfo], "addMusicLocationType": "bottom" } }
{ "action": "list_music_remove", "data": { "listId": "<listId>", "ids": ["<musicInfo.id>"] } }
{ "action": "list_music_update_position", "data": { "listId": "last_played", "position": 0, "ids": ["<musicInfo.id>"] } }
```

源码还存在 `list_music_overwrite` / `list_data_overwrite`。本客户端 **不调用**，避免覆盖其它设备修改。
最近播放写入使用与 web-server `updateLatestPlayList` 相同的 `list_music_add` / `list_music_update_position` / `list_music_remove`（仅针对 `last_played`）。
写操作：超时先 `getListMusics` 再决定是否重试；成功后再读一次确认。测试写只允许独立测试歌单。

## 错误分类

| 现象 | 分类 |
|---|---|
| 密码错 / 无 token | `AUTH_FAILED` |
| 401 WS 升级、会话 JWT 无效 | `SESSION_EXPIRED` |
| 403 Blocked IP | `RATE_LIMITED` |
| 证书失败 | `CERT_INVALID` |
| 无法连接 | `NETWORK_UNREACHABLE` |
| 曲目 404 / 空 URL | `TRACK_UNAVAILABLE` |
| 解码失败 | `FORMAT_UNSUPPORTED` |

重试有上限与退避。认证失败不自动风暴重试。

## 已知限制

- 无官方稳定第三方 REST 文档；适配层隔离协议。
- 现场 Range：`GET /public/medias/:name` 对 `bytes=0-1023` 回 `206` + `Accept-Ranges: bytes`（样本 `audio/mpeg`）。
- 反代：当前实例 hello 在 `/api/ipc/hello`，无额外路径前缀。
- 写并发与长期 ID（改文件路径）仍未测。
- 不删除服务器原文件。

## 服务端歌单变化推送

服务端在 `inited` 后通过 `remoteQueueList.listAction` 发送 REQUEST，路径为 `["listAction"]`，第一个参数为 `{ action, data }`。客户端收到后立即以同一事件名返回成功 RESPONSE，避免阻塞服务端的后续队列推送。

客户端将 `list_*` 通知视为音乐库失效信号，合并 250 ms 内的连续通知及受影响歌单 ID。每轮读取歌单元数据，只回读受影响或新增歌单的歌曲；未知事件、完整覆盖事件或无法解析的事件数据退回完整同步。同步期间的新通知保留到下一轮；失败以 1–30 秒退避重试，离线停止请求，重连后自动完整同步。旧连接关闭后不再接收其通知。该流程不强制刷新音频、封面或歌词缓存。

依据：上游 `packages/web-server/src/app/renderer/winMain/rendererEvent/list.ts` 和 `packages/shared/types/types/list_ipc.d.ts`。

推送初始化 `inited` 必须在 10 秒内成功应答，否则连接关闭并进入重连退避；无返回值的成功 RESPONSE 也算成功。回到前台时，距上次完整校对至少 60 秒才补查；持续连接期间每 5 分钟检查是否需要完整校对。离线不发起校对；局部同步不延后完整校对期限，避免长期漏通知。校对结果不变时不重写音乐库，避免无意义的 UI 更新。
