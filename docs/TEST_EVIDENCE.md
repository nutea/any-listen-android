# 测试记录

未测项保持「未验证」，不写「通过」。

## A 组界面（01–08，2026-09-13）

- `python tools/device/emulator.py test`：8 项 instrumentation 通过（约 19–21 秒）。
- 模拟器 `AnyListen_API_36`：最近播放首屏可见 8 首完整歌曲行（封面、歌名、歌手、收藏/离线标记）；迷你条含进度与队列入口。
- 全屏播放：封面与歌词并列，主控固定在下方；封面取色为浅色背景；歌词当前句高亮，初次打开不出现「回到当前句」。
- 组件样例截图在模拟器 `files/ui-review/`（虚构歌曲）。现场启动截图在 `captures/private/emulator-*`。未测大字体、返回滚动位置的自动化断言。

## 设置页缓存统计（2026-09-13）

- 修复只在初始化/少数操作后更新统计的问题：设置页可见时立即刷新，并每 2 秒重新统计；后台停止轮询。
- 播放缓存计入完整音频、未完成音频和 ExoPlayer 缓冲目录；永久封面与歌词独立显示，并说明清理播放缓存时保留。
- 26 项数据单测通过，补充统计随缓冲文件增长更新的断言；Debug 构建和 Lint 通过。
- 模拟器设置页实测显示播放缓存 12.1 MB、封面与歌词 135 KB，截图 `captures/private/storage-settings-fixed.png`。手机实际缓存目录非空，修复版已覆盖安装；手机禁用了 ADB 输入注入，因此界面显示检查在模拟器完成。

## 播放缓存及下载附带资源（2026-09-13）

- 播放当前曲目时在后台补齐完整音频，同时保存歌词和封面；文件按歌曲身份及指纹哈希写入 `files/offline/`。只使用已完整提交的音频，`.part` 不参与离线解析；完整下载优先，其次完整播放缓存，最后才请求远端。
- 歌词以带时间戳的 LRC 持久保存，冷启动优先读取。播放页显示“已缓存，可离线播放”。缓存未完成就断网不能保证整首离线可用。
- 显式下载在完成状态前保存可获取的封面和歌词；附带资源失败保留已下载音频并提示再次下载补齐。已有完成下载再次入队仅补齐资源；服务端没有的封面/歌词不伪造。
- `:core:data:testDebugUnitTest` 26 项通过，新增 3 项覆盖完整音频/歌词跨实例离线读取、下载同时保存封面歌词、截断音频不能作为离线文件。8 项 instrumentation 回归通过（17.882 秒），Debug 构建与 Lint 通过。
- 实际模拟器：播放后生成 4,339,905 字节完整音频、1,684 字节 LRC 和封面引用；关闭全部网络并 force-stop 后从 62,928 ms 离线播放，歌词页面正常显示，见 `captures/private/offline-audio-session.txt`、`offline-audio-lyrics.png`。
- 实际手动下载：音频 6,461,440 字节，状态 COMPLETED、error=null，同时新增对应 `.cover`、`.lrc`。证据数据库 `captures/private/download-assets-db/`。
- 已覆盖安装并启动于 Xiaomi 14。清理播放缓存会移除自动保存的音频，保留明确下载的歌曲、歌词和永久封面；Release APK 尚未更新。

## 官方应用图标（2026-09-13）

- 使用 Any Listen 官方源码 `packages/desktop/resources/icons/512x512.png` 的原始文件，蓝底白色标志；适配 Android 圆形及其他自适应图标遮罩。来源和上游许可见 `third_party/any-listen/`。
- Debug 构建和 Lint 通过；模拟器桌面实际图标已检查，截图 `captures/private/official-launcher.png`。手机调试恢复连接后，已覆盖安装并启动于 Xiaomi 14；Release APK 尚未更新。

## 封面按 URL 永久保存（2026-09-13）

- 新增 ArtworkStore：按完整规范化 URL 的 SHA-256 保存至应用 `files/artwork/`，没有 TTL、HTTP 过期检查或容量淘汰。相同地址优先读取本地文件，地址（含查询参数）变化生成新文件。列表、播放器及播放通知共用文件。
- 首次显示/播放时保存；同地址并发请求合并，下载后验证图片再原子落盘，失败响应不入库。系统“清除缓存”和应用音频缓存清理不会删除该目录；卸载或清除应用数据会删除。
- `:core:data:testDebugUnitTest` 23 项通过，其中 ArtworkStoreTest 3 项使用原生图片解码：跨实例/极旧时间戳/断网读取不重验（包括 no-store 响应）、并发去重及 URL 变化重新下载、无效响应可重试且不残留文件。
- 模拟器先在线保存 4 个封面，然后关闭 Wi-Fi/数据并 force-stop，冷启动仍显示已保存封面，见 `captures/private/artwork-online.png`、`artwork-offline-restart.png`。测试后网络已恢复。
- 8 项 instrumentation 回归通过（21.885 秒）；Debug 构建和 Lint 通过。已覆盖安装 Xiaomi 14，确认 `files/artwork/` 实际生成 19 个文件。Release APK 尚未更新。

## 断网重连恢复播放（2026-09-13）

- 网络恢复后由播放服务重建会话、清理内存音频 URL 缓存并重新 prepare 错误/空闲的播放时间线；保留当前位置和 playWhenReady。播放按钮在错误状态下也可发起重连。
- 合并并发会话恢复请求，防止重复登录/重建连接；旧 WebSocket 的关闭回调不再影响新连接的在线标记。失败后以退避间隔重试。
- `:core:data:testDebugUnitTest` 20 项通过，含并发重连与断网失败后恢复测试；Debug 构建及 Lint 通过。
- 模拟器关闭 Wi-Fi 与移动数据，选择未缓存歌曲：首次 BUFFERING 后恢复网络自动 PLAYING；再次断网 32 秒并切歌，确认 ERROR，恢复网络后自动 PLAYING（11,994 ms）。全过程进程 PID 均为 8069，无需重启或手动重新播放。测试后恢复网络并暂停。
- 私人证据：`captures/private/reconnect-offline.txt`、`reconnect-online.txt`、`reconnect-offline-long.txt`、`reconnect-online-long.txt`。未进行手机断网测试；Release APK 尚未更新。

## 重启保留播放进度（2026-09-13）

- 修复空 MediaController 在启动轮询时将已恢复进度覆盖成 0 的问题；恢复同时保留歌曲时长，未装载播放器时拖动进度也可保存。
- PlaybackService 成为活动队列的进度保存来源：每秒及播放器事件发生时写入 DataStore，正常销毁时补存。界面关闭后的后台播放不再依赖 ViewModel 写入；系统强杀可能损失最后约一秒或尚未完成写入的进度。
- 真实 ExoPlayer instrumentation 回归通过（1 项）：保存歌曲、队列、25 秒位置，空播放器不生成覆盖快照，重新装载后快照一致。Debug 构建与 Lint 通过。
- 模拟器实际端到端：播放保存 34,474 ms → force-stop → 启动等待 5 秒，DataStore 全部播放字段保持一致 → 点击播放，MediaSession 从 34,474 ms 续播。证据 `captures/private/resume-after-restart.png`、`resume-media-session.txt`。
- 修复版 Debug APK 已覆盖安装并启动于 Xiaomi 14。冷启动恢复为暂停，点击播放续播；本次未更新 Release APK。

## 封面显示修复（2026-09-13）

- 对比手机 Room 数据与服务器 `getListMusics`：271 首歌曲的原始 `meta.picUrl` 全部一致，确认列表同步成功。手机缓存中 87 个封面为 HTTP；应用禁止明文请求，导致图片显示占位图。
- 新增封面专用 HTTPS 地址规范化，在显示旧缓存和刷新保存时都应用；音频地址解析与网络安全策略保持不变。酷狗、酷我三个涉及时的图片域名抽样 HTTPS 请求均返回 200 image/jpeg。
- 刷新音乐库时同步当前曲目及队列的封面字段，不改变播放位置或队列顺序。
- `:core:data:testDebugUnitTest` 19 项通过，含新增封面 URL 回归；`:app:assembleDebug`、`:app:lintDebug` 通过。
- 已覆盖安装到 Xiaomi 14，真机截图确认歌曲列表及迷你播放器恢复真实封面。截图中“黑色毛衣”“龙卷风”仍为占位图，对照服务器返回字段确实为空。
- 私人证据：`captures/private/cover-phone.png`（修复前）、`cover-phone-after.png`（修复后）、`cover-remote.json`、`cover-db/`。本次更新 Debug APK；此前 Release APK 尚未包含此修复。

## 音乐体验改版 0.2.0-dev（2026-09-13）

- 功能来源及边界见 `docs/UI_REFRESH.md`；对照 Web `webserver-v0.11.0-beta.1`。下载保留已有 Android 实现，本轮只调整呈现与操作反馈。
- API 36 专用模拟器：8 项 instrumentation 测试通过，0 失败（18.862 秒）。包含 2 项连接页、5 项音乐界面交互、1 项真实 ExoPlayer 队列删除测试；界面测试使用虚构歌曲，不写入私人服务器歌单。
- `:core:model:test` 14 项、`:core:data:testDebugUnitTest` 18 项通过；共 32 项单元测试通过。
- `:app:lintDebug`、签名 Release 构建及 APK 签名验证通过。连接页状态栏间距已修复，并在真实 MainActivity 截图检查。
- 现场端到端检查：使用本地已有配置登录服务器、读取中文音乐库，从“我喜欢”播放；MediaSession 为 PLAYING，进度推进到 14,577 ms；暂停后为 PAUSED、15,562 ms、speed=0。未测试完整下载、锁屏或蓝牙流程，历史真机未验证项仍保留。
- 自动测试证据：`captures/private/emulator-20260913-175251-210217/`；现场播放/暂停证据：`captures/private/emulator-20260913-180505-229090/`；虚构内容界面预览：`captures/private/ui-review/ui-review/`。
- 修复 `gradlew.bat` 丢失 Gradle 失败退出码的问题，已用不存在的任务验证返回非零。此前 `emulator-20260913-174810-530784` 使用旧 APK 的记录已标为失败，不计入本次通过结果。
- APK：`app/build/outputs/apk/release/app-release.apk`，versionCode=2，versionName=0.2.0-dev；SHA-256：`13E68467EE0AADE4A3439C02E5B2D02B66E348C9D0333B896F58FC2A13E1217D`。

## 模拟器自动化环境（2026-09-13）

- Windows WHPX；Pixel 7 配置；Android 16 / API 36 Google APIs x86_64 r07；AVD `AnyListen_API_36`，序列号 `emulator-5580`。
- 用户提供的完整系统镜像 SHA-1 为 `c6bf44bdcd885bb902b4ba752d111a073ad7a817`，与 Google 仓库元数据一致。
- `python tools/device/emulator.py setup` 创建完成；`python tools/device/emulator.py test` 完成启动、构建、安装、测试、真实 MainActivity 启动及截图/日志采集。
- `ConnectScreenTest`：2 项通过，0 失败；测试运行耗时 12.387 秒。覆盖凭据输入、提交按钮状态与回调、忙碌时禁止重复请求、错误文案展示，不连接私人服务器。
- 真实应用启动后进程存活，截图显示连接页；采集日志未发现 `FATAL EXCEPTION` 或本应用 ANR。截图发现连接页标题与状态栏区域间距不足，尚未修复。
- 本地证据：`captures/private/emulator-20260913-172338-203176/`。登录、播放、下载等完整端到端流程尚未纳入本次自动测试；以下真机记录仍独立保留。

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


## 2026-09-13 播放页图标与下载状态

- 全屏与迷你播放器移除队列数量角标，队列弹层保留数量信息。
- 播放页离线可用提示改为歌手/专辑旁的 OfflinePin 图标，保留读屏描述。
- 播放页、歌单、搜索的单曲下载入口使用 Download / DownloadDone 两态；完成记录且本地文件存在时禁用点击。
- 完整缓存与正式下载分开：仅缓存仍能下载。本地文件不存在的下载记录不会显示为已下载。
- 批量下载排除已下载歌曲；全部已下载时禁用入口。
- 构建与 lint 通过。全套模拟器测试 20 项通过，新增用例验证缓存可下载、下载完成禁用以及恢复未下载状态。
- 全套记录：`captures/private/emulator-20260913-213808-435270/instrumentation.txt`。
- 已检查截图：`captures/private/player-downloaded-icon.png`。


## 2026-09-13 本地页紧凑清单

- 已下载与已缓存移除歌曲卡片，统一为歌单样式：64dp 最小行高、46dp 封面、双行文字、右侧状态图标和更多菜单。
- 点击整行播放，文件大小、资源完整性、错误说明和删除操作移入更多菜单，删除保留确认。
- 顶部存储卡片收紧为摘要；三分类使用吸顶标签；任务行保留紧凑状态、进度与重试/取消图标。
- 构建与 lint 通过；MusicExperienceTest 共 12 项通过。覆盖分类切换、详情完整性、删除取消、任务重试、64dp 行高和整行播放。测试设备首屏可展示 8 首测试歌曲。
- 已检查截图：`captures/private/local-dense-list.png`、`captures/private/local-details.png`。


## 2026-09-13 小情歌重试与任务移除

- 手机下载记录确认：小情歌为 FAILED，错误 source not found，下载字节为 0。失败记录的 serverProfileId 为 session，remoteTrackId 误保存完整 cacheKey；音乐库中存在原始歌曲及本地来源元数据。
- 播放器发布状态时优先取服务内原始 Track，其次查音乐库；下载和重试遇到历史 session 包装身份时按精确 key 恢复完整 Track。重试会迁移到正常任务身份并移除旧失败任务。
- 失败、暂停和已取消任务提供独立「移除任务」图标。移除停止关联任务，清理安全文件名对应的 .part 与未完成文件，并删除记录。已完成下载不受该入口影响。
- DownloadRecoveryTest 2 项通过：恢复历史身份和本地元数据；移除失败任务清理半成品且保留其他下载。
- MusicExperienceTest 12 项通过，包含失败任务重试及移除回调。构建与 lint 通过。
- 新版已覆盖安装到连接的小米手机，保留应用数据。手机系统拒绝 ADB 输入注入，用户操作重试后再次读取手机记录：小情歌已 COMPLETED，错误为空，实际文件 4,392,660 字节，旧 session 失败任务已移除。
- 诊断数据仅保存于 ignored 路径 captures/private/download-diagnosis。


## 2026-09-13 队列就地打开

队列内容抽为共用组件，迷你播放器通过根页面弹层打开，不再导航到 player。音乐库、本地、设置逐页验证队列打开与返回，关闭前后页面文字集合一致；设置背景截图已检查。原播放器的队列移除与稍后播放 2 项回归通过，构建与 lint 通过。已更新手机调试版。
证据：`captures/private/queue-in-place/` 中各页 before/open/closed XML 和 `settings-queue.png`。


## 2026-09-13 自动缓存歌曲开关

- 设置存储分组新增「自动缓存歌曲」，默认 true，DataStore 持久化。关闭保留已有文件和手动下载。
- OfflineAssets 将自动音频任务与封面/歌词任务拆开。关闭取消并等待音频任务停止，不取消封面与歌词缓存；开启后服务可重新缓存当前歌曲。
- 新建播放数据源按设置选择可写缓存或只读缓存；关闭不打断正在播放的已打开数据源，后续数据源不写入磁盘缓存。
- OfflineAssetsTest 7 项通过，覆盖设置默认值/持久化、关闭时仍缓存侧边资源、停止进行中的音频缓存、重新启用、保留已有缓存及关闭时仍能下载完整音频/封面/歌词。
- 设置开关 UI 回归 1 项通过，已检查截图 `captures/private/settings-auto-cache.png`。构建与 lint 通过，已更新到手机。


## 2026-09-13 移除仅 Wi-Fi 下载

移除设置入口、状态流、DataStore 偏好读取与下载协调器网络类型限制；原有偏好不再生效。默认网络具备联网能力时可恢复暂停任务，本地页恢复任务也不再受 Wi-Fi 限制。手动下载支持移动网络，自动音频缓存开关独立保留。
DownloadRecoveryTest 与 OfflineAssetsTest 共 9 项通过，设置页回归 1 项通过。构建、lint 通过，已更新手机。


## 2026-09-13：本地多选与主题设置

- 本地三标签加入多选入口、长按选择、全选/取消全选、返回退出；跨标签清空选择。下载和缓存支持批量播放/稍后播放/删除；任务按状态筛选批量重试/取消/移除，破坏性操作确认后执行。
- 设置加入浅色、深色、跟随系统；DataStore 持久化，根主题、播放页封面背景与系统栏同步。旧 Wi-Fi 下载限制已移除。
- assembleDebug、assembleDebugAndroidTest、lintDebug 成功；core:model 25 项、core:data 37 项单元测试全部通过，包含主题偏好持久化与下载恢复。
- API 36 模拟器 LocalSelectionThemeTest 3 项 + MusicExperienceTest 的本地删除/设置回归 2 项通过（OK 5 tests）。覆盖三个标签多选、状态筛选、取消确认、全选切换、返回退出、主题即时切换及跟随系统。
- 真实 MainActivity 点击深色、force-stop 后冷启动，截图确认深色选择和全应用配色保留，系统栏图标同步。
- 截图存放 captures/private/local-task-selection.png、settings-theme-dark.png、theme-restart.png（本机私有，不提交）。测试容器加入 safeDrawingPadding，避免测试 Activity 状态栏覆盖截图。
- 手机 23127PN0CC（ADB transport 9）覆盖安装 Success，MainActivity 冷启动 Status ok；保留原有数据。


## 2026-09-13：中文文件名导致下载串歌与歌词恢复

- 根因：旧版 DownloadCoordinator 将非 ASCII 字符替换为下划线，完整歌曲键不同却产生同名文件。真机初始 55 条完成记录中，34 条共享 5 个文件；串歌出现在本地下载解析路径。服务端同一歌曲对象的音频与歌词接口正常返回。
- 修复：下载文件使用完整 cacheKey 的 SHA-256；观察记录、播放、下载及删除前执行串行迁移，共用旧路径的记录停止标记离线完成，转为等待重下；可验证大小且路径唯一的旧下载复制迁移。已有正确播放缓存保留。
- 修复：恢复下载需等待网关连接，断网中的任务保持暂停以便恢复；歌词缺失缓存联网后重新查询，失败/空歌词在当前歌曲上可间隔重试，恢复队列的歌词请求从本地元数据表补全协议字段。
- core:model 25 项、core:data 40 项测试通过；包含等长中文歌名下载不同内容、独立删除、旧共享文件失效、唯一旧文件迁移、缺失歌词后来可用及离线复用。assembleDebug、assembleDebugAndroidTest、lintDebug 通过。
- 真机覆盖安装成功。维护期间本机下载集合发生变化，最终按当时仍保留的 12 条记录验收，没有恢复已移除的条目。DeviceRecoveryAcceptanceTest（显式 repair_existing_downloads=true）通过：12 个独立完成文件；抽样 3 首对比其服务端资源前 65536 字节一致，并成功获取、保存 LRC。此为字节抽样验证，不声称全曲人工听检。
- 真机初次验收因固定歌名样本已不在当前下载集合而失败；调整为当前保留记录后重验通过。该维护测试默认跳过，只在明确指定参数时连接设备保存的服务并重试现存未完成任务。
- 原始设备数据库、歌曲与接口数据只保存在 captures/private/jay-debug/，不加入公开截图或测试夹具。


## 2026-09-13：联网缓存校验与离线回退

- 封面、歌词、自动音频缓存及已完成下载增加联网检查，正常检查间隔 15 分钟；音乐库手动刷新强制检查本机保留的资源。音频地址重新向网关解析，图片/音频支持 ETag、Last-Modified 和无验证标记时的内容比对，临时文件验证完整后替换。
- 原文件在网络失败、服务端错误、无效图片/响应或中途断开时保留；已完成下载在刷新期间仍可播放。删除与刷新协调，防止更新写回已经移除的音频。封面通知触发 Coil 重新加载，歌词更新通知当前播放页；音频版本进入播放器缓存键。
- `:core:data:testDebugUnitTest` 47 项、`:core:model:test` 25 项通过；`:app:assembleDebug`、`:app:lintDebug`、`:app:assembleDebugAndroidTest` 通过。覆盖条件请求/304、同地址不同内容、相同内容不改文件、15 分钟节流、刷新失败回退、缓存删除、封面显示版本通知、歌词离线/失败回退、自动缓存及已下载文件更新。
- 手机 23127PN0CC（ADB transport 9）覆盖安装应用和测试 APK 成功。`CacheRefreshAcceptanceTest` 1 项通过，在 Android 原生文件系统及图片解码器上验证封面颜色、音频字节和歌词版本更新、封面地址变更、304 保留文件时间、503 回退及离线零 HTTP 请求。
- 专项设备测试使用独立临时目录和 HTTP 拦截器虚拟响应，不修改真实服务端歌曲，不改动用户下载文件；不将其表述为真实服务端更新后的人工听检。测试后 MainActivity 冷启动 Status ok。


## 2026-09-13：加入歌单弹层与随机队列顺序

- 各页面统一使用根界面的加入歌单底部弹层，包含 56 dp 封面、歌单名称、歌曲数及待加入数量；优先显示歌单封面，缺失时使用第一首歌曲的封面和默认占位。支持长名称、列表滚动、空目标提示、返回/关闭；选择后立即关闭并防止重复提交。
- 随机队列读取实际 ExoPlayer Timeline 的 shuffle traversal，从当前歌曲开始遍历一轮；展示顺序与原始列表分离，不影响按歌曲标识播放、删除及持久化。稍后播放仍遵循播放器优先队列。
- assembleDebug、assembleDebugAndroidTest、lintDebug 通过。Android 36 模拟器上 PlaylistPickerTest 2 项、PriorityQueueTest 3 项通过，覆盖封面真实像素加载、名称/数量、滚动到第 19 个歌单并选择、空列表关闭、确定性随机顺序及切歌/优先项/删除/手动跳转/关闭随机。
- 初次 UI 测试使用 performScrollTo 定位尚未组合的 LazyColumn 远端条目失败，改为在滚动容器中 performScrollToNode 后重验通过。虚拟数据截图：captures/playlist-picker-preview.png。
- 手机 23127PN0CC 覆盖安装 Success，MainActivity 冷启动 Status ok。


## 2026-09-13：首次播放等待与封面闪烁回归修复

- 定位到封面全局 revision 导致所有图片请求清空重建、首次音源请求 isRefresh=true、播放启动同时另行下载整首音频及预取邻曲的问题。图片改为按 URL 版本通知，保持旧请求并以旧图片内存键作占位；未变化和其他 URL 更新不重载当前图片。封面索引文件读取移出主线程并缓存索引。
- 首次音源使用正常查询，同曲并发解析合并；到期/错误恢复才请求更新。去除启动时邻曲音源预取和额外的自动整曲下载。Media3 边读取边缓存分片，StreamingCacheDataSource 复用同一路读取字节，完整顺序响应才转为持久离线音频。中断、跳读、关闭自动缓存及写入失败不发布残缺文件；删除后的旧记录流不再保存。
- 播放起播缓冲 1 秒，再缓冲 2 秒；流式连接保留连接/读取超时，不套用接口客户端 45 秒的整次调用时限。流缓存版本在一次有效解析期间稳定，到期解析/连接更新和新会话重新区分资源，避免刷新时任意变化及跨版本混用。
- PlaybackMaintenance 串行调度缓存维护；播放/缓冲开始时取消本轮维护，空闲后重试。条件 HTTP 校验跟随协程取消真实 Call；音乐库刷新不再等待缓存维护结束。封面/歌词初次获取和显式下载仍保留。
- core:data 49 项单元测试通过（含独立 URL 通知和维护暂停/恢复）；assembleDebug、assembleDebugAndroidTest、lintDebug 通过。
- Android 36 模拟器 StreamPlaybackTest 3 项、ArtworkRefreshUiTest 1 项、CacheRefreshAcceptanceTest 1 项通过。测试限制每次读取 2048 字节并短暂延时，收到 32768 / 160044 字节后阻塞后续数据：真实 ExoPlayer 已进入播放，完整离线文件尚不存在；放行尾部后保存字节完全一致，只有一次数据源 open，无第二次音源下载请求。另覆盖首次解析合并、失败恢复强制更新、中断/跳读/关缓存不发布完整文件。
- 图片 UI 验证：真实解码红色图，刷新其他 URL 后 ImageRequest 对象保持不变；同 URL 换为蓝色后正常显示新图，更新过程不产生空模型，使用旧图占位。保留同地址资源变化、304、失败及断网回退测试。
- 手机 23127PN0CC 覆盖安装最终 APK 后，StreamPlaybackTest 与 CacheRefreshAcceptanceTest 共 4 项通过，MainActivity 冷启动 Status ok。设备专项使用独立临时目录、虚拟音频流和内存数据库，没有改动服务器资源或用户下载集合；这是受控数据流验收，不代表对所有弱网环境的延迟保证。


## 2026-09-13：加载状态与手动暂停区分

- PlayerUiState 分离 isPlaying、playWhenReady 和 isBuffering；缓冲状态取自 Media3 STATE_BUFFERING 且仍有播放意图，错误及播放结束不显示缓冲。首次提交播放立即显示加载状态。
- 播放页与迷你播放器在暂停操作图标外显示进度环，并提供“加载中…”文案及无障碍状态描述；用户仍可在缓冲期间暂停。暂停按钮按播放意图判断，修复缓冲时点击却再次执行 play 的问题。
- assembleDebug、assembleDebugAndroidTest、lintDebug 通过；Android 36 模拟器 BufferingUiTest 2 项通过，覆盖播放页加载/暂停/恢复播放和迷你播放器正常播放/再缓冲/错误。界面测试使用虚拟状态与歌曲；不将其表述为真实弱网端到端测试。


## 2026-09-13：清单定位当前歌曲与缺失封面按需获取

- 歌曲清单固定工具栏新增“定位当前播放”。使用当前显示/排序后的列表计算位置，滚动至歌曲并短暂高亮，不改播放队列或进度；当前歌曲不在清单时按钮禁用，手动浏览不自动被拉回。
- 缺失封面路径：列表元数据 picUrl 可以为空，旧逻辑仅播放/下载时通过 getMusicPic 解析封面。现在可见歌曲需要封面时独立请求该接口，最多并行两项、同曲合并请求、空结果/失败短期退避；不请求音源与歌词，不把单纯浏览过的封面列为本地歌曲。补取后通知界面，已有图继续遵循按 URL 局部更新策略，通知合并 150 ms 以减少重组。
- core:data 50 项通过，新增验证未播放歌曲获取缺失封面、十个重复请求合并为一次、无音源/歌词/完整音频及离线不发请求。assembleDebug、assembleDebugAndroidTest、lintDebug 通过。
- Android 36 模拟器 LocateCurrentTrackTest 2 项、ArtworkRefreshUiTest 1 项通过：80 首倒序清单定位、远距离滚动后使用固定入口、无目标时禁用、不触发歌曲操作；封面局部刷新仍通过回归检查。

## 2026-09-14：音乐库往返歌单误触修复

- 代码排查发现两条风险路径：默认淡入淡出期间旧歌单仍处于组合中，以及底部音乐库入口保存并恢复刚离开的子歌单。音乐库导航改为立即切换；底部音乐库始终回到总览，不恢复子歌单。
- 歌单进入前同步更新选中项；导航入口、返回及歌曲操作校验当前 back stack entry 和 RESUMED 生命周期，退出页面的延迟回调不再执行；旧页面的选择同步 effect 也校验当前 entry。
- assembleDebug、assembleDebugAndroidTest、lintDebug 通过。Android 36 模拟器 LibraryBackStackTest 2 项、LocateCurrentTrackTest 2 项、LibraryNavigationTest 2 项，共 6 项通过。
- 新回归使用真实 Compose 导航及清单、虚拟歌单数据和触摸点击，每次仅推进 64 ms：两个歌单交替进入/返回 16 轮，底部音乐库及设置页往返 6 轮；返回后不存在旧 song_list，旧 entry 拒绝输入，歌曲操作计数为 0。未声称在旧 APK 上复现用户真机问题。
- 手机 23127PN0CC 覆盖安装 Success，MainActivity 冷启动 Status ok。真机未执行触摸自动化；快速往返验证在模拟器完成。

## 2026-09-14：缓存全链路检查与同图闪烁修复

- 详细发现、行为变化与服务器条件请求限制见 CACHE_AUDIT_2026-09-14.md。
- 图片请求按内容 SHA-256 保持稳定，初始通知、相同内容 200、不同 URL / 本地 URI 不再清空或重建已显示图片；真实变化仍替换并保持旧图占位。
- 修复封面清单来源/解析来源不一致绕过间隔；缺失结果缓存 15 分钟、解析失败退避 60 秒；相同歌词/指针不写盘，已知离线的封面不发 HTTP。
- 普通音乐库刷新不强制校验全部资源、不作废所有音源；网络能力回调按网络状态变化触发；单曲失败作废只影响该曲。
- core:data 52 项通过；assembleDebug、assembleDebugAndroidTest、lintDebug 通过。Android 36 模拟器 ArtworkRefreshUiTest 2 项、StreamPlaybackTest 3 项、CacheRefreshAcceptanceTest 1 项通过，共 6 项。
- 新增数据测试最初因旧测试网关未登录却模拟在线调用而失败，修正 fixture 的 isOnline 后全量通过；缺失歌词测试改为显式 force 验证后来出现的资源，另覆盖正常读取的负缓存间隔。
- 手机 23127PN0CC 覆盖安装最终 APK 成功，MainActivity 冷启动 Status ok；真机未逐首复现，图片和流播放的自动化验证使用模拟器受控数据。

## 2026-09-14：页面转场协调与叠影修复

- 音乐库/歌单/搜索层级导航使用 260 ms FastOutSlowIn 同步整页水平进出，返回方向相反；页面具有独立不透明 Surface，NavHost 裁剪边界。底部主标签直接切换。播放页保留垂直展开/收起，改为确定时长并去掉整页透明混合。
- 保留当前 entry + RESUMED 点击保护，系统关闭动画时立即切换。参考 Material 导航方向表达原则，具体完整平移和时长为本项目设计选择：https://github.com/material-components/material-components-android/blob/master/docs/theming/Motion.md
- assembleDebug、assembleDebugAndroidTest、lintDebug 通过。模拟器 animator_duration_scale 临时开启为 1，LibraryBackStackTest 共 3 项通过：动画打开时 6 轮进入/返回，中途检查两页边界不交叠和过渡中 entry 不接受输入；动画关闭时 16 轮快速往返及 6 轮底部导航回归，歌曲误操作为 0。测试后恢复原动画设置 0。
- 手机 23127PN0CC 覆盖安装 Success，MainActivity 冷启动 Status ok。动画中途的自动化验证在模拟器使用虚拟数据完成。

## 2026-09-14：转场流畅度优化

- 保留水平推入/返回，时长改为 320 ms，双方共用 CubicBezier(0.2, 0, 0, 1)：减少起步等待、延长减速收尾。页面增加独立 graphicsLayer，仍保留不透明背景、裁剪和 entry 生命周期点击保护。
- openPlaylist 一次性清空查询并切换选中歌单；查询/选择未变时不重复筛选排序。当前歌曲定位索引及多选结果按输入记忆；未选择歌曲时不扫描全表。Track.cacheKey 改为不可变实例字段，避免列表扫描时反复拼接字符串；歌曲行设置统一 contentType。
- 下载文件存在性检查移到 IO；UI 封面地址只读内存索引，索引继续由已有后台加载/封面获取补全。HTTP 封面候选协议判断只看 localhost/回环字面地址，不在图片通知收集期间同步解析 DNS。
- core:model 25 项、core:data 52 项通过；构建、测试 APK 和 lintDebug 通过。模拟器 LibraryBackStackTest 3 项、ArtworkRefreshUiTest 2 项、LocateCurrentTrackTest 2 项通过。
- 新增 NavigationFrameProbeTest，ActivityScenario + 生产帧时钟，500 首虚拟歌曲、不请求真实图片，10 轮往返。模拟器记录 250 帧，中位数 54.14 ms，P95 213.89 ms。测试只断言采集到真实中间帧，不把通过称为流畅度达标；该调试版模拟器数据不用于推断手机帧率，也没有同条件旧版本对照，不能宣称性能提升百分比。
- 早期使用 ComposeTestRule 的探针会快进动画，样本无效，已替换为生产帧时钟。手机专项 ActivityScenario 未正常完成，终止测试并恢复 MainActivity；不提供无效真机对比。此前读取的真机 gfxinfo 为混合历史数据，存在长 UI 帧，GPU 大多数帧耗时较低，不单独归因为本次导航。
- 新 APK 已覆盖安装到手机 23127PN0CC，测试清理后 MainActivity 冷启动 Status ok；模拟器动画倍率恢复原值 0，未改变手机动画倍率。

## 2026-09-14：v0.1.0 发布前检查

- 下载器移除双重请求；续传使用保存的 URL/版本及 If-Range，严格检查 Content-Range、完整长度和响应类型，无版本凭证的旧临时文件重新下载。错误/中断保留已有完整文件，成功时原子替换；不再使用全请求 45 秒时限，仍保留连接与读取超时。清理任务一并移除续传验证文件。
- 密钥生成脚本不覆盖不完整的既有签名配置，密码通过环境变量传入 keytool；排除本地截图与发布产物，保留虚拟 README 截图。版本 0.1.0 / code 3。
- core:model 25 项、core:data 53 项通过；lintRelease、签名 release 和 debug/test APK 构建通过。APK v2 签名验证通过，SDK 26–36，正式包名与版本匹配。
- 模拟器 LibraryBackStackTest、ArtworkRefreshUiTest、StreamPlaybackTest、CacheRefreshAcceptanceTest、PlaylistPickerTest、LocalSelectionThemeTest、PlayerQueueTest、BufferingUiTest 共 17 项通过。正式 release APK 安装成功、冷启动 Status ok，UI 层级可正常读取。测试没有批量变更真实服务器歌曲。

- 远程 Linux CI 首次因旧手写 gradlew 的 JVM 参数引号而失败；重新生成标准 Gradle 8.13 Wrapper，Git Bash / Windows 启动均验证通过。JAR 与发行包 SHA-256 依据 https://gradle.org/release-checksums/ 核对并固定。
- 补充修复 CookieJar：匹配 Secure、路径及过期时间，同域新增 Cookie 不再清空其他 Cookie，避免同域 HTTP 封面请求携带仅限 HTTPS 的会话 Cookie。新增 2 项单元回归。


## v0.1.3 发布前回归（2026-09-14）

基线：已发布 v0.1.2。检查最近播放、流式缓存、缓冲指示、默认矢量封面及自动同步的累计变更。

- 单元测试：core:model 34 项、core:data 150 项、core:playback 9 项通过，共 193 项。
- 模拟器 API 36：StreamPlaybackTest、BufferingUiTest、LibraryNavigationTest 共 10 项通过；验证先播放再完整缓存、局部读取合并、加载状态、歌单更新和最近播放。
- Debug / AndroidTest 构建、Release Lint、Release 压缩构建及 APK 签名校验通过；正式版本 0.1.3，versionCode 10。
- 审查补修：有界读取 EOF 不再当作完整资源 EOF；已知长度不因提前 EOF 缩短；丢失半成品时废弃旧区间；清缓存后旧 sink 的迟到关闭不能删除新录制。
- 发布采用标签 CI 签名，公开附件另行下载核对校验值及与 v0.1.2 的签名一致性。本轮未执行真机完整联调。
