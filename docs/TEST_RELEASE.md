# 测试与发布

## 测试层次

- 单元：DTO、歌曲身份、队列与播放模式、下载状态、错误分类、刷新单飞。
- 集成：脱敏 fixture、401 / 超时、Range 206 / 200、Room、下载取消与原子提交。
- UI：连接错误、空库、离线、长文本。
- 真机：后台播放、耳机 / 来电、网络切换、离线冷启动、覆盖安装。

CI 跑构建、单测和 Lint，使用 fixture，不连私人生产服务。签名发布与普通 PR 验证分开。

## 真机关注点

| 场景 | 期望 |
|---|---|
| 错误密码 / 证书错误 | 明确失败，无无限重试，不跳过 TLS |
| 会话 | 重启保持登录；密码变更后回到登录页；离线仍可播已下载 |
| 锁屏 / 通知栏 | 切歌与暂停可用 |
| 音频焦点 / 拔耳机 | 按系统语义暂停或让路 |
| 网络切换 | Wi-Fi 与蜂窝均可下载，断网重连后可继续或重试 |
| 断网冷启动 | 可浏览并播放已完成下载，进度可拖 |
| 下载中断 | 已完成不损坏，未完成可重试 |
| Range 被忽略 | 不拼接损坏文件 |
| 删除下载 | 只删手机副本 |
| 歌单写入 | 写后回读；失败不伪报成功 |
| 覆盖安装 | 会话与下载仍在 |

至少用实际库中的样本报告格式支持情况，不泛化。未测项在 `docs/TEST_EVIDENCE.md` 保持「未验证」，不写「通过」。

## 发布

签名步骤见 [RELEASE.md](RELEASE.md)。优先用标签推送触发 CI，由仓库 Secrets 签名并挂到 GitHub Release；本地 `keystore.properties` 仅可选。产物包含 APK、SHA-256、`versionName` / `versionCode`、兼容服务端版本。正式包不携带测试密码、私人域名或详细网络日志。卸载会删除应用私有下载。

## 参考

- https://developer.android.com/media/media3/session/background-playback
- https://developer.android.com/media/media3/exoplayer/downloading-media
- https://github.com/any-listen/any-listen
- https://github.com/any-listen/any-listen-web-server/releases
