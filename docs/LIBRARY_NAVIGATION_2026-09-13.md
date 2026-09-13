# 音乐库与独立搜索验收（2026-09-13）

音乐库以歌单清单为主体，顶部横排「我喜欢 / 最近播放 / 默认列表」三个大图标入口，下方是自定义歌单的封面、名称与歌曲数量。点击入口进入歌曲清单，原有排序、播放、批量操作、下载、收藏和歌单编辑继续可用。

搜索为独立导航页面，总览与歌曲清单均可进入。搜索范围为所有已同步歌单，匹配歌名、歌手、专辑，支持空格分隔的组合关键词。按歌曲 cacheKey 去重并显示所属歌单；不同身份的同名歌曲保留。点击结果以去重后的搜索结果作为播放队列。搜索状态独立，不改变原歌单的过滤条件。离线搜索使用已同步数据，离线播放仍要求完整缓存或下载。

歌单封面复用现有持久缓存与服务器相对地址解析，没有封面时使用歌曲封面或文字占位。歌单在服务端删除并同步后，正在查看的详情页返回总览。

结构参考现有调研与 [Spotify 官方音乐库说明](https://support.spotify.com/us/article/your-library/)，具体入口与搜索范围按本轮用户要求实现，无新增服务端接口。

## 验证

- `:core:model:test`：25 项通过，其中新增 3 项跨歌单检索测试。
- API 36 模拟器全套 instrumentation：19 项通过，含总览入口、跨歌单搜索、去重、结果播放、清空和返回后无残留筛选。
- 最终补充封面地址解析与缺失歌单返回后，重跑 LibraryNavigationTest 与 AcceptanceRegressionTest，4 项通过。
- `:app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug` 通过；lint 0 errors，35 warnings。
- 最终 APK 在专用模拟器上验证真实导航：总览 → 我喜欢（4 首）→ 搜索 BY2，找到跨歌单歌曲并显示来源 → 返回完整我喜欢 → 返回总览。
- 已查看总览与搜索截图；完整应用保留底部迷你播放器和导航。

## 本地证据

- 全套结果：`captures/private/emulator-20260913-213015-979893/instrumentation.txt`
- 总览：`captures/private/library-navigation-review/library-overview.png`
- 搜索：`captures/private/library-navigation-review/library-search.png`
- 最终真实导航层级：`captures/private/library-navigation-review/final-*.xml`
- 安装包：`app/build/outputs/apk/debug/app-debug.apk`

截图中的测试歌单为交互测试数据；真实导航只查询已有库并切换页面，没有修改服务端歌单。
