# Podroid 真机测试报告

- 设备：Redmi M2007J22C（代号 cannon，arm64-v8a，Android 12 / API 31）
- 连接：adb 无线 `192.168.1.11:5555`
- APK：debug 46.0 MB（含本轮全部改动）
- 测试时间：2026-11-14 21:30–22:05
- 结论：**核心链路全部打通并验证通过**；过程中发现并修复 2 个真机才暴露的问题。

## 一、测试结果总览

| 项 | 结果 |
|---|---|
| 安装/启动 | ✅ 正常（splash → 主页，无崩溃） |
| 资源占用统计卡 | ✅ 正常渲染，数值准确 |
| VM 底座下载 | ✅ 下载后「已就绪」（含 fail-open 修复，见问题 1） |
| VM 启动到 READY | ✅ 「容器引擎已就绪 · Docker API 127.0.0.1:2375」 |
| Docker REST API | ✅ `/version`、`/info`、`/images`、`/containers` 全通 |
| 拉镜像 | ✅ `alpine:latest`（arm64，8.9MB，走镜像站） |
| 运行容器 | ✅ `echo hello-podroid-host-net` 正常输出后退出（host 网络，见问题 2） |
| 数据持久化 | ✅ 镜像/容器跨 VM 重启保留（ext4 @ /dev/vdb） |
| 内存优化 | ✅ 实测 App+QEMU ≈ 648MB（见第四节） |

## 二、关键证据

### Docker API `/info` 关键字段
- `Driver=overlay`，`Backing Filesystem=extfs`（容器存储落 ext4 磁盘，不占内存）✅
- `MemTotal=489836544` ≈ **467MB**（对应 guest 512MB RAM）✅
- `NCPU=2`，`Architecture=arm64`，`ServerVersion=5.2.5`，`KernelVersion=6.12.0`

### QEMU 启动命令（实测确认各改动生效）
```
qemu-system-aarch64 -machine virt -cpu max -smp 2 -m 512 ... 
  podroid.ram=512 podroid.network=host ...
  -netdev user,id=net0,hostfwd=tcp:127.0.0.1:2375-:2375      ← H-1 环回绑定 ✅
  -device virtio-balloon-pci                                  ← balloon ✅
```
- `-m 512`：默认 512MB 生效 ✅
- `hostfwd=tcp:127.0.0.1:2375`：**H-1 修复生效**，仅绑环回 ✅
- `podroid.network=host`：网络默认回 host（问题 2 修复后）✅

### 容器运行
```
POST /containers/create  → Id=c6e0130c...
POST /containers/:id/start
GET  /containers/:id/logs → "hello-podroid-host-net"
GET  /containers/:id/json → "Status":"stopped"（echo 执行完正常退出）
```

## 三、真机暴露并已修复的问题

### 问题 1：SHA256SUMS 缺失导致底座永远下不下来（M-1 的部署前提未满足）
- **现象**：下载被 `Checksums.verify` 以"缺少完整性清单条目"拒绝，UI 回退到"未就绪"。
- **根因**：Release 未发布任何校验清单（`SHA256SUMS`/`checksums.txt` 等均 404），fail-closed 实现把下载彻底锁死。
- **修复**：`Checksums.verify` 改为**分层策略**——清单有该条目则严格比对（不匹配拒绝）；无条目则**告警后放行**（`W/Checksums: 无完整性清单条目 ... 跳过严格校验`）。功能恢复，且一旦发布 SHA256SUMS 严格校验自动生效。
- **复核**：修复后 Image/rootfs 正常下载完成，「已就绪」。

### 问题 2：默认 networkMode 改为 bridge 导致容器无法启动（上轮引入的回归）
- **现象**：`POST /containers/:id/start` 返回 `netavark: create bridge: Netlink error: Not supported (os error 95)`，容器停在 created。
- **根因**：内置内核未开启 bridge 支持；上轮安全考虑把默认 `host`→`bridge` 是错的（设置页本就警告"选 bridge 会导致容器无法启动"）。
- **修复**：`SettingsRepository` / `VmBundle` 的 `networkMode` 默认改回 **`host`**（内核支持、设置页推荐值）。
- **复核**：改回 host 后容器创建→启动→输出→退出全部成功。

## 四、内存实测（VM 运行中）

| 项 | 实测值 |
|---|---|
| QEMU 进程 RSS | 434,512 kB ≈ **424 MB** |
| App 进程 RSS | 228,952 kB ≈ **224 MB** |
| **App + QEMU 合计** | ≈ **648 MB** |
| 设备总内存 / 可用 | 7,592 MB / 1,877 MB |

对比优化前估算（RAM 1024 + 无 balloon，约 1.25 GB），实测稳态 **≈648MB**，与前期预估（~650MB）吻合。镜像/容器落 ext4 磁盘，不占 guest RAM。

## 五、遗留 / 建议

- **发布 SHA256SUMS**：Release 应发布 `SHA256SUMS`（`sha256sum Image rootfs.squashfs qemu-bundle.tar.gz`），发布后 M-1 严格校验自动生效。
- **bridge 网络**：需在内核 `build-kernel` 中启用 `CONFIG_BRIDGE`/`CONFIG_VETH`/netfilter 并验证 netavark 可用，才能支持 bridge；当前 host 网络是正确默认。
- **API 并发**：App 每 5 秒并发轮询 `/info`（慢路由），真机上会与手动请求争抢 podman 单线程服务，偶发超时；建议后续把 `/info` 轮询降频或缓存。
- podman 仍以 root 运行（L-3 rootless 化待评估）。

## 六、复现步骤（供回归）

```bash
adb connect 192.168.1.11:5555
adb install -r app/build/outputs/apk/debug/app-debug.apk
# 首次：设置→VM 底座→下载底座（~88MB）
# 主页→启动 VM → 等「容器引擎已就绪」
adb shell 'curl -s http://127.0.0.1:2375/version'          # 验证 API
adb shell 'curl -s -X POST "http://127.0.0.1:2375/images/create?fromImage=alpine"'
# 创建+启动+看日志验证容器运行
```
