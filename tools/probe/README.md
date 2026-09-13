# 协议探针

对已部署的 v0.11.0-beta.1 做只读验证。秘密只来自环境变量或提示，不写仓库。

## 用法

```bash
# PowerShell
$env:ANYLISTEN_BASE_URL = "https://your-host"
$env:ANYLISTEN_PASSWORD = "..."
python tools/probe/probe.py
```

默认只做：hello、id、错误密码、正确登录、会话恢复、WS 握手、`inited`、版本、只读列表、`getMusicUrl` / 封面 / 歌词、Range。
**不会**删除音乐文件。只有同时设置独立的 `ANYLISTEN_TEST_PLAYLIST_ID` 并传入 `--write-playlist` 时，才会对**该用户歌单**做一次 add + 回读 + remove + 回读。内置 `default`/`love`/`last_played` 一律拒绝。

`getMusicUrl` 可能返回 `al-ps-host:/public/medias/<name>` 或 `/api/p_static/<name>`。探针会改写成同主机 HTTPS 后再对 **最多 1024 字节** 做 `Range: bytes=0-1023`。不打印原始 URL、token 或曲名。

输出目录：`captures/private/probe-<timestamp>/`（已 gitignore）。提交前只把脱敏后的内容拷进 `tests/fixtures/`。

## 退出码

- `0`：hello/id 可达，且（若提供密码）登录与会话恢复成功。
- `2`：缺少 `ANYLISTEN_BASE_URL`。
- `3`：TLS/连接失败（证书错误会单独标明）。
- `4`：协议与源码常量不符。
- `5`：认证失败。
