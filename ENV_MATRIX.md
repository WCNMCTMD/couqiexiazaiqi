# 环境适配矩阵（Environment Adaptation Matrix）

> 目的：把「多站点下载器」在真实使用中会遇到的各种环境差异**穷举**出来，
> 逐项标注：现状 / 建议做法 / 优先级 / 冗余策略。
>
> 这不是一次性任务清单，而是**持续维护的活文档**：每发现一类新环境，就追加一行。
> 状态标记：✅ 已覆盖 · 🟡 部分覆盖 · ❌ 未覆盖 · 🔵 需实测确认

---

## 一、网络层（Network）

| # | 场景 | 说明 / 影响 | 建议做法 | 优先级 | 状态 |
|---|------|------------|---------|-------|------|
| N1 | 弱网 / 高延迟 / 高丢包 | 超时误判、反复失败 | 超时可配 + 指数退避重试 + 「弱网模式」开关 | P1 | ✅ |
| N2 | 蜂窝 ↔ WiFi 切换中断 | 下载中断、连接重置 | 断点续传（见 N10）；网络切换监听后自动重试 | P1 | ❌ |
| N3 | 按流量计费网络 | 用户不想用流量下大文件 | 读取 `NOT_METERED`，在设置给「仅 WiFi 下载」开关 | P2 | 🟡（已能读状态，未接开关）|
| N4 | 代理 / VPN | 墙外站点需代理；分应用代理时只有部分 App 走代理 | 只**读取并提示**，绝不内置；提示"若失败先确认是否分应用代理漏勾本 App" | P1 | 🟡（有自检，未在失败路径联动）|
| N5 | DNS 污染 / 被劫持 | 连不上或连到错误 IP | 提示用 DoH/VPN；可选接入自定义 DNS（进阶） | P2 | ❌ |
| N6 | IPv6-only 网络 | 部分运营商；旧代码偏好 IPv4 会失败 | 依赖系统双栈即可，无需特判；异常时提示 | P3 | 🟡 |
| N7 | 站点防盗链 | 413 图片需要正确 Referer/Origin | 各引擎带 referer（已做）；补充 Origin 尝试 | P1 | 🟡 |
| N8 | Cloudflare 5 秒盾 / JS 挑战 | 返回 403 挑战页而非内容 | 识别特征串，报 `PARSE/HTTP` 并提示"可能被反爬" | P2 | ❌ |
| N9 | 限流 429 / 并发上限 | 批量下载被封 | 可配并发（已有）；429 时自动降速/暂停 | P2 | 🟡 |
| N10 | 断点续传 | 下到一半退出/断网，重来浪费 | 记录已完成文件，跳过已下载；单文件 Range 续传 | P1 | ❌ |
| N11 | TLS 证书异常 | 自签 / 过期 / 设备时间错 → 握手失败 | 归类为 NETWORK 并提示"检查系统时间/证书" | P2 | 🟡 |
| N12 | 站点域名变更 | 老域名 404/超时 | 域名可自定义（91 已做）；扩展到其它站点 | P1 | 🟡 |
| N13 | 站点改版 / 接口变化 | 解析失败 | 明确报 `PARSE` + 提示；文档记录各站点接口 | P2 | 🟡 |

## 二、设备 / 系统层（Device / OS）

| # | 场景 | 说明 / 影响 | 建议做法 | 优先级 | 状态 |
|---|------|------------|---------|-------|------|
| D1 | **Android 14+ 前台服务类型强校验** | targetSdk≥34 必须声明 `foregroundServiceType` + 权限，否则 `SecurityException`/崩溃 | 下载服务声明 `foregroundServiceType="dataSync"` + `FOREGROUND_SERVICE_DATA_SYNC` 权限；注意 dataSync 有时长上限，超时需降级 | **P0** | ✅（已建 `DownloadService` + Manifest 声明，含 try/catch 降级）|
| D2 | Android 13+ 通知权限 | 前台服务通知不显示 | 运行时请求 `POST_NOTIFICATIONS` | P1 | ✅（`PermissionHelper` + DownloadTab 首次下载时请求，拒绝不阻塞）|
| D3 | 分区存储（Scoped Storage, API 29+/30+） | 不能随意写外部公共目录 | 已用 SAF 目录 + 应用私有目录兜底 | P0 | ✅ |
| D4 | `MANAGE_EXTERNAL_STORAGE` | 部分用户想直接写 /sdcard | 优先 SAF；不申请全盘权限（上架合规） | P2 | 🟡 |
| D5 | 厂商 ROM 后台限制（MIUI/EMUI/ColorOS） | 息屏后被杀，下载停 | 引导用户加白名单/关省电；前台服务 + 用户可见通知 | P1 | ❌ |
| D6 | 电池优化 / Doze | 后台被冻结 | 请求忽略电池优化（可选，引导式） | P2 | ❌ |
| D7 | Android 版本跨度 24–35 | 新 API 需版本判断 | 代码里做 `Build.VERSION.SDK_INT` 分支；构建 minSdk24 | P0 | 🟡（需系统性排查）|
| D8 | Android 14+ 隐式 Intent / 广播限制 | 后台启动、静态注册广播受限 | 用前台服务 + 显式 Intent | P1 | ❌ |
| D9 | 安装未知来源 | 装更新 APK 被拦 | `REQUEST_INSTALL_PACKAGES` + 引导授权 | P1 | ❌ |

## 三、使用习惯层（Usage）

| # | 场景 | 说明 / 影响 | 建议做法 | 优先级 | 状态 |
|---|------|------------|---------|-------|------|
| U1 | 输入五花八门 | 链接/短链/纯 ID/关键词 | `InputParser` 统一规范化 | P0 | ✅ |
| U2 | 断点续传 / 已下载跳过 | 重复任务浪费流量 | 与 N10 同一套本地记录 | P1 | ❌ |
| U3 | 文件名非法/超长/冲突 | 写盘失败 | `sanitizeFileName` + 冲突加序号 | P2 | 🟡 |
| U4 | 超大合集占满空间 | OOM/存储爆 | 流式下载（已做）；下载前提示剩余空间 | P2 | 🟡 |
| U5 | 退出重进丢状态 | 体验差 | 草稿/历史/任务持久化 | P0 | ✅ |
| U6 | 想重试失败任务 | — | 任务页重试/删除/清空 | P1 | ✅ |

## 四、分发 / 更新层（Distribution / Update）

| # | 场景 | 说明 / 影响 | 建议做法 | 优先级 | 状态 |
|---|------|------------|---------|-------|------|
| S1 | **应用内检查更新（OTA）** | 不想每次改都手动发 APK | 见 §更新方案 | P1 | ✅（已按方案 B 实现 `util/Updater.kt` + 设置页入口）|
| S2 | 自签名 / debug 签名 | 换签名后无法覆盖安装 | 固定 keystore；debug↔release 签名不同会冲突 | P1 | 🟡（机制已就绪，**release keystore 口令/别名待用户提供**）|
| S3 | 从非商店安装 | 用户被"未知来源"拦 | 引导开启 + 显式 Intent | P1 | ✅（`REQUEST_INSTALL_PACKAGES` + `Updater.openInstallPermissionSettings` 引导授权）|
| S4 | 版本落地页 / 更新日志 | 用户不知更新了什么 | 简单静态更新说明页 | P2 | ❌ |

---

## 五、更新机制方案（重点讨论项）

核心问题：**日后想更新软件，怎么更新？**

前提约束（本项目）：
- 不上架任何应用商店（含 Google Play）；
- 不能把用户隐私/密钥打包进 APK；
- 用户可能只有 APK 文件本身，没有复杂后端。

### 方案 A：手动替换（基线）
- 新版本 APK 放到固定下载位置（如 `/sdcard/Download/multi-downloader/`），用户手动安装。
- 优点：零服务器、零依赖。缺点：每次要人工分发、手动装。
- 适用：早期自用阶段。**当前就是这一档。**

### 方案 B：App 内"检查更新"（推荐，轻量）
- App 启动或点按钮时，请求一个**静态版本清单文件**（如放在 GitHub Raw / Gitee / 任意可公网访问的 `version.json`）：
  ```json
  { "versionCode": 12, "versionName": "1.2.0",
    "url": "https://.../app-release.apk",
    "changelog": "修复…", "minSupported": 5 }
  ```
- 比对本地 `versionCode` → 有新版则弹窗 → 用系统 `DownloadManager` 下载 APK → `FileProvider` + 安装 Intent 拉起安装器。
- **关键点**：
  - 用 **debug 之外的固定 release keystore** 签名，保证可覆盖安装；
  - 清单文件里**不含任何隐私**，只是版本号 + 公网 APK 链接；
  - Android 8+ 安装需 `REQUEST_INSTALL_PACKAGES` 权限；
  - `minSupported` 可实现**强制更新**（低于此版本必须更新）。
- 优点：改动小、无后端、可自用可小范围分发。缺点：需要一处公网静态文件托管。

### 方案 C：GitHub Releases（推荐给"要分发"的场景）
- 每次发版把 APK 传到 GitHub Release，App 请求 GitHub API 的 releases/latest 取 `tag_name` 与 APK `browser_download_url`。
- 优点：免费、天然带版本与更新日志、可回滚。缺点：国内访问 GitHub 不稳（正好与 N4 代理问题叠加）。
- 可配合"镜像地址"做冗余：清单里给主/备两个下载 URL。

### 方案 D：自建最小更新服务（重）
- 一个静态文件服务器/对象存储（OSS/COS/Cloudflare R2）放 `version.json` + APK。
- 优点：可控。缺点：要维护。**除非要正式对外，否则不必要。**

### 冗余设计建议
1. **清单地址可配置且多源**：主源 + 备用源，任一可达即可；都不可达就静默跳过（不打扰用户）。
2. **下载走系统 DownloadManager**：天然支持断点续传、通知栏进度、跨机型稳。
3. **签名固定**：先确定一个 release keystore 并**离线备份**（口令由用户保管，不进代码库）。
4. **降级保护**：`versionCode` 比对 + 签名校验（`PackageManager.getPackageArchiveInfo` 校验签名一致才允许安装），防止装到伪造包。
5. **失败不打扰**：检查更新失败不弹错，只在用户主动点"检查更新"时才明确提示。

> 结论倾向：**先用方案 B（静态 version.json + 系统下载器）**，地址留在设置里可改；
> 若日后要公开分发再升级到方案 C（GitHub Releases）。

---

## 六、待用户拍板的点
1. 更新机制选 A / B / C 哪条路线？（默认建议 B，地址可配置）
2. 是否接受"需要一个公网静态文件"（version.json + APK）？还是纯本地手动替换？
3. release keystore 的口令与别名是否现在确定（固定签名是 OTA 的前提）？

---
（文档会随实际实现持续更新；每完成一项就把状态改为 ✅ 并在此节下方追加变更记录。）

## 变更记录
- 初版：建立网络/设备/使用/分发四层矩阵 + 更新机制四方案讨论。
- 追加：**P0 血泪项——Manifest 原先未声明 `INTERNET` 权限**（网络请求硬前提，缺失即"秒失败"）。
  已补入 `INTERNET` / `ACCESS_NETWORK_STATE` / `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_DATA_SYNC`
  / `POST_NOTIFICATIONS` / `WAKE_LOCK`。
- 更新：D1（前台服务类型）→ ✅，新建 `engine/DownloadService.kt`（dataSync 前台服务，
  协程信号量并发 + 进度通知 + try/catch 降级），Manifest 声明 `<service ... foregroundServiceType="dataSync">`。
- 更新：D2（通知权限）→ ✅，新增 `util/PermissionHelper.kt`，DownloadTab 首次下载时
  `ensureNotifPermissionThen` 请求 `POST_NOTIFICATIONS`（授权/拒绝/低于13 均不阻塞下载）。
- 追加：新建 `engine/DownloadBus.kt`（进程内事件总线），为"下载调度从 ViewModel 迁到 Service"铺路。
- 用户决策：更新机制**选定方案 B**（静态 version.json + 系统 DownloadManager），**放到最后实现**。
- **架构重构（本轮）**：下载调度由 `DownloadViewModel` 迁入 `engine/DownloadService.kt`。
  ViewModel 退化为 UI 状态持有者（任务/草稿/历史/站点）并订阅 `DownloadBus`；Service 成为唯一执行者，
  在 RUNNING/DONE/FAILED 三态直接经 `TaskStore.upsertTaskStatus` 落盘。二者生命周期解耦，
  Activity 销毁重进从磁盘恢复（`loadTasks` 把 RUNNING/QUEUED 恢复为 PAUSED = 中断标记）。完整构建通过。
- **方案 B 落地（本轮）**：更新机制已实现并编译/构建通过。
  - `AndroidManifest.xml`：补 `REQUEST_INSTALL_PACKAGES`、声明 `androidx.core.content.FileProvider`
    （authorities=`${applicationId}.fileprovider`）。
  - 新建 `res/xml/file_paths.xml`：暴露外部私有 `/update` 目录（避免暴露整个存储）。
  - 新建 `util/Updater.kt`：**零第三方依赖**（手写极简扁平 JSON 字段提取器），
    流程 = 拉取静态清单 → 比对 versionCode → 系统 `DownloadManager` 下载 APK → `FileProvider` 拉起安装器；
    含「安装未知来源」授权引导、`ACTION_VIEW`/`ACTION_INSTALL_PACKAGE` 双兜底。
  - `data/AppSettings.kt`：新增 OTA 设置项（清单地址/自动检查/上次检查时间），
    **`DEFAULT_UPDATE_URL` 留空**——App 不硬编码任何私人地址/密钥（安全红线）。
  - `ui/SettingsTab.kt`：新增「检查更新」卡片（填地址、检查、下载后「安装新版」、自动检查开关）。
  - 状态：S1 ✅ / S3 ✅ / S2 🟡——覆盖安装仍依赖**固定 release keystore**（口令与别名待用户提供），
    未提供时 OTA 只能停在「下载新版」，需手动卸载旧版再装。
