# 凑企鹅下载器（couqi-downloader）· 开源仓库描述

> 本文用于填写 GitHub 仓库的 **About 简介** 与 **README 首屏介绍**，可直接复制使用。
> 撰写时间：2026-10-10 ｜ 与《项目总结.md》口径一致（正文即真源）。

---

## 一、GitHub About（仓库描述，短句版）

**About（一句话简介，≤350 字符）：**

> 🐧 基于 Jetpack Compose + Kotlin 的 Android 多站点媒体下载器，6 个站点统一收编进一个 App：任务持久化、断点续传、Cronet 反爬、应用内更新。仅用于技术学习与个人研究。

**Topics（标签）：**

```
android  kotlin  jetpack-compose  downloader  media-downloader  material3  cronet  ffmpeg  hls  m3u8
```

**Website：** 留空或填 Release 页直链（`https://github.com/<user>/<repo>/releases/latest`）

---

## 二、README 首屏（完整项目介绍）

# 🐧 凑企鹅下载器 (couqi-downloader)

一个基于 **Jetpack Compose + Kotlin** 的 Android **多站点媒体下载器**，把原先散落在 Termux 的多个 Python 下载脚本（HentaiPaw / 禁漫 / cool18 …）统一收编进**一个 App**，提供一致的下载管理与任务体验。

> ⚠️ **免责声明（务必先读）**
> 本项目**仅供技术学习与个人研究**使用，**不提供、不存储、不中转任何受版权保护的内容**，仅提供技术工具。
> 使用者须自行遵守所在国家/地区法律法规及目标网站服务条款；**任何实际下载行为的合规责任由使用者自负**。
> 作者不对使用后果承担任何责任。**请于下载后 24 小时内删除。**
> 本软件**不面向 18 岁以下用户**，且**不上架任何应用商店**（仅侧载分发）。若权利人认为本仓库功能侵权，请通过 **Issue 联系**，将及时处理。

## ✨ 特性

- **多站点统一管理** —— 6 个站点共用一套下载引擎与任务中心
- **断点续传** —— 基于 HTTP Range 的分片下载，弱网可恢复
- **任务持久化** —— 任务历史 / 输入草稿本地保存，旋屏、切 Tab、重进 App 不丢失
- **Cronet 网络栈** —— 内置 Cronet，攻克部分站点的 403 / Cloudflare 挑战
- **HLS(m3u8) 下载** —— 内置 HLS 分片下载器 + ffmpeg 转封装
- **图片转 PDF** —— 漫画类站点支持图片重排与合并导出 PDF
- **应用内更新** —— 从 GitHub Release 直链检查并下载新版本
- **现代化 UI** —— Jetpack Compose + Material Design 3

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
| 网络 | 自研 HTTP（连接池）+ Cronet + HLS |
| 媒体 | ffmpeg-kit-min |

## 📁 项目结构

```
app/src/main/java/com/java/myapplication/
├── MainActivity.kt
├── data/          # 数据层（设置 / Cookie / 站点定义 / 任务持久化）
├── engine/        # 引擎层（下载调度 / HTTP / Cronet / HLS / 转 PDF / 诊断日志）
├── site/          # 各站点解析器（Jm / Jm91 / HentaiPaw / Cool18 / Pornhub / Twitter）
├── ui/            # Compose 界面与 ViewModel
└── ui/theme/      # 主题
```

## 🔧 构建

```bash
# 1. 配置本地 SDK 路径（local.properties，不入库）
echo "sdk.dir=/path/to/Android/sdk" > local.properties

# 2. 构建 debug 包
./gradlew assembleDebug

# 3. 发布包签名（在 local.properties 中配置，密钥库放 keystore/，均 .gitignore 排除）
#    MD_STORE_PASSWORD / MD_KEY_ALIAS / MD_KEY_PASSWORD
./gradlew assembleRelease
```

> 发布签名需同时开启 **v1 + v2 + v3**（红米 / MIUI 要求 v1）。

## 📄 开源许可

本项目基于 [MIT License](LICENSE) 开源。

- 你可自由使用、修改、分发、商用，**唯一义务是保留版权声明与许可声明**。
- 软件按「原样（AS IS）」提供，作者不对使用后果承担责任。
- ⚠️ **MIT 免的是「软件层面的责任」，不能免除「使用者自身行为的合规责任」**。

## ⚖️ 合规与隐私

- 本仓库**不提供、不存储任何受版权保护的内容**，仅提供技术工具。
- **架构中立**：本地下载、**无中转服务器**、**不采集/上传用户数据**。
- **零密钥**：仓库与 APK 中**不内置任何 cookie / API key / 密钥**。
- 如相关权利人有异议，请通过 **Issue 联系**，将及时处理。

---

## 三、Release 说明模板（发版时用）

```
## 凑企鹅下载器 vX.Y

### 变更
- …

### 安装
下载 `couqi-downloader.apk` 到手机安装（首次需允许「安装未知来源应用」）。

> ⚠️ 仅供技术学习与个人研究，下载后 24 小时内删除。使用者自担合规责任。

MD5: <填入>
```

---

## 四、与合规章节的对应（内部备忘）

| 仓库呈现 | 对应《项目总结》第 11 节风险缓释 |
|---|---|
| 顶部醒目免责区块 | 11.4 建议 1 |
| 「Issue 联系删除」 | 11.4 建议 2 |
| 「不面向 18 岁以下 / 不上架商店」 | 11.4 建议 3 |
| 「不存储、不中转、不采集」架构声明 | 11.4 建议 4 |
| 零密钥红线 | 11.4 已落地 |
