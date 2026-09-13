# ADR-001：直接适配现有 IPC，不改生产服务

状态：已采纳  
日期：2026-09-13

## 背景

客户端对接已部署的 Any Listen Web Server **v0.11.0-beta.1**。默认复用现有协议，不改生产服务。

## 决策

1. **直接适配** 该版本已有 HTTP / IPC WebSocket，经 `AnyListenGateway` 隔离。
2. **不** 新增服务端 REST、不部署补丁、不升级生产，除非现场证明无法完成登录 / 歌单 / 授权音频，再单独立项。
3. UI 与 Room 只依赖领域模型。协议 DTO 不泄漏到 Compose。
4. 现场结果与源码冲突时，改适配层和 `PROTOCOL.md`，不改生产数据。

## 备选

| 方案 | 未选原因 |
|---|---|
| 最小服务端补丁（稳定 ID / Range / 安全写） | 未授权自动改生产 |
| 只抓 Web UI | 无后台播放、无可靠下载状态机 |
| 自建同步服务 / LX / WebDAV | 不需要 |

## 后果

- 必须实现 message2call 与 `/api/ipc/*`，不虚构 REST。
- 歌单写入只用已标明的 `list_music_add` / `list_music_remove`，写后回读。
- ID 不稳时下载变孤立副本，不模糊重绑。
