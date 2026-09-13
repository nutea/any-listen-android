# 第三方与来源

## 本项目许可

本项目为社区独立维护的 **非官方 Any Listen Android 客户端**。除单独声明的第三方内容外，代码与文档适用根目录 [LICENSE](../LICENSE)，版权及非官方声明见 [NOTICE](../NOTICE)。第三方组件保留各自许可证，本项目许可不替代其原有授权。

许可选择依据：所引用的 Any Listen 版本使用标题为 `Custom License based on AGPL v3.0` 的文本，并附加禁止商业使用的条款。本仓库沿用该完整文本，以保留当前分发的上游资源的限制；根目录与 `third_party/any-listen/LICENSE` 一致。这里不主张仅仅实现兼容协议就必然要求采用上游许可证。

该自定义文本不能用标准 `AGPL-3.0-only` / `AGPL-3.0-or-later` 标识替代。非商业限制不符合 [OSI 开源定义第 6 条](https://opensource.org/osd)，所以 README 使用“源码公开”的表述。摘要不替代许可证原文；本项目没有获得、也不声称能够授予上游商业许可。

## Any Listen：协议参考与图标

- 来源：[any-listen/any-listen](https://github.com/any-listen/any-listen)，标签 `webserver-v0.11.0-beta.1`，提交 `e4ef53a5094473687e2d142fb7b63a530436b982`。
- [该提交的许可证](https://github.com/any-listen/any-listen/blob/e4ef53a5094473687e2d142fb7b63a530436b982/LICENSE)；[本地完整副本](../third_party/any-listen/LICENSE)。
- HTTP、WebSocket/IPC 路径、字段和行为依据该版本源码阅读结果实现，客户端使用原生 Kotlin/Compose 实现。
- 启动图标 PNG 直接来自上游，SVG 作为参考保留；适配方式和具体文件路径见[图标来源说明](../third_party/any-listen/README.md)。不能把“原生重实现”理解为仓库完全不含上游资源。
- Any Listen 名称和图标属于相应权利人，本项目的名称、图标使用不表示官方身份或背书。

## message2call：帧格式参考

请求与响应帧格式参考 [lyswhut/message2call](https://github.com/lyswhut/message2call)，在 Kotlin 中重实现 REQUEST/RESPONSE；该 JavaScript 包不是 APK 的运行时依赖。

许可证为 MIT，Copyright (c) 2018 lyswhut；保留[完整许可副本](../third_party/message2call/LICENSE)，来源为[上游 LICENSE](https://github.com/lyswhut/message2call/blob/master/LICENSE)（2026-09-13 核对）。

## 依赖与构建工具

直接依赖坐标与声明版本见 [gradle/libs.versions.toml](../gradle/libs.versions.toml)。以下为主要组件来源索引，**不是最终 APK 所有传递依赖的完整许可清单**；发布时须以实际解析和打包的版本保留各组件的 LICENSE、NOTICE 和版权声明。

| 组件 | 用途 | 许可/来源 |
| --- | --- | --- |
| AndroidX / Compose / Media3 / Room / DataStore | 界面、播放、存储 | [AndroidX 源码](https://android.googlesource.com/platform/frameworks/support/)、[Media3](https://github.com/androidx/media)；以各组件 Apache-2.0 及随附文件声明为准 |
| Kotlin / kotlinx.coroutines / kotlinx.serialization | 语言、协程与序列化 | [Kotlin](https://github.com/JetBrains/kotlin)、[coroutines 1.10.2 LICENSE](https://github.com/Kotlin/kotlinx.coroutines/blob/1.10.2/LICENSE.txt)、[serialization](https://github.com/Kotlin/kotlinx.serialization) |
| OkHttp 4.12.0 | 网络请求 | [Apache-2.0](https://github.com/square/okhttp/blob/parent-4.12.0/LICENSE.txt) |
| Coil 2.7.0 | 图片加载 | [Apache-2.0](https://github.com/coil-kt/coil/blob/2.7.0/LICENSE.txt) |
| Gradle / Android Gradle Plugin / KSP | 构建工具 | [Gradle](https://github.com/gradle/gradle)、[AGP](https://android.googlesource.com/platform/tools/base/)、[KSP](https://github.com/google/ksp) |
| JUnit / Robolectric / AndroidX Test | 测试，不作为正式应用功能分发 | [JUnit 4](https://github.com/junit-team/junit4)、[Robolectric](https://github.com/robolectric/robolectric)、[AndroidX Test](https://github.com/android/android-test) |

## 首版运行时清单

[v0.1.0 运行时依赖清单](../third_party/runtime/README.md)记录正式构建实际解析的 111 个外部坐标、POM 声明及依赖内附带的许可/通知。完整 Apache-2.0 和 Google Protobuf BSD-3-Clause 文本一并保留。生成入口为 `:app:writeReleaseDependencies` 和 `tools/release/collect_licenses.py`。

## 分发说明

发布源码时保留根目录 `LICENSE`、`NOTICE`、本文件及 `third_party` 中的来源与许可。发布 APK 时同时提供匹配该版本的完整可构建源码、构建脚本和许可声明；不要只链接一个会持续变化的默认分支。具体要求以完整许可证为准，发布流程见 [RELEASE.md](RELEASE.md)。

服务器返回的音乐、封面和歌词由用户自行提供或取得授权，不属于本仓库软件许可授予的内容。
