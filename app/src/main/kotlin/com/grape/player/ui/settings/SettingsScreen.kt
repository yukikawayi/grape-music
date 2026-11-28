package com.grape.player.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.grape.player.data.MediaCache
import com.grape.player.data.WebDavSource
import com.grape.player.ui.AppViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 设置页 —— 纯 WebDAV 方案：配好 OpenList（或同类网盘）的 /dav 地址与根目录即可扫描播放。
 * 支持配置**多个**源：扫描时逐个源合并进同一个媒体库，播放时按直链自动匹配对应源的认证头。
 */
@Composable
fun SettingsScreen(
    vm: AppViewModel = viewModel(),
) {
    val scope = rememberCoroutineScope()

    val webDavSources by vm.settings.webDavSources.collectAsState(initial = emptyList())
    val targetLufs by vm.settings.targetLufs.collectAsState(initial = -16)
    val scanState by vm.scanState.collectAsState()
    val autoEnrich by vm.settings.autoScan.collectAsState(initial = true)
    val scanOnLaunch by vm.settings.scanOnLaunch.collectAsState(initial = false)
    val enrichState by vm.enrichState.collectAsState()

    // 播放页封面大小与竖直位置（外观设置）
    val coverSizeSetting by vm.settings.playerCoverSize.collectAsState(initial = 240)
    val coverYSetting by vm.settings.playerCoverY.collectAsState(initial = 0.5f)

    // 新增 / 编辑 WebDAV 源弹窗：null = 关闭；id 为空 = 新增（保存时才分配 id）
    var editingSource by remember { mutableStateOf<WebDavSource?>(null) }
    // 删除源的二次确认
    var pendingDeleteSource by remember { mutableStateOf<WebDavSource?>(null) }
    // 维护区状态：清库确认框 / 正在执行 / 结果提示
    var confirmWipe by remember { mutableStateOf(false) }
    var confirmDeleteOnline by remember { mutableStateOf(false) }
    var maintenanceBusy by remember { mutableStateOf(false) }
    var maintenanceMsg by remember { mutableStateOf("") }
    // 缓存区状态：清空确认框 / 正在清理 / 结果提示 / 当前占用（-1 = 还没统计）
    var confirmClearCache by remember { mutableStateOf(false) }
    var cacheClearing by remember { mutableStateOf(false) }
    var cacheMsg by remember { mutableStateOf("") }
    var cacheUsed by remember { mutableStateOf(-1L) }
    val dynamicColor by vm.dynamicColorEnabled.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        // 标题右侧是「新增」入口（点开一个空表单弹窗），下方是已添加的源列表
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "服务器设置",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { editingSource = WebDavSource() }) { Text("新增") }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "可以添加多个 openlist/CloudDrive2/其他网盘提供的 webDAV 源，如：http://127.0.0.1:5244/dav。"
                + "扫描时逐个源合并进同一个媒体库，播放时按直链自动匹配对应源的账号。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))

        if (webDavSources.isEmpty()) {
            Text(
                "还没有添加 WebDAV 源，点右上角「新增」添加一个。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        } else {
            // 点整行进入编辑；最右边的 ✕ 是删除（会再确认一次）
            webDavSources.forEach { source ->
                WebDavSourceRow(
                    source = source,
                    onClick = { editingSource = source },
                    onDelete = { pendingDeleteSource = source },
                )
            }
        }

        Spacer(Modifier.height(20.dp))
        Text("媒体库", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("扫描时补全元数据", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "启用此项，下面的元数据设置才有效。扫描时将按内嵌->选中的在线源补全元数据，"
                        + "非WIFI环境下每扫描1000首约消耗1GB流量",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = autoEnrich,
                onCheckedChange = { enabled ->
                    scope.launch { vm.settings.setAutoScan(enabled) }
                    if (!enabled) com.grape.player.network.MetadataEnricher.cancel()
                },
            )
        }
        Spacer(Modifier.height(8.dp))

        // 下次启动继续扫描：控制「应用一打开要不要自动扫」，与上面的补全开关互不影响
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("下次启动继续扫描", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "默认关闭。开启后每次打开应用都会扫描一次，上次未扫描完成也会接上。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = scanOnLaunch,
                onCheckedChange = { enabled -> scope.launch { vm.settings.setScanOnLaunch(enabled) } },
            )
        }
        Spacer(Modifier.height(8.dp))

        // 元数据源设置：内嵌标签固定启用（不可关闭），网络源按需启用
        Text("元数据源设置", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("文件内嵌标签", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "始终启用：补全时优先读取，只填空不覆盖手动值",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = true, onCheckedChange = null, enabled = false)
        }
        val enabledProvidersRaw by vm.settings.enabledProviders.collectAsState(initial = "qq,kugou,netease")
        val providerSet = enabledProvidersRaw.split(',').map { it.trim() }.toSet()
        listOf("qq" to "QQ音乐", "kugou" to "酷狗音乐", "netease" to "网易云音乐").forEach { (key, label) ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(label, style = MaterialTheme.typography.bodyMedium)
                }
                Switch(
                    checked = key in providerSet,
                    onCheckedChange = { on ->
                        val newSet = if (on) providerSet + key else providerSet - key
                        // 存储时保持固定顺序 qq,kugou,netease（补全优先级固定，不随开关顺序变）
                        val value = listOf("qq", "kugou", "netease").filter { it in newSet }.joinToString(",")
                        scope.launch { vm.settings.setEnabledProviders(value) }
                    },
                )
            }
        }
        Text(
            "启用在线源会降低扫描入库速度\n" +
                "元数据优先级：内嵌->QQ音乐(若启用)->酷狗音乐(若启用)->网易云音乐(若启用)，" +
                "上一级未补全的用下级结果补全。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))

        Button(
            onClick = { vm.startScan(webDavSources) },
            modifier = Modifier.fillMaxWidth(),
            enabled = webDavSources.any { it.isValid } && scanState !is AppViewModel.ScanUiState.Scanning,
        ) {
            Text("扫描网盘媒体库")
        }

        Spacer(Modifier.height(8.dp))
        // 取消扫描：立即停止枚举，并把已经扫到的条目保存进库（不清库，未扫到的旧条目保留）
        OutlinedButton(
            onClick = { vm.cancelScan() },
            modifier = Modifier.fillMaxWidth(),
            enabled = scanState is AppViewModel.ScanUiState.Scanning,
        ) {
            Text("取消扫描")
        }

        when (val state = scanState) {
            is AppViewModel.ScanUiState.Scanning -> {
                Spacer(Modifier.height(12.dp))
                Text(
                    if (state.total > 0) "${state.phase}  ${state.current}/${state.total}" else state.phase,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            is AppViewModel.ScanUiState.Done -> {
                Spacer(Modifier.height(12.dp))
                Text("扫描完成：${state.total} 项（音乐 ${state.music} / 视频 ${state.video}）")
            }

            is AppViewModel.ScanUiState.Cancelled -> {
                Spacer(Modifier.height(12.dp))
                Text("已取消扫描：已保存 ${state.total} 条到媒体库（未扫到的原有条目保留）")
            }

            is AppViewModel.ScanUiState.Error -> {
                Spacer(Modifier.height(12.dp))
                Text("扫描失败：${state.message}", color = MaterialTheme.colorScheme.error)
            }

            else -> {}
        }

        // 数据存放位置（固定在外部应用目录，文件管理器可直接查看/备份；随应用卸载删除）
        val dataDirPath by vm.settings.dataDir.collectAsState(initial = "")
        Spacer(Modifier.height(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("数据存放位置", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "数据库、歌词缓存、封面与歌曲缓存统一存放在下面的目录（普通文件管理器可直接查看和备份）：",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    dataDirPath.ifBlank { "启动后自动确定（外部应用目录）" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        // 后台元数据补全进度：补完会自动消失，界面显示由 Room 的 Flow 实时驱动
        if (enrichState.running) {
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "元数据补全进度  ${enrichState.done}/${enrichState.total}",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                // 首次扫描后补全要跑很久，必须有停止入口（断点保留，下次启动续跑）——
                // 「取消扫描」按钮在扫描结束（Done）后就禁用了，覆盖不到这个阶段
                TextButton(onClick = { vm.cancelEnrich() }) {
                    Text("停止补全")
                }
            }
            if (enrichState.current.isNotBlank()) {
                Text(
                    enrichState.current,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else if (enrichState.finishedOnce && enrichState.total > 0) {
            Spacer(Modifier.height(10.dp))
            Text(
                "元数据补全完成：${enrichState.total} 首",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // ===== 缓存 =====
        // 播放过的歌在本地留一份（数据目录/Cache/），再次播放直接读本地，省掉重复流量；
        // 超过上限从「最久没播放过」的开始删。
        val cacheLimitMb by vm.settings.cacheLimitMb.collectAsState(initial = MediaCache.DEFAULT_LIMIT_MB)
        // 进页面 / 改上限后重新统计一次（改上限会就地淘汰，占用会变）
        LaunchedEffect(cacheLimitMb) {
            cacheUsed = withContext(Dispatchers.IO) { MediaCache.usedBytes() }
        }
        Spacer(Modifier.height(24.dp))
        Text("缓存", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(12.dp))
        Text("歌曲缓存上限", style = MaterialTheme.typography.bodyLarge)
        Text(
            "播放过的歌曲会在本地留一份，再次播放直接读本地，不再重复消耗流量；"
                + "达到上限后自动删掉最早缓存的那几首。0 = 不缓存。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        // 四挡在同一行：0 / 4GB / 8GB / 16GB
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            MediaCache.LIMIT_OPTIONS_MB.forEachIndexed { idx, mb ->
                SegmentedButton(
                    selected = cacheLimitMb == mb,
                    onClick = { scope.launch { vm.settings.setCacheLimitMb(mb) } },
                    shape = SegmentedButtonDefaults.itemShape(idx, MediaCache.LIMIT_OPTIONS_MB.size),
                ) {
                    Text(if (mb <= 0) "0" else "${mb / 1024}GB")
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "缓存文件位置：${MediaCache.dirPath()}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
        )
        if (cacheUsed >= 0L) {
            Text(
                "当前占用：${formatBytes(cacheUsed)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            enabled = !cacheClearing,
            onClick = { confirmClearCache = true },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (cacheClearing) "清理中…" else "清空缓存")
        }
        if (cacheMsg.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                cacheMsg,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        Spacer(Modifier.height(24.dp))
        Text("外观", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("封面主色驱动主题", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "从正在播放的封面提取主色，改变全站强调色与背景",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = dynamicColor, onCheckedChange = { vm.setDynamicColorEnabled(it) })
        }

        Spacer(Modifier.height(16.dp))
        // 歌曲列表封面：控制音乐库 / 详情页 / 歌单里每一行左侧是否显示封面缩略图
        val songListCover by vm.settings.songListCover.collectAsState(initial = false)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("歌曲列表显示封面", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "音乐库、专辑/艺术家详情、歌单的歌曲行左侧显示封面缩略图；关闭则只显示文字，列表更紧凑",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = songListCover,
                onCheckedChange = { enabled ->
                    scope.launch { vm.settings.setSongListCover(enabled) }
                },
            )
        }

        Spacer(Modifier.height(16.dp))
        Text("播放页封面", style = MaterialTheme.typography.titleMedium)

        // 封面大小：拖动即时显示数值，松手保存
        var coverSizeLocal by remember(coverSizeSetting) { mutableStateOf(coverSizeSetting.toFloat()) }
        Spacer(Modifier.height(8.dp))
        Text(
            "大小：${coverSizeLocal.toInt()} dp",
            style = MaterialTheme.typography.bodyMedium,
        )
        Slider(
            value = coverSizeLocal,
            valueRange = 120f..400f,
            onValueChange = { coverSizeLocal = it },
            onValueChangeFinished = {
                scope.launch { vm.settings.setPlayerCoverSize(coverSizeLocal.toInt()) }
            },
        )

        // 竖直位置：0=顶部 0.5=居中（默认） 1=底部
        var coverYLocal by remember(coverYSetting) { mutableStateOf(coverYSetting) }
        val yLabel = when {
            coverYLocal < 0.25f -> "偏上"
            coverYLocal < 0.42f -> "中上"
            coverYLocal < 0.58f -> "居中"
            coverYLocal < 0.75f -> "中下"
            else -> "偏下"
        }
        Text(
            "竖直位置：$yLabel",
            style = MaterialTheme.typography.bodyMedium,
        )
        Slider(
            value = coverYLocal,
            valueRange = 0f..1f,
            onValueChange = { coverYLocal = it },
            onValueChangeFinished = {
                scope.launch { vm.settings.setPlayerCoverY(coverYLocal) }
            },
        )
        Text(
            "调整播放页封面图片的大小与上下位置，默认大小 240dp、垂直居中",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        TextButton(
            onClick = {
                coverSizeLocal = 240f
                coverYLocal = 0.5f
                scope.launch {
                    vm.settings.setPlayerCoverSize(240)
                    vm.settings.setPlayerCoverY(0.5f)
                }
            },
        ) { Text("恢复默认") }

        Spacer(Modifier.height(12.dp))
        val titleCenter by vm.settings.playerTitleCenter.collectAsState(initial = true)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("标题/艺术家居中显示", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "播放页歌名与歌手名水平居中；关闭后靠左对齐",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = titleCenter,
                onCheckedChange = { enabled ->
                    scope.launch { vm.settings.setPlayerTitleCenter(enabled) }
                },
            )
        }

        // 歌词设置：字号 / 对齐 / 卡拉OK扫光
        Spacer(Modifier.height(16.dp))
        Text("歌词", style = MaterialTheme.typography.titleMedium)
        val lyricFontSizeSetting by vm.settings.lyricFontSize.collectAsState(initial = 16)
        val lyricAlignCenter by vm.settings.lyricAlignCenter.collectAsState(initial = true)
        val lyricKaraoke by vm.settings.lyricKaraoke.collectAsState(initial = true)
        val lyricFullscreen by vm.settings.lyricFullscreen.collectAsState(initial = false)
        val lyricTranslation by vm.settings.lyricTranslation.collectAsState(initial = true)
        var lyricSizeLocal by remember(lyricFontSizeSetting) { mutableStateOf(lyricFontSizeSetting.toFloat()) }

        Spacer(Modifier.height(8.dp))
        Text("字体大小：${lyricSizeLocal.toInt()} sp", style = MaterialTheme.typography.bodyMedium)
        Slider(
            value = lyricSizeLocal,
            valueRange = 12f..40f,
            onValueChange = { lyricSizeLocal = it },
            onValueChangeFinished = {
                scope.launch { vm.settings.setLyricFontSize(lyricSizeLocal.toInt()) }
            },
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("歌词居中对齐", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "关闭后歌词靠左对齐",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = lyricAlignCenter,
                onCheckedChange = { enabled ->
                    scope.launch { vm.settings.setLyricAlignCenter(enabled) }
                },
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("显示翻译", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "双语歌词在原文下方显示译文行；关闭后只显示原文",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = lyricTranslation,
                onCheckedChange = { enabled ->
                    scope.launch { vm.settings.setLyricTranslation(enabled) }
                },
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("卡拉OK扫光", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "当前行逐字点亮高亮（歌词页内也可临时开关）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = lyricKaraoke,
                onCheckedChange = { enabled ->
                    scope.launch { vm.settings.setLyricKaraoke(enabled) }
                },
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("歌词全屏显示", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "歌词页隐藏进度条和播放按钮，仅显示歌词（左右滑回封面页可操作播放）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = lyricFullscreen,
                onCheckedChange = { enabled ->
                    scope.launch { vm.settings.setLyricFullscreen(enabled) }
                },
            )
        }

        // 播放设置：点击歌曲时的行为
        Spacer(Modifier.height(24.dp))
        Text("播放", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(12.dp))
        val playerOpenOnPlay by vm.settings.playerOpenOnPlay.collectAsState(initial = true)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("每次播放打开播放页", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "启用后点击歌曲开始播放会自动进入全屏播放页；关闭则留在当前页面，播放继续，可从底部迷你播放栏进入播放页",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = playerOpenOnPlay,
                onCheckedChange = { enabled ->
                    scope.launch { vm.settings.setPlayerOpenOnPlay(enabled) }
                },
            )
        }

        Spacer(Modifier.height(24.dp))
        Text("响度归一化", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(12.dp))
        Text("目标响度：$targetLufs LUFS", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(8.dp))
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            listOf(-14, -16, -18).forEachIndexed { idx, value ->
                SegmentedButton(
                    selected = targetLufs == value,
                    onClick = { scope.launch { vm.settings.setTargetLufs(value) } },
                    shape = SegmentedButtonDefaults.itemShape(idx, 3),
                ) { Text("$value") }
            }
        }

        Spacer(Modifier.height(24.dp))
        Text("维护", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            "清除数据库只清理 App 内的曲目、歌词、歌单与元数据缓存，不会删除网盘上的文件；"
                + "WebDAV 配置与外观设置会保留。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))

        OutlinedButton(
            enabled = !maintenanceBusy,
            onClick = {
                scope.launch {
                    maintenanceBusy = true
                    val result = vm.deleteInvalidCovers()
                    maintenanceBusy = false
                    maintenanceMsg = if (result.deleted == 0) {
                        "没有可删除的无效封面"
                    } else {
                        "已删除 ${result.deleted} 个无效封面，释放 ${formatBytes(result.freedBytes)}"
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (maintenanceBusy) "处理中…" else "删除无效封面") }

        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            enabled = !maintenanceBusy,
            onClick = { confirmDeleteOnline = true },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("删除所有在线数据") }

        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            enabled = !maintenanceBusy,
            onClick = { confirmWipe = true },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("清除数据库", color = MaterialTheme.colorScheme.error) }

        if (maintenanceMsg.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(
                maintenanceMsg,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Spacer(Modifier.height(24.dp))
    }

    if (confirmDeleteOnline) {
        AlertDialog(
            onDismissRequest = { confirmDeleteOnline = false },
            title = { Text("删除所有在线数据") },
            text = {
                Text(
                    "将清空所有联网获取的元数据：显示字段重置后仅从文件内嵌标签恢复（无内嵌的字段清空，标题回退为文件名）；"
                        + "在线歌词与译文、在线刮削缓存一并清除，所有非内嵌来源的封面文件会被删除。\n\n"
                        + "文件内嵌标签数据（Embedded_）不受影响；网盘上的音乐文件不受影响。\n"
                        + "此操作不可撤销。",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDeleteOnline = false
                    scope.launch {
                        maintenanceBusy = true
                        val result = vm.deleteAllOnlineData()
                        maintenanceBusy = false
                        maintenanceMsg = "已删除所有在线数据，同时清理 ${result.deleted} 个非内嵌封面" +
                            "（${formatBytes(result.freedBytes)}）"
                    }
                }) { Text("确认删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDeleteOnline = false }) { Text("取消") } },
        )
    }

    if (confirmWipe) {
        AlertDialog(
            onDismissRequest = { confirmWipe = false },
            title = { Text("清除数据库") },
            text = {
                Text(
                    "将删除全部曲目、歌词缓存、歌单、元数据缓存，并清空本地封面缓存。\n\n"
                        + "网盘上的音乐文件不受影响；WebDAV 配置与外观设置会保留。\n"
                        + "此操作不可撤销，清完后需要重新扫描媒体库。",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmWipe = false
                    scope.launch {
                        maintenanceBusy = true
                        val result = vm.wipeDatabase()
                        maintenanceBusy = false
                        maintenanceMsg = "已清除数据库，同时清理 ${result.deleted} 个封面缓存" +
                            "（${formatBytes(result.freedBytes)}）"
                    }
                }) { Text("确认清除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmWipe = false }) { Text("取消") } },
        )
    }

    if (confirmClearCache) {
        AlertDialog(
            onDismissRequest = { confirmClearCache = false },
            title = { Text("清空缓存") },
            text = {
                Text(
                    "将删除 Cache 文件夹里所有已缓存的歌曲分片（含索引文件）" +
                        (if (cacheUsed >= 0L) "（当前占用 ${formatBytes(cacheUsed)}）" else "") +
                        "，下次播放这些歌会重新从网盘下载。\n\n"
                        + "不会删除网盘上的音乐、曲目库与封面；缓存上限设置保持不变。\n"
                        + "此操作不可撤销。",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmClearCache = false
                    scope.launch {
                        cacheClearing = true
                        val freed = withContext(Dispatchers.IO) {
                            val before = MediaCache.usedBytes()
                            MediaCache.clearAll()
                            before
                        }
                        cacheClearing = false
                        cacheUsed = 0L
                        cacheMsg = if (freed <= 0L) "缓存已经是空的" else "已清空缓存，释放 ${formatBytes(freed)}"
                    }
                }) { Text("确认清空", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmClearCache = false }) { Text("取消") } },
        )
    }

    // ===== 新增 / 编辑 WebDAV 源 =====
    editingSource?.let { editing ->
        WebDavSourceEditDialog(
            initial = editing,
            onDismiss = { editingSource = null },
            onSave = { updated ->
                val next = if (editing.id.isBlank()) {
                    webDavSources + updated
                } else {
                    webDavSources.map { if (it.id == editing.id) updated else it }
                }
                scope.launch { vm.saveServer(next) }
                editingSource = null
            },
        )
    }

    // ===== 删除 WebDAV 源（二次确认） =====
    pendingDeleteSource?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDeleteSource = null },
            title = { Text("删除 WebDAV 源") },
            text = {
                Text(
                    "确定删除「${target.displayAddress()}」？\n\n"
                        + "只是从配置里移除这一项，网盘上的文件不会被删除。"
                        + "已经扫进媒体库的歌曲仍保留在库里，但会失去对应账号的认证（重扫/播放可能失败），"
                        + "建议删除后重新扫描一次。",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val next = webDavSources.filterNot { it.id == target.id }
                    pendingDeleteSource = null
                    scope.launch { vm.saveServer(next) }
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { pendingDeleteSource = null }) { Text("取消") } },
        )
    }
}

/** 源列表里的一行：点整行编辑，右侧 ✕ 删除 */
@Composable
private fun WebDavSourceRow(
    source: WebDavSource,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    source.displayAddress().ifBlank { "（未填写地址）" },
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "根目录 ${source.rootPath} · " + if (source.user.isBlank()) "匿名访问" else "用户 ${source.user}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Close, contentDescription = "删除这个源")
            }
        }
    }
}

/**
 * 新增 / 编辑一个 WebDAV 源：地址、用户名、密码、根目录四项 + 取消/保存。
 * id 为空视为新增，保存时才分配 UUID；点「取消」什么都不写。
 */
@Composable
private fun WebDavSourceEditDialog(
    initial: WebDavSource,
    onDismiss: () -> Unit,
    onSave: (WebDavSource) -> Unit,
) {
    val isNew = initial.id.isBlank()
    var url by remember { mutableStateOf(initial.url) }
    var user by remember { mutableStateOf(initial.user) }
    var pass by remember { mutableStateOf(initial.pass) }
    var root by remember { mutableStateOf(initial.root) }
    val urlOk = url.trim().let {
        it.startsWith("http://", true) || it.startsWith("https://", true)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "新增 WebDAV 源" else "编辑 WebDAV 源") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("WebDAV 地址") },
                    placeholder = { Text("http://127.0.0.1:5244/dav") },
                    singleLine = true,
                    isError = url.isNotBlank() && !urlOk,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (url.isNotBlank() && !urlOk) {
                    Text(
                        "地址需以 http:// 或 https:// 开头",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = user,
                    onValueChange = { user = it },
                    label = { Text("用户名") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = pass,
                    onValueChange = { pass = it },
                    label = { Text("密码") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = root,
                    onValueChange = { root = it },
                    label = { Text("根目录") },
                    placeholder = { Text("/quark") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "OpenList 里的挂载路径，如 /quark、/123/音乐。留 / 则扫描全部挂载。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = urlOk,
                onClick = {
                    onSave(
                        initial.copy(
                            id = initial.id.ifBlank { java.util.UUID.randomUUID().toString() },
                            url = url.trim(),
                            user = user.trim(),
                            pass = pass,
                            root = root.trim().ifBlank { "/" },
                        ),
                    )
                },
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** 字节数 → 人类可读（GB/MB/KB） */
private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L * 1024L -> "%.2f GB".format(bytes / 1024.0 / 1024.0 / 1024.0)
    bytes >= 1024L * 1024L -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
    bytes >= 1024L -> "%.0f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}
