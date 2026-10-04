# 运行说明

如果只是想使用 App，直接到 [发行页](https://github.com/hikovo/haohaoxiangxiang/releases/tag/v2.0.0) 下载安装包就好。

源码提供功能代码与配置，**不包含角色图形和回看素材**。自行运行时，需要补充有权使用的资源；未补充资源的页面不等同于完整 App。

## 页面运行

需要 Node.js 22.12 或更新版本，以及 pnpm。

```sh
pnpm install --frozen-lockfile
pnpm check
pnpm build
pnpm dev --host 127.0.0.1
```

手机页面预览地址：`http://127.0.0.1:1420/?mobile=1`。

## 资源接入

- 页面图形与动画：`public`，文件名应与源码引用一致。
- 回看素材索引：`public/offline-blindbox/catalog.json`，结构包含 `rows` 与 `letters`。
- 网盘入口：`public/offline-blindbox/archive-links.json`。
- 功能说明书：`content/app-guide.json` 与 `public/offline-blindbox/guide.json`。
- 原生动画与通知图标：`plugins/mobile-pet/android/src/main/res`。
- 应用图标与启动资源：`src-tauri/icons` 与 `src-tauri/gen/android/app/src/main/res`。

请只使用有权使用与分发的资源，并遵守 [内容范围](SCOPE.md) 和 [使用许可](../LICENSE)。

## Android 安装包

除前端依赖外，需要 Rust、JDK 17、Android SDK / NDK 及 Tauri Android 工具链。补齐资源后，通过 `pnpm tauri android init` 生成当前机器的 Gradle 路径、包装器及版本配置，再进行 Android 构建。

自行分发的安装包需使用自己的签名；签名不同，通常不能直接覆盖已安装的版本。请在目标设备上确认功能可用。
