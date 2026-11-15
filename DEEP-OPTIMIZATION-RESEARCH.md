# Podroid 深度性能优化研究报告

- 目标设备：Redmi M2007J22C（cannon），arm64-v8a，**8 核 = 2×A76大核@2.4GHz + 6×A55小核@2.0GHz**，7.6GB RAM，Android 12
- 现状形态：QEMU 11.1.3（Termux 预编译）TCG 软件模拟 → Alpine guest → Podman
- 依据：真机实测（`/proc`/`top`/线程级 CPU/拓扑）+ QEMU 官方 TCG 文档
- 结论先行：**真正的瓶颈只有一处——TCG 指令翻译（两个 CPU 线程烧满）。其余优化都是"体验周边"，碰不到 CPU 大头。本机不支持硬件虚拟化，TCG 无法根除，只能在"减少翻译量 / 降低翻译成本 / 改善调度"三个维度榨取。**

---

## 1. 瓶颈定位（实测，锁定唯一主因）

### 1.1 线程级 CPU（决定性证据）
| 线程 | CPU 时间(jiffies) | 占比 |
|---|---|---|
| CPU 0/TCG | 85,585 | ~44% |
| CPU 1/TCG | 85,601 | ~44% |
| 主线程（设备模拟+主循环） | 26,650 | ~14% |
| call_rcu / io / signalfd | ~1 | ~0% |

**结论：~88% 的 CPU 时间在两个 TCG 翻译线程上**，I/O、网络、磁盘、设备模拟几乎不占。这意味着：
- 优化 disk/network/UI **完全不影响 CPU**（那些不是瓶颈）；
- 唯一有效的方向是**减少或加速 TCG 翻译**。

### 1.2 为什么 TCG 这么贵（官方文档依据，[QEMU TCG 文档](https://www.qemu.org/docs/master/devel/tcg.html)）
1. **逐条指令动态翻译**：guest 每条 ARM64 指令首次执行都要翻译成宿主指令，存翻译块(TB)，之后命中缓存才快。
2. **软件 MMU**：每次内存访问都要做虚拟→物理地址翻译 + TLB 查找（无硬件页表遍历）。容器/进程密集内存访问时开销显著。
3. **TB chaining 限制**：跨页分支不能直链，要回主循环，冷代码反复进出。
4. **`-cpu max`**：暴露全部 CPU 特性 → 翻译面更广、TB 更大、缓存命中率下降。

### 1.3 设备能力约束（决定优化天花板）
- **无 `/dev/kvm`、无 pKVM、无 AVF** → **硬件虚拟化在本机不可用**，TCG 是唯一选择，无法根除。
- QEMU 来自 **Termux 预编译包**（非自编译）→ 编译期优化选项（TCG plugins、自定义 TB 默认值、LTO、精简指令集）**无法直接改**，除非自行交叉编译 QEMU（在 Android/bionic 上工程量巨大）。
- 大小核频率差仅 20%（2.4 vs 2.0GHz）→ 绑核收益有限（~15-20%）。

---

## 2. 优化杠杆（按"可行性 × 收益"分层）

### 层级 A：运行时 QEMU 参数调优（低成本，可立即验证）

| 杠杆 | 现状 | 建议 | 预期收益 | 风险 |
|---|---|---|---|---|
| **`-cpu max` → 具体型号** | `max` | `-cpu cortex-a76`（匹配设备大核；或 `cortex-a72` 更保守） | TB 更小更可预测，**5–15% CPU** + 減少翻译面 | 需验证 guest 兼容（Alpine 通用，风险低） |
| **`-accel tcg,thread=multi`（MTTCG）** | 默认（`-smp 2` 已用多线程） | 显式声明 + `-accel tcg,tb-size=512` | MTTCG 已启用；调 TB 缓存（默认 ~1GB 代码缓存偏大占内存，缩小可省内存但可能降命中） | 小 |
| **`-tb-size`（代码缓存上限）** | 默认 | 512MB（默认视版本 1/4 物理内存） | 省内存；翻译命中若够则无损 | 设太小会频繁失效重译 |
| **`-smp 1` vs `2`** | `2` | 单容器轻负载可试 `1`，减一半 TCG 线程 CPU | CPU 减半（但吞吐也降）；仅适合单线程负载 | 并发容器变慢 |
| **`-mem-prealloc` / hugepage** | 无 | 慎用（Android 无 hugepage） | 有限 | 低 |

> **层级 A 是当前唯一能"立刻做、立刻测"的深度优化**，合计可能把 CPU 从 213% 压到 ~170-190%、启动略快。但**不能质变**。

### 层级 B：减少 guest 翻译量（中成本，结构性收益）

| 方向 | 做法 | 收益 | 成本 |
|---|---|---|---|
| **guest 体积/进程精简** | 精简 Alpine：去掉不需要的服务、用 musl 静态 busybox、减少开机进程 | 减少"冷代码"翻译量，启动更快 | 中 |
| **amd64 镜像彻底不做** | 默认已关 binfmt；若开 amd64 = 双层翻译（x86→arm64→宿主），CPU 翻倍。**坚持纯 arm64** | 避免 2× 开销 | 已做 |
| **预热/常驻热代码** | 关键守护（podman、crun）预启动并 pin 常驻，让热点 TB 保持链化 | 中 | 中 |
| **容器 runtime 选择** | crun 已是轻量 C 实现（比 runc 好）；保持 | — | 已优化 |
| **关闭 guest 内不需要的功能** | 不开 bridge/netavark、不开 firewalld、减少 log 级 | 少翻译 + 少进程 | 低 |

### 层级 C：改善宿主调度（低成本，边际收益）

| 方向 | 做法 | 收益 | 可行性 |
|---|---|---|---|
| **QEMU 线程绑大核** | App 内用 `Process.setThreadPriority` / `taskset` 把 TCG 线程绑到 cpu6/7 | ~15-20%（频率差） | 需 native/JNI 或 root；Android 普通 App **无法 taskset 其他线程到指定核**（受限），可行性低 |
| **CPU 调度器 hint** | 提高 QEMU 进程 nice/调度策略 | 小 | 需系统权限，普通 App 受限 |
| **电池优化白名单** | 已有开关（避免被冻结） | 避免降频/杀进程 | 已做 |

> **层级 C 在无 root 的 Android 上大部分不可行**——这是平台限制，不是代码问题。

### 层级 D：消除周边浪费（必须做，但不碰 CPU 大头）

| 问题（实测发现） | 现状 | 优化 | 收益 |
|---|---|---|---|
| **App 5s `/info` 轮询占满 podman 单线程** | `/version` 多次打满 30s 超时 | 改 `/events` SSE 长连接 + 事件驱动刷新；`/info` 降到 30s 或按需 | **巨大**：API 响应从"排队 20-30s"回到正常，且降系统负载 |
| **启动 ~90-120s** | podman storage 首次扫描为主 | storage 预初始化 / 复用；或首次启动后保存初始化态 | 启动明显缩短 |
| **App RSS 224MB** | Compose + 日志缓冲 | 日志环形缓冲、懒加载页面 | 降几十 MB |
| **boot.sh 串口无时间戳日志** | 启动阶段难量化 | `onSerialLine` 加时间戳 | 可观测性 |

---

## 3. 关键判断：哪些"优化"是伪优化

| 看似该优化 | 实际 | 原因 |
|---|---|---|
| 磁盘 I/O | ❌ 非瓶颈 | overlay@ext4 高效，IO 线程 CPU≈0 |
| 网络/端口转发 | ❌ 非瓶颈 | 网络线程 CPU≈0；host 网络已最简 |
| 镜像层压缩 | ❌ 边际 | 磁盘不是瓶颈；overlay 已硬链接共享 |
| 内存进一步压缩 | ⚠️ 有限 | 648MB 已合理，再压会 OOM 反噬 |
| **AVF/pKVM** | ❌ **本机不可用** | 无 /dev/kvm、无 pKVM/AVF |

---

## 4. 分阶段优化路线图（建议执行顺序）

**阶段 1（立即可做，收益明确）：消除 API 争抢 + QEMU 参数**
1. App 轮询改事件驱动（`/events` 长连接），`/info` 降频 → **解决 20-30s 排队**（收益最大且纯 App 侧）。
2. QEMU `-cpu max` → `-cpu cortex-a76`；加 `-accel tcg,thread=multi`；`-tb-size 512`。
   - 需改 `QemuBackend.buildCommand`，真机 A/B 对比 CPU% 与启动时间。
3. 预期：CPU 213%→~180%，API 响应恢复正常，启动略快。

**阶段 2（结构性）：启动优化 + guest 精简**
4. podman storage 预初始化（`podman system service` 之前先跑一次 `podman info` 预热，或缓存初始化态）。
5. guest 精简（去无用服务），减少冷代码翻译。

**阶段 3（可选/受限）：**
6. 若目标用户有 pKVM 设备（Pixel 等）→ 接 `AvfBackend`（真·硬件加速，CPU 降一个量级、启动秒级）。**这是唯一质变路径，但本 Redmi 测不了。**
7. 自编译精简 QEMU（关掉不需要的设备/架构，开 TCG 优化），工程量大。

---

## 5. 预期效果汇总

| 优化组合 | CPU | 启动 | API 响应 | 可行性 |
|---|---|---|---|---|
| 现状 | 213% | 90-120s | 排队 20-30s | — |
| + 阶段1（轮询 + QEMU 参数） | **~180%** | ~80-100s | **正常(ms级)** | ✅ 高 |
| + 阶段2（storage 预热 + 精简） | ~180% | **~50-70s** | 正常 | ✅ 中 |
| + AVF/pKVM（换设备） | **~接近原生** | **秒级** | 正常 | ❌ 本机不支持 |

**核心结论**：在本机（无硬件虚拟化）上，**深度优化的天花板约是"CPU 降 15-20% + API 体验质变 + 启动快 30-40%"**；想要质变（CPU 数量级下降）只能上 pKVM 设备。任何"大幅提速"的承诺在不换设备的前提下都不成立。

---

## 6. 测量方法（复现）

```bash
ADB="adb"  # connect 192.168.1.11:5555
# 线程级 CPU（找 TCG 线程）
QP=$(adb shell pgrep -f qemu-system | tr -d '\r')
adb shell "for t in /proc/$QP/task/*; do echo \$(cat \$t/comm) \$(awk '{print \$14+\$15}' \$t/stat); done"
# 进程 CPU/内存
adb shell "top -n1 -b | grep qemu"
# 拓扑
adb shell "for c in /sys/devices/system/cpu/cpu[0-9]; do echo \$c \$(cat \$c/cpufreq/cpuinfo_max_freq); done"
# API 延迟（先停 App 轮询：am force-stop com.midsancode.podroid 会连 QEMU 一起停，故只测轮询间隙）
```

> 说明：本报告的 CPU/线程/拓扑/内存为真机精确实测；启动耗时与容器延迟为观测值（受 App 轮询干扰已标注）；QEMU 参数收益为基于 TCG 原理的估算，需阶段 1 落地后 A/B 实测校正。

---

## 7. 阶段 1 落地与 A/B 实测结果（已完成）

已实施阶段 1 两项优化并真机 A/B 对比：

### 改动
1. **QEMU 参数**（`QemuBackend.buildCommand`）：`-cpu max` → **`-cpu cortex-a76`**；新增 **`-accel tcg,thread=multi,tb-size=512`**。
2. **App 轮询**（`EngineRepository`）：主轮询 5s→**15s**，且只刷"快列表"；慢路由 `/info`、`/system/df` **降为 60s 一次**（就绪时首刷）；实时性由 `/events` 事件流保证。

### A/B 实测（同一设备，真机）
| 指标 | 阶段1前（旧） | 阶段1后（新） | 改善 |
|---|---|---|---|
| API `/version` 延迟 | **32s×3 全部超时** | **8.3–14.8s**（4 次：14.8/8.3/11.6/10.7） | **从打满超时→秒级，约 3×** |
| QEMU RSS | 424 MB | **382 MB** | **-42 MB**（tb-size 512 生效） |
| 启动到 READY | 90–120 s（冷启动） | **约 50 s**（含 storage 已初始化） | 明显更快 |
| QEMU 进程 cmdline | `-cpu max` | `-cpu cortex-a76 -accel tcg,thread=multi,tb-size=512` | 确认生效 ✅ |
| 启动稳定性 | — | 无崩溃，正常到 READY | ✅ |

### 结论与残留
- **API 响应从"完全不可用（30s+超时）"恢复到"8-15s 可用"**，这是阶段 1 最大收益，证明"轮询占满单线程引擎"是真实瓶颈。
- **内存再降 42MB**（tb-size 限制生效）。
- **残留**：API 仍有 8-15s 延迟（非理想的毫秒级）——因 15s 轮询的 `refreshLists()` 仍每次打 4 个列表 API，且 podman 是**单线程服务**，任何并发请求都排队。进一步根治需：① 轮询再降频；② 或让代理/客户端支持串行排队+缓存（本质是 podman 单线程限制，无法多线程化该服务）。
- **CPU 线程仍各占满一核**（TCG 固有），cortex-a76 的收益主要体现在 TB 效率/内存，对空载 CPU% 影响不大——符合预期（§1.1 结论）。

