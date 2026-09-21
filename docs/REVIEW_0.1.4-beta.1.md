# v0.1.4-beta.1 发布前代码审查

基线：v0.1.3（fe62e600cca55dbd8cf2bae40b4ca1ff963b9db3）。范围：基线之后的提交及本次新增功能；需求依据为用户请求、USER_GUIDE 和新增功能说明。

## Standards

- P2：评论 UI 直接持有协议 JsonObject 并访问 gateway，不符合 ARCHITECTURE.md 的 Repository 分层。已增加 CommentRepository/CommentThread，在数据层封装协议对象。
- 建议：数据库 v1→v2 缺少升级回归。已补充旧库升级测试，验证歌单、关联曲目和下载路径保留。
- 建议：大曲库索引在 Compose 主线程计算可能卡顿。已移到 ViewModel 的 Default 调度器，仅在快照变化时重建。

## Spec

- P2：评论第二页为空或失败时分页控件消失，无法返回上一页，与 USER_GUIDE 的分页能力不符。已让上一页入口在页码大于 1 时保留，并补充空页和失败页回归。

统计：Standards 1 项明确问题、2 项建议，最严重 P2；Spec 1 项问题，最严重 P2。以上均已处理。

## 发布前验证

- 单元测试：model 47、data 169、playback 12，合计 228 项通过，无失败或跳过；包含 Room v1→v2 数据保留回归。
- Debug/Release Lint 与 Debug/Release APK、AndroidTest APK 构建通过。
- 依赖许可清单 111 项，重新生成后无差异。
- 待发布文件未包含本地 .env 中真实服务器及密码；私有抓图和测试记录不纳入源码包。
- Android 16 ARM64 模拟器完整回归：runner 报告 OK（68 tests），无失败；6 项真实环境测试按默认配置跳过，真实服务测试另见此前的专项记录。

## 云端复核与 beta.2 修正

beta.1 标签的 CI 数据层测试发现 `playRevalidatesCachedLyricsWhenServerCopyChanges` 时序断言失败，因此未执行签名发布。该测试以歌词文件可见作为后台任务完成条件，但更新通知发生在附属文件及检查时间写入之后。通过在文件可见后、通知前注入 150 ms 延迟，本地稳定复现同一 assertTrue 失败。测试改为等待 updates StateFlow 的新版本；延迟保留为回归覆盖。应用行为无需修改。

保留 beta.1 标签，新发布版本为 v0.1.4-beta.2（versionCode 12）。

修正后重跑：228 项单元测试全部通过，Debug/Release Lint 及两种 APK 构建通过。受控延迟下同一回归由失败转为通过。
