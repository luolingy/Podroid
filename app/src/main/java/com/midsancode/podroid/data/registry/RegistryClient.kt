package com.midsancode.podroid.data.registry

import com.midsancode.podroid.data.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** 搜索结果条目。 */
data class ImageSearchHit(
    /** registry 内的仓库路径，如 `library/nginx`、`luolingy/new-api`。 */
    val repo: String,
    /** 展示名，官方镜像去掉 `library/` 前缀。 */
    val display: String,
    val description: String,
    val stars: Int,
    val official: Boolean,
    val pulls: Long,
)

/** tag 列表结果：`source` 是实际命中的镜像站，供 UI 提示。 */
data class TagListResult(
    val tags: List<String>,
    val source: String?,
    /** 所有镜像站都失败时的错误摘要；成功时为 null。 */
    val error: String? = null,
)

/**
 * 镜像仓库客户端：Docker Hub 搜索 + 镜像站 registry v2 的 tag 列表。
 *
 * 两个接口走的网络路径完全不同，必须分开看：
 * - **搜索**只能打 `hub.docker.com`，国内基本被墙（实测绕代理也是 000）。所以返回
 *   [SearchOutcome.Unreachable] 让 UI 回退到内置常用镜像列表，而不是假装"没搜到"。
 * - **tag 列表**走 registry v2 `/v2/{repo}/tags/list`，可以直接用设置里的镜像站，
 *   国内可用。但各站行为不一致，需要逐个试：
 *   - `docker.m.daocloud.io` 会返回 `401` + Bearer 挑战，拿 token 后却回
 *     `{"name":"disable-list-tags","tags":[]}` —— 它**主动禁用了** tag 列表；
 *   - `docker.1panel.live` 直接 200 返回全量 tag；
 *   - `hub.rat.dev` 回 302。
 *   因此判据是"返回非空 tags"，空数组要当成失败继续试下一家。
 */
class RegistryClient(private val settings: SettingsRepository) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    // ---- 搜索 ----

    sealed interface SearchOutcome {
        data class Ok(val hits: List<ImageSearchHit>) : SearchOutcome
        /** 网络不可达 / 超时 / 非 2xx。UI 应回退内置列表。 */
        data object Unreachable : SearchOutcome
    }

    suspend fun search(query: String, limit: Int = 25): SearchOutcome = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.isEmpty()) return@withContext SearchOutcome.Ok(emptyList())
        val url = "https://hub.docker.com/v2/search/repositories/" +
            "?query=${java.net.URLEncoder.encode(q, "UTF-8")}&page_size=$limit"
        runCatching {
            http.newCall(Request.Builder().url(url).header("Accept", "application/json").build())
                .execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext SearchOutcome.Unreachable
                    val body = resp.body?.string().orEmpty()
                    SearchOutcome.Ok(parseSearch(body))
                }
        }.getOrElse { SearchOutcome.Unreachable }
    }

    private fun parseSearch(body: String): List<ImageSearchHit> {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return emptyList()
        val results = root["results"]?.jsonArray ?: return emptyList()
        return results.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val name = o["repo_name"]?.jsonPrimitive?.contentOrNullSafe() ?: return@mapNotNull null
            val official = o["is_official"]?.jsonPrimitive?.booleanOrNull ?: false
            ImageSearchHit(
                repo = if (official && !name.contains('/')) "library/$name" else name,
                display = name,
                description = o["short_description"]?.jsonPrimitive?.contentOrNullSafe().orEmpty(),
                stars = o["star_count"]?.jsonPrimitive?.intOrNull ?: 0,
                official = official,
                pulls = o["pull_count"]?.jsonPrimitive?.longOrNull ?: 0L,
            )
        }
    }

    // ---- tag 列表 ----

    /**
     * 拉取某仓库的 tag 列表。按设置里的镜像站顺序逐个尝试，命中第一个返回非空 tags 的源。
     *
     * @param repoPath registry 内的仓库路径（`library/nginx`），调用方需先经 [RegistryRef.normalizeRepo]
     */
    suspend fun tags(repoPath: String): TagListResult = withContext(Dispatchers.IO) {
        // 自带 registry host 的（ghcr.io/foo/bar）直连该 host —— 镜像站只对 docker.io 有意义
        val directHost = RegistryRef.hostOf(repoPath)
        val candidates: List<String>
        val path: String
        if (directHost != null) {
            candidates = listOf(directHost)
            path = repoPath.substringAfter('/')
        } else {
            candidates = mirrorHosts()
            path = repoPath
        }
        if (candidates.isEmpty()) return@withContext TagListResult(emptyList(), null, "no mirror configured")

        val failures = mutableListOf<String>()
        for (host in candidates) {
            val tags = runCatching { fetchTags(host, path) }.getOrNull()
            if (!tags.isNullOrEmpty()) {
                return@withContext TagListResult(sortTags(tags), host, null)
            }
            failures += host
        }
        TagListResult(emptyList(), null, failures.joinToString(", "))
    }

    /** 命中 `latest` 优先，其余保持服务端顺序（registry 返回的通常是字母序，可预期）。 */
    private fun sortTags(tags: List<String>): List<String> = sortTagsLatestFirst(tags)

    private fun mirrorHosts(): List<String> {
        val raw = settings.current.value.effectiveMirrors
        return raw.split(',', ' ', '\n', '\t')
            .map { it.trim().removePrefix("https://").removePrefix("http://").trimEnd('/') }
            .filter { it.isNotEmpty() }
            .distinct()
    }

    /** 单源取 tag。遇到 401 会按 WWW-Authenticate 换一次 Bearer token 重试。 */
    private fun fetchTags(host: String, repoPath: String): List<String>? {
        val url = "https://$host/v2/$repoPath/tags/list"
        val plain = Request.Builder().url(url).header("Accept", "application/json").build()

        http.newCall(plain).execute().use { resp ->
            var body: String? = null
            when {
                resp.isSuccessful -> body = resp.body?.string()

                resp.code == 401 -> {
                    val token = fetchToken(resp.header("WWW-Authenticate")) ?: return null
                    val authed = Request.Builder().url(url)
                        .header("Accept", "application/json")
                        .header("Authorization", "Bearer $token")
                        .build()
                    http.newCall(authed).execute().use { r2 ->
                        if (!r2.isSuccessful) return null
                        body = r2.body?.string()
                    }
                }

                else -> return null
            }
            return parseTags(body)
        }
    }

    private fun parseTags(body: String?): List<String>? {
        if (body.isNullOrBlank()) return null
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
        val arr = root["tags"] ?: return null
        // tags 可能是 null（仓库存在但无 tag）
        val list = runCatching { arr.jsonArray }.getOrNull() ?: return emptyList()
        return list.mapNotNull { runCatching { it.jsonPrimitive.contentOrNullSafe() }.getOrNull() }
            .filter { it.isNotBlank() }
    }

    /** 解析 `Bearer realm="...",service="...",scope="..."` 并换 token。 */
    private fun fetchToken(challenge: String?): String? {
        if (challenge == null || !challenge.startsWith("Bearer", ignoreCase = true)) return null
        val params = challenge.removePrefix("Bearer").removePrefix("bearer")
            .split(',')
            .mapNotNull { part ->
                val i = part.indexOf('=')
                if (i <= 0) null
                else part.substring(0, i).trim() to part.substring(i + 1).trim().trim('"')
            }
            .toMap()
        val realm = params["realm"] ?: return null
        val sb = StringBuilder(realm)
        val query = buildList {
            params["service"]?.let { add("service=${java.net.URLEncoder.encode(it, "UTF-8")}") }
            params["scope"]?.let { add("scope=${java.net.URLEncoder.encode(it, "UTF-8")}") }
        }
        if (query.isNotEmpty()) sb.append(if (realm.contains('?')) "&" else "?").append(query.joinToString("&"))

        return runCatching {
            http.newCall(Request.Builder().url(sb.toString()).header("Accept", "application/json").build())
                .execute().use { resp ->
                    if (!resp.isSuccessful) return@use null
                    val o = json.parseToJsonElement(resp.body?.string().orEmpty()).jsonObject
                    // Docker token 端点两种字段名都出现过
                    o["token"]?.jsonPrimitive?.contentOrNullSafe()
                        ?: o["access_token"]?.jsonPrimitive?.contentOrNullSafe()
                }
        }.getOrNull()
    }

    private fun kotlinx.serialization.json.JsonPrimitive.contentOrNullSafe(): String? =
        runCatching { content }.getOrNull()?.takeIf { it != "null" }
}

/**
 * `latest` 提到最前，其余保持原序。
 *
 * 单独抽成顶层函数是为了能在 JVM 单测里直接验证 —— [RegistryClient] 依赖
 * SettingsRepository（要 Android Context），普通单测构造不出来。
 */
internal fun sortTagsLatestFirst(tags: List<String>): List<String> {
    if (tags.size <= 1) return tags
    val latest = tags.filter { it == "latest" }
    if (latest.isEmpty()) return tags
    return latest + tags.filter { it != "latest" }
}

/**
 * 镜像引用解析：把用户随手输入的一串东西归一化成 registry 路径 + tag。
 *
 * 支持的输入形态（都实测过）：
 * ```
 * nginx                     -> library/nginx
 * nginx:1.25                -> library/nginx       + tag=1.25
 * bitnami/redis             -> bitnami/redis
 * docker.io/library/nginx   -> library/nginx
 * ghcr.io/foo/bar:latest    -> foo/bar             (host 被剥离)
 * nginx@sha256:abc          -> library/nginx       (digest 被剥离)
 * ```
 */
object RegistryRef {

    /** Docker Hub 的几种写法，等价于"官方仓库"，需要把 host 剥掉才能对上镜像站。 */
    private val DOCKER_HUB_ALIASES = setOf(
        "docker.io", "www.docker.io", "index.docker.io", "registry-1.docker.io",
    )

    /** 拆出 (仓库路径, tag)。仓库路径已归一化。 */
    fun parse(input: String): Pair<String, String?> {
        val raw = input.trim()
        val noDigest = raw.substringBefore('@')
        val body = stripDockerHubHost(noDigest)
        val slash = body.lastIndexOf('/')
        val colon = body.lastIndexOf(':')
        return if (colon > slash && colon >= 0) {
            normalizeRepo(body.substring(0, colon)) to body.substring(colon + 1).ifBlank { null }
        } else {
            normalizeRepo(body) to null
        }
    }

    fun normalizeRepo(input: String): String {
        var s = input.trim().removePrefix("https://").removePrefix("http://")
        s = stripDockerHubHost(s)
        s = s.trim('/')
        if (s.isEmpty()) return s
        // 单段名（nginx）在 docker.io 里属于官方仓库 library/
        return if (!s.contains('/')) "library/$s" else s
    }

    /** 展示用：官方镜像去掉 `library/` 前缀。 */
    fun displayName(repoPath: String): String = repoPath.removePrefix("library/")

    /**
     * 若仓库路径自带 registry host（`ghcr.io/foo/bar`），返回该 host；否则 null。
     *
     * 判据同 Docker：首段含 `.` 或 `:`，或等于 localhost。
     */
    fun hostOf(repoPath: String): String? {
        val i = repoPath.indexOf('/')
        if (i <= 0) return null
        val head = repoPath.substring(0, i)
        val isHost = head.contains('.') || head.contains(':') || head == "localhost"
        return if (isHost) head else null
    }

    /**
     * 只剥 Docker Hub 的别名 host。
     *
     * 早期版本把所有 host 都剥掉，`ghcr.io/foo/bar` 会变成 `foo/bar`，然后去 docker.io
     * 拉一个同名但完全不相干的镜像 —— 静默拉错镜像比报错糟糕得多。现在第三方 registry
     * 的 host 原样保留，拉取和 tag 列表都直连该 host。
     */
    private fun stripDockerHubHost(s: String): String {
        val i = s.indexOf('/')
        if (i <= 0) return s
        val head = s.substring(0, i).lowercase()
        return if (head in DOCKER_HUB_ALIASES) s.substring(i + 1) else s
    }
}

/**
 * 内置常用镜像：Docker Hub 搜索不可达时的兜底。
 *
 * 只放名字不放描述 —— 描述要么是英文（技术名词还算能看），要么得进 i18n 表翻 60 条，
 * 收益不抵成本。用户看到名字基本就知道是什么。
 */
object PopularImages {
    val ALL: List<String> = listOf(
        // 基础系统
        "alpine", "ubuntu", "debian", "busybox", "centos", "rockylinux", "almalinux",
        // Web / 反向代理
        "nginx", "httpd", "caddy", "traefik", "haproxy", "openresty", "tomcat",
        // 数据库
        "mysql", "mariadb", "postgres", "mongo", "redis", "valkey", "memcached",
        "clickhouse", "influxdb", "cassandra", "neo4j", "elasticsearch",
        // 消息队列
        "rabbitmq", "kafka", "nats", "emqx", "zookeeper",
        // 语言运行时
        "node", "python", "openjdk", "golang", "rust", "php", "ruby", "deno", "bun",
        "maven", "gradle", "dotnet-sdk",
        // 工具 / 运维
        "git", "curl", "jq", "openssh", "registry", "gitea", "drone", "jenkins",
        "grafana", "prometheus", "loki", "portainer", "watchtower", "cadvisor",
        // 存储 / 文件
        "minio", "nextcloud", "syncthing", "vsftpd", "alist",
        // 其他常见
        "wordpress", "ghost", "uptime-kuma", "n8nio/n8n", "louislam/uptime-kuma",
        "bitnami/redis", "bitnami/postgresql", "bitnami/mongodb", "bitnami/nginx",
    ).distinct()

    /** 按关键词过滤内置列表；空关键词返回全部。 */
    fun filter(query: String): List<String> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return ALL
        return ALL.filter { it.lowercase().contains(q) }
    }
}
