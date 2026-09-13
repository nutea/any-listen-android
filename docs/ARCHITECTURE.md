# 架构与数据规则

状态：设计提案；M0 结果可以修正，但必须记录原因。

## 技术栈

Kotlin + Compose；协程/Flow；Media3 ExoPlayer + MediaSessionService；Room；设置使用 DataStore；HTTP/WebSocket 使用支持实际认证的客户端库。WorkManager 用于适合延迟执行的元数据维护，不作为长时音频播放引擎。

最低版本暂定 Android 8/API 26，待目标手机确认；target/compile SDK、AGP、Kotlin、Media3、Room 在 M1 按官方兼容表锁定稳定版本，不使用动态依赖。包名暂定 `io.github.nutea.anylisten`，首次签名分发前冻结。

## 分层

UI/ViewModel → Repository → AnyListenGateway / Room / DownloadRepository。
PlaybackService 持有唯一 Player 和 MediaSession；Activity 生命周期不管理播放器生死。DownloadCoordinator 管理正式下载，PlaybackResolver 选择有效本地副本或远程资源。

建议目录先保持少量模块：app、core:model、core:data、core:playback；feature 包按 connect/library/player/download/settings 划分。无需为每个页面立即建立 Gradle 模块。

AnyListenGateway 抽象能力：认证、列表、曲目、媒体资源解析、收藏/歌单操作、事件订阅。方法名是客户端抽象，绝不代表已有服务器路由。UI 不依赖协议原始 DTO。

## 数据模型

| 实体 | 核心字段/规则 |
|---|---|
| ServerProfile | 本地 UUID、baseUrl、实测版本；凭据引用单独保存 |
| Track | serverProfileId + remoteTrackId 联合身份、标题/艺术家/专辑/时长、可选版本指纹 |
| Playlist | 服务器 ID、名称、可选 revision、刷新时间 |
| PlaylistEntry | playlistId、entryId或稳定位置标识、trackId、顺序；允许协议支持的重复曲目 |
| DownloadRecord | Track 身份、资源版本、状态、字节数、文件/cacheKey、错误、完整性信息 |
| PlaybackSnapshot | 本机队列、当前项、位置、模式；不伪装为跨端同步 |

不将短期签名 URL、Android 路径或歌曲标题用作主键。服务端 ID 稳定性未证实前不得实现自动模糊重绑定。

## 下载状态机

QUEUED → DOWNLOADING → VERIFYING → COMPLETED。
DOWNLOADING 可进入 PAUSED/FAILED/CANCELLED；重试回 QUEUED。只在完整性检查通过、文件与数据库状态一致后标记 COMPLETED。

有服务端摘要时比对摘要；没有摘要时核对可得长度并进行本地可读性/解码验证，记录“无服务端校验和”，不把自算摘要当作远端一致性证明。
断点续传校验资源版本/ETag/长度与 Content-Range；服务器忽略 Range 返回 200 时不可追加到旧片段。
临时播放缓存可淘汰；用户明确下载的副本不被普通缓存清理驱逐。保留统一缓存实例/锁，避免多实例争用。
已下载内容离线启动不依赖在线登录成功。远端删除时标记孤立副本，首版不自动删本机文件。

## 同步与错误

服务端歌单权威、本地库镜像；完成刷新后事务性提交，失败保留最后成功数据。下载曲目的最小元数据独立保留。
首版只联网写入。若服务端只有全量替换，必须评估并发丢失更新风险；不盲目采用最后写入覆盖。
对401、403、网络不可达、证书错误、404曲目失效、格式不支持分别提示。限制重试次数与退避，避免认证风暴。
WSS 仅在协议/使用场景需要时连接；后台播放不靠持续全库轮询维持。

## 凭据与传输

使用系统 TLS 校验；禁用明文与 trust-all。私密会话以 Android Keystore 支持的保护方案存储，具体实现遵循选定 SDK 文档。
跨主机重定向默认不转发认证头/Cookie；媒体域名例外在 M0 明确，不把服务器凭据发给第三方。
数据库、备份、日志和诊断中不保存可复用令牌/签名 URL。日志需脱敏；备份规则排除会话和下载音频。
媒体控制器执行最小访问策略。首版使用应用私有存储，不请求全盘文件管理权限。
