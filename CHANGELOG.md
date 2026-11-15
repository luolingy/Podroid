# 更新日志

## Unreleased

### 新功能

- **App 实际资源占用统计**：主页新增「资源占用」卡片，每 2 秒采样一次 `/proc`，展示 App 进程常驻、QEMU 进程常驻、App+QEMU 合计、guest 配置内存，以及设备整体内存使用条（数据源 `data/diag/AppStats.kt`）。
- **amd64 镜像支持改为可选开关**：设置 → 启动 → 「amd64 (x86_64) 镜像支持」，默认关闭。关闭时 guest 不注册 binfmt/qemu-x86_64，省下常驻转译开销；开启后下次启动 VM 生效。
- **VM 后端抽象 `VmBackend`**：抽出 `data/vm/VmBackend.kt` 接口 + `VmBackendKind` 枚举，原 `QemuRunner` 迁为 `QemuBackend` 实现；新增 `VmBackendFactory`（AVF 预留，暂回退 QEMU）。行为不变，为后续接入 AVF/pKVM 硬件加速铺路。

### 内存优化
- **默认 guest RAM 1024MB → 512MB**（可在设置里调 256–4096）。
- **QEMU 增加 `virtio-balloon-pci`**：guest 可把空闲内存归还宿主，降低常驻占用。
- **tmpfs 上限按 RAM 自适应**（生效于真正的 `initramfs/init`）：`/var` 由固定 1536MB 改为 RAM 的 75%（至少 256MB）；容器存储回退 tmpfs 由 1024MB 改为 RAM 的 50%（至少 256MB）。
- **镜像/容器存储落 ext4 磁盘**：`/var/lib/containers` 挂 `/dev/vdb`（`data.ext4`），不占 guest 内存（仅 ext4 挂载失败时才回退 tmpfs）。
- **QEMU 参数调优**：`-cpu max` → `cortex-a76`；`-accel tcg,thread=multi,tb-size=512`（RSS 再降 ~42MB）。
- **App 轮询优化**：主轮询 5s→15s 且只刷快列表，慢路由 `/info`、`/system/df` 降为 60s；实时性交给 `/events`。真机实测 API `/version` 延迟从"打满 30s+超时"降到 8–15s。详见 `DEEP-OPTIMIZATION-RESEARCH.md` §7。
- **amd64 binfmt 默认关闭**（生效于 `boot.sh`）：仅 `podroid.amd64=1` 时注册 qemu-x86_64，默认跳过省内存/CPU。
- 说明：主引导路径为 `initramfs/init → boot.sh`；`build-rootfs/guest/etc/init.d/docklet-boot`（OpenRC）为备用/未启用路径，其内容仅作一致性维护。

### 安全加固（详见 SECURITY-REVIEW.md）

- hostfwd 绑定 `127.0.0.1`（不再暴露到全网卡）；默认网络模式改 `bridge`。
- 底座/QEMU 下载增加 SHA-256 完整性校验（fail-closed）。
- Tar 解压修复路径穿越（Zip-Slip）。
- 收敛明文流量（networkSecurityConfig）、消除 DockerApi 内 JSON 手拼、签名口令支持环境变量。

### 新功能：搜索镜像 + 选择 tag

镜像页原来的「拉取镜像」只是一个输入框，得自己拼 `nginx:1.25`。现在换成三步：

1. **搜索** —— 输入关键词搜 Docker Hub，结果带官方徽章、star 数和 pull 数。
2. **选版本** —— 点进去列出该仓库的全部 tag（`latest` 置顶并标「最新」），带过滤框。
   nginx 有 1381 个 tag，全渲染会卡死弹窗，所以只渲染前 80 个并提示用过滤缩小。
3. **拉取** —— 选中 tag 直接拉，弹窗里显示实时进度与镜像引用。

两条网络路径分开兜底：

- **tag 列表**走镜像站的 registry v2 接口（`/v2/{repo}/tags/list`），国内可用。
  各站行为不一致，需逐个试并跳过空结果 —— `docker.m.daocloud.io` 拿 token 后返回
  `{"name":"disable-list-tags","tags":[]}`（主动禁用），`docker.1panel.live` 正常，
  `hub.rat.dev` 回 302。判据是"非空 tags"。
- **搜索**只能打 `hub.docker.com`，国内基本不可达。失败时回退到内置的 75 个常用镜像
  并明确提示"Docker Hub 在这里连不上"，而不是假装没搜到。任何时候都可以直接输入
  完整镜像名跳过搜索。

### 修复（伴随上述功能发现）

- **拉取带 tag 的镜像会得到 `<none>:<none>`**：`/images/create` 的 `fromImage` 参数被
  整串塞了 `nginx:1-alpine`，podman 把它当成仓库名的一部分，图层下完了却没打 tag。
  现在按 Docker API 规范拆成 `fromImage` + `tag` 两个参数并做 URL 转义。
- **第三方 registry 的 host 被剥掉**：`ghcr.io/foo/bar` 之前被归一化成 `foo/bar`，
  然后去 docker.io 拉一个同名但不相干的镜像 —— 静默拉错比报错糟得多。现在只剥
  Docker Hub 的别名（`docker.io` / `index.docker.io` / `registry-1.docker.io`），
  第三方 registry 原样保留，tag 列表也直连该 host。

### 其他

- 镜像页按钮文案「拉取镜像」→「搜索镜像」，空状态提示同步更新。
- 新增 16 条中英文案，`values/` 与 `values-zh/` 均为 345 条，完全对齐。
- 新增 21 条单测（镜像引用解析、tag 排序、拉取 query 拼装、内置库过滤）。

## v0.6.0 — 2026-11-14

**项目更名为 Podroid**（原 Docklet）。

### 破坏性变更

- **包名 `com.midsancode.docklet` → `com.midsancode.podroid`**，`applicationId` 随之变化。
  在设备上算一个全新应用：旧的 Docklet 需要卸载，已下载的底座和容器数据不会迁移（重新下载即可）。
- **guest 串口标记 `Docklet: xxx` → `Podroid: xxx`**，initramfs 已重新生成，App 与 guest 必须成对升级
  （旧 APK 配新底座会一直停在启动中）。底座目录常量 `BUNDLE_VERSION` 未变，仍为 `0.1.0`。
- 远程仓库 `luolingy/Docklet` → `luolingy/Podroid`。GitHub 会为旧地址保留重定向，
  已发布的 v0.4.2 底座下载链接继续可用。
- 内部标识同步改名：`podroid_prefs` / `podroid_settings` / `Podroid::Vm` WakeLock tag /
  `com.midsancode.podroid.action.*` Intent action。升级用户的语言、保活等本地设置会重置为默认值。

### 新功能

- **明暗模式**：设置 → 外观 → 明暗模式，三选一：跟随系统 / 浅色 / 深色。
  - 不再只依赖 `isSystemInDarkTheme()`，改由 `LocalDarkTheme` 注入，语义色（`toneColors`）跟着走，
    手动选深色而系统是浅色时不会出现"浅底深字"的对比度事故。
  - 新增 `values-night/` 窗口底色，冷启动不会先闪一帧不匹配的背景色。
  - 修复窗口主题：原来是 `android:Theme.Material`（深色变体），浅色模式下冷启动会先黑一下。

### 改名清单（便于排查）

| 位置 | 旧 | 新 |
|---|---|---|
| 包名 / applicationId | `com.midsancode.docklet` | `com.midsancode.podroid` |
| 应用显示名 | Docklet | Podroid |
| Gradle rootProject | DockerDroid | Podroid |
| 入口类 | `DockletApp` / `DockletRoot` | `PodroidApp` / `PodroidRoot` |
| 主题 | `DockletTheme` / `DockletDialog` / `DockletUi.kt` | `PodroidTheme` / `PodroidDialog` / `PodroidUi.kt` |
| 签名 | `docklet-release.jks`（alias `docklet`） | `podroid-release.jks`（alias `podroid`） |
| 串口标记 | `Docklet: READY` | `Podroid: READY` |
| 内核 cmdline | `docklet.dns=` 等 | `podroid.dns=` 等 |

> 签名证书本身没换（SHA-256 `58:06:E4:49:…:F1:00` 不变），只是别名和口令改成 `podroid`。

### 未改名

- `build-rootfs/`、`build-kernel/` 里仍是 Docklet 命名。它们产出的是已发布的二进制资产
  （`rootfs.squashfs` / `Image`），本次不重建；仓库里那几份脚本与线上镜像不一致，改动无实际收益。

---

## v0.5.0 — 2026-11-14

这一版把 Podroid 从"能跑容器"推到"能日常用"：界面重做、双语、主题色、崩溃自恢复、后台保活、更新检测。

### 新功能

- **多语言（简体中文 / English）**
  - 设置 → 外观 → 语言，三选一：跟随系统 / 简体中文 / English，切换后立即生效。
  - 默认英文（`values/`），中文在 `values-zh/`，共 315 条文案；设备语言非中文时自动落英文。
  - 语言值存 SharedPreferences 而非 DataStore —— `attachBaseContext` 需要同步读取。

- **主题色切换**
  - 5 套强调色：蓝 / 青 / 紫 / 绿 / 橙。只替换 primary / tertiary 及其容器色，中性表面色保持不变，深浅色模式都跟随系统。
  - 设置 → 外观 → 强调色，点选即生效，无需重启。

- **更新检测**
  - 设置 → 更新 → 检查更新，读 GitHub Releases 最新 tag，与本地 `versionName` 做语义化比较（`0.10.0 > 0.9.9`）。
  - 仓库还没有 release 时（HTTP 404）按"已是最新"处理，不误报失败。
  - 有新版本时给下载入口，直接跳 Release 页。

- **崩溃自重启**
  - 全局 `UncaughtExceptionHandler` 捕获后写 `files/crash/last_crash.txt`，1.5 秒后用 `AlarmManager.setAndAllowWhileIdle` 重新拉起。
  - 用 AlarmManager 而不是 `Handler.postDelayed`：主线程崩了 Looper 已经死了。
  - 10 分钟内最多重启 3 次，防止"一开就崩"的死循环；恢复后首屏 Snackbar 提示"上次异常退出已自动恢复"。
  - 设置 → 后台 → 最近一次崩溃日志，可原地查看。

- **后台保活**
  - 设置 → 后台 → 后台保活开关（默认开）。划掉最近任务不再停容器。
  - 前台服务 `START_STICKY` + 空 Intent 恢复；`onTaskRemoved` 不清服务。
  - VM 异常退出后按 4s / 8s 退避自动重拉，最多 2 次。
  - 开机自启：`BootReceiver` 监听 `BOOT_COMPLETED` / `QUICKBOOT_POWERON`，仅当"随开机启动 + 底座就绪 + 保活开启"三者都满足才拉 VM。
  - 电池优化白名单状态检测 + 一键跳转申请。

### 界面

- 设计系统重建：统一卡片、状态胶囊、分段按钮、设置行、提示条、空状态；五个对话框收敛到同一套 `PodroidDialog`。
- 容器详情重做：状态胶囊 + 带图标操作栏 + 四个分段页签（日志 / 资源 / 信息 / 执行），日志自动跟到最新一行并支持一键复制。
- 主页降级加载：先出 `/_ping` + 镜像 + 容器，慢接口（`/info`、`/system/df`）后到再刷新，首屏不再转几十秒。
- 设置页新增三个分区：外观、后台、更新。

### 修复

- `KeyValueRow` 在窄屏把中文标签逐字折行（"镜"和"像"分列值两侧）——改为长值强制上下排。
- `ComposeScreen` / `PortForwardDialog` / `ContainerDetailScreen` 用 `startsWith("已")` 判断操作成功，英文文案下永远判失败——改为独立布尔状态。
- 容器详情页镜像全名、挂载路径过长时挤压标签。

### 构建

- 版本号 `0.1.0 (1)` → `0.5.0 (5)`。
- release 签名：`keystore.properties` + `podroid-release.jks`（均 gitignore）；缺失时自动回退 debug 签名，clone 下来直接 `assembleRelease` 也能出包。
- `buildConfig = true`（关于页与更新检测需要 `VERSION_NAME` / `VERSION_CODE`）。
- `isShrinkResources = true`。

---

## v0.4.2

- 底座发布：`Image`（内核）+ `rootfs.squashfs` + `qemu-bundle.tar.gz`。
- guest：Alpine 3.20 + podman 5.2.5 + crun 1.17。

## v0.4.1

- initramfs 由 `.work/build-initramfs.py` 生成并内置到 assets（1.47 MB）。
- 修复 `.gz` 资产名被 AAPT2 自动解压去扩展名的问题，改为 `noCompress += "img"`。

## v0.4.0

- 容器运行时打通：arm64 / amd64 双架构实测通过（binfmt + qemu-x86_64）。
- `/dev/vda` = rootfs（squashfs 只读），`/dev/vdb` = data.ext4。
- 容器网络走 `netns = "host"`：已发布内核无 bridge 支持（`RTNETLINK: Not supported`）。

## v0.3.0

- Docker REST 客户端 + 容器 / 镜像 / 资源 / 终端界面。

## v0.2.0

- VM 生命周期：前台服务 + WakeLock + 常驻通知，串口启动标记驱动状态机。

## v0.1.0

- 初始骨架：Gradle 工程、Compose 底部导航、QEMU 拉起链路。
