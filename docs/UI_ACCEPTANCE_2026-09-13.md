# UI 改版验收结果

> 修复复验更新：本文下方保留首次验收记录。上述四项缺陷现已修复，14 项界面/原有播放器测试及 2 项新增真实 ExoPlayer 测试分两批通过；详情见 [修复记录](UI_ACCEPTANCE_FIXES_2026-09-13.md)。未覆盖的完整产品验收条件仍保留，不代表所有 16 项方案均已完成端到端验收。

结论：**部分通过，暂不接受“全部完成”。** 紧凑音乐库、播放页固定主控、全屏歌词、批量操作入口和本地页均已实现；存在 4 项应修复的问题，以及若干尚未覆盖的验收条件。

验收对象为 2026-09-13 当前工作区，HEAD 为 `97f44dc`，大量实现仍未提交。因此这是当前工作区验收，不是某个独立改版提交的审查。本次仅新增验收测试和本文档，没有修改产品实现，没有安装到手机，没有对私人服务器执行歌单写入或删除。

## 测试与证据

| 检查 | 结果 | 范围 |
|---|---|---|
| Debug APK 与 AndroidTest 构建 | 通过 | 当前代码可编译 |
| 原有模拟器回归 | 12 项通过，54.235 秒 | 连接页、菜单、模式选择、歌词定位、下载分页、设置、选择操作、队列分区、ExoPlayer 队列删除 |
| 单元测试 | 49 项通过：model 22、data 27 | 初次任务复用结果后，使用每个 test 任务的 `--rerun` 再执行 |
| `:app:lintDebug` | 通过 | 在新增验收测试之前执行；不代表全部交互条件已满足 |
| 新增针对性验收 | **2 项失败**，4.685 秒 | 隐藏搜索仍筛选、长纯文本歌词无滚动 |
| 截图人工检查 | 已检查音乐库、播放器、歌词、本地页 | 组件样例，默认字体、API 36；不能代表真实封面、全导航和小屏大字体已验证 |

原有回归证据：`captures/private/emulator-20260913-205317-372546/instrumentation.txt`、`result.json`。新增失败证据：`captures/private/acceptance-ui-20260913/regression-results.txt`。截图同目录下 `library.png`、`player.png`、`lyrics.png`、`downloads.png`。测试结果必须按 instrumentation 输出判断，不能只看 adb 进程退出码；本次新增测试输出明确为 `Tests run: 2, Failures: 2`。

## 必须修复的问题

### 1. [P1] “稍后播放”没有接入随机播放的优先选择

位置：`core/playback/src/main/kotlin/io/github/nutea/anylisten/core/playback/PlaybackService.kt:232`，`app/src/main/java/io/github/nutea/anylisten/ui/AppViewModels.kt:321`。

`PlayLater.insert` 将歌曲移动到当前项后面的物理列表位置，服务的 `applyQueuePreservingCurrent` 重设时间线；`laterKeys` 只用于记录、展示、持久化及删除。下一曲仍由 ExoPlayer 的普通跳转/随机逻辑选择，没有消费稍后播放优先队列的分支。随机模式下“当前项的物理下一项”不等于“实际下一项”，因此界面“稍后播放”分区不能保证优先播放。

这与报告中“对照 Web 的 playLater 优先语义”不符。应让手动下一曲及自然结束都遵守优先队列，并定义所有播放模式下的行为。还应避免仅为添加歌曲就重建当前媒体项及重新 prepare，以免产生重新缓冲。该问题为**源码调用链确认**，本次未将随机音频端到端运行宣称为已复现。现有 PlayLater 单测仅验证纯列表排列，界面测试仅检查分区文字，不能覆盖此问题。

### 2. [P2] 重启后点击续播会清空稍后播放标记

位置：`app/src/main/java/io/github/nutea/anylisten/ui/AppViewModels.kt:245`，关联 `:298`、`:792`；`core/playback/src/main/kotlin/io/github/nutea/anylisten/core/playback/PlaybackService.kt:205`。

`restorePlayback()` 会恢复 `saved.laterKeys`，但冷启动播放器时间线为空，点击播放进入 `togglePlayPause → play`，其中明确设置 `laterKeys = emptyList()`。`PendingPlayback` 没有携带 laterKeys，服务 `playTracks` 也清空自己的集合，后续服务持久化会保存清空后的状态。

预期应区分“开始一份新列表”和“恢复旧队列”。恢复时需要把稍后播放状态一路传给服务，保留队列分区与优先语义。该项为**源码调用链确认**，未将其写成真机 force-stop 复现。

### 3. [P2] 关闭搜索后，列表仍受不可见条件过滤

位置：`app/src/main/java/io/github/nutea/anylisten/ui/screens/LibraryScreen.kt:117`。

复现：打开搜索，输入 Alpha；Beta 被过滤。点击顶部关闭按钮后搜索框消失，但 `state.query` 保持 Alpha，其他歌曲仍隐藏。按钮的无障碍标签还是“清除搜索”，实际只切换 `searching`。

新增 `AcceptanceRegressionTest.closingSearchMustNotLeaveAnInvisibleFilter` **已在模拟器复现失败**，实际值 Alpha，期望空字符串。修复可采用关闭时清空查询；若产品确实要保留条件，则必须提供持续可见的筛选标识和清除入口，并调整测试契约，不能让过滤条件完全不可见。

### 4. [P2] 长篇无时间戳歌词无法完整阅读

位置：`app/src/main/java/io/github/nutea/anylisten/ui/screens/PlayerScreen.kt:240`。

有时间戳歌词使用 LazyColumn，纯文本分支直接渲染 `Text(raw)`，没有滚动容器。80 行纯文本歌词在固定高度阅读区内无法滚动访问后文。新增 `AcceptanceRegressionTest.longUntimedLyricsMustHaveScrollableReadingArea` **已在模拟器复现失败**，该视图不存在滚动操作。

修复应给纯文本歌词独立滚动区域，保持下部主控可达，不给无时间戳歌词添加伪跳转动作。

## 方案覆盖度

| 方案项 | 当前判断 |
|---|---|
| 01 视觉规范、02 紧凑音乐库、04 歌曲列表、05 播放器结构 | 已有明显实现，默认尺寸截图基本符合方向；搜索问题需修复 |
| 03 歌单封面、06 封面取色 | 找到实现路径；现有截图用空封面样例，未完成真实封面效果和缓存切换验收 |
| 07 全屏歌词 | 固定主控和时间戳点击已通过；纯文本阅读失败，手动浏览/自动跟随的完整交互待补 |
| 08 迷你播放器及返回路径 | 迷你条和队列入口存在；完整导航往返保留位置未做端到端验证 |
| 09 菜单、10 批量操作、11 搜索排序 | 入口和样例回调已检查；没有进行私人服务器批量写入、部分失败处理和大数据验证 |
| 12 队列及稍后播放 | 分区展示通过，播放语义及冷启动恢复不通过 |
| 13 本地页、14 完整性状态 | 有三分页和资源状态；READY 与 UNKNOWN 均不显示标识，用户仍无法区别“资源完整”和“未确认”，未完全达到分别展示可用性的目标 |
| 15 设置反馈 | 分组已实现、组件测试通过；容量更新的端到端验证未重做 |
| 16 动效触感 | 存在实现；系统关闭动画、实际触感及性能未验收 |

本地页仍采用大容量卡片与较高的歌曲卡片，信息密度明显低于音乐库，后续可继续收紧；这属于视觉完善建议，不将审美判断混入上述已证实的功能缺陷。

没有验证：360dp 小屏及大字体、30 首数据首屏目标、真实封面取色、断网恢复/离线冷启动在这次新代码下的端到端回归、后台与蓝牙，以及批量服务器写入。历史 TEST_EVIDENCE 的通过记录不能自动替代这次改版验收。

## 交回实现方的复验要求

先修复上述四项，再运行原有测试与新增 AcceptanceRegressionTest。为稍后播放补充真实播放控制层测试：随机模式的手动/自然下一曲、重启恢复、加入歌曲不重置当前进度。离线页面需以实际文件和资产索引验证状态变化，不能只注入固定列表。最后检查小屏大字体、真实封面及完整导航路径。

新增测试目前有意保留正确行为断言，修复前全量 instrumentation 会报告这两项失败；不要删除断言来恢复全绿。本次没有改动生产代码以掩盖验收发现。
