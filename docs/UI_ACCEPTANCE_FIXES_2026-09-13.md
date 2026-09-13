# 验收问题修复记录

## 后续播放器改版：滑动与全屏歌词

- 封面和歌词改为 HorizontalPager 两页，支持左右手势和顶部文字入口；进度条位于分页区域外，拖动进度不会翻页。
- 封面页显示一行随播放时间更新的歌词，点击预览也可进入歌词页。
- 歌词页采用紧凑曲目信息、大字歌词、当前句强调及上下渐隐；移除重复的大标题和快捷按钮，底部进度与主控固定。
- 手动纵向滚动暂停跟随，提供回到当前句；切歌重置阅读状态；保留纯文本歌词滚动及有时间戳歌词点击定位。
- 16 项原有 instrumentation 通过（29.054 秒），证据 `captures/private/emulator-20260913-211212-856055/`；新增滑动测试与加强后的纯文本测试共 2 项通过（5.479 秒），证据 `captures/private/lyrics-pager-review/swipe-results.txt`。两批共覆盖 17 个不同用例。
- Debug / AndroidTest 构建、Lint 通过。已检查模拟器 `player.png`、`lyrics.png`、`lyrics-fullscreen.png`，位于 `captures/private/lyrics-pager-review/`。截图为虚构测试内容。

已修复首次验收的四项缺陷，未扩大到新的 Web 业务功能。

| 问题 | 修复方式 | 验证 |
|---|---|---|
| 随机模式不遵守稍后播放 | 新增 PriorityQueue，调整 ExoPlayer 实际 shuffle 遍历顺序；非随机沿用队列物理顺序。有待播曲时暂时解除引擎的单曲循环，待播曲消耗完恢复；UI、通知与持久化保留用户选择的模式 | 真实 ExoPlayer 自动播放本地静音 WAV，覆盖顺序、列表循环、随机、单曲循环，均依次进入 later1、later2 |
| 冷启动续播丢待播标记 | ViewModel 恢复播放、PendingPlayback、服务 playTracks 全程传递 laterKeys；新列表仍默认清空。服务定时和销毁快照均保存待播集合及用户选择的逻辑模式 | 新播放器加载快照后验证待播集合、25 秒位置、随机下一曲与单曲循环恢复；未宣称真机 force-stop 端到端复验 |
| 关闭搜索保留隐藏条件 | 顶部关闭搜索时同步清空查询 | 原失败 AcceptanceRegressionTest 转为通过，关闭后 Beta 恢复显示 |
| 长纯文本歌词无法滚动 | 纯文本分支增加独立垂直滚动容器，主控仍在阅读区外固定 | 原失败 AcceptanceRegressionTest 转为通过，80 行无时间戳歌词可滚动 |

添加稍后播放歌曲时，使用 addMediaItem / moveMediaItem / removeMediaItems 增量修改现有时间线，不再对当前音频执行 setMediaItems + prepare。真实播放器测试确认当前歌曲、25 秒位置不变，且未产生当前媒体项切换事件。

## 验证结果

- Debug 与 AndroidTest APK 构建通过。
- 模拟器 `AnyListen_API_36` / `emulator-5580`：原有 12 项及 2 项 AcceptanceRegressionTest 共 **14 项通过**，46.118 秒。证据：`captures/private/emulator-20260913-210500-652518/instrumentation.txt`。
- 新增 PriorityQueueTest：**2 项通过**，5.248 秒。证据：`captures/private/acceptance-ui-20260913/priority-fixed-results.txt`。包含所有四种模式的自然结束，以及随机手动下一曲、队列修改、快照恢复。
- `:app:lintDebug` 通过；`:core:model:test`、`:core:data:testDebugUnitTest` 通过，本次复用未变化的 49 项单测结果。
- `git diff --check` 通过（仅有原工作区 LF/CRLF 提示）。

修复版 Debug APK 已覆盖安装到专用模拟器，手机未在本轮安装。保留原验收中的边界：小屏大字体、真实服务器批量写入、真实断网冷启动/重连和厂商后台策略仍需独立验收。
