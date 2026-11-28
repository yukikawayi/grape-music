package com.grape.player.ui.search

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.grape.player.data.MediaItemLite as MediaItem
import com.grape.player.ui.AppViewModel
import com.grape.player.ui.components.FastListScrollBar
import com.grape.player.ui.components.LocatePlayingButton
import com.grape.player.ui.library.AddToPlaylistSheet
import com.grape.player.ui.library.MultiSelectBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 全局搜索 —— 键入即搜，结果按「歌曲 / 专辑 / 艺术家 / 文件夹」四段分组。
 *
 * 匹配规则：
 * - 空格分隔的多个关键词是「且」关系，跨字段一起匹配（如「周杰伦 稻香」能命中）；
 * - 歌曲命中标题 / 艺术家 / 专辑 / 所在文件夹 / 文件名任一项；
 * - 专辑、艺术家、文件夹既按名字命中，也会收录「有歌曲命中」的整组（搜歌名也能看到所属专辑）；
 * - 忽略大小写与首尾空白。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SearchScreen(
    onSongClick: () -> Unit,
    onGroupClick: (String, String) -> Unit = { _, _ -> },
    vm: AppViewModel = viewModel(),
) {
    val allMedia by vm.allMedia.collectAsState()
    val playingUrl by vm.playingUrl.collectAsState()
    val focusManager = LocalFocusManager.current
    val focusRequester = remember { FocusRequester() }
    val resultState = vm.libraryListState("search")

    // 搜索词存在 VM 里：切到别的 tab 时本页会被弹掉，页面内的 remember 全会丢，
    // 而结果是由「词 + 全库」实时算出来的，所以记住词就等于记住了结果。
    // 滚动位置同样放 VM（同一份 libraryListState），回来还是原来那一段结果。
    val query = vm.searchQuery
    val scope = rememberCoroutineScope()

    // 长按歌曲进入多选（与音乐库/详情页同一套操作栏）
    var selectionMode by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var showAddToPlaylist by remember { mutableStateOf(false) }

    // 多选态下按返回 = 退出多选，而不是退出页面
    BackHandler(enabled = selectionMode) {
        selectionMode = false
        selected = emptySet()
    }

    // 各分组的「展开全部」开关
    var expandAlbums by remember(query) { mutableStateOf(false) }
    var expandArtists by remember(query) { mutableStateOf(false) }
    var expandFolders by remember(query) { mutableStateOf(false) }

    // 进入页面直接聚焦（只在还没搜过时）：回来时带着上次的词，直接弹键盘会挡住结果
    LaunchedEffect(Unit) {
        if (query.isBlank()) runCatching { focusRequester.requestFocus() }
    }

    val terms = remember(query) {
        query.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
    }
    // 搜索结果放后台算 + debounce：以前 remember(terms, allMedia) 在组合期主线程执行，
    // 每敲一个字就全库扫一遍（每首拼 haystack + 多次 contains），大库下直接卡输入。
    // 现在 (1) 后台算（Dispatchers.Default）；(2) debounce 200ms —— 连敲一串字只对
    // 停下来的那一次真正扫全库；(3) produceState 的 key 变化会自动取消上一轮还没算完的。
    val hits by produceState(initialValue = SearchHits.EMPTY, terms, allMedia) {
        // 让连敲的中间态安静 200ms 再算；produceState 重启会取消这个 delay，天然「取最新」
        if (terms.isNotEmpty()) {
            kotlinx.coroutines.delay(200)
            val latest = allMedia  // 用最新的库内容
            value = withContext(Dispatchers.Default) { buildHits(latest, terms) }
        } else {
            value = SearchHits.EMPTY
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            // 换关键词 = 换了搜索结果，多选是「当前结果里的选择」，跟着一起退出，
            // 否则批量操作会作用到已经看不见的旧结果上。
            // 同时把列表滚回顶部：新结果从第一条开始看，而不是停在上一组结果的位置。
            onValueChange = {
                vm.setSearchQuery(it)
                scope.launch { resultState.scrollToItem(0) }
                if (selectionMode) {
                    selectionMode = false
                    selected = emptySet()
                }
            },
            placeholder = { Text("搜索歌曲 / 专辑 / 艺术家 / 文件夹") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = {
                        vm.setSearchQuery("")
                        scope.launch { resultState.scrollToItem(0) }
                    }) {
                        Icon(Icons.Filled.Clear, contentDescription = "清空")
                    }
                }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .focusRequester(focusRequester),
        )

        // 长按搜索结果里的任意一首歌进入多选（与音乐库 / 详情页共用同一条操作栏）
        if (selectionMode && hits.songs.isNotEmpty()) {
            val allUrls = remember(hits.songs) { hits.songs.mapTo(LinkedHashSet()) { it.url } }
            val allSelected = selected.containsAll(allUrls)
            MultiSelectBar(
                allSelected = allSelected,
                hasSelection = selected.isNotEmpty(),
                onToggleSelectAll = { selected = if (allSelected) emptySet() else allUrls },
                onCancel = {
                    selectionMode = false
                    selected = emptySet()
                },
                onAddToPlaylist = { showAddToPlaylist = true },
                onEditMeta = {
                    vm.openMetaEditMany(selected.toList())
                    selectionMode = false
                    selected = emptySet()
                },
                countText = "已选 ${selected.size} 首",
            )
        }

        when {
            terms.isEmpty() -> TipState()
            hits.isEmpty() -> EmptyState(query)
            else -> Box(modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    state = resultState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp),
                ) {
                    item(key = "summary") {
                        Text(
                            summaryOf(hits),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                        )
                    }

                    // ===== 歌曲 =====
                    if (hits.songs.isNotEmpty()) {
                        item(key = "h_song") { SectionHeader("歌曲", hits.songs.size) }
                        itemsIndexed(hits.songs, key = { _, it -> it.url }) { idx, item ->
                            SongRow(
                                item = item,
                                terms = terms,
                                selected = selected.contains(item.url),
                                selectionMode = selectionMode,
                                // 正在播放的那一首用主色标出歌名 / 艺术家·专辑
                                playing = item.url == playingUrl,
                                onClick = {
                                    if (selectionMode) {
                                        selected = if (selected.contains(item.url))
                                            selected - item.url else selected + item.url
                                    } else {
                                        focusManager.clearFocus()
                                        // 队列 = 搜索结果顺序，点第几首就从第几首开始
                                        vm.player.setPlaylist(hits.songs, idx)
                                        onSongClick()
                                    }
                                },
                                onLongClick = {
                                    selectionMode = true
                                    selected = selected + item.url
                                },
                            )
                        }
                    }


                    // ===== 专辑 =====
                    if (hits.albums.isNotEmpty()) {
                        item(key = "h_album") { SectionHeader("专辑", hits.albums.size) }
                        val shown = if (expandAlbums) hits.albums else hits.albums.take(GROUP_PREVIEW)
                        items(shown, key = { "album_${it.name}" }) { hit ->
                            GroupRow(
                                icon = Icons.Filled.Album,
                                hit = hit,
                                terms = terms,
                                onClick = { onGroupClick("album", hit.name) },
                            )
                        }
                        if (hits.albums.size > GROUP_PREVIEW) {
                            item(key = "more_album") {
                                MoreRow(expandAlbums, hits.albums.size) { expandAlbums = !expandAlbums }
                            }
                        }
                    }

                    // ===== 艺术家 =====
                    if (hits.artists.isNotEmpty()) {
                        item(key = "h_artist") { SectionHeader("艺术家", hits.artists.size) }
                        val shown = if (expandArtists) hits.artists else hits.artists.take(GROUP_PREVIEW)
                        items(shown, key = { "artist_${it.name}" }) { hit ->
                            GroupRow(
                                icon = Icons.Filled.Person,
                                hit = hit,
                                terms = terms,
                                onClick = { onGroupClick("artist", hit.name) },
                            )
                        }
                        if (hits.artists.size > GROUP_PREVIEW) {
                            item(key = "more_artist") {
                                MoreRow(expandArtists, hits.artists.size) { expandArtists = !expandArtists }
                            }
                        }
                    }

                    // ===== 文件夹 =====
                    if (hits.folders.isNotEmpty()) {
                        item(key = "h_folder") { SectionHeader("文件夹", hits.folders.size) }
                        val shown = if (expandFolders) hits.folders else hits.folders.take(GROUP_PREVIEW)
                        items(shown, key = { "folder_${it.name}" }) { hit ->
                            GroupRow(
                                icon = Icons.Filled.Folder,
                                hit = hit,
                                terms = terms,
                                onClick = { onGroupClick("folder", hit.name) },
                            )
                        }
                        if (hits.folders.size > GROUP_PREVIEW) {
                            item(key = "more_folder") {
                                MoreRow(expandFolders, hits.folders.size) { expandFolders = !expandFolders }
                            }
                        }
                    }
                }

                // 右侧浮层快速滑块：结果超出一屏才出现，按住往下拖可直落页尾
                FastListScrollBar(
                    state = resultState,
                    modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                )
                // 定位到正在播放：结果超出一屏才出现，贴在右下角、底部播放栏上方一点。
                // 歌曲分区在最前面（「汇总行 + 歌曲标题」各占 1 项），所以偏移是 2 + 下标；
                // 正在播的歌不在本次搜索结果里时点它什么也不做。
                LocatePlayingButton(
                    listState = resultState,
                    playingUrl = playingUrl,
                    indexOf = { url ->
                        hits.songs.indexOfFirst { it.url == url }
                            .takeIf { it >= 0 }
                            ?.let { it + 2 }
                    },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 16.dp, bottom = 16.dp),
                )
            }
        }
    }

    // 加入歌单：与音乐库多选栏共用同一个弹层
    if (showAddToPlaylist) {
        AddToPlaylistSheet(
            vm = vm,
            urls = selected.toList(),
            onDone = {
                showAddToPlaylist = false
                selectionMode = false
                selected = emptySet()
            },
            onDismiss = { showAddToPlaylist = false },
        )
    }
}

private const val GROUP_PREVIEW = 5
private const val SONG_LIMIT = 300

/** 一个分组（专辑 / 艺术家 / 文件夹）的命中项 */
private data class GroupHit(
    /** 用于跳详情的真实取值（空专辑用「未知专辑」占位，与音乐库分组保持一致） */
    val name: String,
    val subtitle: String,
    val count: Int,
)

private data class SearchHits(
    val songs: List<MediaItem>,
    val albums: List<GroupHit>,
    val artists: List<GroupHit>,
    val folders: List<GroupHit>,
) {
    fun isEmpty() = songs.isEmpty() && albums.isEmpty() && artists.isEmpty() && folders.isEmpty()

    companion object {
        val EMPTY = SearchHits(emptyList(), emptyList(), emptyList(), emptyList())
    }
}

/** 歌曲的可搜索文本：标题 / 艺术家 / 专辑 / 文件夹 / 文件名 */
private fun MediaItem.haystack(): String {
    val file = url.substringAfterLast('/')
    return "$title\u0001$artist\u0001$album\u0001$folder\u0001$file"
}

private fun MediaItem.matches(terms: List<String>): Boolean {
    val hay = haystack()
    return terms.all { hay.contains(it, ignoreCase = true) }
}

private fun albumNameOf(item: MediaItem) = item.album.ifEmpty { "未知专辑" }
private fun artistNameOf(item: MediaItem) = item.artist.ifEmpty { "未知艺术家" }
private fun folderNameOf(item: MediaItem) = item.folder.ifEmpty { "/" }

private fun buildHits(all: List<MediaItem>, terms: List<String>): SearchHits {
    val matched = all.filter { it.matches(terms) }

    // 歌曲：标题前缀命中优先，其次标题短者靠前（更像用户要找的那首）
    val q = terms.first()
    val songs = matched
        .sortedWith(
            compareByDescending<MediaItem> { it.title.startsWith(q, ignoreCase = true) }
                .thenBy { it.title.length }
                .thenBy { it.title.lowercase() },
        )
        .take(SONG_LIMIT)

    // 全库分组 → 总曲目数，一次遍历算好三份（专辑 / 艺术家 / 文件夹）。
    // 原来是在 buildGroups 里 `counts[key] = all.count { keyOf(it) == key }`，
    // 那是 O(N²)：500 个艺人 × 3000 首 ≈ 150 万次比较，还 ×3 组。
    // 现在先线性扫一遍建好「组名 → 曲目数」，名字命中的组直接查表。
    val albumCounts = HashMap<String, Int>()
    val artistCounts = HashMap<String, Int>()
    val folderCounts = HashMap<String, Int>()
    for (item in all) {
        val a = albumNameOf(item); albumCounts[a] = (albumCounts[a] ?: 0) + 1
        val ar = artistNameOf(item); artistCounts[ar] = (artistCounts[ar] ?: 0) + 1
        val f = folderNameOf(item); folderCounts[f] = (folderCounts[f] ?: 0) + 1
    }

    // 分组：先收「有歌曲命中」的组，再补「名字本身命中」的组（后者可能没有命中歌曲）
    fun buildGroups(
        keyOf: (MediaItem) -> String,
        labelOf: (MediaItem) -> String,
        totalOf: (String) -> Int,
        subtitleOf: (String, Int) -> String,
    ): List<GroupHit> {
        val counts = LinkedHashMap<String, Int>()
        val labels = HashMap<String, String>()
        matched.forEach { item ->
            val key = keyOf(item)
            if (key.isBlank()) return@forEach
            counts[key] = (counts[key] ?: 0) + 1
            labels.putIfAbsent(key, labelOf(item))
        }
        // 名字命中但没有歌曲命中的组：统计该组全部曲目数（直接查上面的预建表）
        all.forEach { item ->
            val key = keyOf(item)
            if (key.isBlank() || counts.containsKey(key)) return@forEach
            if (terms.all { key.contains(it, ignoreCase = true) }) {
                counts[key] = totalOf(key)
                labels.putIfAbsent(key, labelOf(item))
            }
        }
        return counts.map { (key, count) ->
            GroupHit(
                name = labels[key] ?: key,
                subtitle = subtitleOf(key, count),
                count = count,
            )
        }.sortedWith(compareByDescending<GroupHit> { it.count }.thenBy { it.name.lowercase() })
    }

    val albums = buildGroups(
        keyOf = { albumNameOf(it) },
        labelOf = { albumNameOf(it) },
        totalOf = { albumCounts[it] ?: 0 },
        subtitleOf = { key, count ->
            // 专辑补一个年份更有辨识度
            val year = all.firstOrNull { albumNameOf(it) == key && it.year > 0 }?.year ?: 0
            if (year > 0) "$count 首 · $year" else "$count 首"
        },
    )
    val artists = buildGroups(
        keyOf = { artistNameOf(it) },
        labelOf = { artistNameOf(it) },
        totalOf = { artistCounts[it] ?: 0 },
        subtitleOf = { _, count -> "$count 首" },
    )
    val folders = buildGroups(
        keyOf = { folderNameOf(it) },
        labelOf = { folderNameOf(it).substringAfterLast('/').ifEmpty { "/" } },
        totalOf = { folderCounts[it] ?: 0 },
        subtitleOf = { key, count -> "$count 首 · $key" },
    )

    return SearchHits(songs, albums, artists, folders)
}

private fun summaryOf(h: SearchHits): String = buildString {
    append("歌曲 ${h.songs.size}")
    if (h.albums.isNotEmpty()) append(" · 专辑 ${h.albums.size}")
    if (h.artists.isNotEmpty()) append(" · 艺术家 ${h.artists.size}")
    if (h.folders.isNotEmpty()) append(" · 文件夹 ${h.folders.size}")
}

@Composable
private fun SectionHeader(title: String, count: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            "$count",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 搜索结果里的歌曲行。
 * 长按 = 进入多选（和音乐库一致）；多选态下点整行 = 勾选/取消，不再是「点一下就播放」。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SongRow(
    item: MediaItem,
    terms: List<String>,
    selected: Boolean,
    selectionMode: Boolean,
    /** 这首歌是不是播放器当前那一首：是则歌名 / 艺术家·专辑用主色标出来 */
    playing: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selectionMode) {
            Checkbox(checked = selected, onCheckedChange = null)
        }
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            if (item.coverUrl.isNotBlank()) {
                coil.compose.AsyncImage(
                    model = item.coverUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(
                    Icons.Filled.MusicNote,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                highlight(item.title, terms),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                // 正在播放：整体上主色（命中片段本来就是主色加粗，仍然分得清）
                color = if (playing) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface,
            )
            val sub = listOf(item.artist, item.album).filter { it.isNotBlank() }.joinToString(" · ")
            if (sub.isNotBlank()) {
                Text(
                    highlight(sub, terms),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (playing) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(
            formatDuration(item.durationMs),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun GroupRow(icon: ImageVector, hit: GroupHit, terms: List<String>, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(22.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                highlight(hit.name, terms),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                hit.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun MoreRow(expanded: Boolean, total: Int, onToggle: () -> Unit) {
    TextButton(onClick = onToggle, modifier = Modifier.padding(start = 8.dp)) {
        Text(if (expanded) "收起" else "查看全部 $total 项")
    }
}

@Composable
private fun TipState() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Filled.Search,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(48.dp),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "输入关键词开始搜索",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "支持歌名、艺术家、专辑、文件夹；空格分隔多个关键词表示同时满足",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

@Composable
private fun EmptyState(query: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            "没有找到「$query」",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "换个关键词，或先到设置页扫描网盘媒体库",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 命中片段高亮（忽略大小写，命中多处全部着色） */
@Composable
private fun highlight(text: String, terms: List<String>): AnnotatedString {
    val color = MaterialTheme.colorScheme.primary
    return remember(text, terms, color) {
        buildAnnotatedString {
            append(text)
            terms.forEach { term ->
                if (term.isBlank()) return@forEach
                var start = text.indexOf(term, 0, ignoreCase = true)
                while (start >= 0 && start + term.length <= text.length) {
                    addStyle(
                        SpanStyle(color = color, fontWeight = FontWeight.SemiBold),
                        start,
                        start + term.length,
                    )
                    start = text.indexOf(term, start + term.length, ignoreCase = true)
                }
            }
        }
    }
}

private fun formatDuration(ms: Long): String {
    if (ms <= 0) return "--:--"
    val totalSec = ms / 1000
    return "%d:%02d".format(totalSec / 60, totalSec % 60)
}
