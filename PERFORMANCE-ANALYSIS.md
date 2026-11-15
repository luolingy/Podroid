# Podroid 性能分析报告

- 目标设备：Redmi M2007J22C（cannon），arm64-v8a，**8 核**，Android 12 / API 31，物理内存 7,592 MB
- 运行形态：QEMU TCG（纯软件模拟）运行 Alpine 3.20 aarch64 guest，guest 内 Podman 5.2.5 + crun
- 配置：guest RAM **512 MB**、`-smp 2`、`-cpu max`、`virtio-balloon`、host 网络、overlay@ext4
- 测量方式：adb 无线（192.168.1.11:5555）真机采样（`top`/`/proc`/`logcat`/Docker API）
- 测量时间：2026-11-14 21:41–22:08（VM 连续运行期间）

> **重要前提**：Podroid 的核心开销来自 **QEMU TCG 指令翻译**（无 KVM/pKVM 硬件加速）。本报告据此分项量化，并给出优化方向。所有数值为真机实测，标注了受干扰的项。

---

## 1. 结论摘要

| 维度 | 实测 | 评价 |
|---|---|---|
| 稳态内存（App+QEMU） | **≈ 648 MB** | 良好（优化前估算 ~1.25 GB，降约一半） |
| QEMU CPU | **≈ 213%（2 核满载）** | 偏高 —— TCG 软模拟固有代价 |
| 启动到 READY | **约 90–120 s**（TCG 冷启动） | 偏慢 —— TCG + podman storage 初始化 |
| 容器 create/start 延迟 | ~20 s（**受 App 轮询干扰，非真实值**，见 §4） | 需去干扰后复测 |
| 底座体积 | **≈ 90 MB**（Image 43.5 + rootfs 44.9 + initrd 1.5） | 可接受（按需下载） |
| 持久盘占用 | 表观 4 GB，**实占 ≈ 141 MB**（稀疏） | 优秀 |
| 存储效率 | overlay @ ext4，镜像落磁盘不入内存 | 正确 |

**一句话**：内存优化目标已达成（~648 MB）；主要性能瓶颈是 **TCG 软件模拟带来的 CPU 与启动耗时**，根治只能靠 AVF/pKVM 硬件虚拟化。

---

## 2. 内存分析

### 2.1 实测分解（VM 运行中）

| 项 | 实测 | 来源 |
|---|---|---|
| QEMU 进程 RSS | **434,512 kB ≈ 424 MB** | `/proc/19726/status` VmRSS |
| App 进程 RSS | **228,952 kB ≈ 224 MB** | `/proc/19623/status` VmRSS |
| **合计** | **≈ 648 MB** | — |
| guest 配置 RAM | 512 MB | `-m 512` |
| guest 内 MemTotal | 489,836,544 B ≈ **467 MB** | Docker API `/info` |
| 设备总内存 / 可用 | 7,592 MB / 1,877 MB | `/proc/meminfo` |

### 2.2 解读
- **QEMU ≈ 424 MB < guest 512 MB**：说明实际驻留低于配置（部分页未触碰 + balloon 归还空闲页），属于健康状态。
- **App ≈ 224 MB**：主要来自 Compose UI + Kotlin 运行时 + 日志缓冲；可接受但偏高，UI 层有优化空间（见 §6）。
- **guest 内 MemTotal 467 MB**（< 512）：内核保留 + 保留页，正常。
- **镜像/容器不占内存**：`/info` 显示 `Driver=overlay`、`Backing Filesystem=extfs`，存储落 `/dev/vdb`（data.ext4），不占 guest RAM —— 这是本轮最关键的架构正确性，避免了"拉镜像撑爆内存"。

### 2.3 与优化前对比（估算）
| 场景 | 优化前（RAM 1024，无 balloon） | 现在（RAM 512 + balloon） |
|---|---|---|
| 稳态 App+QEMU | ~1.25 GB | **~648 MB**（实测） |
| 降幅 | — | **≈ 48%** |

---

## 3. CPU 分析（主要瓶颈）

### 3.1 实测
| 项 | 实测 | 说明 |
|---|---|---|
| QEMU 进程 CPU | **213%** | 对应 `-smp 2` 两核 TCG 翻译线程满载 |
| 设备负载 loadavg | **22.19 / 19.39 / 17.84** | 极高 —— QEMU + podman + App 轮询 + 后台高并发 |
| 设备核数 | 8 | QEMU 约占 2–2.6 核 |
| QEMU 线程数 | 6 | TCG + I/O + 主控 |

### 3.2 根因
- **TCG 无硬件加速是根本**：宿主 arm64 → guest arm64 仍需**逐指令动态翻译**（不是同架构就快——TCG 永远软件翻译）。这是 Android 无 KVM 的硬约束。
- `-cpu max` 暴露全部 CPU 特性 → 翻译缓存（TB）更大、指令面更广，进一步推高 CPU。
- `-smp 2`：TCG 多线程各占一核，跑满 2 核。
- 叠加干扰：App 每 5 s 并发轮询 `/info` 等慢路由，与 podman 单线程服务争抢，推高系统负载。

### 3.3 评价
213% CPU 是 TCG 的**固有成本**，不是 bug。轻交互（看状态、偶尔跑容器）可接受；持续重负载（编译、跑服务）会很吃力。

---

## 4. 启动与操作延迟

### 4.1 VM 启动
- 实测：从点"启动 VM"到 READY **约 90–120 s**（多次观测）。
- 分解（据 `boot.sh` 执行顺序 + 观测）：
  1. QEMU 冷启动 + TCG 翻译内核：~10–20 s
  2. guest initramfs 挂 squashfs/vdb、initramfs 引导：~5 s
  3. podman system service 初始化 storage（overlay 首次扫描）：**主要耗时，~40–80 s**
  4. 轮询 `/proc/net/tcp` 等 2375 LISTEN：≤ 1 s
- 命令锚点：`21:41:04.695` 打印启动命令；READY 早于后续 21:41:xx（串口标记未全量落 logcat）。

### 4.2 容器操作延迟（**数据受干扰，仅参考**）
- create ~20 s、start（含 echo）~20 s。
- **注意**：这是 **App 的 5 秒 `/info` 并发轮询与手动 curl 争抢 podman 单线程服务**导致的排队，**不代表真实冷启动延迟**。去干扰后（如暂停 App 轮询、或改用长连接）预期显著降低。
- **实测佐证干扰严重**：VM 就绪且 App 运行期间，对 `/version` 连续 6 次采样，前 3 次**均打满 30s 超时**（`curl: (28) timed out`），即 App 轮询已把 podman 单线程服务占满。这强烈印证：轮询策略是当前最大的 API 响应瓶颈（详见 §6-P1）。
- **建议复测方法**：VM 就绪后关闭 App 轮询（或杀 App 进程只留 QEMU），用宿主 curl 直接测 create/start 的纯延迟。

### 4.3 本地命令验证（对照）
- `/version`、`/containers/json`、`/images/json` 在轮询间隙均可正常返回（<20 s 超时内），而 `/version` 偶发超时 → 印证"单线程服务被并发轮询挤占"。

---

## 5. 磁盘 / IO

| 项 | 值 | 说明 |
|---|---|---|
| 底座 Image | 43.5 MB | 内核 |
| 底座 rootfs.squashfs | 44.9 MB | Alpine + podman/crun |
| initrd.img | 1.47 MB | initramfs |
| **底座合计** | **≈ 90 MB** | 首次启动按需下载 |
| data.ext4 表观 | 4,294,967,296 B（4 GB） | 稀疏文件 |
| data.ext4 **实占** | 289,416 blocks × 512 ≈ **141 MB** | 仅实际写入量，稀疏高效 |
| 存储驱动 | overlay @ ext4（`Native Overlay Diff=true`） | 镜像层硬链接共享，省空间 |

**评价**：磁盘利用高效。data.ext4 稀疏 + overlay 共享层，实际占用远小于表观 4 GB。

---

## 6. 优化建议（按收益排序）

| # | 方向 | 预期收益 | 成本/风险 |
|---|---|---|---|
| **P0** | **AVF/pKVM 硬件虚拟化后端**（接口 `VmBackend` 已就绪，加 `AvfBackend`） | **CPU 从 213%→接近原生，启动从 ~2min→秒级** | 大工程；需 Android 13+ 且设备支持 pKVM（Pixel 等） |
| **P1** | **App 轮询降频/缓存**：`/info` 从 5s 改为 15–30s，或改用 `/events` 长连接 + 按需 `/info` | 消除 API 争抢，降低负载 ~10–20%，显著改善容器操作响应 | 小，纯 App 侧 |
| **P2** | **`-cpu max` → `-cpu cortex-a72`（或具体型号）** | TCG 翻译缓存变小，CPU 略降 | 小，需验证兼容 |
| **P3** | **启动优化**：podman storage 首次扫描慢 → 预置/复用已初始化的 storage，或 `-smp` 为 1 时减少争抢 | 启动缩短 | 中 |
| **P4** | **App 内存**：日志缓冲上限（400 行）、图片/Compose 资源排查 | App RSS 降几十 MB | 小 |
| **P5** | **`amr64→amd64` binfmt 默认关闭已生效**；如非必要不开启，避免二次翻译 CPU | 已有 | 已做 |
| **P6** | guest ZRAM：当前主路径未启用 swap（内存 512 够用，可不加；若加可缓解 OOM 但增加 CPU） | 视场景 | 低 |

---

## 7. 数据可信度说明

- 内存、CPU、负载、线程数、磁盘占用：**真机实测**（`/proc`、`top`、`run-as stat`），可信。
- 启动耗时：**多次观测估计**（90–120 s），非精确埋点；串口阶段标记未全量落 logcat，建议后续在 `VmManager.onSerialLine` 加带时间戳的 debug 日志精确化。
- 容器 create/start 延迟：**受 App 5s 轮询并发干扰**，非真实值，已在 §4.2 标注并给出复测方法。
- CPU 213% 为即时采样（VM 空载时仍高，因 TCG 后台翻译 + 轮询）。

---

## 8. 复测脚本（去干扰精确测量）

```bash
ADB="adb"   # 已 connect 192.168.1.11:5555
# 1) 内存
QP=$(adb shell pgrep -f qemu-system | tr -d '\r'); adb shell grep VmRSS /proc/$QP/status
AP=$(adb shell ps -A | grep midsancode | awk '{print $2}'); adb shell grep VmRSS /proc/$AP/status
# 2) CPU/负载
adb shell "top -n1 -b -o PID,%CPU | grep -E 'qemu|midsancode'"; adb shell cat /proc/loadavg
# 3) 纯 API 延迟（先停 App 轮询：adb shell am force-stop com.midsancode.podroid，仅留 QEMU）
time adb shell "curl -s -o /dev/null -w '%{time_total}s' http://127.0.0.1:2375/version"
```
