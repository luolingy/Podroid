package com.midsancode.podroid.data.vm

import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * 极简 TAR 解压（支持 GNU/ustar，处理 file 与 dir，忽略链接）。
 * 用于解压 qemu-bundle.tar.gz。
 */
object TarExtractor {

    fun extractTarGz(archive: File, destDir: File) {
        destDir.mkdirs()
        GZIPInputStream(archive.inputStream().buffered()).use { gz ->
            extractTarStream(gz, destDir)
        }
    }

    /**
     * 把 tar 内的相对条目名解析为 destDir 下的安全路径，阻止路径穿越（Zip-Slip）。
     *
     * 拒绝：绝对路径、含 `..` 段、或规范化后不在 destDir 之内的条目。命中即抛
     * 异常，避免恶意 tar 把文件写到 destDir 之外（详见 SECURITY-REVIEW M-2）。
     * 返回的 File 已用 canonicalPath 校验过，可安全用于 write/mkdirs。
     */
    private fun safeResolve(destDir: File, entryName: String): File {
        // 显式拒绝绝对路径（不同平台/实现的 File(parent, child) 行为不一致，必须前置拦截）。
        if (entryName.startsWith("/") || entryName.startsWith("\\")) {
            throw SecurityException("tar 条目为绝对路径（Zip-Slip）: $entryName")
        }
        val destRoot = destDir.canonicalFile
        val out = File(destRoot, entryName)
        val canonical = out.canonicalFile
        val rootPrefix = destRoot.path + File.separator
        if (canonical.path != destRoot.path && !canonical.path.startsWith(rootPrefix)) {
            throw SecurityException("tar 条目越界（Zip-Slip）: $entryName")
        }
        return out
    }

    private fun extractTarStream(input: InputStream, destDir: File) {
        val bin = BufferedInputStream(input)
        val header = ByteArray(512)
        var longName: String? = null

        while (true) {
            if (!readFully(bin, header, 512)) break
            if (header.all { it == 0.toByte() }) break

            val rawName = String(header, 0, 100, Charsets.UTF_8).trimEnd('\u0000')
            val sizeStr = String(header, 124, 12, Charsets.UTF_8).trim { it <= ' ' || it == '\u0000' }
            val size = sizeStr.toLongOrNull(8) ?: 0L
            val typeFlag = header[156].toInt().toChar()
            val prefix = String(header, 345, 155, Charsets.UTF_8).trimEnd('\u0000')
            val fullName = longName ?: if (prefix.isNotEmpty()) "$prefix/$rawName" else rawName
            longName = null

            // 跳过 data（512 对齐）
            val dataBlocks = ((size + 511) / 512)

            when (typeFlag) {
                'L' -> { // GNU long name
                    val bytes = ByteArray(size.toInt())
                    readFully(bin, bytes, bytes.size)
                    longName = String(bytes, Charsets.UTF_8).trimEnd('\u0000', '/')
                    skipPadding(bin, size)
                }
                '5' -> { // directory
                    safeResolve(destDir, fullName).mkdirs()
                }
                '0', '\u0000', '7' -> { // file
                    val out = safeResolve(destDir, fullName)
                    out.parentFile?.mkdirs()
                    copyN(bin, out, size)
                    out.setExecutable(true)  // 都设可执行，简单可靠
                    skipPadding(bin, size)
                }
                else -> { // link / other：跳过数据
                    skipN(bin, size)
                    skipPadding(bin, size)
                }
            }
        }
    }

    /** 数据已由 copyN 读 size 字节，这里只补 padding */
    private fun skipPadding(input: InputStream, size: Long) {
        val pad = (512 - (size % 512)) % 512
        skipN(input, pad)
    }

    private fun copyN(input: InputStream, out: File, n: Long) {
        out.outputStream().use { o ->
            var remaining = n
            val buf = ByteArray(64 * 1024)
            while (remaining > 0) {
                val toRead = minOf(buf.size.toLong(), remaining).toInt()
                val r = input.read(buf, 0, toRead)
                if (r < 0) break
                o.write(buf, 0, r)
                remaining -= r
            }
        }
    }

    private fun skipN(input: InputStream, n: Long) {
        var remaining = n
        val buf = ByteArray(64 * 1024)
        while (remaining > 0) {
            val r = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
            if (r < 0) break
            remaining -= r
        }
    }

    private fun readFully(input: InputStream, buf: ByteArray, len: Int): Boolean {
        var read = 0
        while (read < len) {
            val r = input.read(buf, read, len - read)
            if (r < 0) return false
            read += r
        }
        return true
    }
}
