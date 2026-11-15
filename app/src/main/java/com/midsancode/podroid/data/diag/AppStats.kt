package com.midsancode.podroid.data.diag

import java.io.File

/**
 * 运行时资源占用快照（纯 /proc 读取，无权限要求）。
 *
 * 覆盖三层：
 *  - App 自身进程 RSS（/proc/self/status VmRSS）
 *  - QEMU 进程 RSS（扫描 /proc/<pid>/cmdline 找 qemu-system*，读其 VmRSS）
 *  - 设备整体内存（/proc/meminfo MemTotal / MemAvailable）
 *
 * 全部为 best-effort：任何一项读失败仅该项返回 null，不影响其余项与 UI。
 */
data class ResourceSnapshot(
    val appRssBytes: Long?,        // App 进程常驻内存
    val qemuRssBytes: Long?,       // QEMU 进程常驻内存（未运行时 null）
    val guestRamConfiguredMb: Int?,// guest 配置的 RAM（来自设置，便于对照）
    val deviceTotalBytes: Long?,   // 设备物理内存总量
    val deviceAvailBytes: Long?,   // 设备当前可用内存
    val sampledAt: Long = System.currentTimeMillis(),
) {
    /** App + QEMU 合计常驻（用户最关心的"这个 App 到底吃多少"）。 */
    val totalOwnRssBytes: Long?
        get() = if (appRssBytes == null && qemuRssBytes == null) null
        else (appRssBytes ?: 0L) + (qemuRssBytes ?: 0L)
}

object AppStats {

    /** 采集一次快照。guestRamConfiguredMb 由调用方传入（来自设置）。 */
    fun snapshot(guestRamConfiguredMb: Int? = null): ResourceSnapshot {
        val mi = readMemInfo()
        return ResourceSnapshot(
            appRssBytes = readVmRss("/proc/self/status"),
            qemuRssBytes = findQemuRss(),
            guestRamConfiguredMb = guestRamConfiguredMb,
            deviceTotalBytes = mi?.first,
            deviceAvailBytes = mi?.second,
        )
    }

    /** 从 status 文件解析 VmRSS（单位 kB → 字节）。 */
    private fun readVmRss(statusPath: String): Long? = runCatching {
        File(statusPath).useLines { lines ->
            lines.firstOrNull { it.startsWith("VmRSS:") }
                ?.trim()
                ?.removePrefix("VmRSS:")
                ?.trim()
                ?.split(Regex("\\s+"))
                ?.firstOrNull()
                ?.toLongOrNull()
                ?.let { it * 1024L }
        }
    }.getOrNull()

    /**
     * 扫描 /proc 找 qemu-system-* 进程，返回其 VmRSS。
     * 用 cmdline 判断（可执行名是 libqemu-system-aarch64.so，argv[0] 为绝对路径）。
     */
    private fun findQemuRss(): Long? = runCatching {
        val proc = File("/proc")
        val pids = proc.listFiles { f -> f.name.all { it.isDigit() } } ?: return@runCatching null
        for (pidDir in pids) {
            val cmdline = runCatching {
                File(pidDir, "cmdline").readBytes()
                    .toString(Charsets.UTF_8)
                    .replace('\u0000', ' ')
            }.getOrNull() ?: continue
            if (cmdline.contains("qemu-system-aarch64") || cmdline.contains("qemu-system-x86_64")) {
                // 找到 QEMU：读它的 VmRSS（优先 pidDir 下的 status）
                val rss = readVmRss(File(pidDir, "status").path)
                if (rss != null && rss > 0) return@runCatching rss
            }
        }
        null
    }.getOrNull()

    /** 读 /proc/meminfo 的 MemTotal 与 MemAvailable（kB → 字节）。 */
    private fun readMemInfo(): Pair<Long, Long>? = runCatching {
        var total: Long? = null
        var avail: Long? = null
        File("/proc/meminfo").useLines { lines ->
            for (line in lines) {
                when {
                    line.startsWith("MemTotal:") ->
                        total = line.split(Regex("\\s+"))[1].toLongOrNull()?.times(1024L)
                    line.startsWith("MemAvailable:") ->
                        avail = line.split(Regex("\\s+"))[1].toLongOrNull()?.times(1024L)
                }
                if (total != null && avail != null) break
            }
        }
        if (total != null && avail != null) total to avail else null
    }.getOrNull()
}
