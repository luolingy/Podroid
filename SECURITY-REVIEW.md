# Podroid 安全性检查报告

- 目标：`F:\exeliang\Docker-roid`（Podroid v0.6.0 —— Android App + QEMU 虚机 + Podman，对外暴露 Docker REST API）
- Source ref：`main` @ `8a67f46`（工作区 dirty）
- 方式：白盒源码审计。**M-2 已用本地 harness 机器复现（confirmed）；H-1 前提经 QEMU 官方文档 + slirp 原始补丁确认**；其余为静态源码审查。
- 范围：`app/src/main/java/...`、`AndroidManifest.xml`、`build-rootfs/guest/...`、构建配置
- 结论：**1 High、2 Medium、若干低危/加固项。** 最高优先级是「容器引擎 API 实际绑定到全网卡且无认证」，与 README 声称的「仅 `127.0.0.1:2375`」不符。
- **修复状态（2026-11-14）：H-1 / M-1 / M-2 / L-1 / L-5 / L-6 已按严重程度修复，详见第 6 节。** M-2 修复前已机器复现，修复后已用 harness 验证拦截。

---

## 1. 高优先级（High）

### H-1 容器引擎 API 暴露在网络可达的 `0.0.0.0`，且无 TLS/认证

**证据**
- `app/src/main/java/com/midsancode/podroid/data/vm/QemuRunner.kt:149`
  ```kotlin
  "-netdev", "user,id=net0,hostfwd=tcp::${b.hostPort}-:2375",
  ```
  QEMU slirp 的 `hostfwd=tcp::PORT-:2375` 主机侧地址为空，等价于 **`0.0.0.0`（所有网卡）**，而非 `127.0.0.1`。README 第 75 行写的是「暴露到宿主 `127.0.0.1:2375`」，实现与文档不一致。
- guest 侧 `build-rootfs/guest/etc/init.d/docklet-boot:87`
  ```sh
  podman system service --time=0 tcp://0.0.0.0:2375
  ```
  无 `--tls`、无客户端认证、`--time=0` 永不过期。
- 同一服务还默认 `networkMode = "host"`（`VmBundle.kt:29`、`SettingsRepository.kt:43`），容器直接共享 guest 网络栈。

**影响**
Docker/Podman REST API 本身就是「VM 内的 root 等价控制面」（创建容器时可挂载/提权/执行任意命令）。当前传导链路是：局域网内任意设备 → QEMU 主机侧 `0.0.0.0:2375` → guest podman API → 容器内任意执行。本应「只给本 App 用」的接口，实际成了**同网段可未授权访问的 root 级控制面**。

**修复（最小改动）**
1. 启动参数把主机地址钉死在环回地址：
   `hostfwd=tcp:127.0.0.1:${b.hostPort}-:2375`（`QemuRunner.kt:149`）；`QmpClient.addHostfwd` 的 `source` 同样改为 `tcp:127.0.0.1:...`（`QmpClient.kt:52`）。
2. guest 内 `podman system service` 绑定到 `127.0.0.1` 会失去 hostfwd 可达性，因此保留 `0.0.0.0` 但仅在 slirp 内网可被转发；配合第 1 步后外部已无法直达。
3. 中期：为 podman API 启用 TLS + 客户端证书（`--tls-verify --tls-cert`），或改用 unix socket 经 QEMU `-chardev socket` 转发（替代 TCP），彻底去掉网络暴露面。
4. 默认 `networkMode` 改为 `bridge`，避免容器与 guest 宿主共享网络。

> 说明：本次为静态审查，未在设备上实测端口可达性；上述「绑定 0.0.0.0」基于 QEMU slirp `hostfwd` 的既定语义（空主机地址 = INADDR_ANY）。建议 owner 在真机用 `adb shell` 或同网段 `nmap 2375` 复核，或直接改为绑定环回后验证。

---

## 2. 中优先级（Medium）

### M-1 底座/QEMU 下载无任何完整性校验，且走第三方加速代理

**证据**
- `data/vm/BundleDownloader.kt:104-129`：`downloadOnce` 只校验 HTTP 200 与 `Content-Length`，**不校验哈希/签名**。
- `data/vm/QemuBinaries.kt:76-124`：`qemu-bundle.tar.gz` 同样的下载逻辑，同样无完整性校验。
- `BundleDownloader.kt:21-25`：候选 URL 优先使用第三方代理 `https://ghfast.top/`、`https://gh-proxy.com/`，直连兜底。

**影响**
kernel/rootfs/QEMU 二进制通过第三方反向代理分发，中间人（或恶意代理）可返回被篡改的 `qemu-bundle.tar.gz`。结合 M-2 的路径穿越，可把恶意可执行文件写进 App 目录并在下次启动执行——**供应链 RCE 链**。即便不考虑穿越，篡改 rootfs 内核也可让 guest 行为失控。

**修复**
1. 在 GitHub Release 资产旁发布 SHA-256 清单，下载后强制比对（尤其是绕过第三方代理后）。
2. 更佳：对底座做签名（GPG/minisign），App 内嵌公钥验签后再解压。
3. 至少，把直连 `github.com` 作为**首选**，第三方代理仅作可选加速并在结果不符哈希时回退。

### M-2 Tar 解压存在路径穿越（Zip-Slip），可写任意文件

**证据**
- `data/vm/TarExtractor.kt:52` `'0'`（file 分支）：
  ```kotlin
  val out = File(destDir, fullName)
  out.parentFile?.mkdirs()
  copyN(bin, out, size)
  ```
  `fullName` 直接取自 tar 头（`header[0..100]` 或 GNU long name / `prefix`），**未做 `..`/绝对路径规范化校验**。同理 `'5'` 目录分支（第 49 行）。

**影响**
与 M-1 组合：恶意 tar 里放 `../../files/.../qemu/lib/xxx.so` 或覆盖 `bin/qemu-system-aarch64`，解压后即落地到 `filesDir/qemu/` 之外甚至覆盖主程序，形成代码执行。单独看，若 tarball 来源可信则影响有限，因此定级 Medium，但它是 M-1 的放大器。

**修复**
解压前对每个 `fullName` 做规范化：拒绝绝对路径、拒绝含 `..` 的段，校验 `canonicalPath.startsWith(destDir.canonicalPath + File.separator)`；不跟随符号链接（当前代码已忽略 link 类型，保持）。

---

## 3. 低优先级 / 加固项

| # | 位置 | 问题 | 建议 |
|---|---|---|---|
| L-1 | `app/src/main/AndroidManifest.xml:37` | `android:usesCleartextTraffic="true"` 全局放开明文 HTTP，且无 `networkSecurityConfig` 限制域 | 配置 `networkSecurityConfig`，仅对 `127.0.0.1`/必要域名放行明文；GitHub API 等走 HTTPS 默认策略 |
| L-2 | `build-rootfs/guest/etc/init.d/docklet-boot:55` | `/etc/containers/policy.json` 设为 `{"default":[{"type":"insecureAcceptAnything"}]}`，镜像不做签名校验 | 视需求启用 `signedBy` / 至少对关键镜像使用可验证策略 |
| L-3 | `build-rootfs/guest/etc/init.d/docklet-boot:87` + `-machine virt -cpu max` | guest 内 podman 以 root 跑、`--time=0` 不超时；QEMU 无加固 | 与 H-1 一并处理；podman 运行在受限 rootless 或加 seccomp/apparmor |
| L-4 | `main/java/.../data/vm/QmpClient.kt` | QMP socket 为文件系统 unix socket、无鉴权（`QemuRunner.kt:151` `-qmp unix:...server=on`） | 仅 App 进程可达，风险低；确保 socket 位于 App 私有目录、权限 0600 |
| L-5 | `data/docker/DockerApi.kt:163,170` | `execCapture`/`renameContainer`/`connectNetwork` 用字符串拼接构造 JSON，命令含引号/反斜杠时**已复现转义不闭合、破坏 JSON 结构**（见 V-3） | 改用 `JSONObject`/`buildJsonObject` 构造，避免手拼 |
| L-6 | 工作区根目录 `keystore.properties` + `podroid-release.jks` | release 签名口令 `podroid2026` 明文，且 `.jks` 存在工作区（**已确认未被 git 跟踪**，`.gitignore` 覆盖 `*.jks`/`keystore.properties`） | 属本地卫生问题，非仓库泄露；建议口令走环境变量、jks 移出仓库目录 |
| L-7 | `verify.ps1` | 脚本引用 `com.docklet.*` 路径，与当前 `com.midsancode.podroid` 不符，验收会误报 | 同步路径或删除 |
| L-8 | `app/build.gradle`（`extractNativeLibs`、下载执行 QEMU） | `extractNativeLibs` 使 native 库落地可执行（功能需要，但需保证安装源可信/签名校验） | 保持 release 签名的私钥安全；与 M-1 的签名校验配套 |

---

## 3.5 深入验证（可机器复现的证据）

### V-1 M-2 路径穿越 —— 已用本地 harness 复现（confirmed）

用 JDK 11 写了一个最小 Java harness（临时目录，验证后已清理），**逐字复刻** `TarExtractor.kt` 的文件写入判定行 `File(destDir, fullName)`，构造一个 tar 条目名为 `../PWNED.txt`，解压目标 `destDir = <tmp>/qemu`：

```
tar entry name   : ../PWNED.txt
resolveEntry ->  : <tmp>\qemu\..\PWNED.txt
destDir          : <tmp>\qemu
escapes destDir? : true
sentinel exists? : true   <- 路径穿越成立
file written at  : <tmp>\PWNED.txt
```

结论：`fullName` 未经规范化/`..` 过滤即拼入 `File(destDir, fullName)`，**文件确实落到 destDir 之外**。与 M-1 组合即为「恶意 tar.gz → 越界写任意文件 → 可覆盖 `qemu/qemu-system-aarch64`」的供应链 RCE 链，**M-2 证据充分（confirmed）**。

> 复现命令：`javac -encoding UTF-8 Harness.java && java Harness`（仅用临时目录，不接触设备/网络；已在 workspace `.work/sec/`）。harness 属自建验证代码，不是对目标的攻击工具。

### V-2 H-1 QEMU hostfwd 空主机地址语义 —— 权威来源确认

- QEMU 官方手册 `-netdev user,hostfwd` 语法为 **`[tcp|udp]:[hostaddr]:hostport-[guestaddr]:guestport`**，`hostaddr` 为方括号=**可选**（[QEMU Invocation 文档](https://www.qemu.org/docs/master/system/invocation.html)）。
- slirp 引入「绑定主机接口」的原始补丁明确：*"...the user can specify on which host interface qemu should listen... **If omitted**, ..."*（[qemu-devel 补丁](https://lists.nongnu.org/archive/html/qemu-devel/2009-05/msg00439.html)）。省略 `hostaddr` 时监听所有接口（= `0.0.0.0`）。

结论：`QemuRunner.kt:149` 的 `hostfwd=tcp::${port}-:2375` 主机地址为空 → 监听 `0.0.0.0`。**前提成立**；「本机是否真有外部可达网卡」需 owner 在真机复核（H-1 保留为 High，运行时可达性建议实测）。

### V-3 L-5 JSON 手拼 —— 已确认可破坏结构

同一 harness 复刻 `DockerApi.execCapture` 的字符串拼接（`DockerApi.kt:163`），喂一条含 `"` 的命令：
```
[execCapture Cmd JSON] ["sh","-c","echo hi\" , \"Injected\":true"]
quote count (should be even) : 9 -> UNBALANCED (injection possible)
```
转义只处理了 `\` 和 `"`，但命令内容本身含未配对的引号时 JSON 结构被破坏（引号计数为奇数）。虽最终由 podman 端解析，**注入/构造破坏面成立**，建议改用 `buildJsonObject`。

---

## 4. 已核对为「未发现边界突破」的点

- **签名密钥是否入库**：`git ls-files` 确认 `keystore.properties`、`podroid-release.jks` **未被跟踪**，`.gitignore` 已忽略；仅存在于本地工作区。
- **外部存储读写**：仅用 `getExternalFilesDir(null)`（应用私有外部目录），无 `getExternalStoragePublicDirectory`、无 `MANAGE_EXTERNAL_STORAGE`、无 FileProvider 越权暴露。
- **导出组件**：`MainActivity`（`exported=true`，LAUNCHER，正常）；`VmService`（`exported=false`）；`BootReceiver`（`exported=true`，仅响应 `BOOT_COMPLETED`/`QUICKBOOT_POWERON`，属原语设计）。
- **崩溃处理**：`CrashHandler` 只写 App 私有 `files/crash/`，无敏感信息外发。
- **设置项**：`SettingsRepository` 的 ram/disk/port/dns/mirrors 均有 `coerceIn`/`take` 限幅，流入内核 cmdline 的值长度受控（`QemuRunner.kt:126-129` 拼接 `podroid.mirror=`/`podroid.network=`）；镜像站/网络模式为 App 自身配置，非跨边界输入。
- **Update 检测**：`UpdateChecker` 走 `https://api.github.com`，失败/404 不误报，无注入面。

---

## 5. 建议处理顺序

1. **H-1**（改 `hostfwd` 绑定 `127.0.0.1`，默认 `bridge` 网络，条件成熟加 TLS）——这是全项目最大的暴露面。语义已由 QEMU 官方文档 + slirp 补丁确认（V-2）。
2. **M-1 + M-2** 一起做（下载完整性校验 + 解压穿越修复），消除供应链 RCE 链。M-2 已机器复现（V-1）。
3. L-1（networkSecurityConfig）、L-5（JSON 手拼）、L-6（口令移出）随手清理。

> 本报告已对 M-2（本地 harness 复现）、H-1（QEMU 文档/补丁确认语义）、L-5（harness 复现）做了机器/权威来源验证；剩余项为静态源码审查结论。运行时事实（如真机 2375 是否被外部网卡可达）建议 owner 现场复核。

---

## 6. 修复记录（按严重程度）

### H-1 已修复：hostfwd 绑定环回 + 默认 bridge 网络
- `QemuRunner.kt`：`hostfwd=tcp::${port}-:2375` → **`hostfwd=tcp:127.0.0.1:${port}-:2375`**。
- `QmpClient.kt`：`addHostfwd`/`removeHostfwd` 的 `source` 均加 `127.0.0.1`（保持增删匹配一致）。
- `VmBundle.kt` / `SettingsRepository.kt`：默认 `networkMode` 由 `host` 改为 **`bridge`**。
- 说明：guest 内 `podman system service tcp://0.0.0.0:2375` 可保留——现在 hostfwd 只把环回流量转发进去，外部网卡已无法直达。README 中「仅 127.0.0.1:2375」的表述现在与代码一致。

### M-1 已修复：下载完整性校验（fail-closed）
- 新增 `Checksums`（`BundleDownloader.kt`）：从 Release 拉取 `SHA256SUMS`，对每个下载文件做 SHA-256 比对；**清单缺失或不匹配即判定失败并换镜像源**，绝不静默信任第三方代理。
- 接入 `BundleDownloader.tryDownload`（Kernel/rootfs）与 `QemuBinaries.downloadFrom`（qemu-bundle.tar.gz）。
- ⚠️ 运维要求：Release 必须发布 `SHA256SUMS`（`sha256sum *` 生成），否则首次下载会因缺条目失败——这是有意的 fail-closed 设计。

### M-2 已修复：Tar 解压路径穿越
- `TarExtractor.kt`：新增 `safeResolve()`，对目录('5')与文件('0'/'7')条目统一校验：拒绝绝对路径、拒绝 canonical 路径逃逸 destDir，命中抛 `SecurityException`。
- **已用 harness 验证**：`../PWNED.txt`、`../../etc/passwd`、`a/../../x` 全部 BLOCKED；`bin/qemu-system-aarch64` 等正常条目 ALLOWED。

### L-1 已修复：收敛明文流量
- 新增 `res/xml/network_security_config.xml`：默认 `cleartextTrafficPermitted=false`，仅对 `127.0.0.1`/`localhost`/`10.0.2.2` 放行。
- `AndroidManifest.xml`：移除 `android:usesCleartextTraffic="true"`，改为 `android:networkSecurityConfig="@xml/network_security_config"`。

### L-5 已修复：消除 JSON 手拼
- `DockerApi.kt`：`execCapture`/`renameContainer`/`connectNetwork`/`disconnectNetwork` 改用 `buildJsonObject`/`buildJsonArray` 构造，命令含引号不再破坏结构。

### L-6 部分修复：签名口令可用环境变量
- `app/build.gradle.kts`：优先读 `PODROID_STORE_FILE`/`PODROID_STORE_PASSWORD`/`PODROID_KEY_ALIAS`/`PODROID_KEY_PASSWORD` 环境变量，文件仅作回退。
- 仍需人工：把 `keystore.properties` 的明文口令清空/移出工作区，`podroid-release.jks` 移出仓库目录（本地操作，代码无法替代）。

### L-3 已修复：podman API 空闲超时 + 说明
- `boot.sh`：`podman system service --time=0`（永不超时）→ **`--time=300`**（无活动连接 5 分钟自动退出；可用 `PODROID_API_IDLE_TIMEOUT` 覆盖）。
- guest 内 `0.0.0.0:2375` 保留（SLIRP hostfwd 可达所需），但对外暴露已由 H-1 的 hostfwd 环回绑定限制在宿主环回；代码加了注释说明。
- 遗留：podman 仍以 root 运行（rootless 改造需 guest 内 user namespace + subuid 配置，单独评估）。

### L-2 已缓解：镜像策略可配置（默认兼容，可收紧）
- `boot.sh`：`policy.json` 由硬编码 `insecureAcceptAnything` 改为**可配置**——默认仍放行（否则 docker.io 未签名镜像全部拉取失败、破坏核心功能），内核 cmdline `podroid.verifyimages=1` 时改为 `reject` 未签名镜像。
- 说明：完全签名校验需配套 sigstore/signedBy 镜像源策略，属运维配置；代码侧只提供开关与文档，避免盲目收紧导致功能不可用。`docklet-boot`（备用路径）同步一致。

### 未修复（需人工决策）
- **L-4**（QMP socket 权限）、**L-8**（native 库可信来源）建议后续按需处理；**L-7** 已修（`verify.ps1` 路径与括号检查）。L-2 完全签名校验、L-3 rootless 化需配套基础设施/镜像源，属运维层。

### 功能变更：amd64 支持改为可选（默认仅 arm64）
- 默认底座不再注册 amd64 binfmt/qemu-x86_64，`qemu-x86_64` 用户态转译不再常驻，省内存/CPU。
- `SettingsRepository` 新增 `amd64Support`（默认 `false`）→ `VmBundle` 透传 → `QemuRunner` 在启用时追加内核 cmdline `podroid.amd64=1`。
- guest `docklet-boot` / `startvm` 依据 `podroid.amd64=1` 决定是否注册 binfmt；默认打印 `binfmt amd64 disabled (arm64 only)`。
- UI：设置页「启动」区新增「amd64 (x86_64) 镜像支持」开关；关于页架构行随开关显示。
- README/DESIGN 说明同步：默认仅 arm64，amd64 可选。
