package com.grape.player.ui.player

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.grape.player.BuildConfig
import com.grape.player.ui.AppViewModel
import com.grape.player.ui.library.SongInfoSheet
import com.grape.player.playback.PlayerController
import com.grape.player.data.MediaItemLite as MediaItem
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 播放器界面 —— 封面、进度、控制、模式、歌词。
 *
 * 封面 / 歌词用 HorizontalPager 左右滑动切换：播放页在左，歌词页在右，
 * 滑动进入歌词页、反向滑回播放页；顶部「词」按钮仍可作为点击快捷入口。
 * 音量调节交给系统控制中心 / 物理音量键，页面内不再提供音量条。
 *
 * 进出场动效（[pullAnim] 统一驱动）：
 *  - 收起 = 整页下移 + 轻微缩小 + 淡出，入口有四个且完全一致：
 *    封面页任意处下拉、歌词页顶栏（标题/艺术家信息条）下拉、顶栏返回键、系统返回键；
 *  - 歌词列表本身不参与收起：滚到顶再下拉只是正常滚动，避免回看前几行时误退出；
 *  - 展开 = 收起动效的倒放，进页时从下方滑上来，和「滑下去」形成对称的过渡。
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
    onBack: () -> Unit,
    onGoto: (String, String) -> Unit = { _, _ -> },
    vm: AppViewModel = viewModel(),
) {
    // 只订阅「换歌 / 换队列 / 播停 / 换模式 / 时长」这几件事，**不读 positionMs**。
    // 以前是 state.collectAsState() 直接收整个 PlayerState：全局 250ms 的进度刷新会让
    // PlayerScreen 整个函数体失效，连带 HorizontalPager 两页、Coil 封面、LyricViewer
    // 一起重组（一秒四次）。进度相关的读取全部下沉到 ProgressSection，
    // 页面主体只在真正换状态时重组。
    val pageState by remember(vm) {
        vm.player.state
            .map {
                PlayerPageState(
                    currentIndex = it.currentIndex,
                    queueVersion = it.queueVersion,
                    isPlaying = it.isPlaying,
                    playMode = it.playMode,
                    durationMs = it.durationMs,
                )
            }
            .distinctUntilChanged()
    }.collectAsState(initial = PlayerPageState.of(vm.player.state.value))

    val current = vm.player.currentItem()
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(initialPage = 0) { 2 }
    // 切歌时清掉拖动/回跳残留（进度条内部的 pendingSeek 也以它为准），
    // 避免沿用上一首的位置
    val trackKey = pageState.currentIndex
    var showQueue by remember { mutableStateOf(false) }
    // 队列弹层的两个出口：用 remember 固定成同一个实例，保证 QueueSheet 能跳过重组
    val dismissQueue: () -> Unit = remember { { showQueue = false } }
    val pickQueueItem: (Int) -> Unit = remember {
        { idx: Int ->
            vm.player.seekToIndex(idx)
            showQueue = false
        }
    }
    var showInfoSheet by remember { mutableStateOf(false) }
    var showLyricSettings by remember { mutableStateOf(false) }
    // 「词」弹层保存偏移后 +1，让歌词视图重新加载偏移
    var offsetReload by remember { mutableStateOf(0) }
    // 封面大小与竖直位置（设置页外观里可调；默认 240dp 居中）
    val coverSizeSetting by vm.settings.playerCoverSize.collectAsState(initial = 240)
    val coverYSetting by vm.settings.playerCoverY.collectAsState(initial = 0.5f)
    // 标题/艺术家水平居中（默认开；关则左对齐）
    val titleCenter by vm.settings.playerTitleCenter.collectAsState(initial = true)
    val titleAlign = if (titleCenter) androidx.compose.ui.text.style.TextAlign.Center
    else androidx.compose.ui.text.style.TextAlign.Start
    // 歌词全屏显示：歌词页隐藏进度条与播放按钮（设置默认关）
    val lyricFullscreen by vm.settings.lyricFullscreen.collectAsState(initial = false)
    val onLyricPage = pagerState.currentPage == 1
    val hideBottomControls = lyricFullscreen && onLyricPage

    // —— 收起 / 展开动效 ——
    // 一个 Animatable（pullAnim）同时承担「下拉跟手位移」和「进出场动画」：
    //   0      = 正常显示
    //   exitPx = 完全收起（整页下移 55% 屏高、缩小 10%、淡出到透明）
    // 收起动效对三种入口一致：下拉手势、顶栏返回键、系统返回键都走 animateOut()，
    // 页面先滑下去再真正 pop —— 不会出现「页面瞬间消失」的硬切。
    // 展开动效是它的倒放：进页时从 exitPx 动画回 0，整页从下方滑上来并放大到原位。
    val exitPx = with(LocalDensity.current) {
        LocalConfiguration.current.screenHeightDp.dp.toPx()
    } * 0.55f
    // 收起阈值按「内容位移」算（手指实际要走 ~1/0.85，约 104dp）：和屏高解耦，
    // 大小屏手感一致；也远小于 exitPx，所以只走完不到三成行程就能收起
    val dragTriggerPx = with(LocalDensity.current) { 88.dp.toPx() }
    // 封面区上滑多少才算「要打开更多弹层」：给得比 touch slop 大一些，
    // 免得手滑（想左右翻页时的轻微竖向位移）就把弹层顶出来
    val swipeUpPx = with(LocalDensity.current) { 56.dp.toPx() }
    val pullAnim = remember { Animatable(exitPx) }
    // 收起动画进行中/已触发：挡住重复触发（连按返回、手势里再滑一下）
    val closing = remember { mutableStateOf(false) }
    // 有手指按在页面上（供看门狗区分「按住不动」和「动画死了」）
    var pointerDown by remember { mutableStateOf(false) }

    suspend fun animateOut() {
        if (closing.value) return
        closing.value = true
        val t0 = android.os.SystemClock.elapsedRealtime()
        animLog { "animateOut start v=${pullAnim.value.toInt()}" }
        // withTimeoutOrNull 兜底：万一动画协程被意外打断/帧时钟停摆，1.5s 后强制定格收起，
        // 保证 onBack 一定被调用 —— 页面绝不会悬在半透明中间态
        val finished = withTimeoutOrNull(1500) {
            pullAnim.animateTo(
                targetValue = exitPx,
                animationSpec = tween(durationMillis = 240, easing = FastOutSlowInEasing),
            )
            true
        }
        if (finished == null) {
            animLogW { "animateTo timed out, force snap" }
            pullAnim.snapTo(exitPx)
        }
        animLog { "animateTo took ${android.os.SystemClock.elapsedRealtime() - t0}ms (期望≈240)" }
        onBack()
        animLog { "onBack done +${android.os.SystemClock.elapsedRealtime() - t0}ms" }
    }

    // 松手定格：过阈值就收起，否则弹回原位
    suspend fun settlePull() {
        if (closing.value) return
        // 没被拉过（点在按钮上、轻触后松手等）：直接返回，不白播一个归零动画
        if (pullAnim.value <= 0.5f) return
        if (pullAnim.value > dragTriggerPx) {
            animateOut()
        } else {
            val t0 = android.os.SystemClock.elapsedRealtime()
            animLog { "settlePull spring start v=${pullAnim.value.toInt()}" }
            pullAnim.animateTo(
                targetValue = 0f,
                animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
            )
            animLog { "settlePull took ${android.os.SystemClock.elapsedRealtime() - t0}ms" }
        }
    }

    // 拖动结束的统一判定：封面页、歌词页顶栏两个拖动区共用
    suspend fun onPullDragStopped(velocity: Float) {
        animLog { "dragStopped velocity=$velocity v=${pullAnim.value.toInt()} trigger=$dragTriggerPx" }
        // 甩得够快就不等位移阈值（过半就没必要再拉回去）
        if (velocity > 1800f && pullAnim.value > dragTriggerPx * 0.5f) animateOut()
        else settlePull()
    }

    // 进页展开：从收起态滑上来（等价于 animateOut 的倒放）
    LaunchedEffect(Unit) {
        val t0 = android.os.SystemClock.elapsedRealtime()
        pullAnim.animateTo(
            targetValue = 0f,
            animationSpec = tween(durationMillis = 320, easing = FastOutSlowInEasing),
        )
        animLog { "enter anim took ${android.os.SystemClock.elapsedRealtime() - t0}ms (期望≈320)" }
    }

    // 系统返回键也走同一套收起动效（否则 NavHost 直接 pop，是硬切）
    BackHandler(enabled = true) { scope.launch { animateOut() } }

    // 下拉跟手：snapTo 会被 Animatable 内部的 MutatorMutex 排序，天然打断正在跑的展开/回弹动画，
    // 两者不会互相覆盖（分别用独立协程写同一个值才会打架）
    val dragState = rememberDraggableState { delta ->
        if (!closing.value) {
            scope.launch {
                // 0.85 阻尼：跟手但略慢于手指，不会一拉就飞；上滑（delta<0）自然停在 0
                pullAnim.snapTo((pullAnim.value + delta * 0.85f).coerceIn(0f, exitPx))
            }
        }
    }

    // 松手出口：scope.launch 包一层 —— 收起/回弹动画绝不在 draggable 的手势协程里跑。
    // 手势协程随时可能被新触摸或系统取消（取消 = 动画死在半路 + closing 已置 true +
    // onBack 永远不执行 = 页面永久悬在半透明中间态，也就是「卡死」）。
    // rememberCoroutineScope 只在页面真正移除时才取消，动画必然跑完。
    fun onPullEnded(velocity: Float) {
        scope.launch { onPullDragStopped(velocity) }
    }

    // 时钟级看门狗（delay 实现，原来用 withFrameNanos 每帧轮询）：
    // withFrameNanos 版本恰好在「收起播放页、覆盖层被移除」的时刻被取消，若取消落在
    // Compose AndroidUiDispatcher 帧回调分发的间隙，会把整个 Recomposer 的帧调度链打断——
    // 实测症状：收起后 StateFlow 照常发新值（poll 日志 idx 已更新），但迷你栏等所有 UI 的
    // 重组再也不执行（compose 探针不再打印、gfxinfo 零出帧），整个界面冻结，直到下一次
    // 输入才恢复。delay() 走 Handler 时钟、不参与帧回调链，同样能发现「值 500ms 不动」，
    // 兜底作用不变，且取消它不会伤及帧调度。
    LaunchedEffect(Unit) {
        var lastValue = -1f
        var lastChangeMs = 0L
        while (isActive) {
            delay(100)
            val now = android.os.SystemClock.elapsedRealtime()
            val v = pullAnim.value
            if (v != lastValue) {
                lastValue = v
                lastChangeMs = now
            } else if (v > 1f && now - lastChangeMs > 500) {
                lastChangeMs = now
                if (closing.value) {
                    animLogW { "watchdog: closing 动画死了, 强制收起" }
                    pullAnim.snapTo(exitPx)
                    onBack()
                } else if (!pointerDown) {
                    animLogW { "watchdog: 页面悬空在 v=${v.toInt()}, 弹回" }
                    settlePull()
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            // 位移/缩放/淡出全在绘制阶段做，不触发重新布局；
            // 纵向手势与 HorizontalPager 的横向手势正交，互不抢
            .graphicsLayer {
                val t = (pullAnim.value / exitPx).coerceIn(0f, 1f)
                translationY = pullAnim.value
                val s = 1f - 0.10f * t
                scaleX = s
                scaleY = s
                // alpha 一旦 < 1，整页（封面 + Pager + 歌词）每帧都要走离屏合成层，
                // 这是低端机上收起卡顿的大头。所以前 55% 行程保持完全不透明（纯位移/缩放，
                // 绘制阶段零离屏），尾段才线性淡出 —— 页面大部分行程已经被下层内容「顶」住，
                // 晚一点淡出视觉上几乎没差别，离屏时间却缩短一半以上
                alpha = ((1f - t) / 0.45f).coerceIn(0f, 1f)
            }
            // 页面背景跟着一起位移/淡出（放在 graphicsLayer 之后才会被同一个 layer 变换），
            // 否则滑动时下移的那一半是空的
            .background(MaterialTheme.colorScheme.surface)
            .draggable(
                orientation = Orientation.Vertical,
                state = dragState,
                onDragStopped = { velocity -> onPullEnded(velocity) },
            )
            // 手势存活跟踪：记录「有手指按在页面上」的时间段，供看门狗区分
            // 「按住不动」（正常）和「动画死了值不动」（异常，需恢复）
            .pointerInput(Unit) {
                awaitEachGesture {
                    try {
                        awaitFirstDown(requireUnconsumed = false)
                        pointerDown = true
                        // 等所有手指抬起；手势被系统取消时 awaitEachGesture 会取消本块，
                        // finally 保证 pointerDown 一定复位
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.changes.all { !it.pressed }) break
                        }
                    } finally {
                        pointerDown = false
                    }
                }
            }
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { scope.launch { animateOut() } }) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
            }
            if (onLyricPage) {
                // 歌词页：标题/艺术家显示在顶栏，歌词视图向下延伸。
                // 这里**不看**「标题/艺术家居中显示」那个设置：歌词页的顶栏是左对齐的信息条，
                // 跟着居中的话长歌名会从中间向两边溢，跟左边返回键、右边「词」/⋮ 挤在一起很难看。
                Column(
                    modifier = Modifier
                        .weight(1f)
                        // 触摸区撑到和两侧 IconButton 同高（48dp），不然单行歌名时只剩二十几 dp 能拖
                        .heightIn(min = 48.dp)
                        // 歌词页的收起入口：按住这条标题/艺术家往下拖。
                        // 这里是歌词页**唯一**的收起手势 —— 歌词列表滚到顶部后再下拉不会退出，
                        // 免得想回看前面几行歌词时被误判成关闭；列表区域的手势仍全归 LazyColumn 滚动。
                        .draggable(
                            orientation = Orientation.Vertical,
                            state = dragState,
                            onDragStopped = { velocity -> onPullEnded(velocity) },
                        ),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        current?.title ?: "未播放",
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Start,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (!current?.artist.isNullOrBlank()) {
                        Text(
                            current?.artist.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Start,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            } else {
                Spacer(Modifier.weight(1f))
            }
            // 「词」按钮只在歌词页显示；点击打开歌词设置弹层
            if (onLyricPage) {
                IconButton(onClick = { showLyricSettings = true }) {
                    Text("词", style = MaterialTheme.typography.labelLarge)
                }
            }
            IconButton(onClick = { showInfoSheet = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = "更多信息")
            }
        }

        Spacer(Modifier.height(16.dp))

        // 封面页 ↔ 歌词页：左右滑动切换（播放页在左，歌词页在右）。
        // 标题/艺术家放在封面页内部：手势生效范围从封面区一直扩到进度条上方整块，
        // 在标题上左滑也能进歌词页（歌词页的标题上移到顶栏，这里的标题随页面滑走）
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) { page ->
            if (page == 0) {
                // 收起手势与进出场动效都挂在最外层 Column 上（整页一起动），这里保持纯内容。
                // 额外的上滑手势：从进度条上方到整个封面区（含标题）上滑 → 打开「更多」弹层。
                // 只消费上滑，下拉一律放行 —— 页面级的下拉收起不能被这里抢走。
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(swipeUpPx) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                var dy = 0f
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                    if (!change.pressed) break
                                    dy += change.position.y - change.previousPosition.y
                                    if (dy <= -swipeUpPx) {
                                        change.consume()
                                        showInfoSheet = true
                                        break
                                    }
                                    // 明显往下走了 = 用户想下拉收起页面，这里收手，别再拦
                                    if (dy > swipeUpPx * 0.5f) break
                                }
                            }
                        },
                ) {
                    // 封面区域：按设置页配置的大小与竖直位置摆放（0=顶部 0.5=居中 1=底部）
                    // weight(1f) 把封面盒压到标题以上，coverY 的比例数学和原来一致
                    BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        val cover = coverSizeSetting.coerceIn(120, 400).dp
                        val topPad = (((maxHeight - cover) * coverYSetting.coerceIn(0f, 1f)).coerceAtLeast(0.dp))
                        val coverUrl = current?.coverUrl
                        val coverModifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = topPad)
                            .size(cover)
                        // 点封面 = 打开「更多」弹层（和右上角 ⋮ 同一个弹窗）
                        val openInfo = { showInfoSheet = true }
                        if (!coverUrl.isNullOrBlank()) {
                            coil.compose.AsyncImage(
                                model = coverUrl,
                                contentDescription = "封面",
                                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                modifier = coverModifier
                                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
                                    .clickable(onClick = openInfo),
                            )
                        } else {
                            Text(
                                current?.title?.take(1) ?: "♪",
                                style = MaterialTheme.typography.displayLarge,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = coverModifier
                                    .wrapContentSize(Alignment.Center)
                                    .clickable(onClick = openInfo),
                            )
                        }
                    }

                    Spacer(Modifier.height(16.dp))

                    // 封面页的标题/艺术家（歌词页已上移到顶栏）
                    Text(
                        current?.title ?: "未播放",
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1,
                        textAlign = titleAlign,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        current?.artist ?: "",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = titleAlign,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            } else {
                LyricViewer(
                    vm = vm,
                    modifier = Modifier.fillMaxSize(),
                    offsetReloadKey = offsetReload,
                    // 收起动画期间冻结歌词更新：50ms 高频重组 + 自动滚动动画会和
                    // 整页下移动画抢主线程帧，是收起卡顿的主要来源
                    frozen = closing.value,
                )
            }
        }

        // 进度条与播放控制：歌词页在全屏模式下隐藏（封面页始终显示）
        if (!hideBottomControls) {
            Spacer(Modifier.height(if (onLyricPage) 0.dp else 16.dp))

            // 进度条整块独立：只有它读 positionMs，跟着 250ms 的轮询重组。
            // 留在本函数体里的话，整页（Pager + 封面 + 歌词）都会跟着一秒四次重跑。
            ProgressSection(vm = vm, trackKey = trackKey, modifier = Modifier.fillMaxWidth())

            // 缓冲停滞提示：独立小组件 + derivedStateOf，只在提示翻转时重组，
            // 250ms 的进度刷新不会带动这里（也不因它出现而重跑整页）
            BufferStallHint(vm = vm, modifier = Modifier.fillMaxWidth())

            Spacer(Modifier.height(8.dp))

            // 控制按钮
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = {
                    val next = when (pageState.playMode) {
                        PlayerController.PlayMode.SEQUENCE -> PlayerController.PlayMode.LOOP
                        PlayerController.PlayMode.LOOP -> PlayerController.PlayMode.RANDOM
                        PlayerController.PlayMode.RANDOM -> PlayerController.PlayMode.SEQUENCE
                    }
                    vm.setPlayMode(next)
                }) {
                    Icon(
                        when (pageState.playMode) {
                            PlayerController.PlayMode.SEQUENCE -> Icons.Filled.Repeat
                            PlayerController.PlayMode.LOOP -> Icons.Filled.RepeatOne
                            PlayerController.PlayMode.RANDOM -> Icons.Filled.Shuffle
                        },
                        contentDescription = "播放模式",
                    )
                }
                IconButton(onClick = { vm.player.previous() }) {
                    Icon(Icons.Filled.SkipPrevious, contentDescription = "上一首", modifier = Modifier.size(36.dp))
                }
                IconButton(onClick = { vm.player.togglePlayPause() }) {
                    Icon(
                        if (pageState.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = "播放/暂停",
                        modifier = Modifier.size(48.dp),
                    )
                }
                IconButton(onClick = { vm.player.next() }) {
                    Icon(Icons.Filled.SkipNext, contentDescription = "下一首", modifier = Modifier.size(36.dp))
                }
                IconButton(onClick = { showQueue = true }) {
                    Icon(Icons.Filled.QueueMusic, contentDescription = "播放列表", modifier = Modifier.size(30.dp))
                }
            }
        }
    }

    // 播放列表弹层（队列）。整块抽成独立组件 QueueSheet：它只吃 QueueSnapshot（@Immutable），
    // 播放进度每 250ms 刷新一次时，这一千多行的列表不会跟着整张重组。
    if (showQueue) {
        // queueVersion 每次换队列/删曲/调序都会 +1，靠它让弹层拿到最新快照
        val queue = remember(showQueue, pageState.queueVersion) { vm.player.queue() }
        QueueSheet(
            snapshot = remember(queue, pageState.currentIndex) {
                QueueSnapshot(items = queue, currentIndex = pageState.currentIndex)
            },
            // 两个 lambda 用 remember 固定住实例：每次重组都新建 lambda 会让 QueueSheet
            // 无法跳过重组（参数 equals 不成立），前面那条"只吃稳定快照"就白费了
            onDismiss = dismissQueue,
            onPick = pickQueueItem,
        )
    }

    // 歌曲信息弹层（竖三点入口）
    if (showInfoSheet) {
        current?.let { item ->
            SongInfoSheet(
                item = item,
                onDismiss = { showInfoSheet = false },
                onEdit = {
                    showInfoSheet = false
                    vm.openMetaEdit(item.url)
                },
                onGoto = { type, value ->
                    showInfoSheet = false
                    onGoto(type, value)
                },
            )
        }
    }

    // 歌词设置弹层（「词」按钮入口）：字号 / 对齐 / 卡拉OK / 全屏 / 时间偏移
    if (showLyricSettings) {
        val lyricSizeSetting by vm.settings.lyricFontSize.collectAsState(initial = 16)
        val lyricAlignCenter by vm.settings.lyricAlignCenter.collectAsState(initial = true)
        val lyricKaraoke by vm.settings.lyricKaraoke.collectAsState(initial = true)
        val lyricFull by vm.settings.lyricFullscreen.collectAsState(initial = false)
        val lyricTranslation by vm.settings.lyricTranslation.collectAsState(initial = true)
        var sizeLocal by remember { mutableStateOf<Float?>(null) }
        var offsetLocal by remember { mutableStateOf<Long?>(null) }

        suspend fun reloadOffset(): Long {
            val url = current?.url ?: return 0L
            return vm.lyricOffset(url)
        }

        LaunchedEffect(current?.url) { offsetLocal = reloadOffset() }

        ModalBottomSheet(onDismissRequest = { showLyricSettings = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 24.dp),
            ) {
                Text("歌词设置", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(12.dp))

                // 字号
                val sizeShown = sizeLocal ?: lyricSizeSetting.toFloat()
                Text("字体大小：${sizeShown.toInt()} sp", style = MaterialTheme.typography.bodyMedium)
                Slider(
                    value = sizeShown,
                    valueRange = 12f..40f,
                    onValueChange = { sizeLocal = it },
                    onValueChangeFinished = {
                        scope.launch { vm.settings.setLyricFontSize(sizeShown.toInt()) }
                    },
                )
                Spacer(Modifier.height(4.dp))

                // 对齐
                SwitchRow("歌词居中对齐", "关闭后歌词靠左对齐", lyricAlignCenter) { enabled ->
                    scope.launch { vm.settings.setLyricAlignCenter(enabled) }
                }
                // 译文行
                SwitchRow("显示翻译", "双语歌词下方显示译文行", lyricTranslation) { enabled ->
                    scope.launch { vm.settings.setLyricTranslation(enabled) }
                }
                // 卡拉OK
                SwitchRow("卡拉OK扫光", "当前行逐字点亮高亮", lyricKaraoke) { enabled ->
                    scope.launch { vm.settings.setLyricKaraoke(enabled) }
                }
                // 全屏
                SwitchRow("歌词全屏显示", "歌词页隐藏进度条与播放按钮", lyricFull) { enabled ->
                    scope.launch { vm.settings.setLyricFullscreen(enabled) }
                }

                Spacer(Modifier.height(8.dp))
                // 时间偏移（滑块）：拖动即时生效，松手保存到当前歌曲
                val off = offsetLocal ?: 0L
                Text(
                    "歌词偏移：" + if (off == 0L) "0ms" else "%+.1fs".format(off / 1000.0),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Slider(
                    value = off.toFloat(),
                    valueRange = -5000f..5000f,
                    steps = 99, // 100ms 一档
                    onValueChange = {
                        offsetLocal = it.toLong()
                        // 手指没松开就先把偏移推给歌词视图，做到「拖到哪歌词跟到哪」
                        current?.url?.let { url -> vm.previewLyricOffset(url, it.toLong()) }
                    },
                    onValueChangeFinished = {
                        val url = current?.url
                        if (url != null) {
                            scope.launch { vm.saveLyricOffset(url, offsetLocal ?: 0L) }
                            offsetReload++
                        }
                    },
                )
                Text(
                    "歌词比音乐快就往负方向调，慢就往正方向调；仅对当前歌曲生效",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * 播放页主体关心的那一份状态 —— **不含 positionMs**。
 *
 * 这是「整页跟着 250ms 重组」的解法：page 只订阅换歌 / 换队列 / 播停 / 换模式 / 时长，
 * 进度交给 [ProgressSection] 单独订阅。PlayerState 里只有 positionMs 是高频变化的，
 * 把它从这份快照里摘掉之后，[distinctUntilChanged] 能把 4 次/秒的刷新全部吃掉。
 */
private data class PlayerPageState(
    val currentIndex: Int,
    val queueVersion: Int,
    val isPlaying: Boolean,
    val playMode: PlayerController.PlayMode,
    val durationMs: Long,
) {
    companion object {
        fun of(s: PlayerController.PlayerState) = PlayerPageState(
            currentIndex = s.currentIndex,
            queueVersion = s.queueVersion,
            isPlaying = s.isPlaying,
            playMode = s.playMode,
            durationMs = s.durationMs,
        )
    }
}

/**
 * 缓冲停滞提示 —— 播放页里唯一读取 bufferStalled 的地方。
 *
 * derivedStateOf 只对布尔翻转敏感：250ms 的进度刷新不重组这里，
 * 提示出现/消失也不影响进度条、封面、歌词各自的重组范围。
 */
@Composable
private fun BufferStallHint(
    vm: AppViewModel,
    modifier: Modifier = Modifier,
) {
    val state by vm.player.state.collectAsState()
    val stalled by remember { derivedStateOf { state.bufferStalled } }
    if (stalled) {
        androidx.compose.foundation.layout.Row(
            modifier = modifier,
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "网络缓慢，正在缓冲…",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/**
 * 进度条 + 左右时间文本 —— 播放页里唯一读取 positionMs 的地方。
 *
 * 单独抽成 composable 的目的：让 250ms 的重组范围收缩到 Slider 与两个 Text，
 * 而不是整个播放页（HorizontalPager 两页、Coil 封面、LyricViewer 都在外面）。
 */
@Composable
private fun ProgressSection(
    vm: AppViewModel,
    /** 换歌时清掉拖动 / 回跳残留（值变化即重置），避免沿用上一首的位置 */
    trackKey: Int,
    modifier: Modifier = Modifier,
) {
    val playState by vm.player.state.collectAsState()
    var seeking by remember { mutableStateOf(false) }
    var seekValue by remember { mutableStateOf(0f) }
    // 刚 seek 完、播放器位置还没追上的这段时间里，进度条按目标值渲染（避免回跳）
    var pendingSeekMs by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(trackKey) {
        pendingSeekMs = null
        seeking = false
    }

    val duration = playState.durationMs.coerceAtLeast(1)

    // 松手后先按「目标位置」显示，等播放器真实位置追上再交还给轮询值。
    // 否则 seek 是异步的，松手瞬间会被 250ms 前的旧 positionMs 拉回去，看起来像一段残余回弹动画。
    LaunchedEffect(playState.positionMs, pendingSeekMs) {
        val target = pendingSeekMs ?: return@LaunchedEffect
        if (kotlin.math.abs(playState.positionMs - target) < 1200) pendingSeekMs = null
    }
    LaunchedEffect(pendingSeekMs) {
        val target = pendingSeekMs ?: return@LaunchedEffect
        delay(2500)   // 兜底：seek 迟迟不生效时也别一直卡在目标值
        if (pendingSeekMs == target) pendingSeekMs = null
    }

    val shownMs = when {
        seeking -> (seekValue * duration).toLong()
        pendingSeekMs != null -> pendingSeekMs!!
        else -> playState.positionMs
    }
    val shownFraction = (shownMs.toFloat() / duration).coerceIn(0f, 1f)

    Slider(
        value = shownFraction,
        onValueChange = {
            seeking = true
            seekValue = it
        },
        onValueChangeFinished = {
            val target = (seekValue * duration).toLong()
            vm.player.seekTo(target)
            pendingSeekMs = target
            seeking = false
        },
        modifier = modifier,
    )
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(formatTime(shownMs), style = MaterialTheme.typography.labelSmall)
        Text(formatTime(duration), style = MaterialTheme.typography.labelSmall)
    }
}

/**
 * 队列弹层的数据快照（队列 + 当前曲序号）。
 *
 * 标 [Immutable] 是为了让 [QueueSheet] 真正能跳过重组：`List<MediaItem>` 这个类型本身
 * 在 Compose 眼里是不稳定的，直接当参数传等于关掉"跳过"——播放中每 250ms 一次的进度
 * 刷新就会把整张列表连同可见行全部重组一遍（上千首的队列是实打实的掉帧）。
 */
@Immutable
private data class QueueSnapshot(
    val items: List<MediaItem>,
    val currentIndex: Int,
)

/**
 * 播放列表（队列）弹层。
 *
 * 只在 showQueue = true 时进入组合，所以内部的 LaunchedEffect(Unit) 等价于"打开时跑一次"。
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun QueueSheet(
    snapshot: QueueSnapshot,
    onDismiss: () -> Unit,
    onPick: (Int) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val queueListState = rememberLazyListState()
    // 用户是否正在拖这个列表。用它而不是 isScrollInProgress：后者连上面那次
    // 程序化的 scrollToItem 也算「正在滚动」，会把自己刚滚完的定位误判成用户操作。
    val dragging by queueListState.interactionSource.collectIsDraggedAsState()

    // 高度固定，不随拖动变化：
    //  1) skipPartiallyExpanded = true —— 不再有"半屏态"，一打开就是完整内容高度（否则
    //     默认会先停在半屏，内容被切掉一截，手指往上一拖又变高）；
    //  2) dragHandle = null —— 去掉那条可拖的横杠，避免误拖；
    //  3) 列表自己吃掉竖向拖拽余量（见下面的 blockSheetDrag）—— 滚到顶/底之后剩下的手势
    //     本来会传给弹层，把弹层拖高拖矮，现在被列表吞掉，列表视口高度不再变化。
    // 关闭只走左上角 × 或点遮罩。
    val sheetState = androidx.compose.material3.rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
    )
    val blockSheetDrag = remember {
        object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
            override fun onPostScroll(
                consumed: androidx.compose.ui.geometry.Offset,
                available: androidx.compose.ui.geometry.Offset,
                source: androidx.compose.ui.input.nestedscroll.NestedScrollSource,
            ): androidx.compose.ui.geometry.Offset = available
        }
    }
    // 关闭统一走 hide() 的下滑动画，动画结束再真正移除弹层
    val close: () -> Unit = {
        scope.launch { sheetState.hide() }.invokeOnCompletion {
            if (!sheetState.isVisible) onDismiss()
        }
    }

    // 打开时把当前播放曲目滚到列表中部 —— 两处"什么时候动列表"的坑：
    //  1) 原来是「先 scrollToItem(current) → delay(50) → animateScrollBy(-半屏)」。
    //     animateScrollBy 走 MutatePriority.Default，用户在这期间按下手指开始拖（UserInput）
    //     时它不会被取消，而是排队等松手那一刻才执行 —— 表现就是"滑了一下，松手列表自己
    //     往回窜半屏"。现在改成一次 scrollToItem(index, -半屏)，直接落到目标位置，不播动画。
    //  2) 原来 key 里有 state.currentIndex：一首播完切下一首就重跑一遍，正在翻列表时会被
    //     拽回新歌那一行。现在只在打开时跑一次。
    LaunchedEffect(Unit) {
        val items = snapshot.items
        if (items.isEmpty()) return@LaunchedEffect
        val current = snapshot.currentIndex.coerceIn(0, items.lastIndex)
        // 先保证第一帧就能看到当前曲（后面拿不到行高时就停在这个位置）
        queueListState.scrollToItem(current)
        // 行高与视口高度要等测量完才知道：等一两帧，最多 300ms
        val metrics = withTimeoutOrNull(300) {
            snapshotFlow {
                val info = queueListState.layoutInfo
                val itemH = info.visibleItemsInfo.firstOrNull { it.index == current }?.size
                    ?: info.visibleItemsInfo.lastOrNull()?.size ?: 0
                info.viewportSize.height to itemH
            }.first { it.first > 0 && it.second > 0 }
        } ?: return@LaunchedEffect
        // 用户已经上手了：别抢，停在他滑到的位置
        if (dragging) return@LaunchedEffect
        val half = ((metrics.first - metrics.second) / 2).coerceAtLeast(0)
        if (half > 0) queueListState.scrollToItem(current, -half)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = null,
    ) {
        // 整块内容自己吃住竖向拖动：标题栏那块没有可滚动的子节点，手势本来会直达弹层，
        // 一长按下滑就把弹层往回拽（高度塌一截）。列表内部的滚动由 LazyColumn 先消费，
        // 这里拿不到未消费的手势，所以不影响列表滑动。
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .pointerInput(Unit) {
                    detectVerticalDragGestures { change, _ -> change.consume() }
                },
        ) {
            // 标题栏：标题在左、叉号在右。
            // start 用 20dp —— 和下面歌曲行的 horizontal 内边距完全一致，标题文字正好压在
            // 序号那一列上；end 只用 4dp —— IconButton 自身有 48dp 触摸区，图标居中后离
            // 屏幕右边缘还有 16dp，不会贴边。
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "播放列表 · ${snapshot.items.size} 首",
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = close) {
                    Icon(Icons.Filled.Close, contentDescription = "关闭播放列表")
                }
            }
            LazyColumn(
                state = queueListState,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(440.dp)
                    .nestedScroll(blockSheetDrag),
            ) {
                // key 用 url：队列整体换掉 / 整体调序时行不会错位复用
                itemsIndexed(snapshot.items, key = { _, it -> it.url }) { idx, it ->
                    val isCurrent = idx == snapshot.currentIndex
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(idx) }
                            .background(
                                if (isCurrent) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                                else MaterialTheme.colorScheme.surface,
                            )
                            // 垂直留白收到 6dp：队列弹层只用来"看顺序 + 点着跳"，不需要卡片式间距
                            .padding(horizontal = 20.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // 序号列固定 40dp：labelMedium 下四位数（3999）在原来的 28dp 里会被裁掉
                        // 最后一位，显示成"398-"；40dp 给四位数留了余量。列宽固定是为了让
                        // 所有歌名的左边缘始终对齐。
                        Text(
                            "${idx + 1}",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (isCurrent) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(width = 40.dp, height = 20.dp),
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                it.title,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                color = if (isCurrent) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface,
                            )
                            if (it.artist.isNotBlank()) {
                                Text(
                                    it.artist,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                )
                            }
                        }
                        if (isCurrent) {
                            Text(
                                "正在播放",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 弹层里的开关行：label + 说明 + Switch */
@Composable
private fun SwitchRow(
    label: String,
    desc: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(
                desc,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

private fun formatTime(ms: Long): String {
    val totalSec = ms / 1000
    val m = totalSec / 60
    val s = totalSec % 60
    // 字符串拼接替代 "%d:%02d".format()：这里每秒被调 ~8 次，Formatter 每次都是纯分配
    return if (s < 10) "$m:0$s" else "$m:$s"
}

/**
 * 进出场动画的诊断日志 —— 只在 debug 版输出。
 *
 * 用 lambda 而不是直接传字符串：Log.i 的参数在调用前就求值了，release 包里
 * 这些 `SystemClock.elapsedRealtime() - t0` 和字符串拼接会照常执行，只是输出被丢弃。
 * 包成 inline + lambda 之后，release 版连拼接都不做。
 */
private inline fun animLog(msg: () -> String) {
    if (BuildConfig.DEBUG) android.util.Log.i("PlayerAnim", msg())
}

private inline fun animLogW(msg: () -> String) {
    if (BuildConfig.DEBUG) android.util.Log.w("PlayerAnim", msg())
}
