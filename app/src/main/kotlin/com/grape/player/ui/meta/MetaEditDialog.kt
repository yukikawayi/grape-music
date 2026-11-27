package com.grape.player.ui.meta

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.grape.player.data.LyricEntry
import com.grape.player.data.MediaItem
import com.grape.player.network.LibraryScanner
import com.grape.player.network.MetadataScraper
import com.grape.player.ui.AppViewModel
import com.grape.player.util.LyricSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 元数据编辑面板 —— 对应 Web 版 EditMetaModal，入口三处（歌曲行、歌单页、播放器）。
 * 单曲模式：原信息只读、表单可编辑，在线综合搜索一键套用，还可单独换封面 / 歌词。
 * 批量模式：勾选要改的字段，只覆盖勾选且有值的字段。
 */
@Composable
fun MetaEditDialog(vm: AppViewModel) {
    val state by vm.metaEdit.collectAsState()
    if (state == AppViewModel.MetaEditState.Closed) return

    Dialog(
        onDismissRequest = { vm.closeMetaEdit() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            when (state) {
                is AppViewModel.MetaEditState.Single -> {
                    SingleMetaEditor(vm, (state as AppViewModel.MetaEditState.Single).url)
                }
                is AppViewModel.MetaEditState.Batch -> {
                    BatchMetaEditor(vm, (state as AppViewModel.MetaEditState.Batch).urls)
                }
                else -> {}
            }
        }
    }
}

private data class FormFields(
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val year: String = "",
    val track: String = "",
    val disc: String = "",
    val coverUrl: String = "",
    val lyric: String = "",
    val translation: String = "",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SingleMetaEditor(vm: AppViewModel, url: String) {
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var original by remember { mutableStateOf(FormFields()) }
    var form by remember { mutableStateOf(FormFields()) }
    var offsetMs by remember { mutableStateOf(0L) }
    // 库里这行歌词的原貌（含出处 / 写入时间）：用来判断「这次保存到底换没换词」，
    // 也用来在歌词框下面显示「来源：网易云音乐 · 11-26 15:20」
    var originalLyric by remember { mutableStateOf<LyricEntry?>(null) }
    // 这次保存的歌词出处：从候选页签/套用结果带进来时非空；null = 没换词，沿用原出处
    var pickedLyricSource by remember { mutableStateOf<String?>(null) }
    // 正在编辑的媒体项（内嵌元数据速览面板要读 Embedded_ 快照与网盘路径，FormFields 里没有）
    var sourceItem by remember { mutableStateOf<MediaItem?>(null) }
    // 顶栏「向下尖括号」：展开 / 收起内嵌元数据速览
    var showEmbedded by remember { mutableStateOf(false) }

    // 在线综合搜索（歌名 / 艺术家 / 专辑任意组合）
    var qTitle by remember { mutableStateOf("") }
    var qArtist by remember { mutableStateOf("") }
    var qAlbum by remember { mutableStateOf("") }
    // 分源搜索：源名 → 候选列表；点搜索时并行查询所有启用的源，选项卡切换显示
    var resultsBySource by remember { mutableStateOf<Map<String, List<MetadataScraper.SearchResult>>>(emptyMap()) }
    var selectedSource by remember { mutableStateOf<String?>(null) }
    var searching by remember { mutableStateOf(false) }

    // 右上角设置：手动搜索启用哪些源（独立于后台补全的 enabledProviders）
    var showSourceSettings by remember { mutableStateOf(false) }
    val manualProvidersRaw by vm.settings.manualSearchProviders.collectAsState(initial = "qq,kugou,netease")
    // 存储顺序固定 qq,kugou,netease；过滤出启用的源（保持固定优先级顺序）
    val manualProviders = remember(manualProvidersRaw) {
        val enabled = manualProvidersRaw.split(',').map { it.trim() }.toSet()
        listOf("qq", "kugou", "netease").filter { it in enabled }
    }
    // 源 key → 选项卡短名（固定顺序 QQ → 酷狗 → 网易云）
    val sourceTabs = listOf("qq" to "Q音", "kugou" to "酷狗", "netease" to "网易云")

    // 单独搜封面 / 歌词的子弹窗状态
    var showCoverPicker by remember { mutableStateOf(false) }
    var showLyricPicker by remember { mutableStateOf(false) }
    var coverResults by remember { mutableStateOf<List<MetadataScraper.SearchResult>>(emptyList()) }
    // 单独搜歌词按源分组（key 与 sourceTabs 对齐），子弹窗里一个源一个页签
    var lyricResultsBySource by remember {
        mutableStateOf<Map<String, List<MetadataScraper.LyricResult>>>(emptyMap())
    }
    var subLoading by remember { mutableStateOf(false) }

    // 载入原信息（歌曲元数据 + 歌词缓存）
    LaunchedEffect(url) {
        loading = true
        val item: MediaItem? = vm.itemForEdit(url)
        val lyricEntry: LyricEntry? = withContext(Dispatchers.IO) { vm.repo.getLyric(url) }
        if (item != null) {
            sourceItem = item
            val fields = FormFields(
                title = item.title,
                artist = item.artist,
                album = item.album,
                year = if (item.year > 0) item.year.toString() else "",
                track = if (item.trackNumber > 0) item.trackNumber.toString() else "",
                disc = if (item.discNumber > 0) item.discNumber.toString() else "",
                coverUrl = item.coverUrl,
                lyric = item.lyric.ifEmpty { lyricEntry?.lrc ?: "" },
                translation = lyricEntry?.translatedLrc ?: "",
            )
            original = fields
            form = fields
            originalLyric = lyricEntry
            pickedLyricSource = null
            qTitle = fields.title
            qArtist = fields.artist
            qAlbum = fields.album
            offsetMs = lyricEntry?.offsetMs ?: 0L
        }
        loading = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("更改元数据") },
                navigationIcon = {
                    IconButton(onClick = { vm.closeMetaEdit() }) {
                        Icon(Icons.Filled.Close, contentDescription = "关闭")
                    }
                },
                // 右上角设置：手动搜索启用哪些源
                actions = {
                    // 内嵌元数据速览：向下尖括号展开，展开后变向上尖括号，再点收起
                    IconButton(onClick = { showEmbedded = !showEmbedded }) {
                        Icon(
                            imageVector = if (showEmbedded) Icons.Filled.KeyboardArrowUp
                            else Icons.Filled.KeyboardArrowDown,
                            contentDescription = if (showEmbedded) "收起内嵌元数据" else "展开内嵌元数据",
                        )
                    }
                    IconButton(onClick = { showSourceSettings = true }) {
                        Icon(Icons.Filled.Settings, contentDescription = "搜索源设置")
                    }
                },
            )
        },
        bottomBar = {
            Surface(tonalElevation = 2.dp) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        status,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                        maxLines = 2,
                    )
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = { vm.closeMetaEdit() }, enabled = !saving) {
                        Text("取消")
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            if (form.title.isBlank() || form.artist.isBlank()) {
                                status = "歌名和艺术家不能为空"
                                return@Button
                            }
                            saving = true
                            scope.launch {
                                vm.applyMetaEdit(
                                    url = url,
                                    title = form.title,
                                    artist = form.artist,
                                    album = form.album,
                                    year = form.year.toIntOrNull() ?: 0,
                                    coverUrl = form.coverUrl.ifEmpty { original.coverUrl },
                                    lyric = form.lyric,
                                    translation = form.translation,
                                    offsetMs = offsetMs,
                                    trackNumber = form.track.toIntOrNull() ?: 0,
                                    discNumber = form.disc.toIntOrNull() ?: 0,
                                    lyricSource = pickedLyricSource,
                                )
                                saving = false
                                status = "已保存"
                                vm.closeMetaEdit()
                            }
                        },
                        enabled = !saving && !loading,
                    ) { Text(if (saving) "保存中…" else "保存") }
                }
            }
        },
    ) { padding ->
        if (loading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                // 内嵌元数据速览已改成独立弹窗（见下方 EmbeddedMetaDialog），
                // 不再占位——展开它不会把「在线综合搜索」及后面的表单往下挤。
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    // ===== 在线综合搜索 =====
                    SectionTitle("在线综合搜索（歌名 / 艺术家 / 专辑任意组合，套用后替换下方信息）")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = qTitle,
                            onValueChange = { qTitle = it },
                            label = { Text("歌名") },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                        )
                        Spacer(Modifier.width(8.dp))
                        OutlinedTextField(
                            value = qArtist,
                            onValueChange = { qArtist = it },
                            label = { Text("艺术家") },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                        )
                        Spacer(Modifier.width(8.dp))
                        OutlinedTextField(
                            value = qAlbum,
                            onValueChange = { qAlbum = it },
                            label = { Text("专辑") },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = {
                            if (qTitle.isBlank() && qArtist.isBlank() && qAlbum.isBlank()) {
                                status = "请至少填写歌名、艺术家或专辑之一"
                                return@Button
                            }
                            if (manualProviders.isEmpty()) {
                                status = "未启用任何搜索源，请点右上角设置开启"
                                return@Button
                            }
                            searching = true
                            scope.launch {
                                // 并行查询所有启用的源（单源失败不影响其他源）；
                                // 返回 Map 的 key 是源 key（qq/kugou/netease），与 sourceTabs 对齐
                                resultsBySource = vm.searchMetaBySource(qTitle, qArtist, qAlbum, manualProviders)
                                // 默认切到第一个有结果的源（QQ → 酷狗 → 网易云）
                                selectedSource = sourceTabs.firstNotNullOfOrNull { (key, _) ->
                                    key.takeIf { resultsBySource[it]?.isNotEmpty() == true }
                                }
                                searching = false
                                if (resultsBySource.isEmpty()) status = "未找到结果"
                            }
                        },
                        enabled = !searching,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Filled.Search, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (searching) "搜索中…" else "搜索")
                    }

                    if (searching) {
                        Spacer(Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text("正在查询 " + manualProviders.map { key -> sourceTabs.first { it.first == key }.second }
                                .joinToString("、"), style = MaterialTheme.typography.bodySmall)
                        }
                    }

                    // 分源选项卡：点哪个源就看哪个源的结果
                    if (!searching && resultsBySource.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            sourceTabs.filter { (key, _) -> resultsBySource.containsKey(key) }
                                .forEach { (key, label) ->
                                    FilterChip(
                                        selected = selectedSource == key,
                                        onClick = { selectedSource = key },
                                        label = { Text("$label ${resultsBySource[key]?.size ?: 0}") },
                                    )
                                }
                        }
                        Spacer(Modifier.height(6.dp))
                        val activeResults = resultsBySource[selectedSource].orEmpty()
                        if (activeResults.isEmpty()) {
                            Text(
                                "该源没有找到结果",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            activeResults.forEach { r ->
                                SearchResultCard(result = r, onApply = {
                                    form = FormFields(
                                        title = r.title,
                                        artist = r.artist,
                                        album = r.album,
                                        year = r.year.takeIf { it != "0" }.orEmpty(),
                                        track = r.trackNumber.takeIf { it != "0" }.orEmpty(),
                                        disc = r.discNumber.takeIf { it != "0" }.orEmpty(),
                                        coverUrl = r.coverUrl,
                                        lyric = form.lyric,
                                        translation = form.translation,
                                    )
                                    status = "已套用「${r.source}」结果，正在获取对应歌词…"
                                    // 套用 = 换了一首歌的信息，歌词必须跟着换：
                                    // 按候选精确取词（QQ 按 songmid / 酷狗按 FileHash），
                                    // 不能用 fetchLyricIfMissing——它对同一 url 只填空，
                                    // 会把数据库里的旧歌词原样返回；也不按歌名模糊搜，避免拿到别的版本。
                                    scope.launch {
                                        val found = runCatching {
                                            vm.searchLyricForApplied(r, manualProviders)
                                        }.getOrNull()
                                        if (found != null) {
                                            form = form.copy(lyric = found.lyric, translation = found.translation)
                                            // 套用结果时歌词是按这条候选精确取回来的，出处跟着它走
                                            pickedLyricSource = LyricSource.normalize(found.source)
                                            status = "已套用「${r.source}」结果并带入歌词"
                                        } else {
                                            status = "已套用「${r.source}」结果（未找到在线歌词，保留原歌词）"
                                        }
                                    }
                                })
                                Spacer(Modifier.height(6.dp))
                            }
                        }
                    }

                    Spacer(Modifier.height(16.dp))
                    Divider()
                    Spacer(Modifier.height(16.dp))

                    // ===== 字段编辑 =====
                    SectionTitle("基本信息（原：${original.title} · ${original.artist}）")
                    EditableField("歌名", form.title, original.title) { form = form.copy(title = it) }
                    EditableField("艺术家", form.artist, original.artist) { form = form.copy(artist = it) }
                    EditableField("专辑", form.album, original.album) { form = form.copy(album = it) }
                    EditableField("年份", form.year, original.year) { form = form.copy(year = it) }
                    Row {
                        OutlinedTextField(
                            value = form.track,
                            onValueChange = { form = form.copy(track = it.filter { c -> c.isDigit() }.take(4)) },
                            label = { Text("音轨号") },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            supportingText = if (original.track.isNotBlank() && original.track != form.track) {
                                { Text("原：${original.track}", style = MaterialTheme.typography.labelSmall) }
                            } else null,
                        )
                        Spacer(Modifier.width(8.dp))
                        OutlinedTextField(
                            value = form.disc,
                            onValueChange = { form = form.copy(disc = it.filter { c -> c.isDigit() }.take(3)) },
                            label = { Text("碟号") },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            supportingText = if (original.disc.isNotBlank() && original.disc != form.disc) {
                                { Text("原：${original.disc}", style = MaterialTheme.typography.labelSmall) }
                            } else null,
                        )
                    }
                    Spacer(Modifier.height(8.dp))

                    Spacer(Modifier.height(8.dp))
                    Divider()
                    Spacer(Modifier.height(16.dp))

                    // ===== 封面（可单独搜索） =====
                    SectionTitle("封面")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val cover = form.coverUrl.ifEmpty { original.coverUrl }
                        if (cover.isNotBlank()) {
                            AsyncImage(
                                model = cover,
                                contentDescription = "封面",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.size(88.dp),
                            )
                        } else {
                            Box(
                                Modifier.size(88.dp),
                                contentAlignment = Alignment.Center,
                            ) { Text("无封面", style = MaterialTheme.typography.labelSmall) }
                        }
                        Spacer(Modifier.width(12.dp))
                        Column {
                            TextButton(onClick = {
                                // 与右上角的「手动搜索源」设置对齐：只查已启用的源，
                                // 不再无条件把三家都问一遍（否则设置里只勾 QQ，封面却冒出酷狗的结果）
                                if (manualProviders.isEmpty()) {
                                    status = "未启用任何搜索源，请点右上角设置开启"
                                } else {
                                    showCoverPicker = true
                                    subLoading = true
                                    coverResults = emptyList()
                                    scope.launch {
                                        coverResults = vm.searchCovers(
                                            form.title, form.artist, form.album, manualProviders,
                                        )
                                        subLoading = false
                                    }
                                }
                            }) { Text("单独搜索封面") }
                            if (form.coverUrl.isNotBlank() && form.coverUrl != original.coverUrl) {
                                TextButton(onClick = { form = form.copy(coverUrl = "") }) { Text("清除新封面") }
                            }
                        }
                    }

                    Spacer(Modifier.height(16.dp))
                    Divider()
                    Spacer(Modifier.height(16.dp))

                    // ===== 歌词（可单独搜索 / 直接编辑） =====
                    SectionTitle("歌词（时间偏移 ${offsetMs}ms 会保留）")
                    Row {
                        TextButton(onClick = {
                            // 同上：歌词也按已启用的源查
                            if (manualProviders.isEmpty()) {
                                status = "未启用任何搜索源，请点右上角设置开启"
                            } else {
                                showLyricPicker = true
                                subLoading = true
                                lyricResultsBySource = emptyMap()
                                scope.launch {
                                    lyricResultsBySource =
                                        vm.searchLyricsBySource(form.title, form.artist, manualProviders)
                                    subLoading = false
                                }
                            }
                        }) { Text("单独搜索歌词") }
                        if (form.lyric.isNotBlank() || form.translation.isNotBlank()) {
                            TextButton(onClick = {
                                form = form.copy(lyric = original.lyric, translation = original.translation)
                                // 还原回库里的原词 = 没有换词，出处交回给原行（不要洗成「手动编辑」）
                                pickedLyricSource = null
                            }) { Text("还原原歌词") }
                        }
                    }
                    OutlinedTextField(
                        value = form.lyric,
                        onValueChange = {
                            form = form.copy(lyric = it)
                            // 用户自己动过正文：出处记「手动编辑」，除非接下来又选了某个源
                            pickedLyricSource = LyricSource.MANUAL
                        },
                        label = { Text("歌词（LRC）") },
                        modifier = Modifier.fillMaxWidth().height(160.dp),
                        maxLines = 12,
                    )
                    // 出处一行：看得见「这份词谁给的、什么时候写的」，也方便用户报告「我的词怎么变了」
                    val lyricOrigin = pickedLyricSource?.let { "${LyricSource.label(it)}（保存后生效）" }
                        ?: LyricSource.describe(
                            key = originalLyric?.source.orEmpty(),
                            updatedAt = originalLyric?.updatedAt ?: 0L,
                        )
                    Text(
                        text = "来源：$lyricOrigin",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = form.translation,
                        onValueChange = { form = form.copy(translation = it) },
                        label = { Text("翻译歌词（可选）") },
                        modifier = Modifier.fillMaxWidth().height(80.dp),
                        maxLines = 6,
                    )

                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }

    // 每次打开窗口都重新取一次库里的值：后台补全可能刚好在这期间把这份快照读进来，
    // 不刷新的话窗口会一直显示打开编辑页那一刻的旧值
    LaunchedEffect(showEmbedded) {
        if (showEmbedded) vm.itemForEdit(url)?.let { sourceItem = it }
    }

    // 内嵌元数据速览：独立窗口浮在页面上（顶栏向下尖括号打开，向上尖括号或点遮罩关闭）
    if (showEmbedded) {
        EmbeddedMetaDialog(item = sourceItem, onDismiss = { showEmbedded = false })
    }

    // ===== 子弹窗：选封面 / 选歌词 =====
    if (showCoverPicker) {
        CoverPicker(
            results = coverResults,
            loading = subLoading,
            onPick = { url ->
                form = form.copy(coverUrl = url)
                showCoverPicker = false
                status = "已选择新封面，保存后生效"
            },
            onDismiss = { showCoverPicker = false },
        )
    }
    if (showLyricPicker) {
        LyricPicker(
            resultsBySource = lyricResultsBySource,
            sourceLabels = sourceTabs,
            loading = subLoading,
            onPick = { r ->
                form = form.copy(lyric = r.lyric, translation = r.translation)
                pickedLyricSource = LyricSource.normalize(r.source)
                showLyricPicker = false
                status = "已使用「${r.source}」的歌词"
            },
            onDismiss = { showLyricPicker = false },        )
    }

    // 手动搜索源设置（右上角齿轮）：勾选手动搜索时并行查询哪些源
    if (showSourceSettings) {
        // 打开弹窗时以当前设置初始化（remember 以 showSourceSettings 为 key，关闭再开即重置）
        var tmpSelected by remember(showSourceSettings) { mutableStateOf(manualProviders.toSet()) }
        AlertDialog(
            onDismissRequest = { showSourceSettings = false },
            title = { Text("手动搜索源设置") },
            text = {
                Column {
                    Text(
                        "点「搜索」时将同时查询勾选的源；后台自动补全的源在 设置 → 元数据源设置 里配置，互不影响。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    listOf(
                        "qq" to "QQ音乐",
                        "kugou" to "酷狗音乐",
                        "netease" to "网易云音乐",
                    ).forEach { (key, label) ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = key in tmpSelected,
                                onCheckedChange = { on ->
                                    tmpSelected = if (on) tmpSelected + key else tmpSelected - key
                                },
                            )
                            Text(label)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    // 存储保持固定顺序 qq,kugou,netease
                    val value = listOf("qq", "kugou", "netease").filter { it in tmpSelected }.joinToString(",")
                    scope.launch { vm.settings.setManualSearchProviders(value) }
                    showSourceSettings = false
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { showSourceSettings = false }) { Text("取消") }
            },
        )
    }
}

/** 顶部小标题 */
@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
    )
    Spacer(Modifier.height(8.dp))
}

/** 一行编辑框，原值不同时以 supportingText 提示 */
@Composable
private fun EditableField(label: String, value: String, original: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        supportingText = if (original.isNotBlank() && original != value) {
            { Text("原：$original", style = MaterialTheme.typography.labelSmall) }
        } else null,
    )
    Spacer(Modifier.height(8.dp))
}

/**
 * 内嵌元数据速览窗口 —— 顶栏「向下尖括号」打开，窗口右上角「向上尖括号」或点外部关闭。
 *
 * 做成独立 Dialog 而不是页面内的一块区域：页面里已经有「在线综合搜索 + 各字段表单」，
 * 内嵌面板插在中间会把下面的内容整体往下挤，用户还得先关掉它才能看搜索结果。
 * 浮窗形态下它只是覆盖在上面，关掉就回到原位，滚动位置也不受影响。
 *
 * 数据全部来自 Embedded_ 快照：扫描时从文件内嵌标签读出，之后的编辑、在线补全都不会覆盖它，
 * 所以这里永远显示「文件里原本写的」那套值，可以拿它和页面表单（Using_ 显示值）对照。
 * 每行右侧的复制按钮把完整值写进剪贴板——歌词复制整段原文（不受预览行数截断影响），
 * 封面复制落盘的真实文件路径（file:// 解码后），「文件名」是网盘路径的最后一级（已 URL 解码）。
 */
@Composable
private fun EmbeddedMetaDialog(item: MediaItem?, onDismiss: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    fun copy(label: String, value: String) {
        if (value.isBlank()) return
        clipboard.setText(AnnotatedString(value))
        android.widget.Toast.makeText(context, "已复制$label", android.widget.Toast.LENGTH_SHORT).show()
    }

    // 网盘路径 → 最后一级文件名（pathKeyOf 内部已 URL 解码并剥掉 /dav、/p 前缀）
    val fileName = remember(item?.url) {
        val u = item?.url ?: return@remember ""
        val path = LibraryScanner.pathKeyOf(u) ?: u.substringBefore('?').substringBefore('#')
        path.trimEnd('/').substringAfterLast('/')
    }
    // 内嵌封面落盘为 file:// 本地路径，复制给用户时还原成真实文件路径
    val coverPath = remember(item?.embCoverUrl) {
        val c = item?.embCoverUrl.orEmpty()
        if (c.startsWith("file:")) {
            runCatching { java.net.URLDecoder.decode(c.removePrefix("file://"), "UTF-8") }.getOrDefault(c)
        } else c
    }

    // 整份快照都是空的时候给一句解释，否则用户只会看到一列「（无）」，不知道是没读过还是文件真没标签
    val allEmpty = item == null || (
        item.embTitle.isBlank() && item.embArtist.isBlank() && item.embAlbum.isBlank() &&
            item.embCoverUrl.isBlank() && item.embLyric.isBlank() &&
            item.embYear <= 0 && item.embTrackNumber <= 0 && item.embDiscNumber <= 0
        )

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        // 「点外部关闭」得自己做：这个窗口铺满全屏（usePlatformDefaultWidth = false，
        // 里面的 Box 是 fillMaxSize），点视觉上的"外部"其实仍然落在窗口内部，
        // 系统那套 dismissOnClickOutside 永远不会触发。
        // 所以让遮罩层自己接收点击 → 关闭；弹窗本体再消费一次点击，别让它冒到遮罩上。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null, // 遮罩是无色透明的，弹涟漪反而突兀
                    onClick = onDismiss,
                )
                .padding(horizontal = 20.dp, vertical = 32.dp),
            contentAlignment = Alignment.Center,
        ) {
            // 窗口本体：与页面同一套主题色（surface + tonalElevation），圆角与 Material3 弹窗一致
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 560.dp)
                    // 点弹窗内部（含空白处）不该关闭：消费掉这次点击
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    ),
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp,
                shadowElevation = 8.dp,
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    // 标题栏：左边标题 + 来源说明，右边收到顶栏的「向上尖括号」位置（同一个收起动作）
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "内嵌元数据",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                "Embedded_ 快照（只读，来自文件标签）",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "收起内嵌元数据")
                        }
                    }
                    Divider(color = MaterialTheme.colorScheme.outlineVariant)

                    Column(
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 20.dp, vertical = 8.dp),
                    ) {
                        // 封面：缩略图 + 复制落盘路径
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "封面",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(width = 64.dp, height = 20.dp),
                            )
                            if (item?.embCoverUrl.isNullOrBlank()) {
                                Text(
                                    "（无）",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f),
                                )
                            } else {
                                AsyncImage(
                                    model = item?.embCoverUrl,
                                    contentDescription = "内嵌封面",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .size(48.dp)
                                        .clip(RoundedCornerShape(6.dp)),
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    coverPath.trimEnd('/').substringAfterLast('/'),
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            CopyIconButton(enabled = coverPath.isNotBlank(), label = "封面路径") {
                                copy("封面路径", coverPath)
                            }
                        }

                        EmbeddedInfoRow("歌名", item?.embTitle.orEmpty()) { copy("内嵌歌名", it) }
                        EmbeddedInfoRow("艺术家", item?.embArtist.orEmpty()) { copy("内嵌艺术家", it) }
                        EmbeddedInfoRow("专辑", item?.embAlbum.orEmpty()) { copy("内嵌专辑", it) }
                        EmbeddedInfoRow("年份", (item?.embYear ?: 0).takeIf { it > 0 }?.toString().orEmpty()) {
                            copy("内嵌年份", it)
                        }
                        EmbeddedInfoRow("音轨号", (item?.embTrackNumber ?: 0).takeIf { it > 0 }?.toString().orEmpty()) {
                            copy("内嵌音轨号", it)
                        }
                        EmbeddedInfoRow("碟号", (item?.embDiscNumber ?: 0).takeIf { it > 0 }?.toString().orEmpty()) {
                            copy("内嵌碟号", it)
                        }
                        // 歌词只预览前 4 行，复制的是完整原文
                        EmbeddedInfoRow("歌词", item?.embLyric.orEmpty(), maxLines = 4) { copy("内嵌歌词", it) }
                        EmbeddedInfoRow("文件名", fileName) { copy("文件名", it) }

                        if (allEmpty) {
                            Spacer(Modifier.height(4.dp))
                            // 注意：花括号块里换行再写「+」会被当成新语句（报 unaryPlus），
                            // 所以拼接符必须留在行尾
                            Text(
                                if ((item?.embReadAt ?: 0L) <= 0L) {
                                    "这首歌的内嵌标签还没读取过。开启设置里的「扫描时补全元数据」后，" +
                                        "后台补全会逐个文件读一次标签（视频除外），读完这里就会有值。"
                                } else {
                                    "已经读过这个文件，但里面没有可用的内嵌标签（标题/艺人/专辑/封面/歌词都为空），" +
                                        "这类歌只能靠在线补全或手动填写。"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    Divider(color = MaterialTheme.colorScheme.outlineVariant)
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(onClick = onDismiss) { Text("关闭") }
                    }
                }
            }
        }
    }
}

/** 内嵌元数据面板的一行：标签 + 值（超长省略）+ 复制按钮 */
@Composable
private fun EmbeddedInfoRow(
    label: String,
    value: String,
    maxLines: Int = 1,
    onCopy: (String) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(width = 64.dp, height = 20.dp),
        )
        Text(
            value.ifBlank { "（无）" },
            style = MaterialTheme.typography.bodySmall,
            color = if (value.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant
            else MaterialTheme.colorScheme.onSurface,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        CopyIconButton(enabled = value.isNotBlank(), label = label) { onCopy(value) }
    }
}

/** 复制图标按钮：无值时置灰不可点，避免把空串写进剪贴板 */
@Composable
private fun CopyIconButton(enabled: Boolean, label: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(32.dp)) {
        Icon(
            Icons.Filled.ContentCopy,
            contentDescription = "复制$label",
            modifier = Modifier.size(15.dp),
            tint = if (enabled) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 综合搜索结果卡片 */
@Composable
private fun SearchResultCard(result: MetadataScraper.SearchResult, onApply: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 1.dp,
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    result.source,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onApply) { Text("应用并修改") }
            }
            Text(result.title.ifEmpty { "—" }, style = MaterialTheme.typography.bodyLarge)
            Text(
                "艺术家：${result.artist.ifEmpty { "—" }}　专辑：${result.album.ifEmpty { "—" }}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "年份：${result.year.takeIf { it != "0" } ?: "未知"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 批量编辑面板 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BatchMetaEditor(vm: AppViewModel, urls: List<String>) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("已选 ${urls.size} 首，勾选要修改的字段后填写新值") }

    val textFields = listOf(
        "title" to "歌名",
        "artist" to "艺术家",
        "album" to "专辑",
        "year" to "年份",
        "track" to "音轨号",
        "disc" to "碟号",
    )
    // 勾选集合 = 文本框字段 + 封面 + 歌词。后两个没有输入框：
    // 封面是「点缩略图选中哪张」，歌词只靠勾选参与「清空元数据」。
    val allKeys = textFields.map { it.first } + listOf("cover", "lyric")
    val checked = remember { mutableStateMapOf<String, Boolean>() }
    val values = remember { mutableStateMapOf<String, String>() }
    allKeys.forEach { key ->
        if (key !in checked) checked[key] = false
        if (key !in values) values[key] = ""
    }

    var pickedCover by remember { mutableStateOf("") }
    var items by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    // 封面必须查库：批量入口只传了 url，列表页那边的快照可能已经不是最新的
    LaunchedEffect(urls) {
        items = runCatching { vm.metaItems(urls) }.getOrDefault(emptyList())
    }
    // 同一张封面只展示一次：所选的歌多半来自同一张专辑，重复缩略图没有意义
    val covers = remember(items) {
        items.map { it.coverUrl }.filter { it.isNotBlank() }.distinct()
    }
    val checkedCount = allKeys.count { checked[it] == true }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清空元数据") },
            text = {
                Text(
                    "将清空这 ${urls.size} 首里勾选的 $checkedCount 个字段，清完会自动重新补全。" +
                        "手动填写过的内容无法恢复，确定继续？",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmClear = false
                        val fields = allKeys.filter { checked[it] == true }.toSet()
                        busy = true
                        scope.launch {
                            val started = vm.clearMetaBatch(urls, fields)
                            busy = false
                            status = "已清空 ${urls.size} 首的 ${fields.size} 个字段" +
                                if (started) "，已重新开始补全" else "；补全开关已关闭，未自动联网"
                        }
                    },
                ) { Text("清空") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("取消") }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("批量修改元数据（${urls.size} 首）") },
                navigationIcon = {
                    IconButton(onClick = { vm.closeMetaEdit() }) {
                        Icon(Icons.Filled.Close, contentDescription = "关闭")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            Text(
                "「保存」只更新勾选且有值的字段；「清空元数据」把勾选字段清回空值，交给补全重新填。" +
                    "封面点一张即替换全部，歌词只能勾选（用于清空）。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            textFields.forEach { (key, label) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = checked[key] == true,
                        onCheckedChange = { checked[key] = it },
                    )
                    OutlinedTextField(
                        value = values[key] ?: "",
                        onValueChange = { values[key] = it },
                        label = { Text("新$label") },
                        enabled = checked[key] == true,
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                    )
                }
                Spacer(Modifier.height(8.dp))
            }

            // ---- 封面：展示所选歌曲的封面，点一张选中（单选），保存时替换全部 ----
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = checked["cover"] == true,
                    onCheckedChange = {
                        checked["cover"] = it
                        if (!it) pickedCover = ""
                    },
                )
                Column {
                    Text("封面", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        if (covers.isEmpty()) "所选歌曲都没有封面，无法挑选"
                        else "点一张替换全部（单选，再点一次取消）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (covers.isNotEmpty()) {
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(covers, key = { it }) { cover ->
                        val selected = pickedCover == cover
                        Box(
                            Modifier
                                .size(76.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    pickedCover = if (selected) "" else cover
                                    // 点了封面就等于要用它，省掉「再勾一下复选框」
                                    checked["cover"] = pickedCover.isNotBlank()
                                },
                        ) {
                            AsyncImage(
                                model = cover,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                            if (selected) {
                                Box(
                                    Modifier
                                        .fillMaxSize()
                                        .background(Color.Black.copy(alpha = 0.45f)),
                                )
                                Icon(
                                    Icons.Filled.Check,
                                    contentDescription = "已选中",
                                    tint = Color.White,
                                    modifier = Modifier
                                        .align(Alignment.Center)
                                        .size(28.dp),
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }

            // ---- 歌词：只有勾选框，勾了才能在「清空元数据」里清掉 ----
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = checked["lyric"] == true,
                    onCheckedChange = { checked["lyric"] = it },
                )
                Column {
                    Text("歌词", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "勾选后点「清空元数据」可清除，播放时会重新匹配",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(16.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { allKeys.forEach { checked[it] = true } }) {
                    Text("全选字段")
                }
                OutlinedButton(
                    onClick = {
                        allKeys.forEach { checked[it] = false }
                        pickedCover = ""
                    },
                ) { Text("清空勾选") }
            }
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = {
                    val changes = mutableMapOf<String, String>()
                    textFields.forEach { (key, _) ->
                        val v = values[key].orEmpty()
                        if (checked[key] == true && v.isNotBlank()) changes[key] = v.trim()
                    }
                    if (checked["cover"] == true && pickedCover.isNotBlank()) {
                        changes["coverUrl"] = pickedCover
                    }
                    if (changes.isEmpty()) {
                        status = when {
                            checked["cover"] == true -> "请先在下面点选一张封面"
                            checked["lyric"] == true -> "歌词请在「清空元数据」里清除"
                            else -> "请至少勾选并填写一个字段"
                        }
                        return@Button
                    }
                    busy = true
                    scope.launch {
                        vm.applyMetaBatch(urls, changes)
                        busy = false
                        status = "已更新 ${urls.size} 首"
                    }
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (busy) "处理中…" else "保存") }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = {
                    if (checkedCount == 0) {
                        status = "请先勾选要清空的字段"
                    } else {
                        confirmClear = true
                    }
                },
                enabled = !busy,
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("清空元数据") }
        }
    }
}

/** 单独搜封面的子弹窗 */
@Composable
private fun CoverPicker(
    results: List<MetadataScraper.SearchResult>,
    loading: Boolean,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            shape = MaterialTheme.shapes.medium,
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("选择封面", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = "关闭") }
                }
                if (loading) {
                    Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else if (results.isEmpty()) {
                    Text("未找到可用封面", modifier = Modifier.padding(16.dp))
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                    ) {
                        results.forEach { r ->
                            Column(
                                modifier = Modifier
                                    .size(100.dp)
                                    .clickable { onPick(r.coverUrl) }
                                    .padding(4.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                AsyncImage(
                                    model = r.coverUrl,
                                    contentDescription = r.source,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.size(80.dp),
                                )
                                Text(r.source, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 单独搜歌词的子弹窗：按启用的源出页签，点页签或左右滑动切换看不同源的结果。
 * 只给「有结果」的源出页签，避免出现空页签；页签顺序固定 QQ → 酷狗 → 网易云。
 */
@Composable
private fun LyricPicker(
    resultsBySource: Map<String, List<MetadataScraper.LyricResult>>,
    sourceLabels: List<Pair<String, String>>,
    loading: Boolean,
    onPick: (MetadataScraper.LyricResult) -> Unit,
    onDismiss: () -> Unit,
) {
    val tabs = sourceLabels.filter { (key, _) -> resultsBySource[key]?.isNotEmpty() == true }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            shape = MaterialTheme.shapes.medium,
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("选择歌词", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = "关闭") }
                }
                when {
                    loading -> {
                        Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                    tabs.isEmpty() -> Text("未找到歌词", modifier = Modifier.padding(16.dp))
                    else -> {
                        // 页签与分页双向同步：点页签滑过去，滑过去页签跟着高亮
                        val pagerState = rememberPagerState(pageCount = { tabs.size })
                        val pagerScope = rememberCoroutineScope()
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            tabs.forEachIndexed { index, (key, label) ->
                                FilterChip(
                                    selected = pagerState.currentPage == index,
                                    onClick = {
                                        pagerScope.launch { pagerState.animateScrollToPage(index) }
                                    },
                                    label = { Text("$label ${resultsBySource[key]?.size ?: 0}") },
                                )
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        HorizontalPager(
                            state = pagerState,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(400.dp),
                            key = { tabs[it].first },
                        ) { page ->
                            val list = resultsBySource[tabs[page].first].orEmpty()
                            Column(
                                Modifier
                                    .fillMaxSize()
                                    .verticalScroll(rememberScrollState()),
                            ) {
                                list.forEach { r ->
                                    Column(
                                        Modifier
                                            .fillMaxWidth()
                                            .clickable { onPick(r) }
                                            .padding(12.dp),
                                    ) {
                                        Text(
                                            r.source,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                        Text(
                                            r.lyric.split("\n").take(4).joinToString("\n"),
                                            style = MaterialTheme.typography.bodySmall,
                                            maxLines = 4,
                                        )
                                        Divider()
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
