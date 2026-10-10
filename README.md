# 凑企鹅下载器 (couqi-downloader)

一个基于 **Jetpack Compose + Kotlin** 的 Android 多站点媒体下载器。

> ⚠️ **免责声明（务必先读）**：本项目仅供**技术学习与个人研究**使用。本项目**不提供、不存储、不中转任何受版权保护的内容**，仅提供技术工具。使用者需自行遵守所在国家/地区的法律法规，以及目标网站的服务条款；**任何实际下载行为的合规责任由使用者自负**。开发者不对任何使用后果承担责任。若您所在地区法律禁止访问/下载相关内容，请勿使用本项目。**请于下载后 24 小时内删除。** 本软件**不面向 18 岁以下用户**，且**不上架任何应用商店**（仅侧载分发）。如权利人认为本仓库功能侵权，请通过 **Issue 联系**，将及时处理。

> 📌 **另请阅读：[NOTICE.md](NOTICE.md)** —— 关于本项目的**开发方式（AI 生成）、目标机型（以红米 / MIUI 为主测环境）与通用性说明**。

## ⬇️ 下载

| 项 | 地址 |
|---|---|
| 当前版本 | **v1.0**（`versionCode 1`） |
| Release 页 | https://github.com/WCNMCTMD/couqiexiazaiqi/releases/tag/v1.0 |
| APK 直链 | https://github.com/WCNMCTMD/couqiexiazaiqi/releases/download/v1.0/couqi-downloader-v1.0.apk |
| 更新清单 `version.json` | https://raw.githubusercontent.com/WCNMCTMD/couqiexiazaiqi/main/version.json |

> APK 体积约 22.8 MB，校验值 MD5：`fc63ced6955b5d63f04a963097dfad94`（仅 arm64-v8a）。

## ✨ 特性

- **多站点支持**：内置 6 个站点的解析与下载逻辑，统一的下载管理体验
- **现代化 UI**：Jetpack Compose + Material Design 3
- **任务持久化**：任务历史 / 输入草稿本地保存，旋屏、切 Tab、重进 App 不丢失
- **断点续传**：基于 Range 的分片下载，弱网可恢复
- **应用内更新**：从 GitHub Release 直链检查并下载新版本

## 📦 已支持站点（均真机验证通过）

| # | 站点 | 下载器 | 说明 |
|---|---|---|---|
| 1 | 禁漫天堂 | `JmDownloader.kt` | 下载 + 图片解密重排 + 转 PDF 全链路 |
| 2 | 91 | `Jm91Downloader.kt` | — |
| 3 | HentaiPaw | `HentaiPawDownloader.kt` | 403 由 Cronet 攻克 |
| 4 | cool18（禁忌书屋） | `Cool18Downloader.kt` | — |
| 5 | PornHub | `PornhubDownloader.kt` | HLS + ffmpeg 转封装（直链有时效） |
| 6 | Twitter / X | `TwitterDownloader.kt` | 以 Syndication API 为主链路，免登录取 1080P |

> 站点解析逻辑集中在 `site/` 目录，抽象为统一接口，便于扩展新站点。

## 📱 技术栈

| 项 | 值 |
|---|---|
| 语言 | Kotlin (100%) |
| UI | Jetpack Compose + Material 3 |
| 构建 | AGP 9.0.0 / Gradle 9.1.0 / Kotlin 2.3.10 |
| SDK | compileSdk 35 / minSdk 24 / targetSdk 35 |

## 📁 项目结构

```
app/src/main/java/com/java/myapplication/
├── MainActivity.kt
├── engine/        # 下载引擎（DownloadManager / TaskStore / DownloadBus）
├── site/          # 各站点解析器
├── ui/            # Compose 界面与 ViewModel
└── ui/theme/      # 主题
```

## 🔧 构建

```bash
# 1. 配置本地 SDK 路径（local.properties，不入库）
echo "sdk.dir=/path/to/Android/sdk" > local.properties

# 2. 构建 debug 包
./gradlew assembleDebug
```

签名配置（**发布包**）：在 `local.properties` 中加入 `MD_STORE_PASSWORD` / `MD_KEY_ALIAS` / `MD_KEY_PASSWORD`，密钥库放 `keystore/`（均已 `.gitignore` 排除）。

## 📄 开源许可

本项目基于 [MIT License](LICENSE) 开源。

- 你可自由使用、修改、分发、商用，**唯一义务是保留版权声明与许可声明**。
- 软件按「原样（AS IS）」提供，作者不对使用后果承担责任。
- 但请注意：**MIT 免的是「软件层面的责任」，不能免除「使用者自身行为的合规责任」**。使用者须自行评估并承担相应风险。

## ⚖️ 合规提示

本仓库不提供、不存储任何受版权保护的内容，仅提供技术工具。任何第三方内容与内容合法性均与本项目无关。如相关权利人有异议，请通过 Issue 联系，将及时处理。
