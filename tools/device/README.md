# 模拟器自动调试（Windows）

前置条件：Python 3、JDK 17+、Android SDK（platform-tools、emulator、cmdline-tools/latest），以及可用的硬件虚拟化。
SDK 从 ANDROID_HOME / ANDROID_SDK_ROOT / local.properties 查找。Java 优先读取 ANYLISTEN_JAVA_HOME，随后尝试 JAVA_HOME、本机已知 JDK 目录和 PATH；不会修改系统环境变量。

在仓库根目录执行：

```powershell
# 首次安装 API 36 Google APIs x86_64 完整系统镜像并创建专用虚拟设备
python tools/device/emulator.py setup

# 自动启动、构建、安装、运行 Compose 测试，再打开应用并采集截图/日志
python tools/device/emulator.py test

# 日常修改后的构建、覆盖安装、启动和采集
python tools/device/emulator.py run

# 查看当前屏幕与日志，不重新构建
python tools/device/emulator.py capture

# 停止专用模拟器
python tools/device/emulator.py stop

# 显示交互窗口（已运行时先 stop，再使用 --show）
python tools/device/emulator.py run --show
```

默认无窗口运行，AVD 为 `AnyListen_API_36`，ADB 序列号为 `emulator-5580`。每条设备命令都指定序列号并验证 AVD 名称，不依赖当前连接设备数量。端口冲突时可传 `--port 5582`，后续命令保持一致。

输出位于 `captures/private/emulator-时间/`（Git 已忽略）：`screen.png`、`logcat.txt`、`activity.txt`、`media-session.txt`、`result.json`，测试时额外有 `instrumentation.txt`。日志和截图可能包含私人内容，不要直接提交。截图使用二进制写入，避免 PowerShell 重定向损坏 PNG。

当前自动回归共 8 项：连接页凭据输入/按钮状态/提交回调、忙碌状态和错误展示；音乐库菜单与离线限制、删除确认、播放模式、歌词跳转、队列操作、深色播放器、下载分页；以及真实 ExoPlayer 队列删除。界面测试直接挂载 Compose 页面，不连接私人服务器，也不清空应用登录状态或下载。它们不是完整的登录、播放或下载端到端测试。脚本还检查真实 MainActivity 能启动且进程存活；登录后的页面取决于模拟器保存的会话。

首次安装镜像需要网络和数 GB 磁盘空间。SDK 若提示未接受许可证，请阅读后按提示处理。启动超时可查看本次 `emulator.log`；`-accel-check` 失败需检查 Windows 虚拟化配置。后台播放、蓝牙和厂商省电策略仍需真机验证。

手动操作也须使用明确序列号，例如：

```powershell
& "$env:LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe" -s emulator-5580 shell input tap 200 300
```

参考：[模拟器命令行](https://developer.android.com/studio/run/emulator-commandline)、[Compose 测试](https://developer.android.com/develop/ui/compose/testing)。
