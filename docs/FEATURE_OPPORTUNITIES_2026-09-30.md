# 客户端下一步功能建议

调研日期：2026-09-30。客户端基线：v0.1.4-beta.3，提交 bf7f96b。服务端兼容基线：Web Server v0.11.0-beta.1，固定提交 e4ef53a5094473687e2d142fb7b63a530436b982。本文核对双方源码及成熟产品官方说明，没有重新连接部署实例。功能可用性仍受实例版本、配置及扩展资源影响。

本次查询到上游最新公开版本为 [v0.11.0-beta.10](https://github.com/any-listen/any-listen-web-server/releases/tag/v0.11.0-beta.10)，发布于 2026-09-30 17:53（上海），源码 f16b56492ea0db9d41f94c7b651a1f9bd6ae2dfe。此信息不代表用户部署实例已经升级，也不构成对新版所有协议的兼容验收。

建议下一批先做定时停止、队列拖动排序、音频信息和桌面小组件；随后加入歌词修复、均衡器与歌单内曲目管理。这一排序是依据个人自建曲库场景、使用频率、实现复杂度和维护成本作出的判断，不是用户行为统计或排期承诺。

## 当前已经覆盖的能力

已有收藏、最近播放、普通歌单管理、跨歌单组合关键词搜索、列表浏览排序、定位当前曲、稍后播放及队列删除、后台播放、通知、播放恢复、下载及缓存、双语/罗马音/逐字歌词与偏移、歌曲评论、歌手与专辑页，以及五种播放页风格。详见 [使用说明](USER_GUIDE.md)。

需要区分：歌手与专辑页聚合当前曲库；搜索只查已同步歌曲；最近播放是一份去重排序列表；手机播放队列独立于 Web 共享播放状态。

## 推荐排序

成本为相对估计，包含交互、持久化及回归验证。

| 优先级 | 建议功能 | 具体体验与价值 | 支持程度与成本 |
|---|---|---|---|
| 第一批 | 定时停止 | 15/30/60 分钟、自定义、当前曲结束后停止；显示倒计时，支持取消。适合睡前和工作场景。 | 手机 PlaybackService 实现，不依赖服务器。低至中。 |
| 第一批 | 队列拖动排序 | 调整待播及稍后播放顺序，支持清空待播、保存为歌单；保留当前曲进度。 | 当前只有选曲/删除；Media3 本地队列基础已具备，需处理随机及单曲循环。中。 |
| 第一批 | 音频信息面板 | 展示当前文件格式、实际可取得的码率/采样率/声道、来源与离线状态。让自建无损曲库信息可见。 | 已有部分元数据与 quality 返回值；补充解码 Format，并保留缓存元数据。低至中。 |
| 第一批 | Android 桌面小组件 | 封面、歌名、播放/暂停、切歌、收藏，快速进入当前曲或常用歌单。 | 复用 MediaSession 与已保存封面，不依赖服务器新增 API。中。 |
| 第二批 | 歌词手动匹配与修复 | 缺失或错配时搜索候选、预览并应用；可导入 LRC。明确“仅本机”与“保存到服务器”的作用范围。 | beta.1 已有歌词资源检索和服务端编辑歌词 IPC；候选依赖扩展。中。 |
| 第二批 | 均衡器与倍速 | EQ 预设、自定义频段和一键还原；倍速提供保调。提高耳机适配和练歌体验。 | Web 已有 EQ/倍速；Android 在播放音频会话实现，需设备差异处理。中。 |
| 第二批 | 歌单内曲目管理 | 歌曲持久排序、批量移至其他歌单、复制歌单、重复项提示。补齐已有“歌单本身管理”。 | listAction 已有曲目位置及移动协议；普通可编辑歌单开放，避免覆盖整个库。中。 |
| 第二批 | 搜索与智能曲库 | 搜索历史、拼音/首字母、已下载过滤；再做常听、久未听、随机探索和本地听歌统计。 | 当前仅标题/歌手/专辑文本匹配。新统计需客户端记录时间、次数和时长，不能从旧最近播放伪造。中。 |
| 条件推进 | 在线搜索与资源歌单 | 在线歌曲/歌单/专辑/歌手搜索，榜单浏览、收藏至自己的歌单；能解析时提供在线歌单链接导入。 | Web 存在资源能力，但必须按 getResourceList 和扩展声明逐类开放。中至高。 |
| 后续 | 下载与缓存策略 | 可选仅 Wi-Fi 下载、缓存容量上限、自动清理及常用歌单离线保持。 | 当前不限制网络类型；属于新增用户选项。自动清理保护显式下载，歌单同步处理删除语义。中。 |
| 后续 | Android Auto | 车机浏览喜欢/歌单/已下载，语音检索、播放控制，断网降级。 | 现有通知会话不等于车机支持；需 MediaLibraryService、浏览树和车机验证。中至高。 |

第一批中的“清空待播、保存为歌单”可在队列拖动之后再补，避免把一个 beta 的范围扩得过大。音频信息先做如实展示，音质切换单独按资源能力设计。

## 服务端可复用能力与边界

- 歌词检索：`lyricSearch`、`lyricDetail` 已在资源 IPC 暴露。来源：[resource.ts](https://github.com/any-listen/any-listen/blob/e4ef53a5094473687e2d142fb7b63a530436b982/packages/web-server/src/app/renderer/winMain/rendererEvent/resource.ts#L37)。
- 编辑歌词：`setMusicLyric`、`removeMusicLyric` 已暴露，可保存或清除服务端编辑结果。来源：[music.ts](https://github.com/any-listen/any-listen/blob/e4ef53a5094473687e2d142fb7b63a530436b982/packages/web-server/src/app/renderer/winMain/rendererEvent/music.ts#L27)。保存前应展示应用目标，客户端原有每曲偏移仍是本机设置。
- 曲目持久排序：`list_music_update_position` 接受 `listId/position/ids`；曲目移动也有协议基础。来源：[list_ipc.d.ts](https://github.com/any-listen/any-listen/blob/e4ef53a5094473687e2d142fb7b63a530436b982/packages/shared/types/types/list_ipc.d.ts#L79)。它修改服务端歌单，和手机临时队列排序是不同动作。
- 在线搜索：Web 按歌曲、歌单、专辑、歌手分类并使用扩展资源能力。来源：[Search.svelte](https://github.com/any-listen/any-listen/blob/e4ef53a5094473687e2d142fb7b63a530436b982/packages/view-main/src/views/Online/Search/Search.svelte#L72)。扩展安装不代表每类能力都可用；备份文件导入也不等于网易云/QQ 分享链接导入。
- 音效和倍速：Web 有相应实现，音效在实际播放设备执行。来源：[音效 UI](https://github.com/any-listen/any-listen/blob/e4ef53a5094473687e2d142fb7b63a530436b982/packages/view-main/src/components/common/SoundEffectBtn/index.svelte#L28)、[倍速 UI](https://github.com/any-listen/any-listen/blob/e4ef53a5094473687e2d142fb7b63a530436b982/packages/view-main/src/components/common/PlaybackRateBtn.svelte#L26)。手机不应通过修改 Web 共享设置来控制本机 EQ。
- 定时停止：源码有 `player.waitPlayEndStop` 等设置字段，但未发现完整计时执行链及专用 IPC，不能宣称已有可直接调用的服务端睡眠计时。应作为 Android 新能力实现。
- 音质：`getMusicUrl` 有 quality 入参/返回值，Android 当前请求未传质量选择。服务端本地音乐直接返回原文件，而且该版本本地解析固定返回 `quality: '128k'`，不能把这个标签当作文件真实码率。音频面板应优先展示播放器实际 Format；无可用码率时省略。quality 参数不构成本地文件转码能力。`isRefresh=true` 对本地曲目还会跳过原文件解析并尝试线上匹配，不宜包装成通用的安全“原文件刷新”。来源：[本地解析](https://github.com/any-listen/any-listen/blob/e4ef53a5094473687e2d142fb7b63a530436b982/packages/web-server/src/app/modules/music/local.ts#L12)、[在线解析](https://github.com/any-listen/any-listen/blob/e4ef53a5094473687e2d142fb7b63a530436b982/packages/web-server/src/app/modules/music/online.ts#L46)、[客户端解析](../core/data/src/main/kotlin/io/github/nutea/anylisten/core/data/gateway/ProtocolAnyListenGateway.kt)。
- 元信息编辑：需要先区分应用数据库覆盖和真正改写媒体标签，避免将二者混为一谈；可放在歌词修复之后。
- 跨设备：已有共享播放状态及广播，但手机当前使用独立 Media3 队列。设备选择、接力和单设备遥控需要定义状态所有权；不能由现有广播直接推断完整接力能力。参考 [此前源码调研](WEB_FEATURE_RESEARCH_2026-09-21.md)。

## 成熟音乐软件对照

网易云官方产品说明侧重个性推荐、场景歌单、评论和一起听；QQ 音乐说明包含专业音效、多类型搜索、歌词海报和车载互联。这些说明证明产品提供相应能力，但不说明 Android 的具体实现，也不证明当前 Any Listen 部署能提供同等资源。[网易云开发者产品页](https://apps.apple.com/cn/app/id590338362)、[QQ 音乐开发者产品页](https://apps.apple.com/cn/app/id414603431)。

更适合当前客户端借鉴的是高频控制和个人曲库体验：Spotify 支持队列拖动、清空、EQ、音量标准化和 Android 桌面小组件；Apple Music 支持可调时长的交叉渐入渐出。[Spotify 队列](https://support.spotify.com/us/article/play-queue/)、[EQ](https://support.spotify.com/us/article/equalizer/)、[音量标准化](https://support.spotify.com/us/article/volume-normalization/)、[桌面小组件](https://support.spotify.com/us/article/spotify-android-widget/)、[Apple 歌曲过渡](https://support.apple.com/zh-cn/105067)。

音量标准化值得后续加入，但需要整曲响度或可信 ReplayGain 等元数据。现有音波 RMS/峰值只是短缓冲区采样，不能直接作为整曲响度算法。先验证专辑连续播放的衔接表现，再考虑交叉渐入渐出；它需要可靠的双曲混音、解码及会话管理，不应当作简单动画开关。

## 适合暂缓的方向

社交动态、直播、K 歌社区、大型推荐流依赖账号、内容和推荐基础设施，维护负担大。听歌识曲还需要额外识别资源。目前更适合从自己的曲库做轻量随机探索和智能筛选。

更多播放页皮肤可继续迭代，但下一批功能价值判断更偏向定时停止、队列操作、小组件及歌词修复。后续若高频使用车机，则 Android Auto 可提前；若主要在线找歌，则在线资源接入可提前。

## 第二批实现进展（2026-10-01）

第二批四组功能已接入客户端：歌词候选与 LRC 导入／本机及服务器保存、音频会话均衡器与保音高倍速、普通歌单排序／批量移动／复制／重复提示，以及搜索历史／拼音首字母／下载筛选／智能曲库和新记录的本机听歌统计。操作入口及限制见 `USER_GUIDE.md`；本次继续使用 beta1 协议，没有升级或写入 Web 端的播放设置。服务端写入在本地协议夹具验证，未修改真实服务器的测试歌单或歌词。
