package com.midsancode.podroid

import com.midsancode.podroid.data.docker.buildPullQuery
import com.midsancode.podroid.data.registry.PopularImages
import com.midsancode.podroid.data.registry.RegistryRef
import com.midsancode.podroid.data.registry.sortTagsLatestFirst
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 镜像引用解析 + tag 排序 + 内置库过滤的单测。
 *
 * 这些是纯逻辑，不碰网络也不碰 Android，所以放在普通 JVM 单测里跑。
 * 解析规则一旦写错，用户输入 `nginx:1.25` 会被拼成 `library/nginx:1.25:1.25`
 * 之类的怪东西，拉取直接 404，所以值得钉住。
 */
class RegistryRefTest {

    @Test
    fun `bare name becomes library path`() {
        assertEquals("library/nginx" to null, RegistryRef.parse("nginx"))
        assertEquals("library/alpine" to null, RegistryRef.parse("  alpine  "))
    }

    @Test
    fun `name with tag splits cleanly`() {
        assertEquals("library/nginx" to "1.25", RegistryRef.parse("nginx:1.25"))
        assertEquals("library/nginx" to "alpine", RegistryRef.parse("nginx:alpine"))
    }

    @Test
    fun `namespaced repo keeps its namespace`() {
        assertEquals("bitnami/redis" to null, RegistryRef.parse("bitnami/redis"))
        assertEquals("luolingy/new-api" to "main", RegistryRef.parse("luolingy/new-api:main"))
    }

    @Test
    fun `explicit docker io host is stripped`() {
        assertEquals("library/nginx" to "1.25", RegistryRef.parse("docker.io/library/nginx:1.25"))
        assertEquals("library/nginx" to null, RegistryRef.parse("index.docker.io/library/nginx"))
        assertEquals("library/nginx" to null, RegistryRef.parse("registry-1.docker.io/library/nginx"))
    }

    @Test
    fun `third party registries keep their host`() {
        // 关键回归：早先把 host 一律剥掉，ghcr.io/foo/bar 会变成 foo/bar，
        // 然后去 docker.io 拉一个同名但不相干的镜像 —— 静默拉错比报错更糟。
        assertEquals("ghcr.io/foo/bar" to "latest", RegistryRef.parse("ghcr.io/foo/bar:latest"))
        assertEquals("quay.io/coreos/etcd" to null, RegistryRef.parse("quay.io/coreos/etcd"))
    }

    @Test
    fun `localhost with port keeps host and parses tag`() {
        assertEquals("localhost:5000/myimg" to null, RegistryRef.parse("localhost:5000/myimg"))
        assertEquals("localhost:5000/myimg" to "v1", RegistryRef.parse("localhost:5000/myimg:v1"))
    }

    @Test
    fun `host detection`() {
        assertEquals("ghcr.io", RegistryRef.hostOf("ghcr.io/foo/bar"))
        assertEquals("localhost:5000", RegistryRef.hostOf("localhost:5000/x"))
        // 官方/命名空间路径没有 host
        assertNull(RegistryRef.hostOf("library/nginx"))
        assertNull(RegistryRef.hostOf("bitnami/redis"))
        assertNull(RegistryRef.hostOf("nginx"))
    }

    @Test
    fun `digest is dropped`() {
        assertEquals("library/nginx" to null, RegistryRef.parse("nginx@sha256:abcdef"))
    }

    @Test
    fun `scheme prefix is tolerated`() {
        assertEquals("library/nginx" to null, RegistryRef.parse("https://nginx"))
    }

    @Test
    fun `display name hides library prefix only`() {
        assertEquals("nginx", RegistryRef.displayName("library/nginx"))
        assertEquals("bitnami/redis", RegistryRef.displayName("bitnami/redis"))
    }

    @Test
    fun `empty input does not explode`() {
        assertEquals("" to null, RegistryRef.parse(""))
        assertNull(RegistryRef.parse("   ").second)
    }

    @Test
    fun `latest is hoisted but order otherwise preserved`() {
        assertEquals(
            listOf("latest", "1", "1-alpine", "alpine"),
            sortTagsLatestFirst(listOf("1", "1-alpine", "alpine", "latest")),
        )
    }

    @Test
    fun `sorting without latest keeps original order`() {
        val tags = listOf("v3", "v1", "v2")
        assertEquals(tags, sortTagsLatestFirst(tags))
    }

    @Test
    fun `sorting handles tiny lists`() {
        assertEquals(emptyList<String>(), sortTagsLatestFirst(emptyList()))
        assertEquals(listOf("latest"), sortTagsLatestFirst(listOf("latest")))
    }

    @Test
    fun `popular list is filtered case insensitively`() {
        assertTrue(PopularImages.filter("REDIS").contains("redis"))
        assertTrue(PopularImages.filter("ngin").contains("nginx"))
        assertEquals(PopularImages.ALL, PopularImages.filter(""))
        assertTrue(PopularImages.filter("zzz-not-exist").isEmpty())
    }

    @Test
    fun `popular list has no duplicates and no stray whitespace`() {
        val all = PopularImages.ALL
        assertEquals(all.size, all.distinct().size)
        assertTrue(all.all { it == it.trim() && it.isNotEmpty() })
        // 官方镜像名不应带 library/ 前缀，前缀由 normalizeRepo 补
        assertTrue(all.none { it.startsWith("library/") })
    }

    // ---- 拉取 query 拼装 ----

    @Test
    fun `pull query splits tag into its own parameter`() {
        // 回归：整串塞进 fromImage 会拉出 <none>:<none> 的游离镜像
        assertEquals(
            "fromImage=library%2Fnginx&tag=1-alpine",
            buildPullQuery("nginx:1-alpine"),
        )
    }

    @Test
    fun `pull query without tag omits tag param`() {
        assertEquals("fromImage=library%2Fnginx", buildPullQuery("nginx"))
    }

    @Test
    fun `pull query normalises docker hub prefix and keeps tag`() {
        assertEquals(
            "fromImage=library%2Falpine&tag=3.20",
            buildPullQuery("docker.io/library/alpine:3.20"),
        )
    }

    @Test
    fun `pull query keeps third party host`() {
        assertEquals(
            "fromImage=ghcr.io%2Ffoo%2Fbar&tag=latest",
            buildPullQuery("ghcr.io/foo/bar:latest"),
        )
    }

    @Test
    fun `pull query keeps namespaced repo`() {
        assertEquals(
            "fromImage=luolingy%2Fnew-api&tag=main",
            buildPullQuery("luolingy/new-api:main"),
        )
    }
}
