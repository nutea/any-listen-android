# Any Listen Web 功能研究（2026-09-21）

研究范围：部署版本由主任务识别为 `webserver-v0.11.0-beta.1`，本文核对该官方 tag 的固定提交 `e4ef53a5094473687e2d142fb7b63a530436b982`。未连接生产服务器、未读取凭据。结论来自源码，不是每项功能的线上操作验收。Web 与桌面共用 view-main，但构建分目标，见 [构建配置](https://github.com/any-listen/any-listen/blob/e4ef53a5094473687e2d142fb7b63a530436b982/packages/view-main/vite.config.js#L21)。

## 已核实的 Web 功能与客户端价值

| 功能 | 匹配版本中的事实 | Android 接入判断 |
|---|---|---|
| 歌词翻译、罗马音、逐字及偏移 | 明确按设置同时装载原文、翻译、罗马音，选择逐字版本；支持偏移修改。[歌词组合](https://github.com/any-listen/any-listen/blob/e4ef53a5094473687e2d142fb7b63a530436b982/packages/view-main/src/modules/lyric/init.ts#L46)、[偏移编辑](https://github.com/any-listen/any-listen/blob/e4ef53a5094473687e2d142fb7b63a530436b982/packages/view-main/src/components/layout/PlayDetail/RightLyric/components/LyricOffset.svelte#L80) | 优先补双语、字号和本地偏移，再实现逐字；复用已有歌词返回字段。偏移同步至服务器需要额外核对写入语义。 |
| 歌单管理 | 我的列表菜单有创建、编辑、删除；列表拖动排序。[菜单](https://github.com/any-listen/any-listen/blob/e4ef53a5094473687e2d142fb7b63a530436b982/packages/view-main/src/components/layout/Aside/MyList/Menu.svelte#L39)、[排序](https://github.com/any-listen/any-listen/blob/e4ef53a5094473687e2d142fb7b63a530436b982/packages/view-main/src/components/layout/Aside/MyList/List.svelte#L38) | 优先补普通歌单创建/重命名/删除、歌单重排。协议已有 list_create/list_update/list_remove/list_update_position，以及 list_music_move/list_music_update_position。[协议](https://github.com/any-listen/any-listen/blob/e4ef53a5094473687e2d142fb7b63a530436b982/packages/shared/types/types/list_ipc.d.ts#L95) |
| 倍速与音调补偿 | UI 提供 0.50–1.80 倍滑块及音调补偿开关。[倍速组件](https://github.com/any-listen/any-listen/blob/e4ef53a5094473687e2d142fb7b63a530436b982/packages/view-main/src/components/common/PlaybackRateBtn.svelte#L26) | 适合第二批，移动端播放器本地实现即可；不要把共享服务器播放参数直接当作所有设备共同设置。 |
| 音效 | 均衡器、卷积效果、声像、升降调真实存在，播放详情页直接挂载，没有桌面条件包围。[音效 UI](https://github.com/any-listen/any-listen/blob/e4ef53a5094473687e2d142fb7b63a530436b982/packages/view-main/src/components/common/SoundEffectBtn/index.svelte#L28)、[Web 共用详情页入口](https://github.com/any-listen/any-listen/blob/e4ef53a5094473687e2d142fb7b63a530436b982/packages/view-main/src/components/layout/PlayDetail/Footer/LeftControlBtns.svelte#L15) | 先均衡器；卷积/升降调价值较窄且耗电/CPU 与设备兼容成本更高，可后置。 |
| 备份、导入与 TXT/CSV 导出 | 备份导入导出、合并策略、其他格式导出存在。[备份组件](https://github.com/any-listen/any-listen/blob/e4ef53a5094473687e2d142fb7b63a530436b982/packages/view-main/src/views/Setting/AppSetting/Backup/Backup.svelte#L16)、[TXT/CSV 导出](https://github.com/any-listen/any-listen/blob/e4ef53a5094473687e2d142fb7b63a530436b982/packages/view-main/src/views/Setting/AppSetting/Backup/ExportOther.svelte)。Web 使用专门文件选择/保存 Modal。[Web 文件选择分支](https://github.com/any-listen/any-listen/blob/e4ef53a5094473687e2d142fb7b63a530436b982/packages/view-main/src/shared/ipc/app/index.ts#L38) | 可后置。不能将 Web 保存弹窗解释成浏览器自动下载到手机；须明确服务器路径与 Android 文件选择、分享的差别。 |
| 在线搜索/浏览 | 在线搜索 UI 明确分歌曲、歌单、专辑、歌手，并按资源能力提供来源；还有歌单与榜单视图。[在线搜索分类](https://github.com/any-listen/any-listen/blob/e4ef53a5094473687e2d142fb7b63a530436b982/packages/view-main/src/views/Online/Search/Search.svelte#L72) | 与现有本地歌单关键词搜索不同。但依赖服务器在线资源开关及扩展能力，不保证当前服务器每类都有结果。 |
| 播放队列 | 存在队列、选曲；协议有 posUpdate。[队列 UI](https://github.com/any-listen/any-listen/blob/e4ef53a5094473687e2d142fb7b63a530436b982/packages/view-main/src/components/common/PlaylistBtn/QueueList.svelte#L15)、[队列协议](https://github.com/any-listen/any-listen/blob/e4ef53a5094473687e2d142fb7b63a530436b982/packages/shared/types/types/player_ipc.d.ts#L35) | 可建议 Android 队列重排，但本次未确认这个版本的队列弹窗支持拖拽，不能宣传为 Web 既有交互。 |
| 远程控制 | 协议及服务端存在播放命令下发，服务端对已初始化 main 连接广播。[广播实现](https://github.com/any-listen/any-listen/blob/e4ef53a5094473687e2d142fb7b63a530436b982/packages/web-server/src/app/renderer/winMain/rendererEvent/player.ts#L65) | 不足以认定存在设备选择、单设备遥控、无缝接力或多房间音频同步。若要做需先设计控制目标和状态所有权，暂不优先。 |

## 不应冒称 Web 既有能力

- **睡眠定时**：在匹配 tag 的 view-main 和中文语言资源中搜索 sleep、定时及停止定时相关实现，未找到面向用户的睡眠定时功能。可作为 Android 手机场景增强单独建议，但不能声称移植 Web。
- **队列拖拽**：协议能力不等于 UI 现成功能，已读队列组件未找到拖动排序入口。
- **桌面歌词/任务栏/托盘**：共享代码出现不代表普通 Web 或 Android 应原样照搬。

## 部署版本与更新上游的差别

本次 `git ls-remote` 得到上游 HEAD `9e8e48a6307dad6de46cc549ad24528ca0f6193d`，其 web-server package 版本为 `0.11.0-beta.4`。与部署 tag 比较，更新上游增加歌曲换源界面和歌曲位置调整弹窗。不能把它们误写为 beta.1 已有完整 UI。beta.1 已有列表曲目重排协议，属于“可扩展接口已有，较新的便捷界面后加入”。

来源：[新上游更新日志](https://github.com/any-listen/any-listen/blob/9e8e48a6307dad6de46cc549ad24528ca0f6193d/packages/web-server/publish/changeLog.md)、[新上游版本](https://github.com/any-listen/any-listen/blob/9e8e48a6307dad6de46cc549ad24528ca0f6193d/packages/web-server/package.json)。

建议结合 Android 现有实现排序：歌词增强、普通歌单管理优先；均衡器/倍速其次；在线资源在确认服务端能力后推进；备份和遥控后置。睡眠定时单列为移动端新增提案。

## 与 Android 已有能力的边界

据主任务独立核对，Android 已有跨歌单组合关键词搜索、列表排序、定位当前曲、稍后播放/队列删除、循环随机、收藏、歌词点击跳转、下载/离线缓存及断线同步；这些不应重复列为待移植功能。上述建议来自双方源码对照，本次没有登录 Web 操作验收。队列拖拽不列为已确认的 Web 移植项。
