# 开发指南

## 构建与调试

技术栈：Kotlin、Jetpack Compose、Media3、Room、OkHttp、Coil、DataStore。

需要 **JDK 17**、Android SDK Platform **36**，以及可访问 Gradle 依赖仓库的网络。仓库自带 Gradle Wrapper。

1. 克隆仓库：

   ```bash
   git clone https://github.com/nutea/any-listen-android.git
   cd any-listen-android
   ```

2. 使用 Android Studio 打开项目并配置 SDK，或在根目录创建 `local.properties`：

   ```properties
   # 替换为本机 Android SDK 的实际路径
   sdk.dir=C:/Users/YOU/AppData/Local/Android/Sdk
   ```

3. 构建调试 APK：

   ```powershell
   # Windows PowerShell，JAVA_HOME 指向 JDK 17
   .\gradlew.bat :app:assembleDebug
   ```

   ```bash
   # macOS / Linux
   ./gradlew :app:assembleDebug
   ```

产物位于 `app/build/outputs/apk/debug/app-debug.apk`。正式包名为 `io.github.nutea.anylisten`，调试包为 `io.github.nutea.anylisten.debug`，可并存。

连接已开启 USB 或无线调试的设备后，可以运行：

```powershell
.\gradlew.bat :app:installDebug
```

模拟器配置与自动调试见 [tools/device/README.md](../tools/device/README.md)。Release 签名和分发说明见 [docs/RELEASE.md](RELEASE.md)。

## 测试

```powershell
.\gradlew.bat :core:model:test :core:data:testDebugUnitTest :app:lintDebug :app:assembleDebugAndroidTest
# 需要连接设备或启动模拟器
.\gradlew.bat :app:connectedDebugAndroidTest
```

macOS / Linux 将 `.\gradlew.bat` 替换为 `./gradlew`。协议探针的配置和执行方法见 [tools/probe/README.md](../tools/probe/README.md)；凭据只放环境变量或本地 `.env`。


## 更多文档

- [架构](ARCHITECTURE.md)
- [协议](api/PROTOCOL.md) / [能力矩阵](api/COMPATIBILITY.md)
- [测试与发布](TEST_RELEASE.md)
- [界面截图生成](screenshots/README.md)
