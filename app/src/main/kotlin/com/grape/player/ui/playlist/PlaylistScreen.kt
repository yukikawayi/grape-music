package com.grape.player.ui.playlist

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
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
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Menu
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.viewmodel.compose.viewModel
import com.grape.player.data.MediaItemLite as MediaItem
import com.grape.player.data.Playlist
import com.grape.player.ui.AppViewModel
import com.grape.player.ui.components.FastListScrollBar
import com.grape.player.ui.components.LocatePlayingButton
import com.grape.player.ui.library.AddToPlaylistSheet
import com.grape.player.ui.library.SongInfoSheet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * 歌单页 —— 主页是歌单列表，点进去是歌单内曲目。
 *
 * 主页：新建 / 重命名 / 删除（二次确认）/ 显示曲目数。
 * 列表第一项固定是「最近播放」（见 [RECENT_PLAYLIST_ID]）：它不是真歌单，不可改名/删除，
 * 内容由播放记录自动生成（最多 100 首，时间由近到远）。
 * 详情：整单播放、点歌播放、长按多选（移除 / 加入其它歌单 / 编辑元数据）、
 * 顶部信息条（曲目数 · 总时长）右侧的竖三杠进入「自定义顺序」（按住行尾上下箭头拖动，
 * 点「退出」才落库）、清空、重命名、删除；每行行尾的 ⋮ 打开「更多」弹窗（与音乐库共用的 SongInfoSheet）。
 * 详情用页内状态 + BackHandler 实现，不新增导航路由，底部四个 tab 高亮不受影响。
 */
@Composable
fun PlaylistScreen(
    onSongClick: () -> Unit = {},
    vm: AppViewModel = viewModel(),
    /** 「更多」弹窗里点歌手 / 专辑的「详情 ›」时跳详情页；不传则不跳 */
    onGoto: (String, String) -> Unit = { _, _ -> },
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
            onGoto = onGoto,
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
            onGoto = onGoto,
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
    onGoto: (String, String) -> Unit = { _, _ -> },
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
    // 行尾「⋮」打开的「更多」弹窗（与音乐库 / 详情页共用 SongInfoSheet）
    var infoUrl by remember { mutableStateOf<String?>(null) }
    // 多选后「加入其它歌单」的目标选择弹层（与音乐库共用 AddToPlaylistSheet）
    var showAddToPlaylist by remember { mutableStateOf(false) }

    // ===== 自定义歌曲顺序 =====
    // 排序模式下用工作副本 sortOrder（url 顺序）渲染：拖动只改本地顺序，点「退出」才整表写回
    // position —— 拖动过程中不写库，避免每帧一次数据库事务。
    var sortMode by remember { mutableStateOf(false) }
    var sortOrder by remember { mutableStateOf<List<String>>(emptyList()) }
    // 正在拖动的行下标（-1 = 没在拖）、手指按在行内的 y、以及这一行相对当前槽位的像素位移
    var dragIndex by remember { mutableIntStateOf(-1) }
    var dragGrabY by remember { mutableFloatStateOf(0f) }
    var dragOffsetPx by remember { mutableFloatStateOf(0f) }

    // 排序模式下的显示顺序；长度对不上（补全期间元数据刷新过）就退回库里的顺序
    val displaySongs = remember(songs, sortOrder, sortMode) {
        if (!sortMode || sortOrder.isEmpty()) songs
        else {
            val byUrl = songs.associateBy { it.url }
            sortOrder.mapNotNull { byUrl[it] }.takeIf { it.size == songs.size } ?: songs
        }
    }
    // 总时长：只统计当前歌单里真实存在的曲目（失效曲目没有时长）
    val totalDurationMs = remember(songs) { songs.sumOf { it.durationMs } }

    // 长按进入多选（与音乐库「全部歌曲」页一致）：批量移除 / 加入其它歌单 / 批量编辑元数据
    var selectionMode by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    fun exitSelection() {
        selectionMode = false
        selected = emptySet()
    }

    fun enterSort() {
        // 排序与多选互斥：先把多选收掉，免得行里又是勾选框又是拖动手柄
        exitSelection()
        sortOrder = songs.map { it.url }
        dragIndex = -1
        dragGrabY = 0f
        dragOffsetPx = 0f
        sortMode = true
    }

    fun exitSort() {
        val shown = sortOrder
        sortMode = false
        dragIndex = -1
        dragGrabY = 0f
        dragOffsetPx = 0f
        sortOrder = emptyList()
        if (shown.isNotEmpty()) {
            // 库里已失效的曲目不在显示列表里，追加到末尾，重排不会把它们的关联行冲掉
            val shownSet = shown.toHashSet()
            val rest = entries.map { it.url }.filterNot { it in shownSet }
            scope.launch { vm.repo.reorderPlaylist(playlist.id, shown + rest) }
        }
    }

    // 排序模式下按返回键先退出排序（顺序照常落库），而不是直接退出歌单页
    BackHandler(enabled = sortMode) { exitSort() }

    // 拖到列表上下边缘时按 1/5 行滚动：长歌单里一次拖动也能跨越屏幕。
    // 只在「拖动的行贴着边缘」时滚，滚到边缘恰好露出一行就停（不是一直滚下去）。
    LaunchedEffect(dragIndex) {
        if (dragIndex < 0) return@LaunchedEffect
        while (true) {
            val layout = listState.layoutInfo
            val info = layout.visibleItemsInfo.firstOrNull { it.index == dragIndex }
                ?: break
            val margin = info.size * 0.75f
            val dy = when {
                listState.canScrollBackward &&
                    info.offset < layout.viewportStartOffset + margin -> -info.size * 0.2f
                listState.canScrollForward &&
                    info.offset + info.size > layout.viewportEndOffset - margin -> info.size * 0.2f
                else -> 0f
            }
            if (dy != 0f) listState.scrollBy(dy)
            withFrameNanos { }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                if (selectionMode) {
                    Column {
                        Text("已选 ${selected.size} 首", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "长按或点选歌曲，可批量移除 / 加入其它歌单 / 编辑元数据",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    // 曲目数 / 总时长已下移到列表顶部的信息条，标题只留歌单名 ——
                    // 空出一行后名字可以放大到 titleLarge，长名字仍走省略号
                    Text(
                        playlist.name,
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            },
            navigationIcon = {
                if (selectionMode) {
                    IconButton(onClick = { exitSelection() }) {
                        Icon(Icons.Filled.Close, contentDescription = "退出多选")
                    }
                } else {
                    IconButton(onClick = { if (sortMode) exitSort() else onBack() }) {
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

        // 多选操作栏：全选 ·…… 移除 · 加入其它歌单 · 编辑元数据
        // 退出多选走左上角的 ✕ / 返回键（与音乐库一致），栏里不再放「取消」
        if (selectionMode) {
            val allSelected = songs.isNotEmpty() && selected.size == songs.size
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
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
                    onClick = { showAddToPlaylist = true },
                ) { Text("加入其它歌单") }
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
            // 列表顶部信息条：曲目数 · 总时长；最右边是「自定义顺序」入口。
            // 曲目数从标题挪到这里（标题腾空后歌单名可以放大），总时长只在有歌时才有意义。
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (sortMode) {
                        "拖动右侧箭头调整顺序"
                    } else {
                        buildString {
                            append("${songs.size} 首")
                            if (missing > 0) append(" · $missing 首已失效")
                            append(" · 总时长 ")
                            append(formatTotalDuration(totalDurationMs))
                        }
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                // 多选时不显示排序入口：两种模式互斥，避免用户在这里误切换
                if (!selectionMode) {
                    if (sortMode) {
                        // 竖三杠 → 退出：点了才把新顺序写回数据库
                        TextButton(onClick = { exitSort() }) { Text("退出") }
                    } else {
                        IconButton(onClick = { enterSort() }) {
                            Icon(
                                // 三横线转 90° 就是「竖三杠」这种排序 / 拖动的通用符号
                                Icons.Filled.Menu,
                                contentDescription = "自定义歌曲顺序",
                                modifier = Modifier.size(20.dp).rotate(90f),
                            )
                        }
                    }
                }
            }

            Box(modifier = Modifier.fillMaxSize()) {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    itemsIndexed(displaySongs, key = { _, it -> it.url }) { idx, item ->
                        val dragging = dragIndex == idx
                        PlaylistSongRow(
                            index = idx + 1,
                            item = item,
                            showCover = songListCover,
                            selectionMode = selectionMode,
                            selected = selected.contains(item.url),
                            playing = item.url == playingUrl,
                            onClick = {
                                if (selectionMode) {
                                    selected = if (selected.contains(item.url)) selected - item.url
                                    else selected + item.url
                                } else {
                                    vm.player.setPlaylist(displaySongs, idx)
                                    onSongClick()
                                }
                            },
                            onLongClick = {
                                selectionMode = true
                                selected = selected + item.url
                            },
                            onMoreClick = { infoUrl = item.url },
                            sortMode = sortMode,
                            isDragging = dragging,
                            dragOffsetPx = if (dragging) dragOffsetPx else 0f,
                            onDragStart = { y ->
                                dragIndex = idx
                                dragGrabY = y
                                dragOffsetPx = 0f
                            },
                            onDrag = { y ->
                                // 位移与目标位置都按「手指在列表里的绝对位置」算，不做 delta 累加：
                                // 行换槽时槽位会整跳一行，绝对算法在同一帧就能扣回来（看不到跳动），
                                // 累加算法则会越拖越偏。
                                if (dragIndex >= 0) {
                                    val visible = listState.layoutInfo.visibleItemsInfo
                                    val self = visible.firstOrNull { it.index == dragIndex }
                                    if (self != null) {
                                        val pointerY = self.offset + y
                                        val hit = visible.firstOrNull {
                                            pointerY >= it.offset && pointerY < it.offset + it.size
                                        }
                                        var slotOffset = self.offset
                                        if (hit != null && hit.index != dragIndex &&
                                            dragIndex in sortOrder.indices &&
                                            hit.index in sortOrder.indices
                                        ) {
                                            // 拖进了别人的格子：把这行挪到那个下标
                                            sortOrder = sortOrder.toMutableList()
                                                .apply { add(hit.index, removeAt(dragIndex)) }
                                            dragIndex = hit.index
                                            slotOffset = hit.offset
                                        }
                                        dragOffsetPx = pointerY - slotOffset - dragGrabY
                                    }
                                }
                            },
                            onDragEnd = {
                                dragIndex = -1
                                dragOffsetPx = 0f
                            },
                        )
                    }
                }
                // 排序模式下藏掉右边缘这两个浮层：拖动把手就在右边缘，快速滑块会抢触控，
                // 「定位到正在播放」也会跟着行乱跳
                if (!sortMode) {
                    // 右侧浮层快速滑块：歌单很长时可按住拖到任意位置（含直接到页尾）
                    FastListScrollBar(
                        state = listState,
                        modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                    )
                    // 定位到正在播放：超出一屏才出现；正在播的歌不属于这个歌单时点了没反应
                    // （排序模式下按当前显示顺序算下标，否则会跳到旧位置）
                    LocatePlayingButton(
                        listState = listState,
                        playingUrl = playingUrl,
                        indexOf = { url ->
                            displaySongs.indexOfFirst { it.url == url }.takeIf { it >= 0 }
                        },
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(end = 16.dp, bottom = 16.dp),
                    )
                }
            }
        }
    }

    // 行尾「⋮」的「更多」弹窗：与音乐库 / 专辑详情 / 播放页用的是同一个 SongInfoSheet
    // （歌曲信息 + 编辑元数据 + 歌手/专辑跳转），不再在这里另做一套。
    infoUrl?.let { url ->
        songs.firstOrNull { it.url == url }?.let { item ->
            SongInfoSheet(
                item = item,
                onDismiss = { infoUrl = null },
                onEdit = {
                    infoUrl = null
                    vm.openMetaEdit(url)
                },
                onGoto = { gotoType, gotoValue ->
                    infoUrl = null
                    onGoto(gotoType, gotoValue)
                },
            )
        }
    }

    // 多选 →「加入其它歌单」：与音乐库多选栏共用同一个弹层
    if (showAddToPlaylist) {
        AddToPlaylistSheet(
            vm = vm,
            urls = selected.toList(),
            onDone = {
                showAddToPlaylist = false
                exitSelection()
            },
            onDismiss = { showAddToPlaylist = false },
        )
    }
}

// ===================== 最近播放（虚拟歌单，只读） =====================

/** 「最近播放」哨兵 id：不存在于 playlists 表，负数不会与自增主键冲突 */
private const val RECENT_PLAYLIST_ID = -1L
private const val RECENT_PLAYLIST_NAME = "最近播放"

/**
 * 最近播放详情 —— 结构对齐 [PlaylistDetail]，但**没有**任何编辑入口：
 * 没有重命名 / 清空 / 删除歌单，也没有从歌单移除（顺序由播放时间决定）。
 * 只保留「播放全部 + 点歌播放 + 每行 ⋮ 的更多弹窗」，长按多选也不给
 * —— 这里没有任何可批量做的事。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecentPlaylistDetail(
    songs: List<Pair<MediaItem, Long>>,
    vm: AppViewModel,
    onBack: () -> Unit,
    onSongClick: () -> Unit,
    onGoto: (String, String) -> Unit = { _, _ -> },
) {
    val songListCover by vm.settings.songListCover.collectAsState(initial = false)
    val playingUrl by vm.playingUrl.collectAsState()
    val listState = rememberLazyListState()
    // 「N 分钟前」按进入页面这一刻算：停留期间不刷新，免得为了这一列每秒重组整个列表
    val now = remember { System.currentTimeMillis() }
    val media = remember(songs) { songs.map { it.first } }
    // 行尾「⋮」打开的「更多」弹窗
    var infoUrl by remember { mutableStateOf<String?>(null) }

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
                            playing = item.url == playingUrl,
                            onClick = {
                                vm.player.setPlaylist(media, idx)
                                onSongClick()
                            },
                            onLongClick = {},
                            onMoreClick = { infoUrl = item.url },
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

    // 每行 ⋮ 的「更多」弹窗：与歌单详情 / 音乐库同一个 SongInfoSheet。
    // 最近播放只读，所以这里不给「编辑元数据」之外的任何写操作入口。
    infoUrl?.let { url ->
        songs.firstOrNull { it.first.url == url }?.let { entry ->
            SongInfoSheet(
                item = entry.first,
                onDismiss = { infoUrl = null },
                onEdit = {
                    infoUrl = null
                    vm.openMetaEdit(url)
                },
                onGoto = { gotoType, gotoValue ->
                    infoUrl = null
                    onGoto(gotoType, gotoValue)
                },
            )
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
    /** 这首歌是不是播放器当前那一首：是则歌名 / 艺术家·专辑用主色标出来 */
    playing: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    /** 行尾 ⋮ 点击：打开「更多」弹窗（SongInfoSheet） */
    onMoreClick: () -> Unit,
    /** 非 null 时替换右侧的时长文本（「最近播放」用它显示「N 分钟前」） */
    trailingText: String? = null,
    /** 自定义顺序模式：行尾换成上下箭头拖动手柄，行本身不再响应点击 / 长按 */
    sortMode: Boolean = false,
    /** 本行正在被拖动 */
    isDragging: Boolean = false,
    /**
     * 拖动中的行相对**当前槽位**的像素位移（其它行恒为 0）。
     * 注意：这是按手指绝对位置算出来的绝对值，不是累加量 —— 行换槽后由调用方在同一帧里减掉，
     * 屏幕上看不到跳动。
     */
    dragOffsetPx: Float = 0f,
    /** 长按行尾箭头开始拖动，参数是手指在行内的 y */
    onDragStart: (Float) -> Unit = {},
    /** 拖动中，参数是手指在行内的 y */
    onDrag: (Float) -> Unit = {},
    onDragEnd: () -> Unit = {},
) {
    // pointerInput(Unit) 的手势循环只跑一次，回调必须取最新的一份：
    // 换序后本行的下标变了，闭包里如果还是旧下标，下次拖动会抓错行
    val dragStartCb by rememberUpdatedState(onDragStart)
    val dragCb by rememberUpdatedState(onDrag)
    val dragEndCb by rememberUpdatedState(onDragEnd)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            // 拖动的行抬到最上层，否则会被后面几行盖住
            .zIndex(if (isDragging) 1f else 0f)
            // 手势挂在这一层（外层），位移加在内层：指针坐标因此永远等于手指在行内的真实位置，
            // 不会因为「自己把自己挪走了」而反馈回来。
            .pointerInput(sortMode) {
                if (!sortMode) return@pointerInput
                // 只有按在行尾箭头那一小块才开始拖，按在歌名上不抢手势
                val handleZone = 56.dp.toPx()
                var active = false
                detectDragGesturesAfterLongPress(
                    onDragStart = { start ->
                        active = start.x >= size.width - handleZone
                        if (active) dragStartCb(start.y)
                    },
                    onDrag = { change, _ ->
                        if (active) {
                            change.consume()
                            dragCb(change.position.y)
                        }
                    },
                    onDragEnd = {
                        if (active) dragEndCb()
                        active = false
                    },
                    onDragCancel = {
                        if (active) dragEndCb()
                        active = false
                    },
                )
            },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // 用 layout offset（不是 graphicsLayer）：内层整体位移，外层手势层不受影响
                .offset { IntOffset(0, dragOffsetPx.roundToInt()) }
                // 拖动中的行抬成一张不透明的小卡片：否则它滑过别的行时会和下面的歌名重叠成一团
                .then(
                    if (isDragging) {
                        Modifier
                            .shadow(elevation = 8.dp, shape = RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(10.dp))
                    } else {
                        Modifier
                    },
                )
                // 排序模式下整行不可点：避免拖动途中的误触播放 / 误触多选
                .then(
                    if (sortMode) Modifier
                    else Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick),
                )
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
                    color = if (playing) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface,
                )
                val sub = listOf(item.artist, item.album).filter { it.isNotBlank() }.joinToString(" · ")
                if (sub.isNotBlank()) {
                    Text(
                        sub,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (playing) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
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
                if (sortMode) {
                    // 排序模式：行尾换成上下箭头（拖动手势在外层，这里只是给用户看的把手）
                    SortHandle()
                } else {
                    // 行尾「⋮」：打开与音乐库 / 专辑详情一致的「更多」弹窗（歌曲信息 / 编辑元数据 / 跳转）。
                    // 原来的「上移 / 下移 / 从歌单移除」下拉菜单已移除：批量移除走长按多选，
                    // 顺序调整改由顶部的竖三杠进入自定义排序。
                    IconButton(onClick = onMoreClick) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "更多信息", modifier = Modifier.size(20.dp))
                    }
                }
            }
        }

        // 插入位置提示线：贴在**槽位顶边**（不是跟着行走的），画在行之后所以不会被行盖住。
        // 行本身随手指浮动、槽位才是它松手后的落点，这条线就是那个落点的醒目标记。
        if (isDragging) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    // 往上压 1dp，正好落在与上一行的接缝上，看起来是「插到这两行之间」
                    .offset(y = (-1).dp)
                    .height(3.dp)
                    .padding(horizontal = 12.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
    }
}

/**
 * 排序模式的行尾把手：上下两个箭头。
 *
 * 本身不接手势 —— 拖动由 [PlaylistSongRow] 外层那一层统一处理（按住这里再往下拖）。
 * 把手必须放在会位移的内层里，才能跟着行一起动；手势放在外层，指针坐标才不会被
 * 自己的位移反过来干扰。
 */
@Composable
private fun SortHandle() {
    Column(
        modifier = Modifier.size(width = 40.dp, height = 44.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Filled.KeyboardArrowUp,
            contentDescription = "按住拖动排序",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
        Icon(
            Icons.Filled.KeyboardArrowDown,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
    }
}

/**
 * 排序模式的拖动手柄：上下两个箭头，**长按**之后再拖动。
 *
 * 用「长按 + 拖动」而不是「按下即拖」，是为了不和列表本身的滚动抢手势 ——
 * 手指落在这一小块上时先进入拖动，落点在别处时列表照常滚。
 * 位移只上报给父级 [PlaylistDetail]（交换行、写库都在那边），这里不管顺序。
 */
@Composable
private fun SortHandle(
    onDragStart: () -> Unit,
    onDragDelta: (Float) -> Unit,
    onDragEnd: () -> Unit,
) {
    // pointerInput(Unit) 只跑一次手势检测循环，回调必须走 rememberUpdatedState 取最新的一份，
    // 否则拖动途中父级重组后拿到的还是第一次组合时的闭包
    val start by rememberUpdatedState(onDragStart)
    val delta by rememberUpdatedState(onDragDelta)
    val end by rememberUpdatedState(onDragEnd)
    Column(
        modifier = Modifier
            .size(width = 40.dp, height = 44.dp)
            .pointerInput(Unit) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { start() },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        delta(dragAmount.y)
                    },
                    onDragEnd = { end() },
                    onDragCancel = { end() },
                )
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Filled.KeyboardArrowUp,
            contentDescription = "拖动排序",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
        Icon(
            Icons.Filled.KeyboardArrowDown,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
    }
}

private fun formatDuration(ms: Long): String {
    if (ms <= 0) return "--:--"
    val totalSec = ms / 1000
    return "%d:%02d".format(totalSec / 60, totalSec % 60)
}

/**
 * 歌单总时长：不足 1 小时给「分:秒」，超过 1 小时给「时:分:秒」。
 * 与单曲时长 [formatDuration] 分开，避免把 3 小时的歌单显示成「183:20」。
 */
private fun formatTotalDuration(ms: Long): String {
    if (ms <= 0) return "0:00"
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
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
