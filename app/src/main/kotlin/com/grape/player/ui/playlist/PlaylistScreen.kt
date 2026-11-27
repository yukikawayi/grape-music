package com.grape.player.ui.playlist

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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.grape.player.data.MediaItemLite as MediaItem
import com.grape.player.data.Playlist
import com.grape.player.ui.AppViewModel
import com.grape.player.ui.components.FastListScrollBar
import com.grape.player.ui.components.LocatePlayingButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 歌单页 —— 主页是歌单列表，点进去是歌单内曲目。
 *
 * 主页：新建 / 重命名 / 删除（二次确认）/ 显示曲目数。
 * 列表第一项固定是「最近播放」（见 [RECENT_PLAYLIST_ID]）：它不是真歌单，不可改名/删除，
 * 内容由播放记录自动生成（最多 100 首，时间由近到远）。
 * 详情：整单播放、从上往下播放、单曲上移下移、从歌单移除、清空、重命名、删除。
 * 详情用页内状态 + BackHandler 实现，不新增导航路由，底部四个 tab 高亮不受影响。
 */
@Composable
fun PlaylistScreen(
    onSongClick: () -> Unit = {},
    vm: AppViewModel = viewModel(),
) {
    val playlists by vm.playlists.collectAsState(initial = emptyList())
    val counts by vm.playlistCounts.collectAsState(initial = emptyMap())
    val scope = rememberCoroutineScope()

    // ===== 最近播放（虚拟歌单，id 用哨兵值，不存在于 playlists 表里）=====
    val allMedia by vm.allMedia.collectAsState()
    val recent by vm.recentPlays.collectAsState(initial = emptyList())
    // 后台构建 url → 媒体项 映射并映射成「媒体项 to 播放时间」。
    // 原来 remember(allMedia) { associateBy } 在组合期主线程跑，allMedia 每 250ms 刷新
    // 就重建一张 3000 条的 HashMap（每秒 4 次）；库里已不存在的曲目（清过库 / 重扫换链）
    // 自动跳过，所以最近播放条数始终和真正能播的歌数一致。
    val recentSongs by produceState(
        initialValue = emptyList<Pair<MediaItem, Long>>(),
        allMedia, recent,
    ) {
        value = withContext(Dispatchers.Default) {
            val byUrl = allMedia.associateBy { it.url }
            recent.mapNotNull { r -> byUrl[r.url]?.let { it to r.playedAt } }
        }
    }

    // 当前打开的歌单 id；null = 列表页
    var openedId by rememberSaveable { mutableStateOf<Long?>(null) }
    val openedRecent = openedId == RECENT_PLAYLIST_ID
    val opened = playlists.firstOrNull { it.id == openedId }

    /**
     * 打开「最近播放」那一刻的顺序快照。
     * 不冻结的话，正在播的那首唱过 2 秒就会被记上新的 playedAt、当场跳到列表最前 ——
     * 用户一边看一边整页往下窜，很晃眼。退出再进就是最新顺序（快照随之重建）。
     * 打开时列表本来为空则不冻结，让新记录边播边显示出来。
     */
    var recentSnapshot by remember { mutableStateOf<List<Pair<MediaItem, Long>>?>(null) }

    fun closeOpened() {
        openedId = null
        recentSnapshot = null
    }

    // 歌单被删掉后自动退回列表页（最近播放永不被删，不参与这个判断）
    if (openedId != null && !openedRecent && opened == null && playlists.isNotEmpty()) {
        closeOpened()
    }
    BackHandler(enabled = openedId != null) { closeOpened() }

    var showCreate by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<Playlist?>(null) }
    var pendingDelete by remember { mutableStateOf<Playlist?>(null) }
    var pendingClear by remember { mutableStateOf<Playlist?>(null) }

    when {
        openedRecent -> RecentPlaylistDetail(
            songs = recentSnapshot ?: recentSongs,
            vm = vm,
            onBack = { closeOpened() },
            onSongClick = onSongClick,
        )
        opened == null -> PlaylistList(
            playlists = playlists,
            counts = counts,
            recentCount = recentSongs.size,
            onOpenRecent = {
                recentSnapshot = recentSongs.ifEmpty { null }
                openedId = RECENT_PLAYLIST_ID
            },
            onOpen = { openedId = it.id },
            onRename = { renaming = it },
            onDelete = { pendingDelete = it },
            onCreate = { showCreate = true },
        )
        else -> PlaylistDetail(
            playlist = opened,
            vm = vm,
            onBack = { closeOpened() },
            onSongClick = onSongClick,
            onRename = { renaming = it },
            onClear = { pendingClear = it },
            onDelete = { pendingDelete = it },
        )
    }

    // ===== 新建歌单 =====
    if (showCreate) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCreate = false },
            title = { Text("新建歌单") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("歌单名") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (name.isNotBlank()) {
                            scope.launch { vm.repo.createPlaylist(name.trim()) }
                            showCreate = false
                        }
                    },
                ) { Text("创建") }
            },
            dismissButton = { TextButton(onClick = { showCreate = false }) { Text("取消") } },
        )
    }

    // ===== 重命名 =====
    renaming?.let { playlist ->
        var text by remember(playlist.id, playlist.name) { mutableStateOf(playlist.name) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("重命名歌单") },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("歌单名") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (text.isNotBlank() && text.trim() != playlist.name) {
                        scope.launch { vm.repo.renamePlaylist(playlist.copy(name = text.trim())) }
                    }
                    renaming = null
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("取消") } },
        )
    }

    // ===== 删除确认 =====
    pendingDelete?.let { playlist ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除歌单") },
            text = { Text("确定删除「${playlist.name}」？歌单内的歌曲不会被删除，只是移出这个歌单。") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { vm.repo.deletePlaylist(playlist) }
                    if (openedId == playlist.id) openedId = null
                    pendingDelete = null
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("取消") } },
        )
    }

    // ===== 清空确认 =====
    pendingClear?.let { playlist ->
        AlertDialog(
            onDismissRequest = { pendingClear = null },
            title = { Text("清空歌单") },
            text = { Text("确定清空「${playlist.name}」里的全部歌曲？歌曲本身不会被删除。") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { vm.repo.clearPlaylist(playlist.id) }
                    pendingClear = null
                }) { Text("清空", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { pendingClear = null }) { Text("取消") } },
        )
    }
}

// ===================== 歌单列表 =====================

@Composable
private fun PlaylistList(
    playlists: List<Playlist>,
    counts: Map<Long, Int>,
    recentCount: Int,
    onOpenRecent: () -> Unit,
    onOpen: (Playlist) -> Unit,
    onRename: (Playlist) -> Unit,
    onDelete: (Playlist) -> Unit,
    onCreate: () -> Unit,
) {
    val listState = rememberLazyListState()
    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = onCreate) {
                Icon(Icons.Filled.Add, contentDescription = "新建歌单")
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("我的歌单", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                Text(
                    // 「最近播放」也算一个歌单，所以 +1（它永远在列表里）
                    "${playlists.size + 1} 个",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Box(modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = 12.dp,
                        end = 12.dp,
                        bottom = 96.dp,
                    ),
                ) {
                    // 固定第一项：最近播放（不是真歌单，没有 ⋮ 菜单 —— 传 null 即不可改名/删除）
                    item(key = "recent") {
                        PlaylistCard(
                            name = RECENT_PLAYLIST_NAME,
                            count = recentCount,
                            icon = Icons.Filled.History,
                            emptyLabel = "还没有播放记录",
                            onClick = onOpenRecent,
                            onRename = null,
                            onDelete = null,
                        )
                    }
                    items(playlists, key = { it.id }) { playlist ->
                        PlaylistCard(
                            name = playlist.name,
                            count = counts[playlist.id] ?: 0,
                            icon = Icons.Filled.QueueMusic,
                            onClick = { onOpen(playlist) },
                            onRename = { onRename(playlist) },
                            onDelete = { onDelete(playlist) },
                        )
                    }
                    // 还没有自建歌单时给条引导（最近播放仍然在第一位可用）
                    if (playlists.isEmpty()) {
                        item(key = "empty-hint") {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 28.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Icon(
                                    Icons.Filled.QueueMusic,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(40.dp),
                                )
                                Spacer(Modifier.height(10.dp))
                                Text("还没有自建歌单", style = MaterialTheme.typography.titleSmall)
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    "点右下角 + 新建，再到音乐库长按多选歌曲加入",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                // 右侧浮层快速滑块：歌单很多时可按住拖到任意位置
                FastListScrollBar(
                    state = listState,
                    modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                )
            }
        }
    }
}

@Composable
private fun PlaylistCard(
    name: String,
    count: Int,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    /** null = 不显示菜单项（「最近播放」这类不可编辑的条目） */
    onRename: (() -> Unit)?,
    onDelete: (() -> Unit)?,
    /** 0 首时的副标题（普通歌单是「空歌单」，「最近播放」是「还没有播放记录」） */
    emptyLabel: String = "空歌单",
) {
    var menu by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 封面位：没有封面归属信息，用主题色方块 + 歌单图标，视觉上不空
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(26.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (count > 0) "$count 首" else emptyLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // 只有可编辑的歌单才给 ⋮ 菜单
            if (onRename != null || onDelete != null) {
                Box {
                    IconButton(onClick = { menu = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "更多操作")
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        if (onRename != null) {
                            DropdownMenuItem(
                                text = { Text("重命名") },
                                leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                                onClick = { menu = false; onRename() },
                            )
                        }
                        if (onDelete != null) {
                            DropdownMenuItem(
                                text = { Text("删除") },
                                leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                                onClick = { menu = false; onDelete() },
                            )
                        }
                    }
                }
            }
        }
    }
}

// ===================== 歌单详情 =====================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlaylistDetail(
    playlist: Playlist,
    vm: AppViewModel,
    onBack: () -> Unit,
    onSongClick: () -> Unit,
    onRename: (Playlist) -> Unit,
    onClear: (Playlist) -> Unit,
    onDelete: (Playlist) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val allMedia by vm.allMedia.collectAsState()
    val songListCover by vm.settings.songListCover.collectAsState(initial = false)
    val playingUrl by vm.playingUrl.collectAsState()
    val listState = rememberLazyListState()
    // 歌单关联行：按 position 排序的 url 列表（库里已不存在的 url 在下面映射时被过滤）
    val entriesFlow = remember(playlist.id) { vm.repo.playlistItems(playlist.id) }
    val entries by entriesFlow.collectAsState(initial = emptyList())
    // 后台映射：关联行按 position 排好，再映射成媒体项（媒体库里已不存在的 url 被过滤）。
    // 原来 remember(allMedia) { associateBy } 在主线程每 250ms 重建一张 3000 条 HashMap。
    val songs by produceState(initialValue = emptyList<MediaItem>(), entries, allMedia) {
        value = withContext(Dispatchers.Default) {
            val byUrl = allMedia.associateBy { it.url }
            entries.mapNotNull { byUrl[it.url] }
        }
    }
    val missing = entries.size - songs.size
    var menuOpen by remember { mutableStateOf(false) }

    // 长按进入多选（与音乐库「全部歌曲」页一致）：批量移除 / 批量编辑元数据
    var selectionMode by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    fun exitSelection() {
        selectionMode = false
        selected = emptySet()
    }

    // 上移 / 下移：按 entries 的真实顺序重排后整表写回 position
    fun move(from: Int, to: Int) {
        if (from !in songs.indices || to !in songs.indices || from == to) return
        val ordered = entries.map { it.url }.toMutableList()
        val f = ordered.indexOf(songs[from].url)
        val t = ordered.indexOf(songs[to].url)
        if (f < 0 || t < 0) return
        ordered.add(t, ordered.removeAt(f))
        scope.launch { vm.repo.reorderPlaylist(playlist.id, ordered) }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Column {
                    if (selectionMode) {
                        Text("已选 ${selected.size} 首", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "长按或点选歌曲，可批量移除 / 编辑元数据",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Text(playlist.name, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                        Text(
                            if (songs.isNotEmpty()) "${songs.size} 首" + if (missing > 0) " · $missing 首已失效" else ""
                            else "空歌单",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            navigationIcon = {
                if (selectionMode) {
                    IconButton(onClick = { exitSelection() }) {
                        Icon(Icons.Filled.Close, contentDescription = "退出多选")
                    }
                } else {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            },
            actions = {
                if (!selectionMode) {
                    IconButton(
                        enabled = songs.isNotEmpty(),
                        onClick = {
                            vm.player.setPlaylist(songs, 0)
                            onSongClick()
                        },
                    ) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = "播放全部")
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "更多操作")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("重命名歌单") },
                                leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                                onClick = { menuOpen = false; onRename(playlist) },
                            )
                            DropdownMenuItem(
                                text = { Text("清空歌单") },
                                leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                                onClick = { menuOpen = false; onClear(playlist) },
                            )
                            DropdownMenuItem(
                                text = { Text("删除歌单") },
                                leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                                onClick = { menuOpen = false; onDelete(playlist) },
                            )
                        }
                    }
                }
            },
        )

        // 多选操作栏：全选 / 移除 / 编辑元数据
        if (selectionMode) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val allSelected = songs.isNotEmpty() && selected.size == songs.size
                TextButton(onClick = {
                    selected = if (allSelected) emptySet() else songs.map { it.url }.toSet()
                }) { Text(if (allSelected) "取消全选" else "全选") }
                Spacer(Modifier.weight(1f))
                TextButton(
                    enabled = selected.isNotEmpty(),
                    onClick = {
                        val toRemove = selected.toList()
                        exitSelection()
                        scope.launch {
                            toRemove.forEach { vm.repo.removeFromPlaylist(playlist.id, it) }
                        }
                    },
                ) { Text("移除", color = MaterialTheme.colorScheme.error) }
                TextButton(
                    enabled = selected.isNotEmpty(),
                    onClick = {
                        vm.openMetaEditMany(selected.toList())
                        exitSelection()
                    },
                ) { Text("编辑元数据") }
            }
        }

        if (songs.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    Icons.Filled.MusicNote,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(44.dp),
                )
                Spacer(Modifier.height(12.dp))
                Text("歌单里还没有歌曲", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                Text(
                    "到音乐库长按多选歌曲 → 加入歌单",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            Box(modifier = Modifier.fillMaxSize()) {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    itemsIndexed(songs, key = { _, it -> it.url }) { idx, item ->
                        PlaylistSongRow(
                            index = idx + 1,
                            item = item,
                            showCover = songListCover,
                            selectionMode = selectionMode,
                            selected = selected.contains(item.url),
                            canMoveUp = idx > 0,
                            canMoveDown = idx < songs.lastIndex,
                            onClick = {
                                if (selectionMode) {
                                    selected = if (selected.contains(item.url)) selected - item.url
                                    else selected + item.url
                                } else {
                                    vm.player.setPlaylist(songs, idx)
                                    onSongClick()
                                }
                            },
                            onLongClick = {
                                selectionMode = true
                                selected = selected + item.url
                            },
                            onMoveUp = { move(idx, idx - 1) },
                            onMoveDown = { move(idx, idx + 1) },
                            onRemove = { scope.launch { vm.repo.removeFromPlaylist(playlist.id, item.url) } },
                        )
                    }
                }
                // 右侧浮层快速滑块：歌单很长时可按住拖到任意位置（含直接到页尾）
                FastListScrollBar(
                    state = listState,
                    modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                )
                // 定位到正在播放：超出一屏才出现；正在播的歌不属于这个歌单时点了没反应
                LocatePlayingButton(
                    listState = listState,
                    playingUrl = playingUrl,
                    indexOf = { url -> songs.indexOfFirst { it.url == url }.takeIf { it >= 0 } },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 16.dp, bottom = 16.dp),
                )
            }
        }
    }
}

// ===================== 最近播放（虚拟歌单，只读） =====================

/** 「最近播放」哨兵 id：不存在于 playlists 表，负数不会与自增主键冲突 */
private const val RECENT_PLAYLIST_ID = -1L
private const val RECENT_PLAYLIST_NAME = "最近播放"

/**
 * 最近播放详情 —— 结构对齐 [PlaylistDetail]，但**没有**任何编辑入口：
 * 没有重命名 / 清空 / 删除歌单，也没有单曲上移下移、从歌单移除（顺序由播放时间决定）。
 * 只保留「播放全部 + 点歌播放」，长按多选也不给 —— 这里没有任何可批量做的事。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecentPlaylistDetail(
    songs: List<Pair<MediaItem, Long>>,
    vm: AppViewModel,
    onBack: () -> Unit,
    onSongClick: () -> Unit,
) {
    val songListCover by vm.settings.songListCover.collectAsState(initial = false)
    val playingUrl by vm.playingUrl.collectAsState()
    val listState = rememberLazyListState()
    // 「N 分钟前」按进入页面这一刻算：停留期间不刷新，免得为了这一列每秒重组整个列表
    val now = remember { System.currentTimeMillis() }
    val media = remember(songs) { songs.map { it.first } }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Column {
                    Text(RECENT_PLAYLIST_NAME, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                    Text(
                        if (songs.isEmpty()) "还没有播放记录" else "${songs.size} 首 · 最近播放在最前",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
                }
            },
            actions = {
                IconButton(
                    enabled = media.isNotEmpty(),
                    onClick = {
                        vm.player.setPlaylist(media, 0)
                        onSongClick()
                    },
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = "播放全部")
                }
            },
        )

        if (songs.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    Icons.Filled.History,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(44.dp),
                )
                Spacer(Modifier.height(12.dp))
                Text("还没有播放记录", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                Text(
                    "播过的歌会自动出现在这里，最多保留 ${com.grape.player.data.RecentPlay.LIMIT} 首",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            Box(modifier = Modifier.fillMaxSize()) {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    itemsIndexed(songs, key = { _, it -> it.first.url }) { idx, entry ->
                        val item = entry.first
                        PlaylistSongRow(
                            index = idx + 1,
                            item = item,
                            showCover = songListCover,
                            selectionMode = false,
                            selected = false,
                            canMoveUp = false,
                            canMoveDown = false,
                            onClick = {
                                vm.player.setPlaylist(media, idx)
                                onSongClick()
                            },
                            onLongClick = {},
                            onMoveUp = {},
                            onMoveDown = {},
                            onRemove = {},
                            showMenu = false,
                            trailingText = formatPlayedAgo(entry.second, now),
                        )
                    }
                }
                FastListScrollBar(
                    state = listState,
                    modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                )
                // 定位到正在播放：超出一屏才出现；歌不在最近播放里时点了没反应
                LocatePlayingButton(
                    listState = listState,
                    playingUrl = playingUrl,
                    indexOf = { url ->
                        songs.indexOfFirst { it.first.url == url }.takeIf { it >= 0 }
                    },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 16.dp, bottom = 16.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlaylistSongRow(
    index: Int,
    item: MediaItem,
    showCover: Boolean,
    selectionMode: Boolean,
    selected: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit,
    /** false = 不给行尾 ⋮ 菜单（顺序固定、不能移除的列表用，如「最近播放」） */
    showMenu: Boolean = true,
    /** 非 null 时替换右侧的时长文本（「最近播放」用它显示「N 分钟前」） */
    trailingText: String? = null,
) {
    var menu by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selectionMode) {
            Checkbox(checked = selected, onCheckedChange = null)
            Spacer(Modifier.width(8.dp))
        }
        Text(
            "$index",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(26.dp),
        )
        if (showCover) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(6.dp))
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
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                item.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val sub = listOf(item.artist, item.album).filter { it.isNotBlank() }.joinToString(" · ")
            if (sub.isNotBlank()) {
                Text(
                    sub,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (!selectionMode) {
            Text(
                trailingText ?: formatDuration(item.durationMs),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (showMenu) {
                Box {
                    IconButton(onClick = { menu = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "更多操作", modifier = Modifier.size(20.dp))
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("上移") },
                            enabled = canMoveUp,
                            onClick = { menu = false; onMoveUp() },
                        )
                        DropdownMenuItem(
                            text = { Text("下移") },
                            enabled = canMoveDown,
                            onClick = { menu = false; onMoveDown() },
                        )
                        DropdownMenuItem(
                            text = { Text("从歌单移除") },
                            leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                            onClick = { menu = false; onRemove() },
                        )
                    }
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

/** 「最近播放」的播放时间显示：近期用相对时间，太久的直接给日期 */
private fun formatPlayedAgo(playedAt: Long, now: Long = System.currentTimeMillis()): String {
    val diff = (now - playedAt).coerceAtLeast(0L)
    return when {
        diff < 60_000L -> "刚刚"
        diff < 3_600_000L -> "${diff / 60_000L} 分钟前"
        diff < 86_400_000L -> "${diff / 3_600_000L} 小时前"
        diff < 30L * 86_400_000L -> "${diff / 86_400_000L} 天前"
        else -> java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
            .format(java.util.Date(playedAt))
    }
}
