package com.grape.player.ui.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.grape.player.data.MediaItemLite as MediaItem
import com.grape.player.network.LibraryScanner
import com.grape.player.ui.AppViewModel
import com.grape.player.ui.components.FastGridScrollBar
import com.grape.player.ui.components.FastListScrollBar
import com.grape.player.ui.components.LocatePlayingButton
import com.grape.player.util.AlphaIndex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 音乐库的五个视图。
 *
 * [key] 是排序设置的持久化键后缀（DataStore 里存成 sort_folder / sort_all / …），
 * 不要随便改：改了等于把用户已保存的排序丢掉。
 */
enum class LibraryView(val key: String) {
    FOLDER("folder"),
    ALL("all"),
    ALBUM("album"),
    ARTIST("artist"),
    VIDEO("video"),
}
enum class SortBy { TITLE, YEAR, ALBUM, ARTIST, SIZE, DURATION, FOLDER, COUNT }

/** 文件夹视图的两种浏览方式 */
enum class FolderBrowseMode(val label: String) {
    /** 层级：点进文件夹逐级下钻，文件夹里可能还有文件夹 */
    HIERARCHY("层级"),

    /** 平铺：只列出「最后一级有歌曲」的文件夹，一列列展示 */
    FLAT("平铺"),
}

/** 层级视图：当前目录下的一个子文件夹 */
private data class FolderChild(
    val name: String,
    /** 解码后的完整路径（与 SongInfoSheet「网盘路径」同源语义） */
    val path: String,
    /** 含子文件夹的递归歌曲数 */
    val songCount: Int,
    val coverUrl: String,
)

/** 层级视图在当前目录要展示的内容 */
private data class HierarchyContent(
    val children: List<FolderChild>,
    /** 直接位于当前目录（不含子文件夹）的歌曲 */
    val direct: List<MediaItem>,
    /** 播放队列：子文件夹按名称顺序递归展开 + 直接歌曲 */
    val queue: List<MediaItem>,
)

/** 音乐库主页 —— 四视图、排序、首字母索引、长按多选 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen(
    onSongClick: () -> Unit,
    onVideoClick: (String, String) -> Unit = { _, _ -> },
    onGroupClick: (String, String) -> Unit = { _, _ -> },
    vm: AppViewModel = viewModel(),
) {
    val allMedia by vm.allMedia.collectAsState()
    val videos by vm.videos.collectAsState()
    // 每个视图各自持久化的排序（Map<视图 key, SortBy.name>）
    val sortPrefs by vm.settings.sortPrefs.collectAsState(initial = emptyMap<String, String>())
    val scanState by vm.scanState.collectAsState()
    val scope = rememberCoroutineScope()

    // —— 页面设置：当前视图 + 文件夹浏览方式（都持久化，重启后照样回到上次的样子）——
    // 两级来源：rememberSaveable 的 override 让「点歌进播放页再返回」立刻回到原视图；
    // DataStore 里的 pref 负责杀进程重启。override 为空时用 pref，pref 也为空才用默认值。
    val prefView by vm.settings.libraryView.collectAsState(initial = "")
    val prefFolderMode by vm.settings.folderBrowseMode.collectAsState(initial = "")
    var viewKeyOverride by rememberSaveable { mutableStateOf("") }
    var folderModeOverride by rememberSaveable { mutableStateOf("") }
    val view = LibraryView.entries.firstOrNull { it.key == viewKeyOverride.ifEmpty { prefView } }
        ?: LibraryView.ALL
    val folderMode = FolderBrowseMode.entries
        .firstOrNull { it.name == folderModeOverride.ifEmpty { prefFolderMode } }
        ?: FolderBrowseMode.HIERARCHY
    // 排序：按视图各存一份（VM 里的 librarySorts，跨导航/旋转保留），没选过才用 DataStore 的持久化值。
    // 必须是**同步可读**的：切视图的首帧就要用目标视图自己的排序渲染 —— 以前用全局
    // sortByName + LaunchedEffect 回填，切回某视图的首帧还按上一个视图的排序渲染（顺序闪一下，
    // 位置索引短暂落在另一套顺序上），所以改成「视图 key → 排序」直接查表。
    val sortOverrides by vm.librarySorts.collectAsState()
    val sortBy: SortBy = remember(view, sortOverrides, sortPrefs) {
        val allowed = sortOptionsFor(view)
        val name = sortOverrides[view.key] ?: sortPrefs[view.key]
        allowed.firstOrNull { it.name == name } ?: allowed.first()
    }
    // 文件夹视图层级浏览的当前目录（解码路径，"" = 根目录）：页面内进出 + 进程重建都要记住
    var folderPath by rememberSaveable { mutableStateOf("") }
    var selectionMode by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var infoUrl by remember { mutableStateOf<String?>(null) }
    var showAddToPlaylist by remember { mutableStateOf(false) }
    // 滚动状态放 VM：跨导航保留，不依赖 NavCompose 的 saveable 恢复
    // （targetSdk 36 预测式返回下恢复不可靠，见 AppViewModel.libraryListState 注释）。
    // 每个视图各一份：在专辑页滚到位置 2、切去艺术家再切回来，专辑仍停在位置 2；
    // 应用进程退出后全部重置。注意 view 变化时这里取到的是另一份状态实例，
    // LazyColumn 换 state 参数即切换滚动上下文，无需任何手动复位。
    val listState = vm.libraryListState(view.key)
    val gridState = vm.libraryGridState(view.key)

    // 当前数据源（视频视图用视频列表）
    val source = if (view == LibraryView.VIDEO) videos else allMedia

    // —— 派生数据（排序 / URL 解码 / 分组 / 层级展开）——
    // 这些是 O(N×M) 的重活，以前用 remember 在主线程组合期间全量重算；
    // 后台补全期间 Room Flow 高频发新列表，大库直接把主线程帧时间吃光（列表卡顿、点按延迟）。
    // 策略分两路：
    // ① 用户操作（切排序/切视图/进出目录）→ remember(key) 同步重算：切视图时派生数据若
    //    还挂在后台异步，第一帧会渲染上一个视图的旧内容，随后滚顶 + 换内容，肉眼可见
    //    「专辑先回顶、再切艺术家」。同步算一次换来切换帧内容即正确（一次性开销，可接受）。
    // ② 补全刷新（source 变化、key 不变）→ 走下面的 LaunchedEffect 在 Default 线程算，
    //    主线程零重计算，高频刷新不掉帧。
    var derived by remember(sortBy, view, folderPath) {
        mutableStateOf(computeDerived(source, sortBy, view, folderPath))
    }
    LaunchedEffect(source, sortBy, view, folderPath) {
        derived = withContext(Dispatchers.Default) {
            computeDerived(source, sortBy, view, folderPath)
        }
    }
    // 后台计算是否就绪：冷启动数据首次到达（source 非空）但派生数据还没算完时，
    // 列表显示骨架屏过渡，而不是像以前那样在组合期同步补一份 computeDerived ——
    // 那一次同步重算在大库上会阻塞主线程百毫秒级。
    // 注意：切视图/切排序走的还是 remember(key) 的同步算（上面），那是一锤子买卖的
    // 用户操作开销；这里只拦「数据到达」这条高频异步通道。
    val derivedReady = derived.sorted.isNotEmpty() || source.isEmpty()
    val derivedNow = derived

    val sorted = derivedNow.sorted
    val dirPaths = derivedNow.dirPaths
    val hierarchy = derivedNow.hierarchy
    val grouped = derivedNow.grouped

    // 平铺视图歌曲层：进入某文件夹后的直接歌曲（按当前排序）
    val flatSongs = remember(sorted, dirPaths, folderPath) {
        sorted.filter { dirPaths[it.url] == folderPath }
    }

    // 平铺视图：同名最后一级文件夹出现在不同父目录时，标题下补一行完整路径以区分
    val duplicateFolderNames = remember(grouped, view) {
        if (view != LibraryView.FOLDER) emptySet()
        else grouped.map { it.first }
            .groupBy { it.substringAfterLast('/').ifEmpty { "/" } }
            .filterValues { it.size > 1 }
            .keys
    }

    // 平铺视图列表层：只列出有歌曲的最后一级文件夹（点进去才显示歌曲）。
    // 排序跟随当前选择：标题 = 按路径升序；歌曲数 = 从多到少（同数按路径）
    val flatFolders = remember(grouped, view, folderMode, sortBy) {
        if (view == LibraryView.FOLDER && folderMode == FolderBrowseMode.FLAT) {
            val folders = grouped.map { (path, items) ->
                FolderChild(
                    name = path.substringAfterLast('/').ifEmpty { "/" },
                    path = path,
                    songCount = items.size,
                    coverUrl = items.firstNotNullOfOrNull { it.coverUrl.takeIf(String::isNotBlank) } ?: "",
                )
            }
            if (sortBy == SortBy.COUNT) {
                folders.sortedWith(
                    compareByDescending<FolderChild> { it.songCount }.thenBy { it.path.lowercase() },
                )
            } else folders.sortedBy { it.path.lowercase() }
        } else emptyList()
    }

    // 首字母索引条用的分组键列表
    val indexLetters = remember(grouped, view) {
        if (view == LibraryView.ALL && sortBy == SortBy.TITLE)
            grouped.map { it.first }
        else emptyList()
    }

    // 每个分组在 LazyColumn 里的起始「项序号」（每个分组前面固定有一个 stickyHeader）。
    // 之前直接把分组序号丢给 scrollToItem：A 组有 5 首时点 B，会跳到 A 组的第 2 首。
    val groupStartIndices = remember(grouped) {
        var acc = 0
        grouped.map { group ->
            val start = acc
            acc += 1 + group.second.size
            start
        }
    }

    // 索引条拖动中的字母（用于中间的大字气泡）
    var draggingLetter by remember { mutableStateOf<String?>(null) }

    // 歌曲行是否显示封面（设置 → 外观 → 歌曲列表显示封面）
    val songListCover by vm.settings.songListCover.collectAsState(initial = false)

    // 当前播放曲目（定位按钮用）
    val playingUrl by vm.playingUrl.collectAsState()

    // 播放队列 = 当前视图的显示顺序（点第几首，队列里就是第几首）；
    // 层级视图 = 当前目录按子文件夹顺序递归展开 + 直接歌曲；平铺视图歌曲层 = 该文件夹的歌曲
    val playQueue = remember(grouped, hierarchy, flatSongs, view, folderMode, folderPath) {
        when {
            view == LibraryView.FOLDER && folderMode == FolderBrowseMode.HIERARCHY -> hierarchy.queue
            view == LibraryView.FOLDER && folderMode == FolderBrowseMode.FLAT && folderPath.isNotEmpty() -> flatSongs
            else -> grouped.flatMap { it.second }
        }
    }

    /**
     * 定位按钮用：url → 它在当前 LazyColumn 里的**项序号**。
     *
     * 列表结构随视图 / 浏览方式而变（分组标题、面包屑、返回行、子文件夹行都是歌曲之外的项），
     * 偏移必须按渲染时那一套结构算，否则会像早年的首字母索引那样「点 B 组却落到 A 组的第 2 首」。
     * 本页没有这首歌时返回 null（按钮点了什么也不做）。
     */
    fun indexOfSong(url: String): Int? = when {
        view == LibraryView.FOLDER && folderMode == FolderBrowseMode.HIERARCHY -> {
            // 面包屑 + 返回上一级 + 子文件夹之后，才是当前目录的直接歌曲
            val base = 1 + if (folderPath.isNotEmpty()) 1 else 0
            val i = hierarchy.direct.indexOfFirst { it.url == url }
            if (i >= 0) base + hierarchy.children.size + i else null
        }
        view == LibraryView.FOLDER && folderMode == FolderBrowseMode.FLAT && folderPath.isNotEmpty() -> {
            // 面包屑 + 返回文件夹列表之后才是歌曲
            val i = flatSongs.indexOfFirst { it.url == url }
            if (i >= 0) 2 + i else null
        }
        // 平铺视图的文件夹列表层：整页都是文件夹，没有歌
        view == LibraryView.FOLDER && folderMode == FolderBrowseMode.FLAT -> null
        else -> {
            // 分组视图：每组 = 1 个标题项 + N 首
            var acc = 0
            var result: Int? = null
            for ((_, songs) in grouped) {
                val i = songs.indexOfFirst { it.url == url }
                if (i >= 0) {
                    result = acc + 1 + i
                    break
                }
                acc += 1 + songs.size
            }
            result
        }
    }

    // 各视图允许的排序项（见 sortOptionsFor）：文件夹=标题/歌曲数；专辑=标题/年份/歌曲数；
    // 艺术家=名称/歌曲数；全部/视频=标题/年份/专辑/艺术家/文件大小/时长/文件夹
    val sortOptions = sortOptionsFor(view)

    // 进出目录 / 切浏览方式时回到列表顶部 —— 只在用户点击的瞬间显式调用 goTop()。
    // 不用「LaunchedEffect 检测 key 变化 → 滚顶」的间接推断：view/folderMode 来自 DataStore，
    // composition 重建（从播放页返回）时先按默认值渲染一帧，几十毫秒后持久化值异步到达，
    // key 变化会被误判成用户切换而滚顶 —— 实测正是「从播放页返回总是回到顶部」的根因。
    // 但滚顶也不能放在点击回调里直接 scrollToItem：内容切换的重组要等下一帧应用，
    // 会露出「旧内容先滚顶、再换新内容」的中间帧。所以这里用两级：用户点击只置
    // pending 标志，实际滚动由下面的 LaunchedEffect 在新内容重组应用后执行。
    // 切视图（歌曲/专辑/艺术家/文件夹）不滚顶：滚动状态按视图各存一份（VM），切换后
    // 各自回到自己上次的位置，天然无需重置。
    //
    // 请求带**目标视图 key**：只有发起请求的那个视图才消费它。以前是一个全局 boolean，
    // 一旦用户在文件夹里点进子目录后紧接着点别的视图标签，effect 会在「新视图」上执行，
    // 把新视图保存的位置清成 0（正好破坏「切回来还停在原处」）。
    var goTopFor by remember { mutableStateOf<String?>(null) }
    fun goTop() {
        goTopFor = view.key
    }
    LaunchedEffect(view, folderMode, folderPath, goTopFor) {
        if (goTopFor != view.key) return@LaunchedEffect
        // 只滚当前视图真正挂载的那一个状态：网格视图里的 listState / 列表视图里的 gridState
        // 从未 attach 到任何 Lazy 布局，scrollToItem 会卡在 AwaitFirstLayout 上永不返回
        //（Compose 1.9 实测：AwaitFirstLayoutModifier.waitForFirstLayout 对未 attach 的 state 永久挂起），
        // 于是真正的回顶可能永远不执行。requestScrollToItem 非挂起、不等首布局，直接排进下次测量。
        if (view == LibraryView.ALBUM || view == LibraryView.ARTIST) {
            gridState.requestScrollToItem(0)
        } else {
            listState.requestScrollToItem(0)
        }
        // 滚完再清标志：清了会改变 key 重启本 effect，放在挂起调用之后最安全（这里无挂起，仍保持顺序）
        goTopFor = null
    }

    // 层级视图在子目录 / 平铺视图进入文件夹时，系统返回键 = 回到上一层（而不是退出页面）
    BackHandler(
        enabled = view == LibraryView.FOLDER && folderPath.isNotEmpty(),
    ) {
        folderPath = if (folderMode == FolderBrowseMode.FLAT) ""
        else folderPath.substringBeforeLast('/')
        goTop()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // 顶部视图切换 + 排序
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LibraryView.entries.forEach { v ->
                TextButton(
                    // 水平内边距从默认 12dp 收到 6dp：5 个标签 + 右侧菜单按钮要在一行里放得下。
                    // 放不下时 TextButton 会把文字压到最窄宽度逐字换行（竖排「挂/月」那一列），
                    // 行高随之变高，整排标签看起来就比别的视图低一截
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp),
                    onClick = {
                        if (v.key == view.key) {
                            // 再点当前标签 = 回到本视图的顶层（文件夹视图回到根目录）+ 回顶。
                            // 只动自己这一个视图的状态，不会误伤其他视图。
                            if (folderPath.isNotEmpty()) folderPath = ""
                            goTop()
                        } else {
                            // 切视图：**不清 folderPath** —— 各视图各自记住自己的位置（含文件夹层级），
                            // 切走再切回来还是原处。这里也绝不能 goTop()：goTop 命中的是「新视图」的状态，
                            // 会把它的历史位置清掉。之前清 folderPath 又不回顶，导致从子文件夹切走再切回
                            // 时「内容回到根目录、滚动位置还停在子文件夹的那一屏」。
                            viewKeyOverride = v.key
                            // 多选是「当前视图里的选择」，跨视图残留会让批量操作作用到看不见的歌
                            selectionMode = false
                            selected = emptySet()
                            scope.launch { vm.settings.setLibraryView(v.key) }
                        }
                    },
                ) {
                    Text(
                        text = when (v) {
                            LibraryView.FOLDER -> "文件夹"
                            LibraryView.ALL -> "全部"
                            LibraryView.ALBUM -> "专辑"
                            LibraryView.ARTIST -> "艺术家"
                            LibraryView.VIDEO -> "视频"
                        },
                        color = if (view == v) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface,
                        // 兜底：极端窄屏也不让标签逐字竖排（宁可截断，也不能把行高撑高）
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
            Box(modifier = Modifier.weight(1f))
            // 右上角只有一个「显示」/「排序」菜单。
            //
            // 文件夹视图以前是「层级/平铺 ▾」+「排序 ▾」两个按钮并排，加上 5 个视图标签后
            // 整行宽度溢出：TextButton 里的文字被压到最窄宽度、逐字换行成竖排（右侧露出
            // 「挂 / 月」那一列字），行高也被撑高，整排标签比其他视图低约 24dp。
            // 现在两者合成一个菜单（浏览方式 + 排序分节），宽度回到和其他视图一致。
            //
            // DropdownMenu 必须被 Box 包住才「挂在按钮上」：直接放 Row 里会按整行原点定位，
            // 弹层飘到按钮左边的空白处。Box + TopEnd 让菜单从按钮右下角往下展开，也不出屏。
            var displayMenuExpanded by remember { mutableStateOf(false) }
            Box(contentAlignment = Alignment.TopEnd) {
                TextButton(onClick = { displayMenuExpanded = true }) {
                    Text(if (view == LibraryView.FOLDER) "显示 ▾" else "排序 ▾")
                }
                DropdownMenu(
                    expanded = displayMenuExpanded,
                    onDismissRequest = { displayMenuExpanded = false },
                ) {
                    if (view == LibraryView.FOLDER) {
                        MenuSectionTitle("浏览方式")
                        // 层级（逐级点进） / 平铺（只列有歌的最后一级文件夹）
                        FolderBrowseMode.entries.forEach { mode ->
                            val chosen = mode == folderMode
                            DropdownMenuItem(
                                leadingIcon = { CheckOrSpace(chosen) },
                                text = {
                                    Text(
                                        mode.label,
                                        color = if (chosen) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurface,
                                    )
                                },
                                onClick = {
                                    folderModeOverride = mode.name
                                    // 切浏览方式 = 换了一套列表结构，当前目录路径不再成立
                                    folderPath = ""
                                    goTop()
                                    displayMenuExpanded = false
                                    scope.launch { vm.settings.setFolderBrowseMode(mode.name) }
                                },
                            )
                        }
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                        MenuSectionTitle("排序")
                    }
                    sortOptions.forEach { s ->
                        // 当前生效的排序在左侧打勾 + 主色文字，一眼能看出现在是按什么排的
                        val chosen = s == sortBy
                        DropdownMenuItem(
                            leadingIcon = { CheckOrSpace(chosen) },
                            text = {
                                Text(
                                    sortLabel(s),
                                    color = if (chosen) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface,
                                )
                            },
                            onClick = {
                                // 内存立刻生效（切视图/切排序首帧就对）+ 落盘（重启后恢复）
                                vm.setLibrarySort(view.key, s.name)
                                displayMenuExpanded = false
                                // 换排序后原滚动偏移指向的是另一条内容（index 不变、内容全变了），
                                // 停在原位等于「随机落点」，所以统一切换排序 = 回顶。
                                // 重复点当前项也给回顶（用户常用它当「回到顶部」）
                                goTop()
                            },
                        )
                    }
                }
            }
        }

        if (scanState is AppViewModel.ScanUiState.Scanning) {
            Box(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        }

        // 多选操作栏（与详情页共用 MultiSelectBar）：全选 · 取消 …… 已选 N 首 …… 加入歌单 · 编辑元数据
        if (selectionMode) {
            // 「当前视图下的歌曲」= 这个视图的播放队列：层级视图是当前目录（含子目录）递归展开，
            // 平铺视图的歌曲层是该文件夹，其余视图是整份（按当前排序）。全选就是全中这些。
            // 例外：平铺视图停在「文件夹列表」层时列表里没有歌，此时可视集合为空，不误选全库。
            val selectableUrls = remember(playQueue, view, folderMode, folderPath) {
                if (view == LibraryView.FOLDER && folderMode == FolderBrowseMode.FLAT && folderPath.isEmpty()) {
                    emptySet()
                } else {
                    playQueue.mapTo(LinkedHashSet()) { it.url }
                }
            }
            val allSelected = selectableUrls.isNotEmpty() && selected.containsAll(selectableUrls)
            MultiSelectBar(
                allSelected = allSelected,
                hasSelection = selected.isNotEmpty(),
                onToggleSelectAll = { selected = if (allSelected) emptySet() else selectableUrls },
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

        // 歌曲行（层级视图与分组视图共用）：点击播放 / 多选 / 竖三点信息
        @Composable fun SongRowItem(item: MediaItem) {
            SongRow(
                item = item,
                showCover = songListCover,
                selected = selected.contains(item.url),
                selectionMode = selectionMode,
                onClick = {
                    if (selectionMode) {
                        selected = if (selected.contains(item.url))
                            selected - item.url else selected + item.url
                    } else if (view == LibraryView.VIDEO) {
                        onVideoClick(item.url, item.title)
                    } else {
                        val idx = playQueue.indexOfFirst { it.url == item.url }
                        vm.player.setPlaylist(playQueue, idx)
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

        // 当前路径面包屑（层级/平铺共用）
        @Composable fun PathBreadcrumb(path: String) {
            Text(
                text = path,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }

        // 「返回上一层」行（层级/平铺共用）
        @Composable fun BackRow(label: String, onClick: () -> Unit) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onClick)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(8.dp))
                Text(label, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            }
        }

        // 文件夹行（层级/平铺共用）：封面缩略图或文件夹图标 + 名称 + 歌曲数 + 右箭头
        @Composable fun FolderRowItem(folder: FolderChild, onOpen: () -> Unit) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpen)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(6.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    if (folder.coverUrl.isNotBlank()) {
                        coil.compose.AsyncImage(
                            model = folder.coverUrl,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        Icon(
                            Icons.Filled.Folder,
                            contentDescription = null,
                            modifier = Modifier.size(28.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = folder.name,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "${folder.songCount} 首",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    Icons.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // 列表 + 右侧首字母索引
        Box(modifier = Modifier.fillMaxSize()) {
            // 冷启动数据到达但派生数据还在后台算：给一个轻量骨架过渡，而不是同步阻塞重算
            if (!derivedReady) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (view == LibraryView.ALBUM || view == LibraryView.ARTIST) {
                // 专辑 / 艺术家：卡片网格，点卡片进详情页
                // 卡片已随排序/分组一起在后台 computeDerived 里算好（主线程零重计算）
                val cards = derivedNow.cards
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 150.dp),
                    state = gridState,
                    // 右边留 16dp 给浮层快速滑块：卡片不铺到边缘，滑块不会盖住卡片点击区
                    modifier = Modifier.fillMaxSize().padding(start = 8.dp, end = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    gridItems(cards, key = { it.name }) { card ->
                        GroupCard(card = card, onClick = { onGroupClick(groupTypeOf(view)!!, card.name) })
                    }
                }
            } else {
            LazyColumn(
                state = listState,
                // 索引条改成浮在列表之上，列表不再为它让出 30dp 右边距 —— 歌名/歌手可以铺满整宽
                modifier = Modifier.fillMaxSize(),
            ) {
                if (view == LibraryView.FOLDER && folderMode == FolderBrowseMode.HIERARCHY) {
                    // —— 文件夹·层级视图：面包屑 + 返回上一级 + 子文件夹 + 直接歌曲 ——
                    item(key = "breadcrumb") {
                        PathBreadcrumb(if (folderPath.isEmpty()) "/" else folderPath)
                    }
                    if (folderPath.isNotEmpty()) {
                        item(key = "back") {
                            BackRow("返回上一级") { folderPath = folderPath.substringBeforeLast('/'); goTop() }
                        }
                    }
                    itemsIndexed(hierarchy.children, key = { _, child -> "dir:${child.path}" }) { _, child ->
                        FolderRowItem(child) { folderPath = child.path; goTop() }
                    }
                    itemsIndexed(hierarchy.direct, key = { _, item -> item.url }) { _, item ->
                        SongRowItem(item)
                    }
                } else if (view == LibraryView.FOLDER && folderMode == FolderBrowseMode.FLAT && folderPath.isEmpty()) {
                    // —— 文件夹·平铺视图（列表层）：只列出有歌曲的最后一级文件夹 ——
                    if (flatFolders.isEmpty()) {
                        item(key = "flat-empty") {
                            Text(
                                "没有找到含歌曲的文件夹",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                            )
                        }
                    }
                    itemsIndexed(flatFolders, key = { _, folder -> "flat:${folder.path}" }) { _, folder ->
                        FolderRowItem(folder) { folderPath = folder.path; goTop() }
                    }
                } else if (view == LibraryView.FOLDER && folderMode == FolderBrowseMode.FLAT) {
                    // —— 文件夹·平铺视图（歌曲层）：点进文件夹后显示该文件夹的歌曲 ——
                    item(key = "flat-breadcrumb") {
                        PathBreadcrumb(folderPath)
                    }
                    item(key = "flat-back") {
                        BackRow("返回文件夹列表") { folderPath = ""; goTop() }
                    }
                    itemsIndexed(flatSongs, key = { _, item -> item.url }) { _, item ->
                        SongRowItem(item)
                    }
                } else {
                grouped.forEach { group ->
                    val groupName = group.first
                    val groupSongs = group.second
                    val groupType = groupTypeOf(view)
                    // 平铺文件夹视图：标题显示最后一级文件夹名；与别处同名时标题下补完整路径
                    val isFolderGroup = groupType != null && view == LibraryView.FOLDER
                    val leafName = groupName.substringAfterLast('/').ifEmpty { "/" }
                    val title = if (isFolderGroup) leafName else groupName
                    // 「全部歌曲」是唯一只用于分节、没有下钻意义的单组标题：
                    // 做成普通 item —— 跟着列表滚走（原来 stickyHeader 会死死钉在顶上），
                    // 并且在标题后面带上总曲目数。
                    val isAllSongs = groupName == ALL_SONGS_GROUP
                    val headerContent: @Composable () -> Unit = {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = groupType != null) {
                                    if (groupType != null) {
                                        onGroupClick(groupType, if (isFolderGroup) leafName else groupName)
                                    }
                                }
                                .padding(horizontal = 16.dp, vertical = 6.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = if (groupType != null) "$title  ›" else title,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                if (isAllSongs) {
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = "${groupSongs.size} 首",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            if (isFolderGroup && leafName in duplicateFolderNames) {
                                Text(
                                    text = groupName,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                    if (isAllSongs) {
                        item(key = "group:$groupName") { headerContent() }
                    } else {
                        stickyHeader(key = "group:$groupName") { headerContent() }
                    }
                    itemsIndexed(groupSongs, key = { _, item -> item.url }) { _, item ->
                        SongRowItem(item)
                    }
                }
                }
            }
            }

            // 右侧浮层快速滑块：内容超出一屏才出现，按住往下拖可直落页尾
            if (view == LibraryView.ALBUM || view == LibraryView.ARTIST) {
                FastGridScrollBar(
                    state = gridState,
                    modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                )
            } else if (indexLetters.isEmpty()) {
                // 首字母索引条在位时这条不画：两条竖条并排会互相挤，功能也重叠
                //（索引条本身就是「直落任意字母组」的更快入口），留着反而让列表右侧挤成一团
                FastListScrollBar(
                    state = listState,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .fillMaxHeight(),
                )
            }

            // 首字母索引条（仅全部歌曲 + 标题排序时显示）：
            // 悬浮在列表右缘的字母列（无底板），不占列表宽度；按下即可定位、按住上下滑动连续跳转；
            // 手势全量消费，不会再穿透到下面的歌曲行造成误触播放。
            // 它出现时右侧快速滑块让位不画，两条竖条不会并排挤在一起。
            if (indexLetters.isNotEmpty()) {
                IndexBar(
                    letters = indexLetters,
                    onPickIndex = { idx ->
                        groupStartIndices.getOrNull(idx)?.let { target ->
                            scope.launch { listState.scrollToItem(target) }
                        }
                    },
                    onLetterChanged = { draggingLetter = it },
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        // 止于定位按钮上方：底部留 64dp（按钮 16dp 边距 + 40dp 高 + 8dp 间隙）；
                        // 顶部 28dp 让整条再往下一点，不跟列表首行齐平
                        .padding(top = 28.dp, bottom = 64.dp)
                        .padding(end = 2.dp)
                        .fillMaxHeight(),
                )
                draggingLetter?.let { letter ->
                    Box(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(84.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.92f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            letter,
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                }
            }

            // 定位到正在播放：列表超出一屏才出现，贴在右下角、底部播放栏上方一点。
            // 只在这几种「有歌曲行」的视图里给：专辑 / 艺术家是卡片网格，视频不走播放队列，
            // 平铺视图的文件夹层整页都是文件夹 —— 这些页面上它永远只能空点，不如不显示。
            val showLocate = view != LibraryView.ALBUM && view != LibraryView.ARTIST &&
                view != LibraryView.VIDEO &&
                !(view == LibraryView.FOLDER && folderMode == FolderBrowseMode.FLAT && folderPath.isEmpty())
            if (showLocate) {
                LocatePlayingButton(
                    listState = listState,
                    playingUrl = playingUrl,
                    indexOf = { indexOfSong(it) },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        // 索引条已改悬浮并抬到按钮上方，这里统一贴 16dp，不再需要给它让位
                        .padding(end = 16.dp, bottom = 16.dp),
                )
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
                    onGoto = { type, value ->
                        infoUrl = null
                        onGroupClick(type, value)
                    },
                )
            }
        }
    }

    // 加入歌单：列出所有歌单供选择，也可以就地新建一个
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

/** 专辑 / 艺术家卡片的数据 */
private data class GroupCardData(
    val name: String,
    val coverUrl: String,
    val subtitle: String,
)

@Composable
private fun GroupCard(card: GroupCardData, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.padding(6.dp)) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                if (card.coverUrl.isBlank()) {
                    Icon(
                        Icons.Filled.MusicNote,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(40.dp),
                    )
                } else {
                    coil.compose.AsyncImage(
                        model = card.coverUrl,
                        contentDescription = card.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                Text(
                    card.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    card.subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SongRow(
    item: MediaItem,
    showCover: Boolean,
    selected: Boolean,
    selectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onMoreClick: () -> Unit,
) {    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selectionMode) {
            Checkbox(checked = selected, onCheckedChange = null)
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
            Text(item.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
            Text(
                "${item.artist} · ${item.album}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        Text(
            formatDuration(item.durationMs),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!selectionMode) {
            IconButton(onClick = onMoreClick) {
                Icon(Icons.Filled.MoreVert, contentDescription = "更多信息", modifier = Modifier.size(20.dp))
            }
        }
    }
}


/** 「全部歌曲」分组的标题。它没有下钻意义，单独按普通 item 渲染（不吸顶）并带曲目数。 */
private const val ALL_SONGS_GROUP = "全部歌曲"

/** 哪些视图的分组标题可以下钻进详情页；ALL/VIDEO 的分组不可点 */
private fun groupTypeOf(view: LibraryView): String? = when (view) {
    LibraryView.FOLDER -> "folder"
    LibraryView.ALBUM -> "album"
    LibraryView.ARTIST -> "artist"
    else -> null
}

/** 各视图允许的排序项：专辑=标题/年份/歌曲数；艺术家=名称/歌曲数；其余视图全量（不含歌曲数） */
/** 各视图允许的排序项：文件夹=标题/歌曲数（子文件夹按歌数从多到少）；专辑=标题/年份/歌曲数；
 *  艺术家=名称/歌曲数；其余视图全量（不含歌曲数——那几类没有可数的分组） */
private fun sortOptionsFor(v: LibraryView): List<SortBy> = when (v) {
    LibraryView.FOLDER -> listOf(SortBy.TITLE, SortBy.COUNT)
    LibraryView.ALBUM -> listOf(SortBy.TITLE, SortBy.YEAR, SortBy.COUNT)
    LibraryView.ARTIST -> listOf(SortBy.TITLE, SortBy.COUNT)
    else -> SortBy.entries.filter { it != SortBy.COUNT }
}

/** 菜单里的小节标题（「浏览方式」/「排序」） */
@Composable
private fun MenuSectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp),
    )
}

/** 菜单项左侧的「已选」勾 / 等宽占位：未选中项的文字不会左右跳 */
@Composable
private fun CheckOrSpace(chosen: Boolean) {
    if (chosen) {
        Icon(
            Icons.Filled.Check,
            contentDescription = "已选择",
            tint = MaterialTheme.colorScheme.primary,
        )
    } else {
        Spacer(Modifier.size(24.dp))
    }
}

private fun sortLabel(s: SortBy) = when (s) {
    SortBy.TITLE -> "标题"
    SortBy.YEAR -> "年份"
    SortBy.ALBUM -> "专辑"
    SortBy.ARTIST -> "艺术家"
    SortBy.SIZE -> "文件大小"
    SortBy.DURATION -> "时长"
    SortBy.FOLDER -> "文件夹"
    SortBy.COUNT -> "歌曲数"
}

/**
 * 右侧首字母索引条 —— **悬浮**在列表之上，不再占列表宽度，也没有底板：只有字母本身。
 *
 * 老实现的每个字母只有 8px 高、10px 宽，点不准就会漏到下面的歌曲行触发播放 —— 这就是「容易误触」的原因。
 * 现在：
 *  - 只有 24dp 宽的字母列压在列表右缘，列表铺满整宽，歌名/歌手不再被挤窄换行；
 *  - 高度由调用方控制（止于右下角定位按钮上方），字母按等高槽位铺满；
 *  - 按下即定位，按住上下滑动连续跳转（不需要抬起手指）；
 *  - 手势在带内全量 consume，绝不穿透到下面的列表；
 *  - 当前字母回抛给外层显示居中大字气泡。
 *
 * 行尾「⋮」不会被吃掉：图标中心距右缘约 40dp，字母列只压住最外侧 24dp。
 */
@Composable
private fun IndexBar(
    letters: List<String>,
    onPickIndex: (Int) -> Unit,
    onLetterChanged: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var activeIndex by remember { mutableStateOf(-1) }

    Column(
        modifier = modifier
            .width(24.dp)
            // 触控区 = 整列（padding 放在 pointerInput 之后，按到上下端也算按到）
            .pointerInput(letters) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()

                    fun pick(y: Float) {
                        if (letters.isEmpty() || size.height <= 0) return
                        val idx = (y / size.height * letters.size).toInt()
                            .coerceIn(0, letters.lastIndex)
                        if (idx != activeIndex) {
                            activeIndex = idx
                            onLetterChanged(letters[idx])
                            onPickIndex(idx)
                        }
                    }

                    pick(down.position.y)
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) {
                            change.consume()
                            break
                        }
                        change.consume()
                        pick(change.position.y)
                    }
                    activeIndex = -1
                    onLetterChanged(null)
                }
            }
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        letters.forEachIndexed { idx, letter ->
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    letter,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                    color = if (idx == activeIndex) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 一次后台计算产出的全部派生数据（列表刷新时整体换引用，主线程不做重计算） */
private data class LibraryDerived(
    val sorted: List<MediaItem> = emptyList(),
    val dirPaths: Map<String, String> = emptyMap(),
    val hierarchy: HierarchyContent = HierarchyContent(emptyList(), emptyList(), emptyList()),
    val grouped: List<Pair<String, List<MediaItem>>> = emptyList(),
    val cards: List<GroupCardData> = emptyList(),
)

/**
 * 从原始列表算出排序 / 目录映射 / 层级内容 / 分组。
 * 纯函数、无状态，跑在 Dispatch.Default 上；逻辑与拆出来之前完全一致。
 */
private fun computeDerived(
    source: List<MediaItem>,
    sortBy: SortBy,
    view: LibraryView,
    folderPath: String,
): LibraryDerived {
    // 标题首字母 key 只算一次：原来用 sortedBy { alphaKeyOf(...).letter }，Kotlin 的
    // sortedBy 会为每次比较都调 selector（O(n log n) 次），而 alphaKeyOf 里有 dropWhile、
    // toString、normalizeWidth 一堆分配；紧接着 groupBy 又调一遍。现在先算好一份
    // Map<url, AlphaKey>，排序与分组共用，总共 O(n) 次。
    val titleKey: Map<String, AlphaIndex.AlphaKey> =
        if (sortBy == SortBy.TITLE || sortBy == SortBy.COUNT) {
            source.associate { it.url to AlphaIndex.alphaKeyOf(it.title) }
        } else {
            emptyMap()
        }

    val sorted = when (sortBy) {
        // 三级排序：首字母组 → 组内 rank（汉字在前英文在后）→ 标题字典序。
        // 原来只按 letter 排序，AlphaKey.rank 算了却从没用上，注释里承诺的
        // 「同组内汉字在前英文在后」从未生效。
        SortBy.TITLE -> source.sortedWith { a, b ->
            compareValuesBy(
                a, b,
                { titleKey[it.url]?.letter ?: "#" },
                { titleKey[it.url]?.rank ?: 1 },
                { it.title.lowercase() },
            )
        }
        SortBy.YEAR -> source.sortedByDescending { it.year }
        SortBy.ALBUM -> source.sortedBy { it.album }
        SortBy.ARTIST -> source.sortedBy { it.artist }
        SortBy.SIZE -> source.sortedByDescending { it.sizeBytes }
        SortBy.DURATION -> source.sortedByDescending { it.durationMs }
        SortBy.FOLDER -> source.sortedBy { it.folder }
        // 歌曲数排序只作用于「分组」（专辑/艺术家卡片、文件夹），歌曲列表本身按标题排，
        // 否则组内歌曲会退化成数据库原始顺序
        SortBy.COUNT -> source.sortedWith { a, b ->
            compareValuesBy(
                a, b,
                { titleKey[it.url]?.letter ?: "#" },
                { titleKey[it.url]?.rank ?: 1 },
                { it.title.lowercase() },
            )
        }
    }

    // url → 解码后的真实目录路径。与 SongInfoSheet 的「网盘路径」同源（LibraryScanner.pathKeyOf，
    // 已做 URL 解码并剥离 /dav、/p 前缀），保证层级视图的文件夹名与网盘真实目录一致
    val dirPaths: Map<String, String> = source.associate { item ->
        val key = runCatching { LibraryScanner.pathKeyOf(item.url) }.getOrNull()
        val dir = (key ?: item.url.substringBefore('?')).trimEnd('/').substringBeforeLast('/')
        item.url to dir.ifEmpty { "/" }
    }

    // 层级视图内容：当前目录的直接子文件夹（递归歌曲数 + 代表封面）与直接歌曲
    val prefix = folderPath.ifEmpty { "/" }
    val childPrefix = if (prefix == "/") "/" else "$prefix/"
    val direct = sorted.filter { dirPaths[it.url] == prefix }
    val rec = LinkedHashMap<String, MutableList<MediaItem>>()
    for (item in source) {
        val dir = dirPaths[item.url] ?: continue
        if (dir != prefix && dir.startsWith(childPrefix)) {
            rec.getOrPut(dir.removePrefix(childPrefix).substringBefore('/')) { mutableListOf() }.add(item)
        }
    }
    val children = rec.map { (name, items) ->
        FolderChild(
            name = name,
            path = childPrefix + name,
            songCount = items.size,
            coverUrl = items.firstNotNullOfOrNull { it.coverUrl.takeIf(String::isNotBlank) } ?: "",
        )
    }.let { list ->
        // 文件夹视图支持「标题 / 歌曲数」两种排序：按歌曲数时子文件夹从多到少，
        // 同数按名称，避免每次刷新顺序抖动
        if (sortBy == SortBy.COUNT) list.sortedWith(
            compareByDescending<FolderChild> { it.songCount }.thenBy { it.name.lowercase() },
        ) else list.sortedBy { it.name.lowercase() }
    }
    // 队列构建：原来是 children × N（每个子文件夹都对整个 sorted 过滤一遍）。
    // 改成一次遍历：先把每首歌归到「它在当前目录下的直接子文件夹」，再按 children 顺序拼接。
    val directSet = direct.toHashSet()
    val byChild: Map<String, MutableList<MediaItem>> = linkedMapOf<String, MutableList<MediaItem>>().also { map ->
        for (item in sorted) {
            if (item in directSet) continue
            val d = dirPaths[item.url] ?: continue
            if (d.startsWith(childPrefix)) {
                val name = d.removePrefix(childPrefix).substringBefore('/')
                map.getOrPut(name) { mutableListOf() }.add(item)
            }
        }
    }
    val queue = buildList {
        for (child in children) {
            addAll(byChild[child.name].orEmpty())
        }
        addAll(direct)
    }
    val hierarchy = HierarchyContent(children, direct, queue)

    // 分组（四视图）
    val grouped: List<Pair<String, List<MediaItem>>> = when (view) {
        // 平铺：按完整目录路径分组（同名文件夹不会被合并），组内为该文件夹的歌曲
        LibraryView.FOLDER -> {
            val groups = sorted.groupBy { dirPaths[it.url] ?: it.folder }.toList()
            if (sortBy == SortBy.FOLDER) groups.sortedBy { it.first } else groups
        }
        LibraryView.ALL -> if (sortBy == SortBy.TITLE)
            sorted.groupBy { titleKey[it.url]?.letter ?: "#" }.toList()
        else listOf(ALL_SONGS_GROUP to sorted)
        LibraryView.ALBUM -> sorted.groupBy { it.album.ifEmpty { "未知专辑" } }.toList()
        LibraryView.ARTIST -> sorted.groupBy { it.artist.ifEmpty { "未知艺术家" } }.toList()
        LibraryView.VIDEO -> listOf("全部视频" to sorted)
    }

    // 专辑 / 艺术家卡片网格（排序：专辑=标题/年份/歌曲数；艺术家=名称/歌曲数）。
    // 之前在组合期间 remember 重算，补全期间与上面的派生数据一起拖慢主线程；现在并入后台计算
    val cards: List<GroupCardData> = when (view) {
        LibraryView.ALBUM, LibraryView.ARTIST -> {
            val pairs = grouped.map { it.first to it.second }
            val sortedPairs = when {
                sortBy == SortBy.COUNT -> pairs.sortedByDescending { it.second.size }
                view == LibraryView.ALBUM && sortBy == SortBy.YEAR ->
                    pairs.sortedByDescending { (_, songs) -> songs.maxOf { it.year } }
                else -> pairs.sortedBy { it.first.lowercase() }
            }
            sortedPairs.map { (name, songs) ->
                GroupCardData(
                    name = name,
                    coverUrl = songs.firstNotNullOfOrNull { it.coverUrl.takeIf(String::isNotBlank) } ?: "",
                    subtitle = buildString {
                        append(songs.size)
                        append(" 首")
                        if (view == LibraryView.ARTIST) {
                            val albums = songs.map { it.album.ifEmpty { "未知专辑" } }.distinct().size
                            append(" · ")
                            append(albums)
                            append(" 张专辑")
                        } else {
                            val year = songs.maxOf { it.year }
                            if (year > 0) append(" · ").append(year)
                        }
                    },
                )
            }
        }
        else -> emptyList()
    }

    return LibraryDerived(sorted, dirPaths, hierarchy, grouped, cards)
}
