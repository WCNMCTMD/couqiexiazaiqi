# App 开发前置框架（App Development Playbook）

> **这是什么**：一份**跨项目复用**的"开工前必读"。任何新 App（或对现有 App 做大改造）开始写代码之前，
> 都应先走完本流程。目的：**用一次结构化思考，避免一路补丁式返工**。
>
> **谁用**：AI 助手 + 用户共同使用。AI 负责**主动发起询问、主动发散、主动搜索**；用户负责**拍板**。
>
> **状态**：这是**活文档**。每做一次项目，就把新踩的坑、新问出的好问题，追加回本文件。

---

## 0. 铁律（Iron Rules）

1. **先问，再动手**。不清楚需求与环境，不写代码。
2. **先发散，再收敛**。至少覆盖下面 §2 的全部维度，逐条问"这个项目会不会遇到"。
3. **先搜索，再设计**。凡涉及平台限制、版本适配、权限、分发渠道，必须先查最新官方/社区资料，不凭记忆。
4. **先列清单，再实现**。把结论落成"环境适配矩阵"式的表格，逐项标状态。
5. **先留痕，再迭代**。所有决策、变更写进文档的"变更记录"，不删旧结论（保留演进史）。
6. **零隐私红线**：任何 App 都不得内置/打包用户的密钥、cookie、账号。凭证一律由用户运行时导入、隔离存储。

---

## 1. 开工前必问清单（Ask Before Building）

> AI 必须在写第一行代码前，向用户逐条确认（可批量提问）。能推断的先推断，但关键项要明说。

### 1.1 目标与范围
- [ ] 这个 App **到底解决什么问题**？一句话说清。
- [ ] **给谁用**？（只有自己 / 小范围 / 公开分发）——决定分发与更新方案。
- [ ] **明确不做什么**？（防止范围蔓延）
- [ ] 有无**对标 App**？想抄/想避开的点是什么？

### 1.2 平台与设备
- [ ] 目标平台？（Android / iOS / 桌面 / Web）
- [ ] **minSdk / targetSdk** 各是多少？→ 直接决定要适配哪些系统限制。
- [ ] 目标设备范围？（老机型 / 新机型 / 平板 / 折叠屏 / 车机）
- [ ] 是否需要**离线可用**？是否依赖网络？

### 1.3 数据与隐私
- [ ] App **会碰到哪些用户敏感数据**？（账号/cookie/位置/通讯录/文件）
- [ ] 这些数据**存哪、怎么加密、会不会上传**？
- [ ] 是否有**合规红线**（上架商店？涉及受监管内容？）

### 1.4 分发与更新（重点！常被忽略）
- [ ] 上架商店吗？（Google Play / 应用宝 / 华为 / 小米…）→ 决定签名、合规、审核约束
- [ ] 还是**侧载（sideload）分发**？→ 决定"更新机制"与"未知来源安装"处理
- [ ] **日后怎么更新？** → 见 §4 更新机制
- [ ] **签名密钥**由谁保管、是否固定？（固定签名是覆盖安装/OTA 的硬前提）

### 1.5 技术与约束
- [ ] 有无**第三方依赖限制**？（本项目：零第三方网络库，全用 java.net）
- [ ] 有无**性能/体积**约束？（APK 大小、内存、冷启动）
- [ ] 构建环境？（本地 / CI / proot 容器）
- [ ] 目标**语言/框架**？（Kotlin+Compose / 原生 / RN…）

---

## 2. 发散思维维度（Divergence Dimensions）

> 对每个维度，问一句："**这个 App 会不会遇到？遇到了怎么办？**"
> 这就是"环境适配矩阵"的四个大类，任何 App 都适用。

### 维度 A：网络
弱网/丢包 · 蜂窝↔WiFi 切换 · 计费网络 · 代理/VPN（含分应用代理）· DNS 污染 ·
IPv6-only · 防盗链 · CDN/Cloudflare 盾 · 限流 429 · 断点续传 · TLS 证书 · 域名变更 · 接口改版

### 维度 B：设备/系统
系统版本跨度 · **前台服务类型强校验（Android14+）** · 通知权限（Android13+）· 分区存储 ·
厂商 ROM 杀后台 · 电池优化/Doze · 后台广播/Intent 限制 · 权限运行时申请 · 刘海/挖孔/折叠屏 · 深色模式 · 字体缩放

### 维度 C：使用习惯
输入形态多样 · 状态持久化（退出不丢）· 断点/重试 · 重复操作去重 · 文件命名冲突 · 存储空间 ·
多任务并发 · 误操作可撤销

### 维度 D：分发/更新
商店 or 侧载 · OTA 更新 · 签名固定 · 未知来源安装 · 版本降级保护 · 更新日志

---

## 3. 必做搜索清单（Search Before Design）

> 每类项目**至少**搜这些，且**要搜最新**（平台规则年年变）。

1. **目标平台的"当年适配要点"**：如 `Android 14/15 前台服务适配`、`Android 15 分区存储变化`。
2. **权限清单核对**：网络类必查 `INTERNET`；后台类必查 `FOREGROUND_SERVICE*`；通知类必查 `POST_NOTIFICATIONS`。
   —— **血泪教训：本项目一开始漏了 `INTERNET`，导致所有网络请求"秒失败"。**
3. **分发渠道规则**：若上架，查该商店的最低 targetSdk、隐私政策、敏感权限审核。
4. **更新机制现状**：搜 `应用内自动更新 2024/2025`、`自签名 APK 覆盖安装`。
5. **对标生态**：主流同类 App 用了什么框架（下载类：OkDownload / FileDownloader / 系统 DownloadManager）。
6. **安全红线**：搜"凭证泄露案例"，确认自己的存储方案是否达标。

---

## 3.5 权限决策（Permission Strategy）—— 框架级决策

> 用户明确要求：**权限必须开工前就规划，代码 + 文档同步落地**。
> 权限分两类，处理方式完全不同，绝不可混为一谈。

### 两类权限

| 类型 | 例子 | 怎么获得 | 用户可见性 |
|---|---|---|---|
| **安装即得（normal / 清单声明即可）** | `INTERNET`、`ACCESS_NETWORK_STATE`、`FOREGROUND_SERVICE`、`WAKE_LOCK` | 只需在 `AndroidManifest.xml` 声明，**装上即有，不弹窗** | 用户无感（但商店/审查可能看） |
| **运行时申请（dangerous）** | `POST_NOTIFICATIONS`（Android13+）、`CAMERA`、`ACCESS_FINE_LOCATION`、`READ_MEDIA_*` | 清单声明 **且** 在**合适时机**用 `ActivityResultContracts.RequestPermission` **弹窗问用户** | 用户必须点"允许/拒绝" |

> ⚠️ **最常见的坑**：以为"声明了就完事"。`POST_NOTIFICATIONS` 在 Android 13+ 是 **dangerous 权限**，
> 光声明**不会**弹窗，**必须**代码里主动请求；不请求 → 通知直接不显示（前台服务也会静默变无通知）。

### 权限决策表（模板）

| 权限 | 类型 | 本 App 是否需要 | 何时请求 | 拒绝后怎么办 |
|---|---|---|---|---|
| `INTERNET` | 安装即得 | ✅ 必需 | — | 无法降级（没它 App 无意义） |
| `ACCESS_NETWORK_STATE` | 安装即得 | ✅（网络状态检测） | — | — |
| `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_DATA_SYNC` | 安装即得 | ✅（前台下载服务，Android14+ 强校验） | — | — |
| `WAKE_LOCK` | 安装即得 | ✅（下载期间保持唤醒） | — | — |
| `POST_NOTIFICATIONS` | **运行时** | ✅（Android13+ 进度通知） | **首次"开始下载"时** | **不阻塞下载**，仅退化为"无通知"，并在设置页留"去授权"入口 |
| `READ/WRITE_EXTERNAL_STORAGE` | 运行时 | ❌ 不需要 | — | 存储走 **SAF**（系统在选择目录时自动授权），无需申请 |
| `CAMERA`/`LOCATION`/`CONTACTS` | 运行时 | ❌ 不使用 | — | — |

### 运行时权限标准流程（代码规范）
1. `rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions())` 注册回调；
2. **合适时机**触发（例：首次执行需要它的动作时），**不要**一进 App 就糊一堆弹窗；
3. 先 `ContextCompat.checkSelfPermission` 判断，已授权直接走；
4. **拒绝要优雅降级**（核心功能不依赖它就别阻塞）；
5. 用 `shouldShowRequestPermissionRationale` 判断是否要**解释**（用户曾拒过一次）；
6. 若"拒绝且不再询问"，引导去**系统设置**手动开（`ACTION_APPLICATION_DETAILS_SETTINGS`）。
7. 统一收口到一个 `PermissionHelper` 工具类，避免散落各处。

> 本项目落地：`util/PermissionHelper.kt` + DownloadTab 的 `ensureNotifPermissionThen`。
> 策略：**授权、拒绝、系统版本低于 13（不弹）三种情况，都继续下载**——通知只是锦上添花。

---

## 4. 更新机制（Update Strategy）—— 框架级决策

> 与具体 App 无关，属通用能力。**必须在开工时就定**，因为它反向约束签名方案。

| 方案 | 做法 | 前置条件 | 适用 |
|---|---|---|---|
| A 手动替换 | 新版 APK 放固定位置，手动装 | 无 | 极早期自用 |
| B 静态清单 + 系统下载器 | App 拉 `version.json`（版本号+APK URL+changelog+minSupported），有新版→`DownloadManager` 下载→`FileProvider` 拉起安装器 | 固定 release 签名 + 一处公网静态文件 | 自用/小范围（**推荐默认**） |
| C GitHub Releases | 拉 `releases/latest` | 同上 + GitHub 可访问 | 半公开分发 |
| D 自建更新服务 | 对象存储放清单+APK | 同上 + 维护成本 | 正式对外 |

**公共冗余设计**（无论哪条）：
1. 清单地址**可配置、多源**（主/备），失败静默跳过不打扰用户；
2. APK 下载**走系统 DownloadManager**（天然断点续传+通知）；
3. `versionCode` 比对 + **签名校验**（防伪包）；
4. 支持 `minSupported` 强制更新；
5. **固定 release keystore，离线备份，口令不进代码库。**

> 本项目当前决策：**选 B，已实现**（见 §4.1 落地）。
> 覆盖安装的前提是**固定 release 签名**——签名未定时，OTA 只能完成"下载新版"，安装需手动卸载重装。

### 4.1 本项目落地（方案 B 实现要点）
- `util/Updater.kt`：**零第三方依赖**（手写极简扁平 JSON 字段提取器，不引 org.json/kotlinx.serialization）。
  流程：拉静态清单 → 比对 `versionCode` → 系统 `DownloadManager` 下载 APK → `FileProvider` 拉起安装器。
  含「安装未知来源」授权引导（`ACTION_MANAGE_UNKNOWN_APP_SOURCES`）与 `ACTION_VIEW`/`ACTION_INSTALL_PACKAGE` 双兜底。
- `AndroidManifest.xml`：`REQUEST_INSTALL_PACKAGES` + `FileProvider`（authorities=`${applicationId}.fileprovider`）。
- `res/xml/file_paths.xml`：只暴露外部私有 `/update` 目录，**不暴露整个存储**。
- `data/AppSettings.kt`：OTA 设置项；`DEFAULT_UPDATE_URL` **留空**（不硬编码任何私人地址/密钥）。
- `ui/SettingsTab.kt`：「检查更新」卡片（填地址 / 检查 / 下载后安装 / 自动检查开关）。
- 清单格式（供托管 `version.json`）：
  ```json
  { "versionCode": 2, "versionName": "1.1",
    "url": "https://.../app-release.apk",
    "changelog": "修复…", "minSupported": 1 }
  ```

### 4.2 架构原则（本轮沉淀）：执行与 UI 状态解耦
> 下载/上传等**长任务**，执行逻辑**不应放在 ViewModel**（ViewModel 生命周期短于任务）。
- **Service = 唯一执行者**（前台服务 + 通知保活）；**ViewModel = 只持 UI 状态**（列表/草稿/历史）。
- 两者通过**进程内事件总线**（本项目 `DownloadBus`）单向通信；Service 在任务开始/结束**直接落盘**。
- 好处：Activity 销毁/进程被杀后，任务状态可从磁盘恢复（`loadTasks` 把 RUNNING 态恢复为 PAUSED = 中断标记）。

---

## 5. 落地产物（Deliverables）

开工前/中，必须产出并持续维护这些文档：

| 文档 | 作用 |
|---|---|
| `APP_DEV_PLAYBOOK.md`（本文件） | 通用元流程 |
| `ENV_MATRIX.md` | 本项目的环境适配矩阵（逐项状态） |
| `REBUILD.md` | 如何从零重建本项目（工具链、步骤） |
| `CHANGELOG.md`（建议） | 版本变更记录（供 OTA 的 changelog 字段用） |

---

## 6. 标准开工流程（SOP）

```
1. 走 §1 询问清单  → 用户逐项回答/拍板
2. 走 §2 发散维度  → 生成初稿 ENV_MATRIX
3. 走 §3 搜索清单  → 补齐平台限制/权限/分发规则
3.5. 定 §3.5 权限方案 → 区分"安装即得"vs"运行时申请"，列出"何时请求/拒绝降级"
4. 定 §4 更新机制  → 顺带定签名方案
5. 产出 §5 文档，冻结"最小可行架构"
6. 再开始写第一行代码
7. 每次上线/大改，回填文档"变更记录"（代码与文档同步）
```

---

## 变更记录
- 初版：因"漏 INTERNET 权限 → 网络秒失败"事故而建立本通用流程。含询问清单/发散维度/搜索清单/更新机制/SOP。
- 追加：确立"前台服务重构"为下载类 App 的必做架构项（见 §2 维度 B 加粗项）。
- 追加：新增 **§3.5 权限决策**专节（安装即得 vs 运行时申请、权限决策表、运行时权限标准流程）。
  起因：用户指出"App 通常会向用户询问权限，而此前文档只在代码里提过一句"。
  明确要求：**权限规划必须代码 + 文档同步落地**。同时把"代码与文档同步"补进 SOP 第 7 步。
- 本项目落地：`util/PermissionHelper.kt`、Manifest 6 项权限、DownloadTab 首次下载请求 `POST_NOTIFICATIONS`。
- 追加：**§4.1 本项目落地**（方案 B 已实现：`util/Updater.kt` 零依赖清单解析 + DownloadManager + FileProvider 安装）、
  **§4.2 架构原则**（长任务的执行与 UI 状态解耦：Service 执行 + ViewModel 只持状态 + 事件总线 + 任务落盘）。
  起因：用户要求"开始重构 + 方案 B 可以开始了"，两者并行推进。对应本项目已把下载调度从 ViewModel 迁入
  `DownloadService`（`TaskStore.upsertTaskStatus` 落盘），并完成 OTA 全链路（Manifest/资源/Updater/设置页），
  完整构建通过（12M APK）。更新机制结论由"选 B，放最后实现"更新为"选 B，已实现"。