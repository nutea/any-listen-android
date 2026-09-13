# ADR-002：独立文件下载 + Room 单一索引

状态：已采纳  
日期：2026-09-13

## 背景

需要可恢复、可校验、可离线使用的正式下载，并与临时播放缓存分开。Media3 `DownloadManager` 对半成品、无校验和不拼接、磁盘不足的控制弱于自管文件。

## 决策

1. **权威索引** 只有 Room `DownloadRecord`。文件存在但记录非 `COMPLETED` 则不可离线播放。
2. **正式下载** 由 `DownloadCoordinator` 写入应用私有目录 `downloads/`，校验通过后原子 rename。
3. **播放缓存** 使用 Media3 `SimpleCache`。缓存驱逐不得删除 `downloads/`。
4. 有 `Content-Range` / 206 才续传；Range 被忽略返回 200 则丢弃半成品并整文件重下。
5. 有服务端摘要则比对；否则核对长度并做本地可读检查，记录 `NO_REMOTE_CHECKSUM`。
6. 并发上限默认 2；可设仅 Wi-Fi。

## 备选

| 方案 | 未选原因 |
|---|---|
| 仅 Media3 DownloadManager | 不够表达校验 / 原子提交 / 孤立副本 |
| 双索引（Media3 + Room） | 易出现「文件有、库无」的假完成 |

## 后果

- 播放解析：已完成本地文件优先，否则现场 `getMusicUrl`。
- 远端删除不自动删本机文件，只标孤立。
- 更换服务器时需明确是否保留旧副本。
