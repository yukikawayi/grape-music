package com.grape.player.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.grape.player.ui.AppViewModel
import com.grape.player.util.LrcParser
import com.grape.player.util.LyricLine
import com.grape.player.util.LyricQuality
import com.grape.player.util.LyricSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.ceil

/**
 * 歌词视图 —— 三级优先（内嵌 → 缓存 → 在线）、逐行滚动、双语、逐字卡拉 OK 高亮。
 * 时间偏移在播放页的「词」按钮弹层里调整，歌词视图本身不再带控制条。
 *
 * 收起播放页的手势不在这里：由播放页顶栏（标题/艺术家信息条）上的拖动触发。
 * 歌词列表本身只负责滚动，滚到顶再下拉不会收起页面 —— 避免「看歌词时想把顶部几行
 * 拉回来看清」被误判成退出。
 *
 * 自动跟随：当前句停在视口 35% 处，上下各留一片空白（按视口比例算，任何字号都成立）。
 * 用户手拖歌词后暂停跟随，松手满 LYRIC_RESUME_DELAY_MS 才拉回当前句；拖动期间在锚点那句
 * 下方浮出一个写着该句起始时间的小胶囊，点它把播放头跳到这句并开播。
 *
 * 逐字扫光：
 *  - 增强歌词（LRC 带 <mm:ss.xx> 逐字时间戳）：按每个字/词的时间戳点亮，最精确；
 *  - 普通 LRC：按当前行进度均分字符数点亮（对齐 Web 版实现）。
 * 位置以 50ms 高频读取，保证扫光流畅（全局 250ms 轮询只服务于进度条/响度/预热）。
 */
@Composable
fun LyricViewer(
    vm: AppViewModel,
    modifier: Modifier = Modifier,
    /** 外部（歌词设置弹层）保存偏移后 +1，触发重新加载当前偏移 */
    offsetReloadKey: Int = 0,
    /**
     * 冻结所有时间驱动的更新（50ms 位置轮询、卡拉OK重算、自动滚动）。
     * 播放页收起动画期间传 true —— 歌词的逐帧重组和滚动动画会和整页下移动画
     * 抢主线程帧，是收起卡顿的主要来源；页面反正马上就看不见了，直接静止最划算。
     */
    frozen: Boolean = false,
) {
    val current = vm.player.currentItem()
    // queueVersion 变化 = 队列内容被改过（切歌 / 元数据编辑保存后同步内存项等）。
    // 编辑弹层是悬浮 Dialog，背后的歌词页保持组合，光靠 url 不变判断不出「歌词被改过」，
    // 必须把它作为 produceState 的 key，保存后才能立即重载新歌词。
    //
    // 注意这里**不能** collectAsState 整个 PlayerState：全局 250ms 的进度刷新会让
    // 整个歌词视图（含 LazyColumn 与所有可见行）一秒重组四次。只留真正需要的两件事：
    // queueVersion（换队列）和 durationMs（「跳到这句」的夹取上限 / 匹配时长兜底）。
    val lyricMeta by remember(vm) {
        vm.player.state
            .map { it.queueVersion to it.durationMs }
            .distinctUntilChanged()
    }.collectAsState(initial = vm.player.state.value.let { it.queueVersion to it.durationMs })
    val queueVersion = lyricMeta.first
    val durationMs = lyricMeta.second
    val listState = rememberLazyListState()
    // 「跳到这句」按钮要等播放头落位后再解除跟随抑制，用一个组合内的 scope
    val scope = rememberCoroutineScope()

    // 歌词外观设置：字号 / 对齐 / 卡拉OK扫光（设置页与「词」弹层均可改，实时生效）
    val lyricFontSize by vm.settings.lyricFontSize.collectAsState(initial = 16)
    val lyricAlignCenter by vm.settings.lyricAlignCenter.collectAsState(initial = true)
    val karaokeOn by vm.settings.lyricKaraoke.collectAsState(initial = true)
    // 译文行开关（默认显示）
    val showTranslation by vm.settings.lyricTranslation.collectAsState(initial = true)
    val lyricAlign = if (lyricAlignCenter) TextAlign.Center else TextAlign.Start

    // 歌词时间偏移：库里读到的值 + 「刚拖过的那首」的内存预览值。
    // 用组合期直接取值（而不是再用一个 LaunchedEffect 同步），拖动滑块时每一帧都能立刻反映到歌词上。
    var dbOffset by remember { mutableStateOf(0L) }
    val offsetOverride by vm.offsetOverride.collectAsState()
    LaunchedEffect(current?.url, offsetReloadKey) {
        val url = current?.url ?: return@LaunchedEffect
        dbOffset = vm.lyricOffset(url)
    }
    val override = offsetOverride
    val offsetMs = if (override != null && override.first == current?.url) override.second else dbOffset

    // 高频读取真实播放位置，专供逐字扫光与当前行定位（50ms ≈ 20fps，视觉平滑）。
    //
    // 关键：它是一个 State，**本函数体不读它的 .value**。读了就等于整个歌词视图
    // （LazyColumn + 所有可见行）跟着 50ms 重组 —— 这正是「歌词页 20fps 全列表重组」
    // 的根因。位置只在这些地方被读：下面的 derivedStateOf（当前行号）、
    // 以及当前行内部（扫光）。两者的读取都发生在各自的组合/计算作用域里，
    // 只会让「真的变了」的那一小块重组。
    //
    // frozen 时整个循环停摆（协程挂起），解冻后自动恢复；
    // 暂停时降到 5Hz —— 没有播放推进，就没有逐字扫光可言，20Hz 空转只是白烧 CPU。
    val finePos = remember { mutableStateOf(0L) }
    LaunchedEffect(frozen) {
        while (!frozen) {
            finePos.value = vm.player.controller?.currentPosition
                ?: vm.player.state.value.positionMs
            delay(if (vm.player.state.value.isPlaying) 50 else 200)
        }
    }

    // 加载歌词：内嵌 → Room 缓存（原文+独立译文）→ 在线搜索（搜到即存，只填空）
    // queueVersion 也作为 key：元数据编辑保存后会同步内存项并 bump，
    // 否则编辑弹层关掉后歌词页一直显示旧词，得滑走再滑回来才刷新
    // 「歌词是否已落定」：跑完之前不显示「暂无歌词」，免得一闪而过（真正没词时它才是终态）
    var lyricSettled by remember(current?.url) { mutableStateOf(false) }
    val lyricFetching by vm.lyricFetching.collectAsState()
    val lyrics by produceState<List<LyricLine>>(
        emptyList(),
        key1 = current?.url,
        key2 = offsetReloadKey,
        key3 = queueVersion,
    ) {
        lyricSettled = false
        if (current == null) {
            value = emptyList()
            lyricSettled = true
            return@produceState
        }
        val cached = vm.repo.getLyric(current.url)
        // 用户手动编辑定稿的歌词（media_items.lyric 列）优先级最高。队列已改成
        // MediaItemLite 不带歌词正文，这里按 url 回表取这一份。
        val itemLyric = vm.repo.getByUrl(current.url)?.lyric.orEmpty()
        // 占位文案（老版本把「纯音乐，请欣赏」当成功结果存进库）当没有处理，
        // 否则这里一旦取到它就再也不会走在线兜底，界面永远只有一行废话
        var lrc = when {
            itemLyric.isNotBlank() && !LyricQuality.isPlaceholder(itemLyric) -> itemLyric
            cached != null && cached.lrc.isNotBlank() && !LyricQuality.isPlaceholder(cached.lrc) -> cached.lrc
            else -> ""
        }
        var trans = cached?.translatedLrc.orEmpty()
        if (lrc.isBlank()) {
            // 前面都空：在线兜底（按设置里启用的源依次试 QQ / 酷狗 / 网易云，各自带自己的译文；
            // 内部已做候选打分、有效性校验与保存；播放切歌时也会预取，这里多为二次命中）。
            // 时长一并传下去：首播那一刻 MediaItem.durationMs 往往还是 0，用播放器的实测值兜底，
            // 匹配时才能区分「录音室版 / 现场版」这类同名不同轴的结果
            val durationHint = if (durationMs > 0) durationMs else current.durationMs
            val (fetchedLrc, fetchedTrans) = vm.fetchLyricIfMissing(
                current.url, current.title, current.artist, current.isVideo,
                durationMs = durationHint,
            )
            lrc = fetchedLrc
            trans = fetchedTrans
        }
        // 确保缓存行存在（偏移行）
        // 出处：正文来自 Using_ 覆盖列 = 用户手动定的；否则沿用库里那行的出处
        val rowSource = when {
            lrc.isBlank() -> ""
            lrc == itemLyric && itemLyric.isNotBlank() -> LyricSource.MANUAL
            else -> cached?.source.orEmpty()
        }
        vm.ensureLyricRow(current.url, lrc, rowSource)
        // 解析挪到 Default：QRC 解密出来的歌词动辄几百 KB，主线程解析会直接掉帧/卡顿
        value = withContext(Dispatchers.Default) { LrcParser.parse(lrc, trans) }
        lyricSettled = true
    }

    // 当前行按「播放位置 - 偏移」计算。
    //
    // 用 derivedStateOf 而不是 remember(lyrics, finePos, ...)：位置每 50ms 变一次，
    // remember 的 key 一变就要重算、并且（因为 finePos 在组合期被读了）让整个歌词视图重组。
    // derivedStateOf 只在**行号真的变了**的时候才把新值通知给组合层，
    // 也就是「一句换一句」那一下，而不是 20 次/秒。
    val currentLine by remember(lyrics, offsetMs) {
        derivedStateOf { LrcParser.indexAt(lyrics, finePos.value, offsetMs) }
    }

    // 自动滚动：当前行停在视口 35% 处（中间偏上，符合视觉焦点，上一句可见）。
    // 负 scrollOffset = 目标行顶部距视口顶部的距离。
    // 首次定位（刚进页面/切歌/重载）或大跨度跳变时用 scrollToItem 直接落位：
    // animateScrollToItem 跨很多行会先瞬移大半程再补一小段动画，肉眼看是「高亮行
    // 先偏上、再慢慢挪回目标位」——正是滑去播放页再滑回来时那个晃动；正常逐行推进才用动画
    var lastScrolledLine by remember { mutableStateOf(Int.MIN_VALUE) }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val density = LocalDensity.current
        // 视口高度在组合期就有（LazyColumn 撑满这个 Box），不必再等 layoutInfo 量出来。
        // 上下各垫一个「留白 item」，高度全部按视口比例算 —— 与本机字号设置无关，
        // 12~40sp 全都成立：列表起止不再被滚动边界钳住，第一句能停在锚点，最后一句
        // 也能滚到锚点，同时天然得到「上方/下方都留着空白」的效果。
        val viewportPx = if (constraints.hasBoundedHeight) constraints.maxHeight else 0
        val anchorPx = (viewportPx * LYRIC_ANCHOR_FRACTION).toInt()
        val topGap = with(density) { anchorPx.toDp() }
        val bottomGap = with(density) { (viewportPx - anchorPx).coerceAtLeast(0).toDp() }

        // 手动拖动后的「跟随暂停窗口」：
        // 手指按住并拖动（LazyList 会发 DragInteraction）就暂停自动跟随，松手后再等
        // LYRIC_RESUME_DELAY_MS 才恢复，恢复的那一刻按当时唱到的那句重新落位。
        // 为什么要显式做这个：自动滚动只由 currentLine 变化驱动，没有拖动时列表本来就不动，
        // 所以「不暂停」的表现其实是「下一句开始就被拉回」——间隔跟着句子长短在 2~6s 之间漂，
        // 想多看两行全靠运气。这里改成固定窗口，长度只由下面那个常量决定。
        // 注意 programmatic 的 animateScrollToItem 不会发 DragInteraction，不会被误判成手拖。
        val isDragging by listState.interactionSource.collectIsDraggedAsState()
        var followSuspended by remember { mutableStateOf(false) }
        LaunchedEffect(isDragging) {
            if (isDragging) {
                followSuspended = true
            } else if (followSuspended) {
                delay(LYRIC_RESUME_DELAY_MS)
                followSuspended = false
            }
        }

        // frozen 也作为 key：解冻瞬间会带着当前行重新定位一次，不会漏掉冻结期间的行推进。
        // viewportPx 也作为 key：全屏模式开关、旋屏改变了可视高度时按新高度重新落位
        // followSuspended 也作为 key：抑制期结束时立刻重跑一次，把列表拉回当前句（这就是「恢复滚动」）
        LaunchedEffect(currentLine, frozen, viewportPx, followSuspended) {
            if (frozen) return@LaunchedEffect
            if (followSuspended) return@LaunchedEffect
            if (currentLine !in lyrics.indices) return@LaunchedEffect
            // 兜底：约束无界（理论上不会发生）时退回 layoutInfo，并等一帧量出视口，
            // 否则 target 退化成 -80 会把当前行顶到视口最上面（进页瞬间的上偏）
            var viewport = viewportPx
            var waits = 0
            while (viewport <= 0 && waits < 10) {
                withFrameNanos { }
                viewport = listState.layoutInfo.viewportSize.height
                waits++
            }
            val target = if (viewport > 0) -(viewport * LYRIC_ANCHOR_FRACTION).toInt() else -80
            // 0 号 item 是顶部留白，歌词行下标整体后移一位
            val itemIndex = currentLine + LYRIC_TOP_GAP_ITEMS
            if (kotlin.math.abs(currentLine - lastScrolledLine) > 1) {
                listState.scrollToItem(itemIndex, scrollOffset = target)
            } else {
                listState.animateScrollToItem(itemIndex, scrollOffset = target)
            }
            lastScrolledLine = currentLine
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // 顶部留白高度 == 锚点位置：第一句的顶部正好落在视口 35% 处
            item(key = LYRIC_TOP_GAP_KEY) {
                Spacer(Modifier.height(topGap))
            }
            itemsIndexed(lyrics) { index, line ->
                // 每一行只吃「稳定的入参」：行内容、是不是当前行、下一行时间（算扫光区间用）。
                // 位置本身以 State 形式传下去，由当前行自己去读 —— 这样 50ms 的位置刷新
                // 只让当前那一行重组，其余可见行（10~20 行）原地不动。
                LyricLineRow(
                    line = line,
                    isCurrent = index == currentLine,
                    nextTimeMs = lyrics.getOrNull(index + 1)?.timeMs ?: 0L,
                    offsetMs = offsetMs,
                    position = finePos,
                    baseSp = lyricFontSize.coerceIn(12, 40),
                    align = lyricAlign,
                    showTranslation = showTranslation,
                    karaokeOn = karaokeOn,
                )
            }
            // 底部留白 == 视口下半部：最后一句也能滚到锚点，其余就是下方空出来的那片
            item(key = LYRIC_BOTTOM_GAP_KEY) {
                Spacer(Modifier.height(bottomGap))
            }
        }

        // 占位提示：以前「正在取词」和「确实没有歌词」都是一片空白，用户分不清是卡住了还是没词。
        // 取词中给明确文案（首播要联网跑三源，通常 1~8 秒）；跑完仍为空才说「暂无歌词」
        if (lyrics.isEmpty() && !frozen) {
            val fetchingNow = current != null && current.url in lyricFetching
            val hint = when {
                fetchingNow -> "正在匹配歌词…"
                lyricSettled -> "暂无歌词"
                else -> ""
            }
            if (hint.isNotEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = hint,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                    )
                }
            }
        }

        // —— 拖动时浮在歌词区右侧中间的小胶囊 ——
        // 位置固定（垂直居中、贴右边缘），不跟着歌词行走：拇指够得着、不会随拖动上下跳。
        // 关键：取哪一句必须按「胶囊自己的垂直位置」算，不能用自动跟随的 35% 锚点。
        //  35% 是当前句停靠的位置，胶囊在 50%，两者差 15% 视口 ≈ 一到两行——
        //  用 35% 取句会让胶囊贴着 A 句、点下去却播 A 的上一句（正是之前那个 bug）。
        // 只在手拖 / 松手后的跟随窗口内出现：自动跟随的时候它没有意义，还会挡住歌词。
        val chipItem by remember {
            derivedStateOf {
                val info = listState.layoutInfo
                if (info.viewportSize.height <= 0) return@derivedStateOf null
                // 与 Alignment.CenterEnd 对齐：胶囊的垂直中心 = 视口中心
                val cy = (info.viewportSize.height * CHIP_CENTER_FRACTION).toInt()
                // 两个留白 item 没有时间轴含义，先剔掉
                val lines = info.visibleItemsInfo.filter {
                    it.key != LYRIC_TOP_GAP_KEY && it.key != LYRIC_BOTTOM_GAP_KEY
                }
                // 优先取被胶囊压住的那句；压在两句之间的空隙里就取「行中心离胶囊最近」的一句
                lines.firstOrNull { cy >= it.offset && cy < it.offset + it.size }
                    ?: lines.minByOrNull {
                        kotlin.math.abs(it.offset + it.size / 2 - cy)
                    }
            }
        }
        val chipLineIndex = chipItem?.index?.minus(LYRIC_TOP_GAP_ITEMS) ?: -1
        val chipLine = lyrics.getOrNull(chipLineIndex)
        if (chipLine != null && (isDragging || followSuspended) && !frozen) {
            // 时间槽固定宽度（并按系统字体缩放一起放大）：
            // 时间文字在槽内居中，槽宽不随「0:38 / 1:20 / 10:05」的字符数变化，
            // 整颗胶囊的宽度因此恒定 —— 之前是按文字自适应，时间一换长度就抖一下。
            val timeSlot = CHIP_TIME_SLOT * density.fontScale
            Row(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    // 只留 2dp：歌词页外层本身已有 16dp 边距，这里再叠多了会显得缩在里面
                    .padding(end = 2.dp)
                    .shadow(6.dp, RoundedCornerShape(50))
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.primary)
                    .clickable {
                        // 落点要算上歌词偏移：高亮是按 offsetMs 校正过的，跳转也得一起偏；
                        // 末句加正偏移可能越过结尾，夹一下，避免一 seek 就把这首吃掉
                        val dur = durationMs
                        vm.player.seekTo(
                            (chipLine.timeMs + offsetMs)
                                .coerceIn(0L, if (dur > 0) dur else Long.MAX_VALUE)
                        )
                        // 「跳到这个时间播放」：停着也直接开播
                        vm.player.play()
                        // 等播放位置真的落到这句再解除抑制 —— 否则会先按旧行回位、再跳到新行，
                        // 肉眼是一下回弹再一下瞬移
                        scope.launch {
                            var waited = 0
                            while (waited < 800) {
                                val pos = vm.player.controller?.currentPosition ?: break
                                if (LrcParser.indexAt(lyrics, pos, offsetMs) >= chipLineIndex) break
                                delay(30)
                                waited += 30
                            }
                            followSuspended = false
                        }
                    }
                    .padding(horizontal = 6.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(12.dp),
                )
                Spacer(Modifier.width(2.dp))
                Box(
                    modifier = Modifier.width(timeSlot),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = formatLyricTime(chipLine.timeMs),
                        // tnum = 等宽数字：'1' 和 '0' 一样宽，槽内居中时不会左右微移
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontFeatureSettings = "tnum",
                        ),
                        color = MaterialTheme.colorScheme.onPrimary,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
        }
    }
}

/**
 * 一行歌词（正文 + 可选译文）。
 *
 * 只有当前行会进 [KaraokeLine] —— 那里才是整个歌词页唯一读 50ms 高频位置的地方。
 * 非当前行的入参全是「换行 / 改字号 / 改对齐」才会变的值，Compose 能把它们整体跳过，
 * 于是「20fps 全列表重组」退化成「换行时整列表重组一次 + 当前行按字重组」。
 */
@Composable
private fun LyricLineRow(
    line: LyricLine,
    isCurrent: Boolean,
    /** 下一行的起始时间（0 = 没有下一行）：扫光区间按它算 */
    nextTimeMs: Long,
    offsetMs: Long,
    position: State<Long>,
    baseSp: Int,
    align: TextAlign,
    showTranslation: Boolean,
    karaokeOn: Boolean,
) {
    // 字号按设置缩放：当前行 +2、普通行 = 设置值；
    // 行高必须随字号放大（否则 fontSize > lineHeight 会重叠），垂直间距也随字号增长
    val curSp = baseSp + 2
    val textStyle = if (isCurrent) MaterialTheme.typography.titleMedium.copy(
        fontSize = curSp.sp,
        lineHeight = (curSp * 1.35f).sp,
    ) else MaterialTheme.typography.bodyLarge.copy(
        fontSize = baseSp.sp,
        lineHeight = (baseSp * 1.35f).sp,
    )
    // 行间距策略：
    //  - 同一行内 歌词↔译文：紧贴（译文自身 2dp 垂直边距）
    //  - 不同行之间：底部留 baseSp 大小的间距，随字号增长
    val itemGap = baseSp.dp
    val linePad = Modifier
        .fillMaxWidth()
        .padding(horizontal = 16.dp)
    Column(modifier = Modifier.fillMaxWidth().padding(bottom = itemGap)) {
        if (isCurrent && karaokeOn) {
            // 当前行且开启扫光：逐字着色（已点亮=主色加粗，未点亮=半透明）
            KaraokeLine(
                line = line,
                nextTimeMs = nextTimeMs,
                offsetMs = offsetMs,
                position = position,
                textStyle = textStyle,
                align = align,
                modifier = linePad,
            )
        } else {
            Text(
                text = line.text,
                style = textStyle,
                color = if (isCurrent) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                textAlign = align,
                modifier = linePad,
            )
        }
        if (showTranslation && line.translation.isNotBlank()) {
            Text(
                text = line.translation,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontSize = (baseSp - 3).coerceAtLeast(10).sp,
                    lineHeight = ((baseSp - 3).coerceAtLeast(10) * 1.3f).sp,
                ),
                color = if (isCurrent) MaterialTheme.colorScheme.secondary
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                textAlign = align,
                // 与正文相同的横向内边距，左对齐时才对得齐；紧贴正文下方
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 2.dp),
            )
        }
    }
}

/**
 * 当前行的卡拉 OK 扫光。
 *
 * 50ms 一次只做两件轻活：算出「点亮了几个字」（Int），字数变了才重建 AnnotatedString。
 * 以前是每次新建 BooleanArray + 每帧重建 TextStyle/SpanStyle/Column/AnnotatedString，
 * 而且因为数组被 itemsIndexed 的 lambda 捕获，还会把可见的十几行一起拖进重组。
 */
@Composable
private fun KaraokeLine(
    line: LyricLine,
    nextTimeMs: Long,
    offsetMs: Long,
    position: State<Long>,
    textStyle: TextStyle,
    align: TextAlign,
    modifier: Modifier = Modifier,
) {
    val litStyle = SpanStyle(
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
    )
    val dimStyle = SpanStyle(
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
    )

    // 扫描区间取行时长的 85%（留一点尾缓冲，避免末字一直不亮）
    val litCount by remember(line, nextTimeMs, offsetMs) {
        derivedStateOf {
            val effective = position.value - offsetMs
            val lineEnd = if (nextTimeMs > line.timeMs) nextTimeMs else line.timeMs + 6000L
            val span = (lineEnd - line.timeMs).coerceAtLeast(1L)
            val scanMs = maxOf(span * 0.85, (span - 600).toDouble()).toLong().coerceAtLeast(1L)
            val p = ((effective - line.timeMs).toFloat() / scanMs).coerceIn(0f, 1f)
            karaokeLitCount(line, effective, p)
        }
    }

    // 只有字数变化才重建文本（BooleanArray 的 equals 是引用比较，直接当 key 等于每帧都重建）
    val annotated = remember(line, litCount, litStyle, dimStyle) {
        buildKaraokeText(
            text = line.text,
            flags = karaokeFlagsForCount(line, litCount),
            litStyle = litStyle,
            dimStyle = dimStyle,
        )
    }
    Text(
        text = annotated,
        style = textStyle,
        textAlign = align,
        modifier = modifier,
    )
}

/** LRC 起始时间的展示格式：m:ss（分钟不进位成小时，如 62:03）。
 *  刻意把小时折进分钟 —— 这样字符串最长只有 5 个字符，胶囊的固定时间槽放得下，
 *  宽度不会因为「9:59 → 10:00」或「→ 1:02:03」这类进位而变化。 */
private fun formatLyricTime(ms: Long): String {
    val totalSec = ms.coerceAtLeast(0L) / 1000
    val m = totalSec / 60
    val s = totalSec % 60
    return "$m:${s.toString().padStart(2, '0')}"
}

/** 当前行停靠位置：视口高度的 35%（中间偏上）。留白高度与停靠位置共用同一比例。 */
private const val LYRIC_ANCHOR_FRACTION = 0.35f
/**
 * 「跳到这句」胶囊自身的垂直位置 —— 视口 50%（与 Alignment.CenterEnd 一致）。
 * 胶囊取句必须用这个比例而不是 LYRIC_ANCHOR_FRACTION：两者不一致时，
 * 胶囊贴着的那句和它显示/跳转的那句会差一到两行（点下去像在播上一句）。
 */
private const val CHIP_CENTER_FRACTION = 0.5f
/**
 * 胶囊里「时间文字」的槽位宽度。固定值 → 胶囊整体宽度恒定，不随时间数字变化。
 * 34dp 是「能放下 5 字符的 m:ss（12sp 下约 32dp）」的下限，再窄就会裁字；
 * 想更短只能省图标（14→12 已做）或内边距，不能压槽位。
 * 实际使用时乘 density.fontScale，系统字号调大时槽位一起变大，不会把数字裁掉。
 */
private val CHIP_TIME_SLOT = 34.dp
/**
 * 手指离开歌词后，隔多久恢复自动跟随（毫秒）。
 * 调大 = 松手后能自己翻看更久；调小 = 更快回到正在唱的那句。
 * 只在「用户真的拖过歌词」后生效，正常播放不受影响。
 */
private const val LYRIC_RESUME_DELAY_MS = 5_000L
/** 顶部留白占 LazyColumn 的 0 号 item，歌词行下标要整体 +1。 */
private const val LYRIC_TOP_GAP_ITEMS = 1
private const val LYRIC_TOP_GAP_KEY = "lyric_top_gap"
private const val LYRIC_BOTTOM_GAP_KEY = "lyric_bottom_gap"

/**
 * 当前行已经点亮了几个字。
 *
 * 优先用逐字时间戳（<mm:ss.xx>）：把每个 WordTiming 的文本映射到 line.text 的字符区间，
 * 播放位置越过该字时间戳就计入点亮；没有逐字时间戳时按 [progress] 均分降级。
 *
 * 返回 Int 而不是 BooleanArray 是这段优化的关键：这个函数 50ms 跑一次，
 * Int 不变 → derivedStateOf 不通知组合层；BooleanArray 每次都是新对象（equals 走引用比较）
 * → 每帧都会把下游拖进重组。
 */
private fun karaokeLitCount(line: LyricLine, effectiveMs: Long, progress: Float): Int {
    val n = line.text.length
    if (n == 0) return 0

    if (line.wordTimings.isNotEmpty()) {
        var cursor = 0
        var count = 0
        var matched = false
        for (wt in line.wordTimings) {
            if (wt.text.isEmpty()) continue
            val start = line.text.indexOf(wt.text, cursor)
            if (start < 0) continue
            matched = true
            val end = minOf(start + wt.text.length, n)
            if (effectiveMs >= wt.timeMs) count += end - start
            cursor = end
        }
        if (matched) return count.coerceIn(0, n)
    }

    // 降级：按行进度均分字符（对齐 Web 版 litCharCount）
    return ceil(progress * n).toInt().coerceIn(0, n)
}

/**
 * 按「点亮字数」重建逐字标记。
 *
 * 与 [karaokeLitCount] 用同一套推进顺序（按 wordTimings 文本顺序把字填进 line.text 的区间），
 * 所以只要字数一致，亮的字也一致 —— 不会出现「数字对了、亮的却是别的字」。
 */
private fun karaokeFlagsForCount(line: LyricLine, litCount: Int): BooleanArray {
    val n = line.text.length
    val flags = BooleanArray(n)
    if (litCount <= 0 || n == 0) return flags

    var remaining = minOf(litCount, n)
    if (line.wordTimings.isNotEmpty()) {
        var cursor = 0
        var matched = false
        for (wt in line.wordTimings) {
            if (remaining <= 0) break
            if (wt.text.isEmpty()) continue
            val start = line.text.indexOf(wt.text, cursor)
            if (start < 0) continue
            matched = true
            val end = minOf(start + wt.text.length, n)
            val take = minOf(remaining, end - start)
            for (i in start until start + take) flags[i] = true
            remaining -= take
            cursor = end
        }
        if (matched && remaining <= 0) return flags
    }

    // 没有逐字时间戳（或时间戳覆盖不到这么多字）：前 litCount 个字点亮
    for (i in 0 until minOf(litCount, n)) flags[i] = true
    return flags
}

/** 把逐字点亮标记合并成尽量少的 Span，构建 AnnotatedString（通常只有亮/暗两个 Span）。 */
private fun buildKaraokeText(
    text: String,
    flags: BooleanArray,
    litStyle: SpanStyle,
    dimStyle: SpanStyle,
): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < text.length) {
        val lit = flags[i]
        var j = i + 1
        while (j < text.length && flags[j] == lit) j++
        withStyle(if (lit) litStyle else dimStyle) {
            append(text, i, j)
        }
        i = j
    }
}
