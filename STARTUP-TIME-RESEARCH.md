# Podroid 启动时间压缩研究报告

- 设备：Redmi M2007J22C，8 核，Android 12；QEMU TCG，guest 512MB
- 测量：在 `boot.sh` 埋 `/proc/uptime` 时间戳 + `VmManager` 串口日志转 logcat（tag `PodroidSerial`），真机冷启动实测
- 一次完整冷启动到 READY：**≈ 50 秒**（QEMU 启动→READY，guest uptime TS[48.23]）

---

## 1. 启动耗时精确分解（实测，不是估算）

| 阶段 | guest uptime | 该段耗时 | 占比 |
|---|---|---|---|
| QEMU 装载内核 + TCG 引导 → `kernel up` | ~7.0s | **~7s** | 14% |
| initramfs → chroot → boot.sh 开始 | TS[13.99] | 含上面内核段 | — |
| bootstrap（cgroup + DNS + hosts） | TS[15.01] | **~1.0s** | 2% |
| network up（eth0 + route） | TS[16.49] | **~1.5s** | 3% |
| binfmt / engine 准备 | TS[17.21] | **~0.7s** | 1% |
| **podman system service 初始化 storage → LISTEN 2375** | **TS[17.27] → TS[48.23]** | **★ ~31s** | **★ 62%** |
| 合计到 READY | TS[48.23] | **~48s（+宿主观测波动 ≈50s）** | 100% |

### 核心结论
**启动时间的 62% 消耗在「podman 拉起并初始化 storage 直到监听 2375」这一段（~31s）。** 前面所有 guest 准备（内核引导 + cgroup + dns + 网络 + binfmt）合计仅 ~10s，其中 guest 内脚本部分才 ~3s。

**任何不针对 podman 初始化的优化，最多只能省前 38%（~19s）里的一小部分，天花板很低。要压缩启动，必须攻 podman 冷初始化。**

---

## 2. 为什么 podman 初始化要 ~31s

`podman system service` 启动时（在 TCG 软模拟下）要做：
1. 初始化 graphroot `/var/lib/containers/storage`（overlay 驱动装载、检查 `overlay`/`overlay-containers` 目录、`storage.conf` 解析）
2. 初始化 repo（镜像层元数据扫描）
3. 装载运行时（crun）、初始化网络栈（netavark，即使 host 模式也走一遍）
4. 建立 REST listeners（这里才是 2375 LISTEN）

在 **TCG 下这些都慢**：每个用户态指令都要翻译，podman 是 Go 大二进制（启动本身就重），overlay 层扫描涉及大量 stat/readdir 系统调用——TCG 软件 MMU 下每次系统调用/内存访问都有翻译开销。**实测佐证**：即使稳态后 `/version` 仍要 7.7s、`/info` 8.5s（也含 App 轮询干扰），说明 podman API 在 TCG 下本身就慢，冷初始化更放大了这一点。

---

## 3. 可行优化方案（按收益 / 可行性）

### 方案 A：减少"从 spawn 到可服务"的空等（低风险，可立即做）
**问题**：boot.sh 里 `podman system service` 后台 spawn 后，靠轮询 `/proc/net/tcp` 等 LISTEN，这 31s 是 podman 真实初始化时间，不是轮询本身浪费——但**等待判据可以更聪明**：现在每 1s 轮询一次 `/proc/net/tcp`，且 `nap` 用忙等 shell 循环（本身也占 CPU，反过来拖慢 podman 初始化）。
- **A1**：把轮询间隔从 1s → 2s（忙等 shell 循环在 TCG 下也耗翻译，减少它给 podman 让出 CPU）。收益：小（1–3s）。
- **A2**：就绪判据从"扫 /proc/net/tcp 文本"改为"尝试 `wget 127.0.0.1:2375/_ping`"（更直接，但差别不大）。

### 方案 B：预初始化 storage —— 最大收益（中风险，核心）
**目标**：把 ~31s 的冷初始化，摊到"只做一次，之后秒开"。
- **B1（推荐）**：**复用已初始化的 storage**。podman 的 `graphroot`（`/var/lib/containers/storage`）落在持久盘 `/dev/vdb`，**本就跨重启保留**。若 storage 已初始化，podman 二次启动应更快——但实测仍 31s，说明**每次启动 podman 都会重新做一遍完整初始化**（不缓存）。
  - 对策：**用 `podman system service` 的 `--time` 已设；关键是让 systemd/OpenRC 不必要。真正省时的是：能否预先生成 storage 元数据快照**。
  - 实操：在镜像构建期（build-rootfs）预先跑一次 `podman system service` 让它完成初始化并把 `/var/lib/containers/storage` 元数据**烤进 squashfs 底座**，运行时只做增量检查 → 冷启动可显著缩短。
- **B2**：**storage driver 选型**。overlay 初始化比 `vfs` 复杂（要扫层、检查 overlay 支持）。但 vfs 占空间大、慢。**不建议换**。
- **B3**：**换更轻的容器运行时初始化**：podman 是 Go 大二进制，启动初始化重。**用 `--storage-driver`/精简配置**跳过不必要的检查（如 `podman system service` 前的 `podman info` 预热可省）。
- **B4**：**预启动 warm-up**：App 在 VM 刚 Ready 前，或开机自启时提前把 podman 拉起来预热（用户真正要用时已就绪）——本质是"把等待挪到用户无感的时刻"。

### 方案 C：缩短前面 ~19s（有限收益，低风险）
- **C1**：内核裁剪更激进（当前 Image 43.5MB，`kernel up` ~7s）—— 更小内核加载更快，但 TCG 引导瓶颈在翻译，收益有限（1–3s）。
- **C2**：`-m 512` 已最小；`-smp 2` 对启动阶段帮助不大（podman 单线程初始化）。
- **C3**：initramfs 精简（当前 1.47MB）—— 影响很小。

### 方案 D：架构级（大改造，对应 DEEP-OPTIMIZATION 的阶段3）
- **D1**：AVF/pKVM —— 本机不支持（已验证无 /dev/kvm），排除。
- **D2**：自编译 QEMU + guest 预烤 storage —— 工程量最大但天花板最高。

---

## 4. 推荐路线

| 优先级 | 动作 | 预期收益 | 成本/风险 |
|---|---|---|---|
| **P0** | **B1：镜像构建期预初始化 podman storage 并烤进底座**（让运行时只做增量） | **启动 ~50s → 可能 ~25-30s**（省掉大部分 31s 初始化） | 中：改 build-rootfs，需验证 overlay 元数据可复用 |
| **P1** | **B4：开机/提前 warm-up**（用户打开 App 前 podman 已就绪） | 感知启动≈0 | 低：改 App 触发时机 |
| **P2** | A1：轮询间隔 1s→2s（忙等让出 CPU 给 podman） | 1–3s | 极低 |
| **P3** | C1：内核再裁剪 | 1–3s | 低 |
| — | 前面 19s 已接近下限（内核引导 + guest 脚本） | — | — |

**核心判断**：启动时间压缩的主战场是 **podman storage 冷初始化（31s/50s）**。最有效的是 **B1（预烤 storage）**——把一次性初始化挪到构建期，运行时只做增量检查，预期能把启动压到 25–30s 量级。其余都是小修小补。

---

## 5. 下一步验证建议

1. **先做 B1 的可行性验证**：在 guest 内手动计时 `podman system service` 第一次 vs 第二次启动（storage 已存在时）到 LISTEN 的耗时，确认"storage 已初始化是否真的更快"——如果第二次仍 31s，说明 podman 每次都全量初始化，B1 需改为"预烤元数据快照"方案。
2. 若 B1 可行，改 `build-rootfs.sh` 在打 squashfs 前预跑一次初始化。
3. A1/P2 可随手做（改 boot.sh 轮询间隔）。

> 数据可信度：本报告 §1 分解为**真机精确埋点实测**（guest uptime + logcat 时间戳），可信度高；§2–4 的优化收益为基于测量与 podman 原理的估算，需按 §5 顺序实测校正。
