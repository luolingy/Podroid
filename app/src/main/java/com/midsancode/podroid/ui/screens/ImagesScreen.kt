package com.midsancode.podroid.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.midsancode.podroid.R
import com.midsancode.podroid.data.docker.EngineRepository
import com.midsancode.podroid.data.docker.model.ImageSummary
import com.midsancode.podroid.data.registry.ImageSearchHit
import com.midsancode.podroid.data.registry.PopularImages
import com.midsancode.podroid.data.registry.RegistryClient
import com.midsancode.podroid.data.registry.RegistryRef
import com.midsancode.podroid.data.vm.VmManager
import com.midsancode.podroid.data.vm.VmStatus
import com.midsancode.podroid.ui.components.PodroidDialog
import com.midsancode.podroid.ui.components.PodroidTonalButton
import com.midsancode.podroid.ui.components.EmptyState
import com.midsancode.podroid.ui.components.ScreenHeader
import com.midsancode.podroid.ui.components.podroidCardColors
import com.midsancode.podroid.ui.theme.Tone
import com.midsancode.podroid.ui.theme.toneColors
import kotlinx.coroutines.launch

/**
 * 镜像列表 + 拉取/构建对话框。
 */
@Composable
fun ImagesScreen(
    repo: EngineRepository,
    registry: RegistryClient,
    modifier: Modifier = Modifier,
) {
    val state by repo.images.collectAsState()
    val engineOk by repo.engineAvailable.collectAsState()
    val vmStatus by VmManager.status.collectAsState()
    var showPullDialog by remember { mutableStateOf(false) }
    var showBuildDialog by remember { mutableStateOf(false) }

    Column(
        modifier.fillMaxSize().padding(horizontal = 16.dp).padding(top = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ScreenHeader(
            title = stringResource(R.string.images_title),
            subtitle = if (state.items.isEmpty()) stringResource(R.string.images_sub_empty)
            else stringResource(
                R.string.images_sub_count,
                state.items.size,
                formatSize(state.items.sumOf { it.Size }),
            ),
            trailing = {
                if (state.loading) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                IconButton(onClick = { repo.refreshImages() }) {
                    Icon(
                        Icons.Rounded.Refresh,
                        contentDescription = stringResource(R.string.common_refresh),
                    )
                }
            },
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PodroidTonalButton(onClick = { showPullDialog = true }, enabled = engineOk) {
                Text(stringResource(R.string.images_search_title))
            }
            PodroidTonalButton(onClick = { showBuildDialog = true }, enabled = engineOk) {
                Text(stringResource(R.string.images_build))
            }
        }

        when {
            vmStatus !is VmStatus.Ready -> EmptyState(
                icon = Icons.Rounded.Inventory2,
                title = stringResource(R.string.images_not_ready_title),
                message = when (vmStatus) {
                    is VmStatus.Stopped -> stringResource(R.string.images_not_ready_stopped)
                    is VmStatus.Booting -> stringResource(R.string.images_not_ready_booting)
                    is VmStatus.Error -> stringResource(R.string.images_not_ready_error)
                    else -> "…"
                },
            )

            !engineOk -> EmptyState(
                icon = Icons.Rounded.Inventory2,
                title = stringResource(R.string.images_engine_off_title),
                message = stringResource(R.string.images_engine_off_msg),
            )

            state.items.isEmpty() -> EmptyState(
                icon = Icons.Rounded.Inventory2,
                title = stringResource(R.string.images_empty_title),
                message = stringResource(R.string.images_empty_msg),
            )

            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(state.items, key = { it.Id }) { img -> ImageCard(img, repo) }
                item { Spacer(Modifier.size(80.dp)) }
            }
        }
    }

    if (showPullDialog) {
        ImagePullDialog(repo, registry, onDismiss = { showPullDialog = false })
    }
    if (showBuildDialog) {
        BuildDialog(repo, onDismiss = { showBuildDialog = false })
    }
}

@Composable
private fun ImageCard(img: ImageSummary, repo: EngineRepository) {
    Card(Modifier.fillMaxWidth(), colors = podroidCardColors()) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                val tag = img.RepoTags.firstOrNull()
                Text(
                    tag ?: "<none>:<none>",
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (tag != null) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(formatSize(img.Size), style = MaterialTheme.typography.labelSmall)
                    Text(
                        "sha256:${img.Id.removePrefix("sha256:").take(12)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            IconButton(onClick = {
                val t = img.RepoTags.firstOrNull() ?: return@IconButton
                repo.removeImage(t)
            }) {
                Icon(
                    Icons.Rounded.Delete,
                    contentDescription = stringResource(R.string.common_delete),
                )
            }
        }
    }
}

/** 拉取流程的三步。 */
private enum class PullStep { Browse, Tags, Pulling }

/** tag 列表最多渲染多少个 —— nginx 有 1381 个 tag，全渲染会卡死弹窗。 */
private const val MAX_TAGS = 80

/**
 * 拉取镜像：搜索 → 选 tag → 拉取，三步在同一个弹窗里切换。
 *
 * 为什么要拆成三步，而不是让用户自己拼 `nginx:1.25`：
 * 1. 用户记不住 tag。nginx 有 1381 个 tag，不列出来根本没法选。
 * 2. 两条网络路径不一样，必须分开兜底：**tag 列表**走镜像站的 registry v2 接口（国内可用），
 *    **搜索**只能打 hub.docker.com（国内基本不可达，实测绕代理也超时）。
 *    所以搜索失败时回退到内置常用镜像列表，而不是让用户对着空白发呆。
 */
@Composable
private fun ImagePullDialog(
    repo: EngineRepository,
    registry: RegistryClient,
    onDismiss: () -> Unit,
) {
    val cs = rememberCoroutineScope()
    var step by remember { mutableStateOf(PullStep.Browse) }

    // 搜索
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var searched by remember { mutableStateOf(false) }
    var offline by remember { mutableStateOf(false) }
    var hits by remember { mutableStateOf<List<ImageSearchHit>>(emptyList()) }

    // tag
    var targetRepo by remember { mutableStateOf("") }
    var tags by remember { mutableStateOf<List<String>>(emptyList()) }
    var tagLoading by remember { mutableStateOf(false) }
    var tagError by remember { mutableStateOf<String?>(null) }
    var tagFilter by remember { mutableStateOf("") }

    // 拉取
    var pulling by remember { mutableStateOf(false) }
    var pullRef by remember { mutableStateOf("") }
    var progressLine by remember { mutableStateOf<String?>(null) }
    var pullError by remember { mutableStateOf<String?>(null) }

    fun runSearch() {
        val q = query.trim()
        if (q.isEmpty()) return
        searched = true
        searching = true
        cs.launch {
            when (val r = registry.search(q)) {
                is RegistryClient.SearchOutcome.Ok -> {
                    hits = r.hits
                    offline = false
                }

                RegistryClient.SearchOutcome.Unreachable -> {
                    hits = PopularImages.filter(q).map { name ->
                        ImageSearchHit(
                            repo = RegistryRef.normalizeRepo(name),
                            display = name,
                            description = "",
                            stars = 0,
                            official = !name.contains('/'),
                            pulls = 0L,
                        )
                    }
                    offline = true
                }
            }
            searching = false
        }
    }

    fun openTags(repoPath: String) {
        targetRepo = repoPath
        step = PullStep.Tags
        tags = emptyList()
        tagFilter = ""
        tagError = null
        tagLoading = true
        cs.launch {
            val r = registry.tags(repoPath)
            tags = r.tags
            tagError = r.error
            tagLoading = false
        }
    }

    fun doPull(ref: String) {
        step = PullStep.Pulling
        pullRef = ref
        pulling = true
        pullError = null
        progressLine = null
        cs.launch {
            repo.pullImage(ref).collect { p ->
                if (p.error != null) {
                    pullError = p.error
                } else {
                    progressLine = listOfNotNull(p.id?.take(12), p.status, p.progress)
                        .joinToString(" ")
                        .ifBlank { null }
                }
            }
            pulling = false
            if (pullError == null) {
                repo.refreshImages()
                onDismiss()
            }
        }
    }

    // 打开时先摆出常用镜像，省得用户对着空列表猜关键词
    val shown: List<ImageSearchHit> = if (searched) {
        hits
    } else {
        PopularImages.ALL.map { name ->
            ImageSearchHit(
                repo = RegistryRef.normalizeRepo(name),
                display = name,
                description = "",
                stars = 0,
                official = !name.contains('/'),
                pulls = 0L,
            )
        }
    }
    val directTag = remember(query) { RegistryRef.parse(query).second }

    PodroidDialog(
        title = when (step) {
            PullStep.Browse -> stringResource(R.string.images_search_title)
            PullStep.Tags -> RegistryRef.displayName(targetRepo)
            PullStep.Pulling -> stringResource(R.string.images_pull_title)
        },
        subtitle = when (step) {
            PullStep.Browse -> stringResource(R.string.images_search_hint)
            PullStep.Tags -> stringResource(R.string.images_tag_title)
            PullStep.Pulling -> pullRef
        },
        onDismiss = { if (!pulling) onDismiss() },
        footer = {
            when (step) {
                PullStep.Browse -> {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.common_cancel))
                    }
                    Spacer(Modifier.size(8.dp))
                    if (directTag != null) {
                        // 用户已经写了 tag（nginx:1.25），就别再让他点两下了
                        Button(
                            enabled = !searching,
                            onClick = { doPull(query.trim()) },
                        ) { Text(stringResource(R.string.images_pull_direct)) }
                    } else {
                        Button(
                            enabled = query.isNotBlank() && !searching,
                            onClick = { runSearch() },
                        ) { Text(stringResource(R.string.images_search_action)) }
                    }
                }

                PullStep.Tags -> {
                    TextButton(onClick = { step = PullStep.Browse }) {
                        Text(stringResource(R.string.common_back))
                    }
                }

                PullStep.Pulling -> {
                    if (pullError != null) {
                        TextButton(onClick = onDismiss) {
                            Text(stringResource(R.string.common_close))
                        }
                    }
                }
            }
        },
    ) {
        when (step) {
            PullStep.Browse -> BrowseStep(
                query = query,
                onQueryChange = {
                    query = it
                    if (it.isEmpty()) {
                        searched = false
                        offline = false
                    }
                },
                searching = searching,
                offline = offline,
                searched = searched,
                shown = shown,
                onPick = { openTags(it.repo) },
            )

            PullStep.Tags -> TagsStep(
                loading = tagLoading,
                tags = tags,
                error = tagError,
                filter = tagFilter,
                onFilterChange = { tagFilter = it },
                onPick = { doPull("$targetRepo:$it") },
            )

            PullStep.Pulling -> PullingStep(progressLine = progressLine, error = pullError)
        }
    }
}

@Composable
private fun BrowseStep(
    query: String,
    onQueryChange: (String) -> Unit,
    searching: Boolean,
    offline: Boolean,
    searched: Boolean,
    shown: List<ImageSearchHit>,
    onPick: (ImageSearchHit) -> Unit,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        label = { Text(stringResource(R.string.images_search_label)) },
        placeholder = { Text("nginx / redis / luolingy/new-api") },
        singleLine = true,
        enabled = !searching,
        trailingIcon = {
            if (searching) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        },
        modifier = Modifier.fillMaxWidth(),
    )

    if (offline) {
        Text(
            stringResource(R.string.images_search_offline),
            style = MaterialTheme.typography.labelSmall,
            color = toneColors(Tone.Warning).content,
        )
    }

    if (shown.isEmpty() && searched && !searching) {
        Text(
            stringResource(R.string.images_search_empty),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    shown.forEach { hit ->
        Surface(
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.fillMaxWidth().clickable { onPick(hit) },
        ) {
            Row(
                Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            hit.display,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (hit.official) {
                            Spacer(Modifier.size(6.dp))
                            OfficialBadge()
                        }
                    }
                    if (hit.description.isNotBlank()) {
                        Text(
                            hit.description,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (hit.stars > 0 || hit.pulls > 0) {
                        Text(
                            "★ ${hit.stars} · ${formatPulls(hit.pulls)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Icon(
                    Icons.Rounded.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun TagsStep(
    loading: Boolean,
    tags: List<String>,
    error: String?,
    filter: String,
    onFilterChange: (String) -> Unit,
    onPick: (String) -> Unit,
) {
    when {
        loading -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(Modifier.size(8.dp))
            Text(stringResource(R.string.images_tag_loading), style = MaterialTheme.typography.bodySmall)
        }

        tags.isEmpty() -> Text(
            if (error != null) stringResource(R.string.images_tag_failed, error)
            else stringResource(R.string.images_tag_empty),
            style = MaterialTheme.typography.bodySmall,
            color = toneColors(Tone.Danger).content,
        )

        else -> {
            OutlinedTextField(
                value = filter,
                onValueChange = onFilterChange,
                label = { Text(stringResource(R.string.images_tag_filter)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            val filtered = tags.filter { it.contains(filter.trim(), ignoreCase = true) }
            val shownTags = filtered.take(MAX_TAGS)
            shownTags.forEach { t ->
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier.fillMaxWidth().clickable { onPick(t) },
                ) {
                    Row(
                        Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            t,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (t == "latest") {
                            Text(
                                stringResource(R.string.images_tag_latest),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
            if (filtered.size > shownTags.size) {
                Text(
                    stringResource(R.string.images_tag_more, shownTags.size, filtered.size),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun PullingStep(progressLine: String?, error: String?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (error == null) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(Modifier.size(8.dp))
        }
        Text(
            error ?: progressLine ?: "…",
            style = MaterialTheme.typography.bodySmall,
            color = if (error != null) toneColors(Tone.Danger).content
            else MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun OfficialBadge() {
    val c = toneColors(Tone.Info)
    Surface(shape = MaterialTheme.shapes.extraSmall, color = c.container) {
        Text(
            stringResource(R.string.images_official),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            color = c.content,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
        )
    }
}

/** 1234567 -> 1.2M。搜索结果的 pull 数会到十亿级，不缩写放不下。 */
private fun formatPulls(n: Long): String = when {
    n >= 1_000_000_000 -> "%.1fB".format(n / 1_000_000_000.0)
    n >= 1_000_000 -> "%.1fM".format(n / 1_000_000.0)
    n >= 1_000 -> "%.1fK".format(n / 1_000.0)
    else -> n.toString()
}
