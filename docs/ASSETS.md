# 构建说明

仓库提供应用逻辑与配置，未包含角色图形、动画、照片、视频、音频及 PDF。素材目录使用空数据结构；网页检查通过不等于完整视觉演示或 Android 发行版重建成功。

## 资源接入

- 页面图形与动画：`public`，文件名应与源码引用一致。
- 回看素材索引：`public/offline-blindbox/catalog.json`，结构包含 `rows` 与 `letters`。
- 网盘入口：`public/offline-blindbox/archive-links.json`。
- 功能说明书：`content/app-guide.json` 与 `public/offline-blindbox/guide.json`。
- 原生动画与通知图标：`plugins/mobile-pet/android/src/main/res`。
- 应用图标与启动资源：`src-tauri/icons` 与 `src-tauri/gen/android/app/src/main/res`。

自行补充资源时，请确认使用与分发权限。用户插入便签的图频属于本地数据，不是内置素材。

## Android 构建准备

除前端依赖外，需要 Rust、JDK 17、Android SDK / NDK 及 Tauri Android 工具链。补齐资源后，通过 `pnpm tauri android init` 生成当前机器的 Gradle 路径、包装器及版本配置，再进行 Android 构建。

不要复制他人的本机路径或签名配置。自行分发安装包需使用自己的签名；不同签名通常不能直接覆盖已安装版本。完整重建还需自行提供素材数据，并完成网页、原生编译与设备验证。

Release 中的 2.0.0 APK 是已通过作者真机验收的完整发行包，分发资源范围与本仓库源码不同。
