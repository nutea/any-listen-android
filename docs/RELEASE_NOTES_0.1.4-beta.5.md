# Any Listen Android v0.1.4-beta.5（非官方客户端）

本版本为界面与搜索体验测试版，versionCode 15，兼容 Any Listen Web Server `0.11.0-beta.1`。

## 播放页布局

- 收藏移到歌名旁；封面和歌词页保留下载、评论和「更多」三个快捷操作，给封面和歌词更多空间。
- 「更多」打开歌曲详情菜单：定时停止、加入歌单、播放页风格，以及专辑、歌手、音效与倍速、音频信息、歌词设置。
- 已设置的定时停止继续在播放页显示剩余时间或曲终停止状态；可直接点击调整。
- 详情菜单支持滚动，适配小屏、大字体及深色模式；继续支持经典、沉浸和三种音波风格。

## 搜索范围

- 从音乐库总览或智能曲库进入：搜索全部已同步歌单。
- 从某个歌单进入：只搜索该歌单，页面显示当前搜索范围。
- 中文、英文、拼音与首字母搜索、下载筛选、智能筛选及听歌统计均遵守当前范围；使用历史关键词也不会扩大范围。
- 搜索结果去重并标注范围内的所属歌单；选择其他歌曲播放时使用当前范围的结果清单；点击正在播放的歌曲仍直接打开播放页。返回后仍可浏览原歌单。
- 搜索中的歌单被删除时提示返回重新选择，不会自动转为全局搜索。

## 更新与验证

沿用现有 CI 签名密钥，可从 beta.4 覆盖安装。没有数据库结构变更，保留登录、曲库、下载和设置。

发布前完成代码检视、单元测试、Debug／Release Lint 及 Android 16 ARM64 模拟器回归，检查 360dp 小屏、130% 字体和深色界面。详细结果见 [测试记录](https://github.com/nutea/any-listen-android/blob/v0.1.4-beta.5/docs/TEST_EVIDENCE.md) 和 [检视记录](https://github.com/nutea/any-listen-android/blob/v0.1.4-beta.5/docs/REVIEW_0.1.4-beta.5.md)。本轮功能验证使用本地夹具，未修改真实服务器数据，未进行实体手机验收。

## 下载与源码

附件提供签名 APK、对应标签完整源码 ZIP 和 SHA256SUMS.txt。

项目：[nutea/any-listen-android](https://github.com/nutea/any-listen-android)。这是非官方客户端，采用**基于 AGPL v3.0、附加非商业条款的自定义许可证**。源码包包含 LICENSE、NOTICE、docs/THIRD_PARTY.md 及 third_party 依赖许可资料。
