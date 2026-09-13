# README 演示截图

这些 PNG 来自 API 36 Android 模拟器中运行的真实 Compose 界面组件。截图使用固定的虚拟歌曲、歌手、歌单、原创演示歌词、存储数字与保留示例域名 `music.example.com`；封面由测试代码通过 Canvas 绘制，没有复制音乐专辑封面。所有示例内容仅用于演示。

生成入口：`app/src/androidTest/java/io/github/nutea/anylisten/ReadmeScreenshotsTest.kt`。该测试不创建业务 ViewModel，不读取服务器音乐库，界面数据全部由测试夹具传入；不用真实设备的私人截图。只捕获应用内容区域，不含模拟器系统状态栏。

仓库原始代码及上述原创演示素材适用根目录 [LICENSE](../../LICENSE)。界面中的第三方组件仍遵循[第三方声明](../THIRD_PARTY.md)。

## 重新生成

在配置好的模拟器上构建并安装 debug 应用与 androidTest APK，然后执行：

```powershell
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest
adb -s emulator-5580 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5580 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5580 shell am instrument -w -e class io.github.nutea.anylisten.ReadmeScreenshotsTest io.github.nutea.anylisten.debug.test/androidx.test.runner.AndroidJUnitRunner
adb -s emulator-5580 pull /sdcard/Android/data/io.github.nutea.anylisten.debug/files/readme-screenshots/. docs/screenshots/
```

将设备序列号替换为自己的模拟器。测试通过后检查六张图片：library、playlist、player、lyrics、local、settings。公开截图只来自这个虚拟数据夹具，不从 `captures/private` 复制。
