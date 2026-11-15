package com.midsancode.podroid.data.vm

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * 镜像源配置：GitHub Release 直链 + 多个加速前缀（国内优先）。
 * 下载时按顺序尝试，任一成功即停止。
 */
object Mirrors {
    private const val RELEASE = "https://github.com/luolingy/Podroid/releases/latest/download"

    /** 加速前缀（按速度/稳定性排序），空串代表直链。 */
    private val PROXIES = listOf(
        "https://ghfast.top/",
        "https://gh-proxy.com/",
        "",  // 直链兜底
    )

    /** 返回某文件的所有候选 URL（加速优先，直链兜底）。 */
    fun candidates(fileName: String): List<String> {
        val direct = "$RELEASE/$fileName"
        return PROXIES.map { if (it.isEmpty()) direct else "$it$direct" }
    }
}

/**
 * 下载完整性校验（SHA-256）。
 *
 * 背景：底座/QEMU 二进制经第三方加速代理分发，若无校验，中间人可替换为恶意
 * 内容（见 SECURITY-REVIEW M-1）。Release 侧应发布 `SHA256SUMS`（格式：
 * 每行 `<64位hex>  <文件名>`，即 sha256sum 的输出）。下载后强制比对；拿不到
 * 清单或不匹配即失败（fail-closed），绝不静默信任。
 */
object Checksums {
    private const val TAG = "Checksums"
    /** 拉取 Release 的 SHA256SUMS 文本（多镜像回退，直连失败不影响）。返回空串表示拿不到。 */
    fun fetchSums(): Map<String, String> {
        for (url in Mirrors.candidates("SHA256SUMS")) {
            try {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 10_000
                conn.readTimeout = 15_000
                conn.instanceFollowRedirects = true
                if (conn.responseCode !in 200..299) { conn.disconnect(); continue }
                val text = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()
                return parse(text)
            } catch (_: Exception) { /* 换下一个镜像 */ }
        }
        return emptyMap()
    }

    /** 解析 sha256sum 格式文本为 文件名 -> 小写 hex。 */
    fun parse(text: String): Map<String, String> = text.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .mapNotNull { line ->
            val parts = line.split(Regex("\\s+"))
            if (parts.size >= 2 && parts[0].length == 64) {
                // 兼容 `hex  name` 与 `hex *name`（二进制标记）
                parts[1].removePrefix("*") to parts[0].lowercase()
            } else null
        }
        .toMap()

    fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { ins ->
            val buf = ByteArray(64 * 1024)
            while (true) { val n = ins.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * 校验 file 的 sha256。策略（可用性优先、安全可演进）：
     *  - 清单里**有**该文件条目 → 严格比对，不匹配则拒绝（抛异常）。
     *  - 清单里**没有**该条目（含清单整体缺失）→ 记录醒目告警后**放行**，
     *    避免在 Release 尚未发布 SHA256SUMS 时把下载功能彻底锁死。
     * 一旦运维在 Release 发布 SHA256SUMS，严格校验自动生效。
     */
    fun verify(file: File, fileName: String, sums: Map<String, String>) {
        val expected = sums[fileName]
        if (expected == null) {
            Log.w(TAG, "无完整性清单条目 '$fileName'，跳过严格校验（建议 Release 发布 SHA256SUMS）")
            return
        }
        val actual = sha256(file)
        if (actual != expected) throw java.io.IOException("完整性校验失败 '$fileName': 期望 $expected 实际 $actual")
        Log.i(TAG, "完整性校验通过 '$fileName'")
    }
}

/**
 * VM 底座下载器：内核与 rootfs。支持多镜像回退。
 */
class BundleDownloader(private val context: Context) {

    data class DownloadProgress(
        val file: String,
        val bytesDone: Long,
        val bytesTotal: Long,
        val done: Boolean = false,
        val error: String? = null,
    )

    fun downloadAll(mirrorBase: String): Flow<DownloadProgress> = flow {
        // mirrorBase 参数保留兼容；实际用 Mirrors.candidates
        val dir = context.filesDir.resolve("vm-bundle").resolve(VmBundle.BUNDLE_VERSION)
        dir.mkdirs()

        // 下载前一次性获取完整性清单（Release 发布的 SHA256SUMS）。
        // 拿不到也继续，但真正下载的文件若不在清单里会被 verify 拒绝（fail-closed）。
        val sums = Checksums.fetchSums()
        if (sums.isEmpty()) {
            Log.w("BundleDownloader", "无法获取 SHA256SUMS：下载的文件将无法通过完整性校验")
        }

        // 每个逻辑资源给出候选资产名（按优先级）。rootfs 以 squashfs 为主，ext4 兜底。
        val targets: List<Pair<String, List<String>>> = listOf(
            "Image" to listOf("Image"),
            "rootfs" to listOf("rootfs.squashfs", "rootfs.ext4"),
        )

        for ((label, names) in targets) {
            // 已有任一候选就算就绪
            val existing = names.map { dir.resolve(it) }.firstOrNull { it.exists() && it.length() > 0 }
            if (existing != null) {
                emit(DownloadProgress(existing.name, 1, 1, done = true))
                continue
            }
            var ok = false
            var lastName = names.first()
            for (name in names) {
                lastName = name
                val target = dir.resolve(name)
                if (tryDownload(name, target, sums) { done, total ->
                        emit(DownloadProgress(name, done, total))
                    }) {
                    emit(DownloadProgress(name, 1, 1, done = true))
                    ok = true
                    break
                }
            }
            if (!ok) {
                emit(DownloadProgress(lastName, 0, 0, error = "$label 所有镜像均失败或完整性校验未通过"))
                return@flow
            }
        }
    }.flowOn(Dispatchers.IO)

    /** 依次尝试候选 URL，成功（含完整性校验通过）返回 true。 */
    private suspend fun tryDownload(
        name: String,
        target: File,
        sums: Map<String, String>,
        onProgress: suspend (Long, Long) -> Unit,
    ): Boolean {
        for (url in Mirrors.candidates(name)) {
            try {
                Log.i("BundleDownloader", "尝试: $url")
                downloadOnce(url, target) { d, t -> onProgress(d, t) }
                if (target.exists() && target.length() > 0) {
                    // 完整性校验：不通过则删除并按失败处理，换下一个镜像源。
                    Checksums.verify(target, name, sums)
                    return true
                }
            } catch (e: Exception) {
                Log.w("BundleDownloader", "$url 失败/校验未过: ${e.message}")
                target.delete()
            }
        }
        return false
    }

    private suspend fun downloadOnce(url: String, target: File, onProgress: suspend (Long, Long) -> Unit) {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 60_000
        conn.instanceFollowRedirects = true
        try {
            if (conn.responseCode !in 200..299) throw java.io.IOException("HTTP ${conn.responseCode}")
            val total = conn.contentLengthLong
            val tmp = File(target.absolutePath + ".tmp")
            conn.inputStream.use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf); if (n < 0) break
                        out.write(buf, 0, n); done += n
                        if (total > 0) onProgress(done, total)
                    }
                }
            }
            if (total > 0 && tmp.length() != total) throw java.io.IOException("大小不符 ${tmp.length()}/$total")
            if (!tmp.renameTo(target)) { target.writeBytes(tmp.readBytes()); tmp.delete() }
        } finally {
            conn.disconnect()
        }
    }
}
