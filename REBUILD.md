# 重构说明（ARM64 proot 环境下构建 APK）

> 本文档记录：**在 Android 手机内的 proot Ubuntu(arm64) 环境中，把这个 Jetpack Compose 项目编译成可安装 APK** 的完整可复现步骤。
> 环境若被重置（缓存清空/格式化），照本文一步步走即可重建。
>
> 最后验证：`assembleDebug` → `BUILD SUCCESSFUL`，产出 `app-debug.apk`（11.7MB，签名校验通过）。

---

## 一、环境基线（实测）

| 组件 | 路径 / 版本 |
|---|---|
| 工作区根 | `/data/user/0/com.ai.assistance.operit/files/workspace/52250186-a00f-40b4-927c-25f0bfdc8c39` |
| Android SDK | `/root/Android`（platforms: `android-35`） |
| build-tools | `35.0.0`、`36.0.0`（**AGP 9 强制要 36**） |
| cmdline-tools | `/root/Android/cmdline-tools/latest/bin/sdkmanager` |
| JDK | `/usr/lib/jvm/java-17-openjdk-arm64`（17.0.20.1） |
| Gradle | `/root/gradle/gradle-9.1.0/`（wrapper dists 亦有副本） |
| 项目 | AGP 9.0.0 + Gradle 9.1.0，`compileSdk=35 / minSdk=24 / targetSdk=35` |
| 包名 | `com.java.myapplication` |

---

## 二、本项目构建时会踩的 4 个坑（核心）

ARM64 proot 跑官方 Android 工具链，有三处**架构/版本**冲突 + 一处环境缺失，缺一不可修：

### 坑 1｜`/lib64` 动态链接器缺失
- **现象**：aapt2 / zipalign 报 `cannot execute: required file not found`，或构建卡在 `:app:processDebugResources` 挂起（CPU 不涨、无 aapt2 进程）。
- **根因**：ARM64 二进制的 `PT_INTERP` 指向 `/lib64/ld-linux-aarch64.so.1`，而该环境只有 `/lib/ld-linux-aarch64.so.1`。
- **修复**：
  ```bash
  mkdir -p /lib64
  ln -sf /lib/ld-linux-aarch64.so.1 /lib64/ld-linux-aarch64.so.1
  ```
- **验证**：`/root/Android/build-tools/35.0.0/aapt2 version` 应能打印版本。

### 坑 2｜AGP 9.0.0 强制 build-tools 36
- **现象**：日志回吐 `The specified Android SDK Build Tools version (35.0.0) is ignored, as it is below the minimum supported version (36.0.0) for Android Gradle Plugin 9.0.0`。
- **根因**：AGP 9 最低要求 build-tools 36；写 35 无效。
- **修复（需联网/代理）**：
  ```bash
  export ANDROID_HOME=/root/Android ANDROID_SDK_ROOT=/root/Android
  export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-arm64
  yes | /root/Android/cmdline-tools/latest/bin/sdkmanager \
        --sdk_root=/root/Android "build-tools;36.0.0"
  ```
- 若无法联网，可退而复制 35 伪装 36（不推荐，见附录 B）。

### 坑 3｜build-tools 36 里的 aapt2 是 x86-64
- **现象**：工具存在但无法执行（`required file not found`）。
- **修复**：用工作区自带的 ARM64 版覆盖：
  ```bash
  WS=/data/user/0/com.ai.assistance.operit/files/workspace/52250186-a00f-40b4-927c-25f0bfdc8c39
  cp $WS/tools/aapt2/aapt2-arm64-v8a /root/Android/build-tools/36.0.0/aapt2
  chmod +x /root/Android/build-tools/36.0.0/aapt2
  /root/Android/build-tools/36.0.0/aapt2 version   # 验证
  ```

### 坑 4｜Gradle 缓存内的 aapt2 也是 x86-64（最隐蔽）
- **现象**：`AAPT2 aapt2-9.0.0-14304508-linux Daemon #0: Daemon startup failed`。
- **根因**：构建 `androidx.core:core` 等依赖时，AGP 解开的是 Gradle 缓存里的 **linux(x86-64)** aapt2；AGP 9 下 `useTarget(...linux-aarch64)` 不生效。
- **修复**：把缓存里两处都换成 ARM64（**改前先备份**）：
  ```bash
  AAPT=$WS/tools/aapt2/aapt2-arm64-v8a
  # (a) transforms 解压产物
  find /root/.gradle/caches -path '*transformed/aapt2-9.0.0-14304508-linux/aapt2' \
       -exec cp $AAPT {} \; -exec chmod +x {} \;
  # (b) modules 缓存里的 jar 内二进制（需解压→替换→重打包）
  #     见附录 A 的完整脚本
  ```

---

## 三、标准重构流程（从零到出包）

```bash
WS=/data/user/0/com.ai.assistance.operit/files/workspace/52250186-a00f-40b4-927c-25f0bfdc8c39
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-arm64
export ANDROID_HOME=/root/Android ANDROID_SDK_ROOT=/root/Android
cd $WS

# 1. 修动态链接器（坑1）
mkdir -p /lib64 && ln -sf /lib/ld-linux-aarch64.so.1 /lib64/ld-linux-aarch64.so.1

# 2. 确认 local.properties 有 sdk.dir
grep -q '^sdk.dir=' local.properties || echo 'sdk.dir=/root/Android' >> local.properties

# 3. 装 build-tools 36（坑2，需代理）
yes | /root/Android/cmdline-tools/latest/bin/sdkmanager \
      --sdk_root=/root/Android "build-tools;36.0.0"

# 4. 替换 36 里 aapt2 为 ARM64（坑3）
cp $WS/tools/aapt2/aapt2-arm64-v8a /root/Android/build-tools/36.0.0/aapt2
chmod +x /root/Android/build-tools/36.0.0/aapt2

# 5. 替换 Gradle 缓存 aapt2（坑4，见附录 A）

# 6. 构建
./gradlew :app:assembleDebug
# 产物: app/build/outputs/apk/debug/app-debug.apk
```

> **构建建议**：单次构建约 3~4 分钟，建议后台跑并轮询日志，避免前台超时被中断：
> ```bash
> nohup ./gradlew :app:assembleDebug > /tmp/build.log 2>&1 &
> tail -f /tmp/build.log      # 另一条命令轮询
> ```

---

## 四、产物校验

```bash
AAPT2=/root/Android/build-tools/36.0.0/aapt2
APK=$WS/app/build/outputs/apk/debug/app-debug.apk

$AAPT2 dump badging $APK | head        # 看包名/版本/sdk
$AAPT2 dump xmltree --file AndroidManifest.xml $APK | head
/root/Android/build-tools/36.0.0/apksigner verify -v $APK   # 签名校验
```
**合法结果示例**：
- `package: name='com.java.myapplication' versionCode='1' versionName='1.0'`
- `minSdkVersion:'24'  targetSdkVersion:'35'`
- `launchable-activity: name='com.java.myapplication.MainActivity'`
- `Verifies` + `Verified using v2 scheme: true`

> 注：`zipalign` 可能是 x86-64 而无法运行——**不影响 APK 合法性**，Gradle 打包内部已自动对齐。

---

## 五、已知待办 / 风险点

1. **`gradle/libs.versions.toml` 缺 `[versions].kotlin`**
   `[plugins]` 里 `kotlin-compose` 用 `version.ref = "kotlin"` 引用了它，但未定义。本次未触发错误，**升级依赖时可能报错**，建议补上。
2. **Release 构建未配签名**
   没有 `signingConfig`，`assembleRelease` 会失败，需先加 keystore。
3. **联网依赖代理**
   装 build-tools 36 / 首次下 Gradle 依赖需网络。代理关闭时可能卡在下载。
4. **`/root/.gradle/caches`（~908M）是命脉**
   里面含修好的 ARM64 aapt2。**清磁盘时勿删**，否则要重走坑 4。

---

## 附录 A：替换 Gradle 缓存 aapt2（重打包 jar）脚本

```bash
WS=/data/user/0/com.ai.assistance.operit/files/workspace/52250186-a00f-40b4-927c-25f0bfdc8c39
ARM=$WS/tools/aapt2/aapt2-arm64-v8a
WORK=/root/aapt2_patch && mkdir -p $WORK

# A1. 找到缓存 jar
JAR=$(find /root/.gradle/caches -name 'aapt2-9.0.0-*-linux.jar' | head -1)
echo "JAR=$JAR"
cp "$JAR" $WORK/aapt2.jar.bak            # 备份

# A2. 解压 → 替换内部 aapt2 → 重打包
cd $WORK && rm -rf x && mkdir x && cd x
unzip -q "$JAR"
# 内部二进制通常位于 aapt2-9.0.0-14304508-linux/aapt2
find . -name aapt2 -type f -exec cp $ARM {} \; -exec chmod +x {} \;
zip -qr ../aapt2-new.jar ./*
cp ../aapt2-new.jar "$JAR"

# A3. transforms 解压产物也一并替换
find /root/.gradle/caches -path '*transformed/aapt2-9.0.0-14304508-linux/aapt2' \
     -exec cp $ARM {} \; -exec chmod +x {} \;
```
> 提示：Gradle 缓存路径中的 hash（如 `eaff7ba9...`）因机器而异，用 `find` 通配更稳。

## 附录 B：无网络时伪装 build-tools 36（不推荐）

```bash
cd /root/Android/build-tools
cp -a 35.0.0 36.0.0
cd 36.0.0
sed -i 's/35\.0\.0/36.0.0/g' source.properties package.xml README.txt 2>/dev/null
cp $WS/tools/aapt2/aapt2-arm64-v8a ./aapt2 && chmod +x ./aapt2
```
> 缺点：元数据与实际工具不完全一致，可能引发其它工具的版本校验问题。仅在万不得已时使用。

---

## 附录 C：清理清单（腾空间时用）

| 可删（无影响） | 说明 |
|---|---|
| `$WS/app/build/` | 构建中间产物 + APK（50M） |
| `$WS/.gradle/`、`$WS/.kotlin/` | 项目级缓存 |
| `/tmp/build*.log`、`/tmp/*daemon*.log` | 构建日志 |
| `/root/Android/.temp/*` | SDK 安装临时目录 |
| `/root/aapt2_backup/` | 改缓存前的旧 aapt2 备份（失去回滚点，确认后删） |
| `/root/.android/cache/`、`/root/.cache/` | 通用缓存 |

| ⚠️ 勿删 | 原因 |
|---|---|
| `$WS/.backup/` | Operit 自身的对话/状态备份 |
| `/root/.gradle/caches/` | 含修好的 ARM64 aapt2，删后需重走坑 4 |
| `/root/.gradle/wrapper/` | Gradle 9.1.0 发行版，删后需重下 |
| `/root/Android/build-tools/36.0.0/` | 当前在用工具链 |

---

*文档生成于环境修复完成后，所有路径与命令均为实测。*
