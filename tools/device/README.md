# 模拟器自动调试（Windows / macOS / Linux）

前置条件：Python 3、JDK 17+、Android SDK（platform-tools、emulator、cmdline-tools/latest），以及可用的硬件虚拟化。
SDK 从 ANDROID_HOME / ANDROID_SDK_ROOT / local.properties 查找。Java 优先读取 ANYLISTEN_JAVA_HOME，随后尝试 JAVA_HOME、本机已知 JDK 目录和 PATH；不会修改系统环境变量。

在仓库根目录执行：

```powershell
# 首次安装 API 36 Google APIs 完整系统镜像（Apple Silicon 自动使用 ARM64）并创建专用虚拟设备
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

macOS（Apple Silicon）首次安装命令行开发环境：

```bash
brew install openjdk@17 android-commandlinetools
export JAVA_HOME="$(brew --prefix openjdk@17)/libexec/openjdk.jdk/Contents/Home"
export ANDROID_HOME="$HOME/Library/Android/sdk"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"
mkdir -p "$ANDROID_HOME/cmdline-tools"
ditto "$(brew --prefix)/share/android-commandlinetools/cmdline-tools/latest" "$ANDROID_HOME/cmdline-tools/latest"
# 按 SDK Manager 提示阅读并接受许可证。
"$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$ANDROID_HOME" "platform-tools" "platforms;android-36" "build-tools;35.0.0" "emulator"
printf 'sdk.dir=%s\n' "$ANDROID_HOME" > local.properties
python3 tools/device/emulator.py setup
python3 tools/device/emulator.py run --show
```

脚本会自动查找 Homebrew 的 JDK 17。`local.properties` 记录 SDK 路径后，无需全局修改 Java 配置。

默认无窗口运行，AVD 为 `AnyListen_API_36`，ADB 序列号为 `emulator-5580`。每条设备命令都指定序列号并验证 AVD 名称，不依赖当前连接设备数量。端口冲突时可传 `--port 5582`，后续命令保持一致。

macOS 默认使用 `--gpu auto`，让模拟器选择硬件图形渲染；需要排查驱动问题时可指定
`--gpu software`（改变渲染模式前先停止模拟器）。脚本保留 Animator 倍率为 1，
因为播放指示动画和导航帧测试需要真实动画；系统窗口和 Activity 转场仍关闭以减少等待。

输出位于 `captures/private/emulator-时间/`（Git 已忽略）：`screen.png`、`logcat.txt`、`activity.txt`、`media-session.txt`、`result.json`，测试时额外有 `instrumentation.txt`。日志和截图可能包含私人内容，不要直接提交。截图使用二进制写入，避免 PowerShell 重定向损坏 PNG。

自动回归覆盖：连接页凭据输入/按钮状态/提交回调、忙碌状态和错误展示；音乐库菜单与离线限制、删除确认、播放模式、歌词跳转、队列操作、深色播放器、下载分页；以及真实 ExoPlayer 队列删除。界面测试直接挂载 Compose 页面，不连接私人服务器，也不清空应用登录状态或下载。它们不是完整的登录、播放或下载端到端测试。脚本还检查真实 MainActivity 能启动且进程存活；登录后的页面取决于模拟器保存的会话。

首次安装镜像需要网络和数 GB 磁盘空间。SDK 若提示未接受许可证，请阅读后按提示处理。启动超时可查看本次 `emulator.log`；`-accel-check` 失败需检查 Windows 虚拟化配置。后台播放、蓝牙和厂商省电策略仍需真机验证。

手动操作也须使用明确序列号，例如：

```powershell
& "$env:LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe" -s emulator-5580 shell input tap 200 300
```

参考：[模拟器命令行](https://developer.android.com/studio/run/emulator-commandline)、[Compose 测试](https://developer.android.com/develop/ui/compose/testing)。

## 真实服务器播放验收（显式启用）

`RealServerPlaybackTest` 默认跳过，需要专用模拟器和 `.env` 中的真实服务器配置。
它会使用真实 MainActivity 登录，并通过应用实际 PlaybackService/MediaController 验证
播放进度、暂停/续播、跳转、上一首/下一首、自动切歌、单曲循环、随机模式、后台播放、
熄屏完整播放一首歌、完整流缓存、下载及离线歌词/音频。
播放会正常更新服务端最近播放记录；不会修改收藏、删除歌曲或编辑用户歌单。

凭据通过 `adb shell run-as` 的标准输入写入应用私有 `files/real-server.env`，
测试读取后立即删除。不要通过 `am instrument -e`、命令行字符串或源码传递密码。
先运行普通回归，再运行完整真实服务器验收：

```bash
python3 tools/device/emulator.py test --show
python3 tools/device/real_server_test.py
```

脚本显式指定专用模拟器，在线阶段后关闭模拟器 Wi-Fi/蜂窝数据执行离线测试，
并在结束或失败时恢复原网络开关。可用 `--phase online` / `--phase offline` 单独重跑。
真实验收会在该专用模拟器保留登录状态和一首测试下载歌曲。
脱敏逐项结果在应用私有 `files/real-server-results.txt`；原始 instrumentation/logcat
和截图仅保存在已忽略的 `captures/private/`，分享前应检查隐私。

## 歌单管理与双语歌词验收

`PlaylistLyricsFeatureTest` 包含普通歌单创建、重命名、上下移动、删除确认、离线禁用，
以及翻译开关、偏移后歌词跳转和偏移重置；使用测试数据，不修改真实服务器。
`RealServerPlaylistTest` 默认跳过，需要模拟器已登录，显式传入 `-e real_playlists true`。
它仅创建两个带临时测试名称的歌单，验证改名、排序和添加歌曲后，在 finally 中删除自身创建的歌单，
并校验原有歌单顺序；不会编辑已有歌单。

```bash
"$ANDROID_HOME/platform-tools/adb" -s emulator-5580 shell am instrument -w -r \
  -e class io.github.nutea.anylisten.RealServerPlaylistTest -e real_playlists true \
  io.github.nutea.anylisten.debug.test/androidx.test.runner.AndroidJUnitRunner
```
