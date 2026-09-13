# Any Listen Android

面向私人 Any Listen 音乐服务的原生安卓客户端。仓库名为 `any-listen-android`，不代表 Any Listen 官方客户端。

## 当前状态

2026-09-13：完成开发计划，尚未实现或验证客户端代码。

- 用户已部署 **Any Listen Web Server v0.11.0-beta.1**，通过反向代理提供公网 HTTPS。
- 域名、认证凭据、反代配置、服务器镜像摘要和测试手机尚未提供。
- 首版单用户、单服务端；原生后台播放和正式离线下载是核心目标。
- 不将“网页可访问”视作接口、后台播放或下载测试通过。

## 阅读顺序

1. [完整开发计划](docs/DEVELOPMENT_PLAN.md)：需求、里程碑、任务和交付标准。
2. [架构与数据规则](docs/ARCHITECTURE.md)：客户端分层、歌曲身份、播放与离线状态。
3. [接口验证清单](docs/API_DISCOVERY.md)：Hermes/开发者执行的首个阶段，不包含猜测的 API。
4. [测试与发布](docs/TEST_RELEASE.md)：验收矩阵、CI、签名、备份与兼容策略。
5. [执行交接](docs/HANDOFF.md)：从哪里开始、何时可以继续。

## 关键约定

服务端是音乐库和歌单的权威来源；手机保存下载副本、元数据缓存和自己的队列/进度。首版只在线修改歌单，不实现离线编辑合并、跨设备播放接力或服务器原文件删除。

技术方向：Kotlin、Jetpack Compose、Media3、Room、协程/Flow；具体版本和最低 Android 版本在 M1 固定。此仓库目前只有规划文件，无可运行 APK。

不提交真实域名配置、密码、Cookie、令牌、签名密钥或私人音乐。生产配置通过本机或 CI 的秘密存储注入。
