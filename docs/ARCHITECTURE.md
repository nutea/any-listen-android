# 架构与数据规则

## 技术栈

Kotlin + Compose；协程 / Flow；Media3 ExoPlayer + MediaSessionService；Room；设置使用 DataStore；HTTP / WebSocket 使用 OkHttp。WorkManager 只适合延迟元数据维护，不作为长时音频播放引擎。

- minSdk 26，compile / target SDK 36
- 包名 `io.github.nutea.anylisten`（debug 后缀 `.debug`）
- 模块：`:app`、`:core:model`、`:core:data`、`:core:playback`

## 分层

UI / ViewModel → Repository → `AnyListenGateway` / Room / 下载协调器。

`PlaybackService` 持有唯一 Player 和 MediaSession；Activity 不管理播放器生死。`DownloadCoordinator` 管理正式下载，`PlaybackResolver` 选择有效本地副本或远程资源。首次播放通过 `PendingPlayback` 交给服务。系统媒体卡片上的收藏与播放模式是 MediaSession 自定义按钮，不把歌词写进锁屏元数据。

`AnyListenGateway` 抽象：认证、列表、曲目、媒体解析、收藏 / 歌单、事件。方法名是客户端能力，不是服务器路由。UI 不依赖协议原始 DTO。

## 数据模型

| 实体 | 规则 |
|---|---|
| ServerProfile | 本地 id、baseUrl、实测版本；凭据单独加密保存 |
| Track | `serverProfileId` + `remoteTrackId` 联合身份；标题 / 艺术家 / 专辑 / 时长；可选指纹 |
| Playlist | 服务器 ID、名称、刷新时间；`last_played` 不可在线改 |
| PlaylistEntry | 列表、曲目、顺序 |
| DownloadRecord | 身份、状态、字节、路径、错误、完整性 |
| PlaybackSnapshot | 本机队列、当前项、位置、播放模式 |

不用短期 URL、本机路径或歌名当主键。服务端 ID 变化后旧下载标为孤立，不按歌名自动合并。

## 下载状态机

`QUEUED` → `DOWNLOADING` → `VERIFYING` → `COMPLETED`。

下载中可进入暂停 / 失败 / 取消；重试回排队。只有校验通过且文件与库一致才标完成。

有服务端摘要则比对；否则核对长度并做本地可读检查，记录无远端校验和。206 才续传；服务器忽略 Range 返回 200 时整段重下，不拼接旧片段。

`downloads/` 是用户下载；Media3 `SimpleCache` 是播放缓存，驱逐不得删正式副本。已下载内容离线启动不依赖重新登录成功。

## 同步与错误

服务端歌单权威，本地库是镜像；刷新成功后事务提交，失败保留上次成功数据。只联网写入歌单。写入后回读确认，失败不伪报成功。离线禁止改服务端列表。

对认证失败、会话失效、证书错误、网络不可达、曲目不可用、磁盘不足分别提示。限制重试，避免认证风暴。后台播放不靠全库轮询维持。

## 凭据与传输

系统 TLS 校验；禁止明文（非 localhost）和 trust-all。会话用 EncryptedSharedPreferences。跨主机重定向不转发认证 Cookie。日志、备份、诊断不写可复用令牌或完整媒体 URL。媒体文件在应用私有存储，不申请全盘管理权限。
