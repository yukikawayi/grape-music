package com.grape.player.ui.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.grape.player.data.MediaItemLite as MediaItem
import com.grape.player.ui.AppViewModel
import com.grape.player.ui.components.FastListScrollBar
import com.grape.player.ui.components.LocatePlayingButton
import com.grape.player.util.AlphaIndex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 详情页 —— 文件夹 / 专辑 / 艺术家的下钻列表。
 * type 与 value 来自导航参数，刷新/前进后退自洽，便于分享。
 */
@OptIn(ExperimentalFoundationApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(
    type: String,
    value: String,
    vm: AppViewModel,
    onBack: () -> Unit,
    onSongClick: () -> Unit,
    onGoto: (String, String) -> Unit = { _, _ -> },
) {
    val allMedia by vm.allMedia.collectAsState()
    val songListCover by vm.settings.songListCover.collectAsState(initial = false)
    val playingUrl by vm.playingUrl.collectAsState()
    // 滚动状态放 VM（key 按 type+value 区分），与 LibraryScreen 同源：
    // rememberLazyListState 在离开组合（进播放页再返回）后会丢位置回到顶部，
    // VM 状态横跨所有导航路径天然存活
    val listState = vm.libraryListState("detail:$type:$value")
    var selectionMode by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var infoUrl by remember { mutableStateOf<String?>(null) }
    var showAddToPlaylist by remember { mutableStateOf(false) }

    // 按类型过滤出该分组下的歌曲。
    // 放后台算：原来 remember(allMedia, type, value) 在组合期主线程 filter + 全库 sortedBy，
    // allMedia 每 250ms 刷新一次就重算一遍，大库上把主线程帧时间吃光。
    val items by produceState(initialValue = emptyList<MediaItem>(), allMedia, type, value) {
        this.value = withContext(Dispatchers.Default) {
            val filtered = when (type) {
                "folder" -> allMedia.filter { it.folder == value }
                "album" -> allMedia.filter { it.album.ifEmpty { "未知专辑" } == value }
                "artist" -> allMedia.filter { it.artist.ifEmpty { "未知艺术家" } == value }
                else -> emptyList()
            }
            // 专辑内按「碟号 → 音轨号 → 标题」排序，与唱片实际曲序一致；
            // 碟号 0 视为第 1 碟，音轨号 0（未填）排在本碟最后，按标题兜底。
            // 其它视图（文件夹 / 艺术家）仍按标题首字母。
            when (type) {
                "album" -> filtered.sortedWith(
                    compareBy<MediaItem> { it.discNumber.takeIf { d -> d > 0 } ?: 1 }
                        .thenBy { it.trackNumber.takeIf { t -> t > 0 } ?: Int.MAX_VALUE }
                        .thenBy { it.title.lowercase() },
                )
                else -> filtered.sortedBy { AlphaIndex.alphaKeyOf(it.title).letter }
            }
        }
    }

    // 专辑视图把「碟分隔标题 + 歌曲行」拍平成一维列表：多碟专辑在碟与碟之间插一条 Disc 分隔，
    // 单碟专辑不插（否则顶上多一条无意义的「Disc 1」）。文件夹 / 艺术家没有碟的概念，只放歌曲行。
    val rows: List<DetailRow> = remember(items, type) {
        if (type != "album") {
            items.map { DetailRow.Track(it) }
        } else {
            // items 已按「碟号 → 音轨号」排好，groupBy 保持迭代顺序，碟的顺序不会乱
            val byDisc = items.groupBy { it.discNumber.takeIf { d -> d > 0 } ?: 1 }
            buildList {
                byDisc.forEach { (disc, songs) ->
                    if (byDisc.size > 1) add(DetailRow.DiscHeader(disc, songs.size))
                    songs.forEach { add(DetailRow.Track(it)) }
                }
            }
        }
    }

    val titleLabel = when (type) {
        "folder" -> "文件夹"
        "album" -> "专辑"
        "artist" -> "艺术家"
        else -> "详情"
    }

    // 专辑年份：取本专辑内各曲目年份的最大值，与音乐库专辑卡片的算法保持一致
    // （多碟合辑里只要有一首带年份就显示，零散曲目没填也不影响）。
    // 文件夹 / 艺术家没有「专辑年份」这个概念，恒为 0 即不显示。
    val albumYear = remember(items, type) {
        if (type == "album") items.maxOfOrNull { it.year } ?: 0 else 0
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Column {
                    Text(
                        "$titleLabel：$value",
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        // 多选时「已选 N 首」在下方的多选栏里显示，标题这行只留总数，不重复。
                        // 专辑再加一段年份（「12 首 · 2019」），没填年份就只有曲目数。
                        if (albumYear > 0) "${items.size} 首 · $albumYear" else "${items.size} 首",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            },
            navigationIcon = {
                IconButton(onClick = {
                    if (selectionMode) {
                        selectionMode = false
                        selected = emptySet()
                    } else onBack()
                }) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
                }
            },
        )

        // 多选操作栏 —— 固定显示在专辑 / 艺术家标题下方，不再塞进 TopAppBar 的 actions。
        // 原因：标题是「专辑：xxx + N 首」两行排版，长专辑名 / 长艺术家名会把标题区域撑满，
        // 挤得右侧按钮只剩一条缝，多选菜单里的选项看不全。独立成行后标题再长也压不到它。
        // 行本身与音乐库共用 MultiSelectBar（顺序：全选 / 取消 / 已选 N 首 / 加入歌单 / 编辑元数据）。
        if (selectionMode) {
            val allUrls = items.map { it.url }.toSet()
            val allSelected = allUrls.isNotEmpty() && selected.containsAll(allUrls)
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

        if (items.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("暂无歌曲", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            Box(modifier = Modifier.fillMaxSize()) {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    items(
                        items = rows,
                        key = {
                            when (it) {
                                is DetailRow.DiscHeader -> "disc:${it.disc}"
                                is DetailRow.Track -> it.item.url
                            }
                        },
                    ) { row ->
                        when (row) {
                            is DetailRow.DiscHeader -> DiscHeaderRow(row.disc, row.count)
                            is DetailRow.Track -> {
                                val item = row.item
                                DetailSongRow(
                                    item = item,
                                    // 音轨号只在专辑里显示（文件夹 / 艺术家没有曲序概念）；
                                    // -1 = 不显示，0 = 有歌但没填音轨号（显示成 –）
                                    trackNumber = if (type == "album") item.trackNumber else -1,
                                    showCover = songListCover,
                                    selected = selected.contains(item.url),
                                    selectionMode = selectionMode,
                                    // 正在播放的那一首用主色标出歌名 / 艺术家·专辑
                                    playing = item.url == playingUrl,
                                    onClick = {
                                        if (selectionMode) {
                                            selected = if (selected.contains(item.url))
                                                selected - item.url else selected + item.url
                                        } else {
                                            vm.player.setPlaylist(items, items.indexOf(item))
                                            onSongClick()
                                        }
                                    },
                                    onLongClick = {
                                        selectionMode = true
                                        selected = selected + item.url
                                    },
                                    onMoreClick = { infoUrl = item.url },
                                )
                            }
                        }
                    }
                }
                // 右侧浮层快速滑块：歌曲多到超出一屏时出现，按住往下拖可直落页尾。
                // 行尾内边距是 16dp，14dp 的触控带只压在空白上，不会盖住「⋮」按钮。
                FastListScrollBar(
                    state = listState,
                    modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                )
                // 定位到正在播放：列表超出一屏才出现，贴在右下角、底部播放栏上方一点
                LocatePlayingButton(
                    listState = listState,
                    playingUrl = playingUrl,
                    // rows 与 LazyColumn 的项一一对应（多碟专辑的「Disc N」分隔也算一项），
                    // 直接用行下标就是 scrollToItem 需要的项序号
                    indexOf = { url ->
                        rows.indexOfFirst { it is DetailRow.Track && it.item.url == url }
                            .takeIf { it >= 0 }
                    },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 16.dp, bottom = 16.dp),
                )
            }
        }
    }

    // 歌曲信息弹层（竖三点入口）
    infoUrl?.let { url ->
        allMedia.firstOrNull { it.url == url }?.let { item ->
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

    // 加入歌单：与音乐库多选栏共用同一个弹层（AddToPlaylistSheet）
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

/** 详情列表里的一行：要么是碟分隔标题，要么是一首歌。 */
private sealed interface DetailRow {
    /** 多碟专辑里的「Disc N」分隔；count = 该碟歌曲数。 */
    data class DiscHeader(val disc: Int, val count: Int) : DetailRow

    data class Track(val item: MediaItem) : DetailRow
}

/** 「Disc N」分隔标题：只出现在多碟专辑里，把上一碟和下一碟的曲目分开。 */
@Composable
private fun DiscHeaderRow(disc: Int, count: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Disc $disc",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            "$count 首",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DetailSongRow(
    item: MediaItem,
    trackNumber: Int,
    showCover: Boolean,
    selected: Boolean,
    selectionMode: Boolean,
    /** 这首歌是不是播放器当前那一首：是则歌名 / 艺术家·专辑用主色标出来 */
    playing: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onMoreClick: () -> Unit,
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
        // 音轨号：固定 22dp、右对齐，一位数和两位数不会把后面的标题挤得参差不齐
        if (trackNumber >= 0) {
            Text(
                text = if (trackNumber > 0) "$trackNumber" else "–",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End,
                modifier = Modifier.width(22.dp),
            )
            Spacer(Modifier.width(10.dp))
        }
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
                color = if (playing) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                "${item.artist} · ${item.album}",
                style = MaterialTheme.typography.bodySmall,
                color = if (playing) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        if (!selectionMode) {
            IconButton(onClick = onMoreClick) {
                Icon(Icons.Filled.MoreVert, contentDescription = "更多信息", modifier = Modifier.size(20.dp))
            }
        }
    }
}
