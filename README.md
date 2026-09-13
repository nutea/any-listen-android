# Any Listen Android

面向私人 Any Listen 音乐服务的原生安卓客户端。仓库名为 `any-listen-android`，不代表 Any Listen 官方客户端。

## 项目信息

- 目标服务：**Any Listen Web Server v0.11.0-beta.1**（源码标签 `webserver-v0.11.0-beta.1` / 提交 `e4ef53a`）。
- 包名：`io.github.nutea.anylisten`（debug 为 `.debug`）；minSdk 26；compile / target SDK 36。
- 技术栈：Kotlin、Compose、Media3、Room、OkHttp、DataStore。
- 服务端是歌单权威；手机保存下载副本、元数据缓存和本机播放队列。联网时可收藏、增删可变歌单中的曲目。

闭环：服务器管理自有音乐 → 安卓读取歌单 → 后台播放 → 单曲/列表下载 → 断网后浏览并播放已下载 → 联网刷新库。

界面：连接页；登录后为音乐库、下载、设置；播放页与迷你播放器。基础展示：歌名、歌手、专辑、封面；有接口时显示 LRC。封面或歌词缺失不阻断播放。

不做：多账号切换、离线改服务端歌单、跨设备进度接力、服务器文件删除或标签编辑、在线音源市场、DRM 绕过、逐字歌词、音效引擎、Android Auto、投屏、应用商店发布。

不提交域名、密码、Cookie、令牌、签名密钥或私人音乐。

## 文档

- [使用说明](docs/USER_GUIDE.md)
- [架构](docs/ARCHITECTURE.md)
- [协议](docs/api/PROTOCOL.md) / [能力矩阵](docs/api/COMPATIBILITY.md)
- [ADR-001 服务端适配](docs/adr/001-server-integration.md) / [ADR-002 下载](docs/adr/002-download-strategy.md)
- [测试记录](docs/TEST_EVIDENCE.md) / [测试与发布](docs/TEST_RELEASE.md) / [签名发布](docs/RELEASE.md)
- [第三方与来源](docs/THIRD_PARTY.md)

## 本地构建

需要 JDK 17 与 Android SDK（平台 36）。

```bash
echo sdk.dir=C:/Users/YOU/AppData/Local/Android/Sdk> local.properties
./gradlew testDebugUnitTest :core:model:test assembleDebug
```

协议探针（凭据只走环境变量或本地 `.env`，已 gitignore）：

```bash
python tools/probe/probe.py
```

详见 [tools/probe/README.md](tools/probe/README.md)。
