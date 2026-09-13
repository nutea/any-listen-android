# 音乐交互与界面改版

## 功能边界

以 Any Listen Web Server `v0.11.0-beta.1` 对应源码 `e4ef53a5094473687e2d142fb7b63a530436b982` 为功能依据。参考 Apple Music 的迷你播放器 / 全屏播放层级、Spotify 的歌曲菜单 / 播放队列入口，采用原生 Compose 实现，不复制它们的品牌和在线服务能力。

新功能必须能在该 Web 版本中找到实际实现，注释掉的入口不算已支持。安卓端上一阶段已经实现的离线下载、缓存、诊断只整理现有界面，不在这一轮扩展为新业务。

| 项目 | Web 源码依据 | 本轮实现 |
|---|---|---|
| 歌单、搜索、曲目菜单、收藏、加入 / 移除 | `packages/view-main/src/components/common/MusicList/`、`modules/player/store/playerActions.ts` | 歌单卡片、搜索空态、整行播放、更多操作底部菜单；在线写入在离线时禁用；移除前确认 |
| 四种播放模式 | `packages/view-main/src/modules/player/store/playerActions.ts` | 单入口直接选择列表循环、列表随机、顺序播放、单曲循环；沿用 Media3 播放状态 |
| 队列移除 | `packages/view-main/src/modules/player/store/listRemoteAction.ts` 的 `removePlayListMusic` | 独立队列面板，点击切歌，按媒体身份从本机 Media3 队列移除；不删除服务器歌单内容 |
| 歌词定位 | `packages/view-main/src/components/layout/PlayDetail/RightLyric/useLyric.svelte.ts` 的 `handleSkipPlay` / `seekTo` | 独立歌词面板，当前句高亮，点击定位播放进度 |
| 播放控制 | `packages/view-main/src/modules/player/store/actions.ts` | 紧凑迷你播放器、进度细线、全屏封面和主控件；全屏播放隐藏底部主导航 |

Web 曲目菜单中的 `download` 入口在此版本被注释，因此不把安卓原有下载能力描述成此次从 Web 新移植的功能。不新增推荐流、排行榜、社交、会员、音效、AI 功能或其他没有核实的入口。Web 中的其它能力也不因存在就全部搬到手机。

## 界面规则

- 浅色使用暖白 / 森林绿，深色使用墨绿背景；排版、间距和触控区域统一。
- 歌曲标题优先展示，单行省略长文本；次级操作进入底部菜单，封面缺失 / 加载失败仍有占位。
- 连接页处理状态栏、输入法和小屏滚动；支持密码可见切换与键盘提交；用户文案使用“测试连接”，不展示 Hello 协议名。
- 通知权限在已登录页面请求。下载删除和退出连接均有确认；诊断与缓存操作保留反馈。
- 歌词 / 队列 / 模式以独立面板呈现，返回键关闭面板。

## 验证

运行 `python tools/device/emulator.py test`。`MusicExperienceTest` 使用隔离样例数据检查菜单、离线操作限制、歌词定位、模式选择、队列按钮和下载确认；`PlayerQueueTest` 用真实 ExoPlayer 时间线检查移除前项保持当前进度、移除当前项切到下一项和清空最后一项。测试不调用私人服务器写接口。

测试截图保存在模拟器的应用外部文件目录 `files/ui-review/`，其中歌名与歌单是测试样例，不代表服务器真实内容。需人工检查截图，不能只把测试通过当成视觉验收。

参考：[Apple 播放控制](https://support.apple.com/guide/iphone/use-the-music-player-controls-iph676daac9b/26/ios/26)、[Spotify 播放队列](https://support.spotify.com/us/article/play-queue/)、[Web 源码版本](https://github.com/any-listen/any-listen/tree/e4ef53a5094473687e2d142fb7b63a530436b982)。
