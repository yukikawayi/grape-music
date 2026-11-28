package com.grape.player.ui

import android.app.Application
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.grape.player.data.MediaItem
import com.grape.player.data.Repository
import com.grape.player.data.WebDavSource
import com.grape.player.network.LibraryScanner
import com.grape.player.network.MetadataScraper
import com.grape.player.playback.LoudnessStore
import com.grape.player.playback.PlayerController
import com.grape.player.util.LyricQuality
import com.grape.player.util.LyricSource
import com.grape.player.util.toHex
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * 应用共享 ViewModel —— 持有 Repository、Scanner、PlayerController，供各页面共用。
 *
 * [androidx.compose.runtime.Stable] 是这里刻意加的：Compose 默认把「有公开 var 属性、
 * 又没标 @Immutable/@Stable 的类」当成 unstable，于是 `LyricViewer(vm = vm, ...)`、
 * `MiniPlayerBar(vm = vm, ...)` 这类调用永远无法跳过重组 —— 父级每 4 次/秒重组，
 * 它们就被拖下水 4 次/秒。VM 的公开属性都是 state flow / 回调这类「引用一旦赋值就不再变」
 * 的字段，加 @Stable 是安全的。
 */
@androidx.compose.runtime.Stable
class AppViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as com.grape.player.WebDAVPlayerApp
    val repo = Repository(app.database)
    val settings = app.settings
    val player = PlayerController(application)

    /**
     * 音乐库各视图的滚动状态，放在 VM 里跨导航保留，每个视图一份（按视图 key 分配）。
     * 不用 rememberLazyListState（saveable）的原因：targetSdk 36 上预测式返回默认开启，
     * navigation-compose 2.8.9 在返回动画中对前一页的 saveable 恢复不可靠（已知问题，
     * 2.9 才修稳），表现为从播放页返回时列表位置丢失回到顶部。VM 状态横跨所有导航
     * 路径天然存活，不依赖任何恢复机制。
     * 按视图分开：专辑页/艺术家页各有各的位置，切换视图互不覆盖；应用进程退出即重置。
     */
    private val libraryListStates = mutableMapOf<String, LazyListState>()
    private val libraryGridStates = mutableMapOf<String, LazyGridState>()

    fun libraryListState(viewKey: String): LazyListState =
        libraryListStates.getOrPut(viewKey) { LazyListState() }

    fun libraryGridState(viewKey: String): LazyGridState =
        libraryGridStates.getOrPut(viewKey) { LazyGridState() }

    /**
     * 搜索页上次的搜索词（空串 = 没搜过）。
     *
     * 为什么放 VM：搜索页是底部 tab 的一个路由，切到别的 tab 时这个 destination 会被弹掉，
     * 页面内的 `remember` / `rememberSaveable` 全部丢失 —— 表现就是「切走再回来，搜索页空白」。
     * 结果本身是由搜索词 + 全库实时算出来的，所以**记住词就等于记住了结果**，
     * 列表滚动位置另用 [libraryListState]("search") 保存。
     * 只在进程存活期间有效（重启后清空），这是刻意的：搜索是临时行为，不该写进设置文件。
     */
    private val _searchQuery = androidx.compose.runtime.mutableStateOf("")

    /** 当前搜索词（读它是快照读，Compose 里用可直接触发重组） */
    val searchQuery: String get() = _searchQuery.value

    /** 改搜索词（唯一写入口） */
    fun setSearchQuery(value: String) {
        _searchQuery.value = value
    }

    /**
     * 各视图的排序（viewKey → SortBy.name），与滚动状态同源：跨导航/旋转保留，进程退出即重置
     * （重启后由 DataStore 里的持久化值兜底）。
     *
     * 为什么必须放 VM 且可同步读：切视图时**首帧**就要用目标视图自己的排序渲染。
     * 以前是一个全局 sortByName + LaunchedEffect 回填，切回某视图的首帧还按「上一个视图的排序」
     * 渲染，列表顺序闪一下；若两种排序不兼容（专辑按年份 ↔ 艺术家），首帧等于无排序（库原始顺序）。
     */
    private val _librarySorts = MutableStateFlow<Map<String, String>>(emptyMap())
    val librarySorts: StateFlow<Map<String, String>> = _librarySorts.asStateFlow()

    /** 切换某视图的排序：立刻生效（内存）并落库（下次启动恢复） */
    fun setLibrarySort(viewKey: String, sortName: String) {
        _librarySorts.value = _librarySorts.value + (viewKey to sortName)
        viewModelScope.launch(Dispatchers.IO) { settings.setSort(viewKey, sortName) }
    }

    /** 后台批量补全用（MetadataEnricher 使用，慢一点无所谓） */
    private val scraper = MetadataScraper()

    /**
     * 前台交互用：手动综合搜索 / 搜封面 / 搜歌词 / 播放时歌词在线匹配。
     * 独立的 OkHttpClient（独立连接池与 Dispatcher），后台补全跑满时也不会排队。
     */
    private val interactiveScraper = MetadataScraper.interactive()

    /**
     * 播放相关的轻量任务（歌词在线匹配、响度写库）专用作用域：
     * 不占 viewModelScope 的主线程调度，也不受后台批量任务影响。
     */
    private val lyricScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val scanner = LibraryScanner(repo)

    /**
     * 进程内负缓存：url → (记录时间, TTL)。
     *
     * 两种 TTL 必须分开，否则「一次网络抖动」会被记成「这首歌没有歌词」：
     *  - 确认没有（所有启用的源都正常响应且确实没有词）→ 24h，与 lyrics.missAt 一致；
     *  - 这次没拿到（源全关 / 超时 / 解析失败）→ 60s，给个短退避防抖，马上能重试。
     * 之前这里是个无 TTL 的 Set，等于「本进程内永不重试」，注释里说的 24h 并不成立。
     */
    private data class LyricMissMark(val at: Long, val ttlMs: Long)
    private val lyricMiss = java.util.concurrent.ConcurrentHashMap<String, LyricMissMark>()

    /**
     * 歌词取词的单飞锁（url → Mutex）。
     *
     * 同一首歌的取词请求至少有三条来源：播放页 produceState、切歌预取、后台补全，
     * 它们会同时进来。没有锁时两边都会「先查库 = 空」然后各跑一遍完整流程
     * （两次 Range 下载文件头 + 两轮三源搜索），最后晚到的那个若拿到空结果，
     * 还会用 REPLACE 把先成功的那份歌词整行抹掉。加锁后第二个进来时直接读到已写好的歌词。
     */
    private val lyricFetchLocks = java.util.concurrent.ConcurrentHashMap<String, Mutex>()

    /** 正在取歌词的 url（歌词页据此显示「正在匹配歌词」而不是一片空白） */
    private val _lyricFetching = MutableStateFlow<Set<String>>(emptySet())
    val lyricFetching: StateFlow<Set<String>> = _lyricFetching.asStateFlow()

    /**
     * 启动即清一次旧版本误写的「无歌词」黑名单。
     * [fetchLyricIfMissing] 进来会先等它跑完，否则打开 App 直接播的第一首歌
     * 还是会撞上那条还没来得及作废的记录。
     */
    private val lyricMissResetJob = viewModelScope.launch(Dispatchers.IO) {
        runCatching { resetStaleLyricMissOnce() }
    }

    /** 后台元数据补全进度（艺人/专辑/封面） */
    val enrichState: StateFlow<com.grape.player.network.MetadataEnricher.State> =
        com.grape.player.network.MetadataEnricher.state

    // 后台补全每补一首就写库，Room 会重跑整表查询并让列表重组；
    // 250ms 节流既能保持「补到哪显示到哪」，又不会把主线程压住（否则手动搜索像点不动）。
    val allMedia: StateFlow<List<com.grape.player.data.MediaItemLite>> =
        repo.music.toStateFlow(viewModelScope, emptyList(), throttleMs = 250)
    val videos: StateFlow<List<com.grape.player.data.MediaItemLite>> =
        repo.videos.toStateFlow(viewModelScope, emptyList(), throttleMs = 250)
    val playlists: StateFlow<List<com.grape.player.data.Playlist>> =
        repo.playlists.toStateFlow(viewModelScope, emptyList())
    /** 歌单 id → 曲目数，供歌单列表页显示「N 首」 */
    val playlistCounts: StateFlow<Map<Long, Int>> =
        repo.playlistCounts.toStateFlow(viewModelScope, emptyMap())

    /** 最近播放（时间倒序，最多 100 条）；歌单页首个「最近播放」虚拟歌单的数据源 */
    val recentPlays: StateFlow<List<com.grape.player.data.RecentPlay>> =
        repo.recentPlays.toStateFlow(viewModelScope, emptyList())

    /**
     * 当前播放曲目的 url（null = 没有在播的曲目）。
     *
     * 给各页的「定位到正在播放」按钮用：只要「哪首歌在播」，不关心进度。所以先按
     * currentIndex / queueVersion 去重再取当前项 —— 250ms 的进度刷新不会让它变成
     * 高频发射，按钮所在页面也就不必跟着整页重组。
     * 注意 [PlayerController.state] 是普通 StateFlow，直接读 currentItem() 不会触发
     * 重组（MiniPlayerBar 有同样的注释），必须走这条 Flow。
     */
    val playingUrl: StateFlow<String?> = player.state
        .map { it.currentIndex to it.queueVersion }
        .distinctUntilChanged()
        .map { player.currentItem()?.url }
        .toStateFlow(viewModelScope, player.currentItem()?.url)

    private val _scanState = MutableStateFlow<ScanUiState>(ScanUiState.Idle)
    val scanState: StateFlow<ScanUiState> = _scanState.asStateFlow()

    /** 当前扫描任务：退出应用 / 划掉任务时显式取消，网络请求会立刻断开 */
    private var scanJob: Job? = null

    /**
     * 「取消扫描」软停止信号：置 true 后扫描器在下一个检查点跳出枚举，
     * 把已经扫到的条目 upsert 进库（不清库）后返回，见 LibraryScanner.scan 的 cancelled 分支。
     * 用 @Volatile 是因为它只被 IO 线程轮询、由主线程置位。
     */
    @Volatile private var scanCancelRequested = false

    /**
     * 本进程是否已经扫过一次（启动自动扫 / 用户手动扫都算）。
     * onStart 每次回前台都会调用 resumeBackgroundTasks，没有这个标记就会反复触发整库扫描。
     */
    @Volatile private var launchScanDone = false


    // 封面主色（ARGB），驱动全站动态主题。持久化在 settings 里，刷新不闪。
    private val _accentSeed = MutableStateFlow<Int?>(null)
    val accentSeed: StateFlow<Int?> = _accentSeed.asStateFlow()
    private val _dynamicColorEnabled = MutableStateFlow(true)
    val dynamicColorEnabled: StateFlow<Boolean> = _dynamicColorEnabled.asStateFlow()

    /** 刚调整过偏移的曲目（url → offsetMs）：歌词视图优先用它，写库在后台补 */
    private val _offsetOverride = MutableStateFlow<Pair<String, Long>?>(null)
    val offsetOverride: StateFlow<Pair<String, Long>?> = _offsetOverride.asStateFlow()

    /**
     * 刚改过内嵌歌词格式的曲目（url → 是否译文在前）。
     *
     * 和偏移一样必须有内存值：写库是异步的，只靠写完后触发重载会读到旧值 ——
     * 表现就是「勾了没反应，得重进歌词页才对」。歌词视图直接读它，勾选当场生效。
     */
    private val _lyricFormatOverride = MutableStateFlow<Pair<String, Boolean>?>(null)
    val lyricFormatOverride: StateFlow<Pair<String, Boolean>?> = _lyricFormatOverride.asStateFlow()

    // 响度实测覆盖率统计：当前曲目 + 已覆盖的 2 秒槽位（去重）+ 是否已写库
    private var loudnessUrl = ""
    private val coveredSlots = HashSet<Int>()
    private var loudnessWritten = false
    private var lastTrackUrl = ""
    /** 当前曲目的取词任务：切歌即取消，避免连切歌把并发管线堆起来抢带宽 */
    private var lyricJob: Job? = null
    /** 下一首的歌词预热任务（同上，只保留最新一个） */
    private var lyricPrefetchJob: Job? = null
    private var lastWarmUrl = ""
    private var lastWarmIndex = -1
    /** 最近一次已记进「最近播放」的 url（换曲目才记一次，避免每 250ms 轮询重复写库） */
    private var lastRecentUrl = ""
    /** 已回填过时长的曲目，避免重复写库 */
    private val durationSaved = HashSet<String>()

    init {
        player.connect()

        // —— 播放记忆：一开 App 就把上次的队列恢复到迷你栏，并持续落盘 ——
        // ① 播放服务里还留着队列（划掉任务栏后播放没停、进程被回收后服务还活着）：以服务为准
        player.onSessionQueueFound = { urls, index ->
            viewModelScope.launch {
                applyRestoredQueue(urls, index, positionMs = 0L, fromSession = true)
            }
        }
        // ② 否则读上次存下来的队列（进程真死了的情况）
        viewModelScope.launch { restoreLastPlayback() }
        // ③ 落盘时机：换曲目 / 队列变动 / 暂停时才写一次。
        //    位置每 250ms 都在变，把它排除在 key 之外，否则等于每 250ms 写一次数据库。
        viewModelScope.launch {
            player.state
                .map { Triple(it.currentIndex, it.queueVersion, it.isPlaying) }
                .distinctUntilChanged()
                .collect { persistPlayback() }
        }
        // 一次性回填「内嵌」来源标记（幂等）：旧版本内嵌补全不写 metaSource，
        // 已补全的老歌来源恒为 0。放这里保证不重扫也能生效。
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { repo.backfillEmbeddedMetaSource() }
        }
        // 一次性清理旧派生设置键：stream_gain_*（只写不读）、accent_url_* / lyric_off_*
        // （String.hashCode 做键，换 MD5 后旧行再没人读）。不清就会一直占着 settings 表。
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { clearLegacyKeyedSettingsOnce() }
        }
        // 目标响度同步给音频处理器
        viewModelScope.launch {
            settings.targetLufs.collect { LoudnessStore.setTarget(it) }
        }
        // 全局每 250ms 刷新播放进度，并统计响度覆盖率
        viewModelScope.launch {
            while (true) {
                kotlinx.coroutines.delay(250)
                // 缓冲停滞检测（「网络缓慢」提示）：必须在 isPlaying 门控之前 ——
                // BUFFERING 时 isPlaying=false，走不进下面的进度轮询
                player.checkBufferStall()
                // 暂停/未播放时位置不会变，预热、最近播放、覆盖率统计也全部依赖「在播」：
                // 追加 750ms（合计 1s 心跳）等恢复播放，避免后台/锁屏时仍每秒醒 4 次
                if (!player.state.value.isPlaying) {
                    kotlinx.coroutines.delay(750)
                    continue
                }
                player.updatePosition()
                trackLoudnessCoverage()
                // 切歌后预热下一首（index 或 url 变化都触发）。
                // 只在真的在播时预热：恢复出来的「暂停在上一首」也算没在播，
                // 否则每次开 App 都会替一首没打算听的歌发一次网络请求
                val item = player.currentItem()
                val idx = player.state.value.currentIndex
                val playing = player.state.value.isPlaying
                // —— 最近播放：真的唱起来才记（进度过 2 秒算「听过」，不算「路过」）——
                // 只在换曲目时记一次；同一首重复播放由 playedAt 覆盖（见 Repository.recordRecentPlay）。
                // 视频不进「最近播放」：歌单页的曲目映射只走音乐库（allMedia）。
                if (playing && item != null && item.url != lastRecentUrl &&
                    player.state.value.positionMs >= RECENT_PLAY_MIN_MS
                ) {
                    lastRecentUrl = item.url
                    if (!item.isVideo) {
                        viewModelScope.launch(Dispatchers.IO) {
                            runCatching { repo.recordRecentPlay(item.url) }
                        }
                    }
                }
                if (playing && item != null && (idx != lastWarmIndex || item.url != lastWarmUrl)) {
                    lastWarmIndex = idx
                    lastWarmUrl = item.url
                    com.grape.player.network.WarmTracker.warm(player.nextItemUrl(), delayMs = 150)
                }
                // 曲尾兜底：剩 <30s 时再预热一次（长曲目可能已超过 6 分钟 TTL）
                if (playing && item != null) {
                    val dur = player.state.value.durationMs
                    val pos = player.state.value.positionMs
                    if (dur > 0 && dur - pos in 1..30_000L) {
                        com.grape.player.network.WarmTracker.warm(player.nextItemUrl())
                    }
                    // 扫描阶段（WebDAV 只给文件大小）拿不到时长，播过一次就把真实时长补进库
                    if (dur > 0 && item.durationMs <= 0 && durationSaved.add(item.url)) {
                        viewModelScope.launch(Dispatchers.IO) {
                            repo.updateDurationIfEmpty(item.url, dur)
                        }
                    }
                }
            }
        }
        // 启动时恢复上次的主色与开关
        viewModelScope.launch(Dispatchers.IO) {
            val accent = runCatching { repo.getSetting(KEY_ACCENT) }.getOrNull()
            val dynamic = runCatching { repo.getSetting(KEY_DYNAMIC_COLOR) }.getOrNull()
            val savedMode = runCatching { repo.getSetting(KEY_PLAY_MODE) }.getOrNull()
            // MediaController 只能在主线程访问：setPlayMode 会写 controller.repeatMode，
            // 在 IO 协程里直接调会被 Media3 的线程检查当场炸掉（connect 时序对了才炸，
            // 时序错了就静默跳过 —— 表现为「模式偶尔不恢复」，两边都是坑）。
            // 读库留在 IO，应用统一回主线程。
            withContext(Dispatchers.Main) {
                accent?.toIntOrNull()?.let { _accentSeed.value = it }
                dynamic?.let { _dynamicColorEnabled.value = it == "1" }
                // 恢复播放模式：模式只存在内存里的话，重启后静默变回顺序播放，
                // 用户会以为「单曲循环没生效」（播放器实际 repeatMode 也已随服务重启被重置）
                savedMode?.let { saved ->
                    runCatching { PlayerController.PlayMode.valueOf(saved) }.getOrNull()?.let {
                        player.setPlayMode(it)
                    }
                }
            }
        }
        // 切歌后延后取色，让路音频首字节；同一首只算一次（url 去重）
        viewModelScope.launch {
            var lastCover = ""
            player.state.collect { st ->
                val item = player.currentItem() ?: return@collect
                val cover = item.coverUrl
                if (cover.isBlank() || cover == lastCover) return@collect
                lastCover = cover
                kotlinx.coroutines.delay(600)
                val cached = withContext(Dispatchers.IO) {
                    repo.getSetting(accentKey(cover))?.toIntOrNull()
                }
                if (cached != null) {
                    _accentSeed.value = cached
                    return@collect
                }
                val rgb = com.grape.player.util.CoverColorExtractor.extract(cover)
                if (rgb != null) {
                    _accentSeed.value = rgb
                    withContext(Dispatchers.IO) {
                        repo.setSetting(KEY_ACCENT, rgb.toString())
                        repo.setSetting(accentKey(cover), rgb.toString())
                    }
                }
            }
        }
    }

    fun setDynamicColorEnabled(enabled: Boolean) {
        _dynamicColorEnabled.value = enabled
        viewModelScope.launch(Dispatchers.IO) {
            repo.setSetting(KEY_DYNAMIC_COLOR, if (enabled) "1" else "0")
        }
    }

    /** 切换播放模式：改播放器 + 落库（下次启动恢复） */
    fun setPlayMode(mode: PlayerController.PlayMode) {
        player.setPlayMode(mode)
        viewModelScope.launch(Dispatchers.IO) { repo.setSetting(KEY_PLAY_MODE, mode.name) }
    }

    /**
     * 把「当前在放什么」落盘（恢复上次播放用）。
     *
     * 存 Room 的 setting 键值表（和播放模式同一处），队列**只存 url**、用 \n 连接：
     * 恢复时再按 url 回库查一遍最新条目 —— 元数据改过 / 重扫过都不会读到旧标题，
     * 库里已经删掉的歌会被自动跳过，不需要额外做失效处理。
     *
     * 队列为空时**不写**：启动首帧、清除数据库都会走到这里，写空会把上次的存档抹掉
     *（清除数据库那两处是显式清 key 的）。
     */
    private suspend fun persistPlayback() {
        val queue = player.queue()
        if (queue.isEmpty()) return
        val st = player.state.value
        val urls = queue.joinToString("\n") { it.url }
        withContext(Dispatchers.IO) {
            try {
                repo.setSetting(KEY_LAST_QUEUE, urls)
                repo.setSetting(KEY_LAST_INDEX, st.currentIndex.toString())
                repo.setSetting(KEY_LAST_POS, st.positionMs.toString())
            } catch (_: Exception) {
                // 存档失败不影响播放，下次操作还会再写一遍
            }
        }
    }

    /** 读上次存档并还原（进程被杀后重新打开 App 走这条） */
    private suspend fun restoreLastPlayback() {
        val saved = withContext(Dispatchers.IO) {
            try {
                val urls = (repo.getSetting(KEY_LAST_QUEUE) ?: "")
                    .split('\n').filter { it.isNotBlank() }
                if (urls.isEmpty()) {
                    null
                } else {
                    RestoredPlayback(
                        urls = urls,
                        index = repo.getSetting(KEY_LAST_INDEX)?.toIntOrNull() ?: 0,
                        positionMs = repo.getSetting(KEY_LAST_POS)?.toLongOrNull() ?: 0L,
                    )
                }
            } catch (_: Exception) {
                null
            }
        } ?: return
        applyRestoredQueue(saved.urls, saved.index, saved.positionMs)
    }

    /**
     * 把一串 url 还原成库里的条目交给播放控制器。
     *
     * @param fromSession true = 队列来自还活着的播放服务（服务里的才是在响的那个，
     *   除非用户已经手动换成了别的歌，否则以它为准）；
     *   false = 来自上次的存档（恢复成「暂停在那一首」的样子，等用户按播放才真正加载）
     */
    private suspend fun applyRestoredQueue(
        urls: List<String>,
        index: Int,
        positionMs: Long,
        fromSession: Boolean = false,
    ) {
        if (urls.isEmpty()) return
        val anchor = urls.getOrNull(index)
        // 按 url 回库取最新条目，顺序保持队列顺序（库里已删除的自然被跳过）
        val catalog: Map<String, MediaItem> = withContext(Dispatchers.IO) {
            try {
                repo.getAllOnce().associateBy { it.url }
            } catch (_: Exception) {
                emptyMap()
            }
        }
        val items = urls.mapNotNull { catalog[it]?.let { m -> com.grape.player.data.MediaItemLite.from(m) } }
        if (items.isEmpty()) return
        // 过滤后下标会错位：以「原来那一首」的 url 重新定位，找不到才回落到第一首
        val newIndex = anchor
            ?.let { a -> items.indexOfFirst { it.url == a } }
            ?.takeIf { it >= 0 }
            ?: 0

        if (fromSession) {
            // 用户已经手动放别的歌（不在服务这份队列里）：不动它
            val cur = player.currentItem()?.url
            if (cur != null && cur !in urls) return
            player.adoptQueue(items, newIndex)
        } else {
            // 读库期间用户可能已经点歌开播：这时别用旧的存档盖掉他
            if (player.queue().isNotEmpty()) return
            player.restoreQueue(items, newIndex, positionMs)
        }
    }

    /**
     * 清除数据库（保留 WebDAV 配置与外观设置）。
     * 曲目全没了以后三个封面目录（旧 cover/ + EmbeddedCovers/ + OnlineCovers/）全是孤儿，
     * 连同内嵌数据一起清掉 —— 内嵌数据的清除只发生在这里。
     * 会先硬停止正在进行的扫描与补全（含作废在途结果），否则清完立刻被它们写回来。
     * 注意：内部会操作 MediaController，必须从主线程调用。
     */
    suspend fun wipeDatabase(): com.grape.player.util.CoverStore.CleanResult {
        player.clearQueue()
        return withContext(Dispatchers.IO) {
            // 扫描必须先硬停：清库不拦扫描的话，扫描协程会按批把条目写回来，
            // 连它启动时读到内存里的那份旧行快照（artist / album / 封面 / 内嵌字段）一起复活，
            // 观感就是「刚清完库，库自己长回来了」
            stopScanForMaintenance()
            // 补全同理，而且要连在途结果一起作废：走到「已刮到、还没写库」的那几首
            // 会在取消生效前把行写回已经清空的库
            com.grape.player.network.MetadataEnricher.cancel(discardInFlight = true)
            // 曲目全没了，播放记忆也就不成立了：清掉存档，免得下次启动恢复出一个空队列
            runCatching { repo.setSetting(KEY_LAST_QUEUE, "") }
            repo.clearDatabase()
            // 队列已空，续跑标记必须归零：留着 pending=1 会让下次启动白白空跑一轮
            runCatching {
                repo.setSetting(com.grape.player.network.MetadataEnricher.KEY_PENDING, "0")
            }
            com.grape.player.util.CoverStore.wipeAll(app)
        }
    }

    /**
     * 删除无效封面 —— 删除 Using_ 未使用且不是 Embedded_ 引用的封面文件。
     * 只扫 OnlineCovers/（在线/手动下载封面所在目录）；
     * EmbeddedCovers/ 内嵌封面永不清理，内嵌数据只在「清除数据库」时删除。
     */
    suspend fun deleteInvalidCovers(): com.grape.player.util.CoverStore.CleanResult =
        withContext(Dispatchers.IO) {
            val items = repo.getAllOnce()
            val referenced = items.map { it.coverUrl } + items.mapNotNull { it.embCoverUrl.takeIf { c -> c.isNotBlank() } }
            com.grape.player.util.CoverStore.cleanOrphans(app, referenced)
        }

    /**
     * 删除所有在线数据：
     * 1. Using_ 全字段清空，再从内嵌快照（Embedded_）复制回对应字段（无内嵌的字段保持为空，
     *    标题回退为文件名推导值）；来源标记按内嵌有无元字段记 1/0；
     * 2. 歌词表清空后按内嵌歌词重建（保留原时间偏移，译文随在线数据一起清除）；
     * 3. 删除所有非内嵌来源的封面文件（OnlineCovers/ 与旧版 cover/ 整体清空，
     *    EmbeddedCovers/ 不动——内嵌数据只在「清除数据库」时删除）；
     * 4. 清空在线刮削缓存表 meta_cache；
     * 5. 清掉续跑标记 enrich_pending —— 否则重置后的空字段会被下次启动的续跑整库刮回来。
     * 全过程先硬停扫描与补全（含作废在途结果），否则它们会把刚删的数据写回来。
     */
    suspend fun deleteAllOnlineData(): com.grape.player.util.CoverStore.CleanResult {
        // 操作 MediaController 必须在主线程（与 wipeDatabase 一致）
        player.clearQueue()
        return withContext(Dispatchers.IO) {
            // 扫描也要硬停：扫描的 REPLACE 会把「启动时读到的旧行快照」整行写回，
            // 其中就带着刚被重置掉的在线元数据 —— 等于这次操作白做
            stopScanForMaintenance()
            runCatching { repo.setSetting(KEY_LAST_QUEUE, "") }
            // 连在途结果一起作废，否则走到「已刮到、还没写库」的那几首会把在线数据补回来
            com.grape.player.network.MetadataEnricher.cancel(discardInFlight = true)
            val items = repo.getAllOnce()
            // 歌词表旧时间偏移先读出来（清空后按内嵌歌词重建时要带上）
            val lyricOffsets = items.associate { old ->
                old.url to (repo.getLyric(old.url)?.offsetMs ?: 0L)
            }
            for (item in items) {
                val hasEmbeddedMeta = item.embTitle.isNotBlank() || item.embArtist.isNotBlank() ||
                    item.embAlbum.isNotBlank() || item.embYear > 0 ||
                    item.embTrackNumber > 0 || item.embDiscNumber > 0 || item.embCoverUrl.isNotBlank()
                repo.resetUsingFromEmbedded(
                    url = item.url,
                    // 标题：内嵌优先，否则回退文件名推导值（与扫描逻辑一致，避免列表显示空白）
                    title = item.embTitle.ifBlank {
                        com.grape.player.util.MediaPathUtil.titleFromPath(item.url)
                            .ifBlank { item.url.substringAfterLast('/') }
                    },
                    artist = item.embArtist,
                    album = item.embAlbum,
                    year = if (item.embYear > 0) item.embYear else 0,
                    trackNumber = if (item.embTrackNumber > 0) item.embTrackNumber else 0,
                    discNumber = if (item.embDiscNumber > 0) item.embDiscNumber else 0,
                    coverUrl = item.embCoverUrl,
                    lyric = item.embLyric,
                    metaSource = if (hasEmbeddedMeta) MediaItem.META_SOURCE_EMBEDDED else MediaItem.META_SOURCE_NONE,
                )
            }
            // 歌词表整体重建：在线歌词/译文全清，内嵌歌词按快照复制回来（保留时间偏移）
            repo.clearLyrics()
            items.forEach { item ->
                if (item.embLyric.isNotBlank()) {
                    repo.saveLyric(
                        com.grape.player.data.LyricEntry(
                            url = item.url,
                            lrc = item.embLyric,
                            translatedLrc = "",
                            offsetMs = lyricOffsets[item.url] ?: 0L,
                            // 这张表刚被整体清空，现在写回去的只有内嵌快照，出处自然是内嵌
                            source = LyricSource.EMBEDDED,
                            updatedAt = System.currentTimeMillis(),
                        )
                    )
                }
            }
            repo.clearMetaCache()
            // 全量重置 = 所有歌都重新获得一次机会：连补全的失败退避一起归零，
            // 否则前不久刚失败过的歌会压着 TTL 不重试，用户会以为重置没生效
            runCatching { repo.clearMetaMissAll() }
            // 关键：把续跑标记归零。重置 Using_ 之后全库 artist/album/封面都是空，
            // 只要 pending 还是 1，下次启动 resumeIfNeeded 就会把刚删掉的在线数据整库刮回来。
            // 想重新补全，用户主动扫描一次即可（扫描结束会 restart 一轮）。
            runCatching {
                repo.setSetting(com.grape.player.network.MetadataEnricher.KEY_PENDING, "0")
            }
            // 非内嵌封面（OnlineCovers/ + 旧版 cover/）此时已无任何引用，整体删除
            // （放最后：它的返回值就是本函数的 CleanResult）
            com.grape.player.util.CoverStore.deleteAllNonEmbedded(app)
        }
    }

    /**
     * 启动自愈：清掉指向已丢失文件的封面引用。
     * Using 封面丢了先回退到可用的内嵌快照封面，都没有才清空；
     * 内嵌快照封面文件丢了同样清空。清空的歌会重新进补全队列，
     * 由后台补全用内嵌封面或在线封面重新填上。
     */
    private suspend fun healCoverRefs() = withContext(Dispatchers.IO) {
        val items = repo.getAllOnce()
        for (item in items) {
            if (item.coverUrl.isNotBlank() && isLocalFileMissing(item.coverUrl)) {
                val emb = item.embCoverUrl.takeIf { it.isNotBlank() && !isLocalFileMissing(it) }
                repo.updateCover(item.url, emb ?: "")
            }
            if (item.embCoverUrl.isNotBlank() && isLocalFileMissing(item.embCoverUrl)) {
                repo.updateEmbCover(item.url, "")
            }
        }
    }

    /** 本地路径（file:// 或绝对路径）指向的文件是否已不存在；远程 URL 恒为 false */
    private fun isLocalFileMissing(url: String): Boolean {
        val raw = url.trim().removePrefix("file://")
        if (raw == url.trim() && !raw.startsWith("/")) return false
        val path = try {
            java.net.URLDecoder.decode(raw, "UTF-8")
        } catch (_: Exception) {
            raw
        }
        return !File(path).exists()
    }

    /**
     * 响度覆盖率统计：按 2 秒一个槽位去重累计已播放区间。
     * 覆盖率 >60% 且实测有结果（且无 ReplayGain 标签）时写库，每首只写一次。
     * 逻辑与 Web 版 loudnessStream.ts 一致：不同片段可累加、重复片段只算一次，与是否 seek 无关。
     */
    private fun trackLoudnessCoverage() {
        val item = player.currentItem() ?: return
        val url = item.url

        // 切歌：通知处理器注入本曲 ReplayGain / 重置测量，清空覆盖率统计。
        // 只在真的在播时才做：恢复出来的「暂停在上一首」不该在开 App 时就去联网搜歌词
        //（lastTrackUrl 不更新，等真正按下播放的那一帧再补跑这一段）
        if (url != lastTrackUrl && player.state.value.isPlaying) {
            lastTrackUrl = url
            loudnessUrl = url
            coveredSlots.clear()
            loudnessWritten = false
            LoudnessStore.onTrackStart(item.replayGainGain)
            // 播放时歌词兜底：内嵌 → 在线匹配，搜到即存（只填空）。
            // 放独立作用域：不跟后台补全/主线程抢，切歌即可用。
            // 先取消上一首没跑完的取词 —— 连切 10 首不再堆 10 条管线抢同一个 WebDAV 的带宽
            lyricJob?.cancel()
            lyricJob = lyricScope.launch {
                try {
                    fetchLyricIfMissing(
                        item.url, item.title, item.artist, item.isVideo,
                        durationMs = item.durationMs,
                    )
                } catch (_: Exception) {
                }
            }
            // 顺手把下一首的词也取了：首播不必等现场联网
            prefetchNextLyric()
        }
        if (loudnessWritten || LoudnessStore.usingReplayGain) return

        val pos = player.state.value.positionMs
        val dur = player.state.value.durationMs
        if (dur <= 0 || pos <= 0) return
        // 每 2 秒一个槽位，总槽数随曲目时长动态计算（短曲也能达到 100% 覆盖）
        val slotMs = 2000L
        val totalSlots = ((dur + slotMs - 1) / slotMs).toInt().coerceAtLeast(1)
        val slot = (pos / slotMs).toInt().coerceIn(0, totalSlots - 1)
        coveredSlots.add(slot)
        val coverage = coveredSlots.size.toFloat() / totalSlots

        if (coverage > 0.6f) {
            val lufs = LoudnessStore.measuredLufs
            if (lufs != null) {
                loudnessWritten = true
                val peak = LoudnessStore.measuredPeak
                // 增益由 lufs 与目标响度现算即可。这里曾经还往 settings 写一行
                // "stream_gain_${url.hashCode()}" 当缓存，但全工程没有任何读取方 ——
                // 等于每播一首就多一行永不清理的垃圾键，已移除（旧行由
                // clearLegacyKeyedSettingsOnce 统一清掉）
                viewModelScope.launch(Dispatchers.IO) {
                    repo.updateLoudness(url, lufs, peak, coverage)
                }
            }
        }
    }

    /**
     * 把任意 url / 封面地址压成定长键片段（MD5 十六进制）。
     *
     * 不能再用 `String.hashCode()` 当键：那是 32 位，几千首歌里撞一次的概率虽低但非零，
     * 一旦撞上就是「两首歌共用同一份歌词偏移」「两首不同的歌共用同一个取色」这类
     * 极难复现的怪问题。MD5 128 位，实际不会撞。
     */
    private fun stableKey(raw: String): String =
        java.security.MessageDigest.getInstance("MD5")
            .digest(raw.toByteArray())
            .toHex()

    /**
     * 取色缓存键。前缀从 `accent_url_` 换成 `accent2_`：旧键是 hashCode 格式，
     * 换格式后必须整批作废（旧行由 [clearLegacyKeyedSettingsOnce] 删除），
     * 顺手把前缀也换掉，免得新旧两种格式混在同一前缀下无从区分。
     */
    private fun accentKey(cover: String) = "accent2_${stableKey(cover)}"

    // ===== 元数据编辑面板 =====
    private val _metaEdit = MutableStateFlow<MetaEditState>(MetaEditState.Closed)
    val metaEdit: StateFlow<MetaEditState> = _metaEdit.asStateFlow()

    fun openMetaEdit(url: String) {
        if (url.isNotBlank()) _metaEdit.value = MetaEditState.Single(url)
    }

    fun openMetaEditMany(urls: List<String>) {
        val valid = urls.filter { it.isNotBlank() }
        if (valid.isNotEmpty()) _metaEdit.value = MetaEditState.Batch(valid)
    }

    fun closeMetaEdit() { _metaEdit.value = MetaEditState.Closed }

    suspend fun itemForEdit(url: String): MediaItem? = withContext(Dispatchers.IO) { repo.getByUrl(url) }

    /** 读取某曲的歌词时间偏移（毫秒）—— 刚拖动过的那首以内存值为准，避免读库竞态 */
    suspend fun lyricOffset(url: String): Long {
        val override = _offsetOverride.value
        if (override != null && override.first == url) return override.second
        return withContext(Dispatchers.IO) { repo.getLyric(url)?.offsetMs ?: 0L }
    }

    /**
     * 按歌曲持久化歌词时间偏移（拖一次，下次播放自动生效）。
     *
     * 两个坑：
     * 1. 写库是异步的，如果只靠写完后 +1 触发歌词重载，重载往往先跑完、读到旧值 —— 表现为「拖了没反应」。
     *    所以先落内存 override，歌词视图立刻按新偏移渲染。
     * 2. `UPDATE lyrics SET offsetMs` 在没有歌词行时影响 0 行，偏移永远存不下来（下次打开又归零）。
     *    这里先确保行存在再更新。
     */
    /**
     * 拖动中（手指未松开）的实时预览：只改内存、不写库。
     * 歌词视图直接读这个值，所以滑块一动歌词就跟着走；松手后再落库。
     */
    fun previewLyricOffset(url: String, offsetMs: Long) {
        _offsetOverride.value = url to offsetMs
    }

    fun saveLyricOffset(url: String, offsetMs: Long) {
        _offsetOverride.value = url to offsetMs
        viewModelScope.launch(Dispatchers.IO) {
            if (repo.getLyric(url) == null) {
                repo.saveLyric(com.grape.player.data.LyricEntry(url = url, lrc = ""))
            }
            repo.setLyricOffset(url, offsetMs)
        }
    }

    /**
     * 读某曲的内嵌歌词格式（true = 译文在前）—— 内存里刚改过的以内存值为准，避免读库竞态。
     * 没有歌词行时返回 false（先原词后翻译），与老数据迁移过来的默认值一致。
     */
    suspend fun lyricTranslationFirst(url: String): Boolean {
        val override = _lyricFormatOverride.value
        if (override != null && override.first == url) return override.second
        return withContext(Dispatchers.IO) { repo.getLyric(url)?.translationFirst ?: false }
    }

    /**
     * 按曲目保存内嵌歌词格式（勾一次，下次播放这首自动按这个顺序解析）。
     *
     * 先落内存 override 再写库：写库是异步的，只等写完后重载会读到旧值（「勾了没反应」）。
     * 歌词行不存在时先建行 —— `UPDATE lyrics` 在没有行时影响 0 行，设置会悄悄丢掉。
     */
    fun saveLyricTranslationFirst(url: String, first: Boolean) {
        _lyricFormatOverride.value = url to first
        viewModelScope.launch(Dispatchers.IO) {
            if (repo.getLyric(url) == null) {
                repo.saveLyric(com.grape.player.data.LyricEntry(url = url, lrc = ""))
            }
            repo.setLyricTranslationFirst(url, first)
        }
    }

    /** 确保歌词缓存行存在（写入 offset 时若行不存在则 insert 会失败，需要先建行）。
     *
     *  @param source 只在真要新建行时才用到 —— 正文是调用方拿来的，出处也得由它说清楚，
     *         否则这行会以「来源未记录」的面目留在库里，永久失去可追溯性。 */
    suspend fun ensureLyricRow(url: String, lrc: String, source: String = "") =
        withContext(Dispatchers.IO) {
            if (repo.getLyric(url) == null) {
                repo.saveLyric(
                    com.grape.player.data.LyricEntry(url = url, lrc = lrc, source = source)
                )
            }
        }

    /**
     * 综合搜索 / 单独搜封面 / 单独搜歌词 —— 全部走**前台专用**刮削器。
     * 用后台补全那个实例的话，补全 4 并发会把连接池占住，手动搜索排队等到超时（观感就是「点了没反应」）。
     */
    suspend fun searchMeta(title: String, artist: String, album: String = "") =
        interactiveScraper.searchMulti(title, artist, album)

    /** 手动搜索（编辑弹窗分源选项卡）：并行查询启用的多个源，返回 源名 → 候选列表 */
    suspend fun searchMetaBySource(
        title: String,
        artist: String,
        album: String = "",
        providers: List<String>,
    ) = interactiveScraper.searchMultiBySource(title, artist, album, providers)
    suspend fun searchCovers(
        title: String,
        artist: String,
        album: String = "",
        providers: List<String> = listOf(
            MetadataScraper.SOURCE_QQ,
            MetadataScraper.SOURCE_KUGOU,
            MetadataScraper.SOURCE_NETEASE,
        ),
    ) = interactiveScraper.searchCovers(title, artist, album, providers)

    suspend fun searchLyrics(
        title: String,
        artist: String,
        providers: List<String> = listOf(
            MetadataScraper.SOURCE_QQ,
            MetadataScraper.SOURCE_KUGOU,
            MetadataScraper.SOURCE_NETEASE,
        ),
    ) = interactiveScraper.searchLyrics(title, artist, providers)

    /** 单独搜歌词（分源）：返回 源 key → 候选，编辑弹窗按源出页签 */
    suspend fun searchLyricsBySource(
        title: String,
        artist: String,
        providers: List<String> = listOf(
            MetadataScraper.SOURCE_QQ,
            MetadataScraper.SOURCE_KUGOU,
            MetadataScraper.SOURCE_NETEASE,
        ),
    ) = interactiveScraper.searchLyricsBySource(title, artist, providers)

    /** 综合搜索「应用并修改」某条候选后，按候选精确取对应歌词（QQ 按 songmid / 酷狗按 FileHash） */
    suspend fun searchLyricForApplied(
        result: MetadataScraper.SearchResult,
        providers: List<String> = listOf(
            MetadataScraper.SOURCE_QQ,
            MetadataScraper.SOURCE_KUGOU,
            MetadataScraper.SOURCE_NETEASE,
        ),
    ) = interactiveScraper.lyricForApplied(result, providers)

    /**
     * 播放时歌词兜底：内嵌（已在补全阶段落库）→ 在线匹配（多候选打分：
     * QQ 旧接口 + QRC 译文 / 酷狗 m3ws（含译文）/ 网易云 id 直取（含译文）），
     * 搜到即保存，原文/译文分开存。只填空不覆盖；保留用户已调好的时间偏移。
     *
     * 「确认没有」才写 24h 负缓存（lyrics 表 missAt 列）；
     * 网络异常 / 所有源都关着只做 60s 的内存退避，绝不落库——
     * 否则一次 Wi-Fi 抖动就会让这首歌一天没歌词。
     *
     * @param durationMs 当前音轨真实时长（拿不到给 0），用于判断候选是不是同一版本
     */
    suspend fun fetchLyricIfMissing(
        url: String,
        title: String,
        artist: String,
        isVideo: Boolean = false,
        durationMs: Long = 0L,
    ): Pair<String, String> = withContext(Dispatchers.IO) {
        // 等一次性清理跑完：旧版本的误写记录必须先失效，否则第一首歌照样拿不到歌词
        lyricMissResetJob.join()
        // 单飞：同一首被多个入口同时请求时，后到的等前者跑完，进锁后查库就能命中
        val lock = lyricFetchLocks[url] ?: lyricFetchLocks.computeIfAbsent(url) { Mutex() }
        lock.withLock {
            fetchLyricLocked(url, title, artist, isVideo, durationMs)
        }
    }

    /** 真正干活的那段（调用方必须已持有 [lyricFetchLocks] 里对应 url 的锁） */
    private suspend fun fetchLyricLocked(
        url: String,
        title: String,
        artist: String,
        isVideo: Boolean,
        durationMs: Long,
    ): Pair<String, String> {
        val existing = repo.getLyric(url)
        val dbLrc = existing?.lrc.orEmpty()
        // 用户手动编辑定稿的歌词优先（存 media_items.lyric，与 lyrics 表同步）。
        // 队列已改成 MediaItemLite 不带歌词正文，这里按 url 回表取这一份最高优先级正文。
        val itemLyric = repo.getByUrl(url)?.lyric.orEmpty()
        if (itemLyric.isNotBlank()) return itemLyric to existing?.translatedLrc.orEmpty()
        // 进锁后重查：并发的另一路可能刚写好（这是单飞生效的关键一步）。
        // 老版本把「纯音乐，请欣赏」这类占位文案当成功结果存进库里，这里认出它当没有，
        // 交给下面的流程重新匹配（fillLyricIfBlank 允许覆盖占位行）
        val dbUsable = dbLrc.isNotBlank() && !LyricQuality.isPlaceholder(dbLrc)
        if (dbUsable) return dbLrc to existing?.translatedLrc.orEmpty()

        // 负缓存：内存（带分级 TTL）+ 库里的 missAt（兼容旧版本写入的行）
        val missAt = existing?.missAt ?: 0L
        if (isRecentlyMissed(url) ||
            (missAt > 0 && System.currentTimeMillis() - missAt < LYRIC_MISS_TTL_MS)
        ) {
            return "" to ""
        }

        _lyricFetching.update { it + url }
        try {
            // 视频（MV / 演唱会）没有「歌词」这回事：按标题/艺人匹配到的只会是录音室版歌词，
            // 时间轴必然对不上。直接不取，也不写负缓存
            if (isVideo) return "" to ""

            // 用户手动把歌词清空过 = 明确表示「这首不要词」，别再自动搜回来
            if (repo.getSetting(lyricOffKey(url)) == "1") return "" to ""

            // ① 内嵌歌词兜底：补全阶段只读「进了补全队列」的曲目（缺艺人/专辑/年份/封面），
            //    三字段齐全的老歌永远轮不到它，所以播放时单独再试一次（读过的已在库，上面就返回了）。
            //    5 秒上限：坏文件/不支持 Range 时不至于卡住歌词显示。
            val embedded = try {
                withTimeoutOrNull(5_000L) {
                    com.grape.player.network.EmbeddedTagReader.read(app, url)?.lyric
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            // 内嵌歌词同样要过时间轴校验：USLT/SYLT 拿到的可能是无时间戳的纯文本
            if (!embedded.isNullOrBlank() && LyricQuality.isUsable(embedded)) {
                repo.fillLyricIfBlank(
                    com.grape.player.data.LyricEntry(
                        url = url,
                        lrc = embedded,
                        translatedLrc = "",
                        offsetMs = existing?.offsetMs ?: 0L,
                        translationFirst = existing?.translationFirst ?: false,
                        source = LyricSource.EMBEDDED,
                    )
                )
                // Embedded_ 歌词快照（补全阶段没覆盖到的歌在这里补上）
                repo.updateEmbLyric(url, embedded)
                lyricMiss.remove(url)
                return embedded to ""
            }

            // ② 在线兜底：走前台专用刮削器（独立连接池，不跟后台补全排队），
            //    多候选打分后取最优，再校验是否真的有可用时间轴
            val hit = try {
                interactiveScraper.matchLyric(
                    title = title,
                    artist = artist,
                    providers = lyricProviders(),
                    durationMs = effectiveDuration(durationMs),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                MetadataScraper.LyricFetch.Failed
            }

            when (hit) {
                is MetadataScraper.LyricFetch.Found -> {
                    repo.fillLyricIfBlank(
                        com.grape.player.data.LyricEntry(
                            url = url,
                            lrc = hit.lrc,
                            translatedLrc = hit.translation,
                            offsetMs = existing?.offsetMs ?: 0L,
                            translationFirst = existing?.translationFirst ?: false,
                            // 记下是哪家给的：以后用户说「词变了」，能直接看出换了源还是上游改了词
                            source = hit.source,
                        )
                    )
                    lyricMiss.remove(url)
                    // 读回真实值：并发方可能已经写了更好的结果，fillLyricIfBlank 此时是 no-op
                    val after = repo.getLyric(url)
                    return if (after != null && after.lrc.isNotBlank()) {
                        after.lrc to after.translatedLrc.orEmpty()
                    } else {
                        hit.lrc to hit.translation
                    }
                }
                MetadataScraper.LyricFetch.NotFound -> {
                    markMiss(url, confirmed = true)
                    // 持久化负缓存：事务内写，已有正文的行不会被清掉
                    repo.markLyricMiss(url, System.currentTimeMillis())
                    return "" to ""
                }
                MetadataScraper.LyricFetch.Failed -> {
                    // 只是「这次没问到」：只做短退避，绝不落库、绝不 24h
                    markMiss(url, confirmed = false)
                    return "" to ""
                }
            }
        } finally {
            _lyricFetching.update { it - url }
        }
    }

    /** 时长兜底：入库时长 → 播放器实测（首播那一刻 MediaItem.durationMs 往往还是 0） */
    private fun effectiveDuration(hintMs: Long): Long {
        if (hintMs > 0) return hintMs
        val fromPlayer = player.state.value.durationMs
        return if (fromPlayer > 0) fromPlayer else player.currentItem()?.durationMs ?: 0L
    }

    private fun isRecentlyMissed(url: String): Boolean {
        val mark = lyricMiss[url] ?: return false
        if (System.currentTimeMillis() - mark.at > mark.ttlMs) {
            lyricMiss.remove(url)
            return false
        }
        return true
    }

    private fun markMiss(url: String, confirmed: Boolean) {
        lyricMiss[url] = LyricMissMark(
            at = System.currentTimeMillis(),
            ttlMs = if (confirmed) LYRIC_MISS_TTL_MS else LYRIC_RETRY_DELAY_MS,
        )
    }

    /**
     * 「用户手动清空歌词，别再自动搜」的标记 key（settings 表，跨进程有效）。
     * 与 [accentKey] 同理：换成 MD5 键 + `lyric_off2_` 前缀，旧 hashCode 键整批作废。
     */
    private fun lyricOffKey(url: String) = "lyric_off2_${stableKey(url)}"

    /**
     * 预热下一首的歌词 —— 与音频预热（WarmTracker）对齐。
     * 一首歌第一次播放要现场跑「内嵌 Range 下载 + 三源搜索」，3~8 秒内歌词区是空的。
     * 在上一首播放期间就把词取好落库，切过去直接命中；预热失败也无所谓，
     * 真正播放时 [fetchLyricIfMissing] 会再试一次（失败只做 60s 退避，不写负缓存）。
     */
    private fun prefetchNextLyric() {
        val next = player.nextItem() ?: return
        if (next.isVideo) return
        lyricPrefetchJob?.cancel()
        lyricPrefetchJob = lyricScope.launch {
            try {
                fetchLyricIfMissing(
                    next.url, next.title, next.artist, next.isVideo,
                    durationMs = next.durationMs,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // 预热失败不影响播放
            }
        }
    }

    /** 单曲保存：只改可编辑字段（仅覆盖 Using_），保留时长 / 响度 / 偏移；同步歌词缓存（保留 offset）。
     *  Embedded_ 内嵌快照不可被覆盖——文件里的内嵌标签是文件自己的事实，与用户编辑无关。
     *
     *  @param lyricSource 这次正文的出处（从候选页签 / 套用结果带进来的 [com.grape.player.util.LyricSource] key）；
     *         null = 用户没换词，沿用库里原行的出处（只改歌名/封面时不该把出处洗成「手动编辑」）。 */
    suspend fun applyMetaEdit(
        url: String,
        title: String,
        artist: String,
        album: String,
        year: Int,
        coverUrl: String,
        lyric: String,
        translation: String,
        offsetMs: Long,
        trackNumber: Int = 0,
        discNumber: Int = 0,
        lyricSource: String? = null,
    ) = withContext(Dispatchers.IO) {
        // 在线/远程封面先落盘到 OnlineCovers/，数据库只存本地 file:// 路径
        val finalCover = com.grape.player.util.CoverStore.downloadToLocal(app, coverUrl.trim())
            ?: coverUrl.trim()
        repo.updateMeta(url, title.trim(), artist.trim(), album.trim(), year, finalCover, lyric)
        lyricMiss.remove(url)
        repo.updateTrackDisc(url, trackNumber, discNumber)
        // 手动编辑 = 最高优先级来源：只写 Using_，来源记 3=手动
        repo.setMetaSource(url, com.grape.player.data.MediaItem.META_SOURCE_MANUAL)
        // 歌词写进缓存表，保留用户已调好的时间偏移。
        // 必须在 updateItemMeta 之前落库：updateItemMeta 会 bump queueVersion 触发歌词页重载，
        // 顺序反了重载会读到旧缓存（表现为「保存了但歌词页还是旧的」）。
        // 无条件写（含清空）：两个框都留空 = 用户想清掉歌词，覆盖旧行。
        // 「别再自动搜」标记只在**真的删掉了歌词**时才打（原来有、现在空）：
        // 保存会 bump queueVersion 触发歌词页重载，不打标记就会立刻联网把用户刚清掉的词搜回来；
        // 但本来就没有词、只是顺手改个封面的歌不能被误标，否则以后永远不再自动匹配。
        val existingLyric = repo.getLyric(url)
        val hadLyric = !existingLyric?.lrc.isNullOrBlank()
        // 出处判定：换过词就记这次的来源；正文没动（只改歌名/封面/年份）就原样继承老出处，
        // 否则「顺手改个封面」会把这行洗成「手动编辑」，真正的出处就再也看不出来了
        val lyricChanged = existingLyric?.lrc != lyric
        val resolvedSource = when {
            lyric.isBlank() -> LyricSource.UNKNOWN
            lyricSource != null -> LyricSource.normalize(lyricSource)
            !lyricChanged && existingLyric != null -> existingLyric.source
            else -> LyricSource.MANUAL
        }
        // 正文没变就不刷新写入时间：它表示「词是哪天拿到手的」，不是「这行最后一次被碰的时间」
        val writtenAt = if (!lyricChanged && existingLyric != null) existingLyric.updatedAt
        else System.currentTimeMillis()
        repo.saveLyric(
            com.grape.player.data.LyricEntry(
                url = url,
                lrc = lyric,
                translatedLrc = translation,
                offsetMs = existingLyric?.offsetMs ?: offsetMs,
                // 内嵌歌词格式是「这首歌怎么显示」，与正文是不是被换过无关，一律继承
                translationFirst = existingLyric?.translationFirst ?: false,
                source = resolvedSource,
                updatedAt = writtenAt,
            )
        )
        if (lyric.isNotBlank()) {
            repo.setSetting(lyricOffKey(url), "")
        } else if (hadLyric) {
            repo.setSetting(lyricOffKey(url), "1")
        }
        // 同步播放器内存中的当前项，标题/封面/歌词立即刷新（queueVersion 同时触发歌词页重载）
        player.updateItemMeta(url, title.trim(), artist.trim(), album.trim(), finalCover, lyric)
    }

    /**
     * 采用内嵌元数据（Embedded_ → Using_）：整组覆盖显示字段与封面（内嵌封面文件在 EmbeddedCovers/），
     * 内嵌有歌词时同步为当前歌词（内嵌无译文），来源记「内嵌」。
     *
     * 注意：竖三点信息弹层里的「采用内嵌元数据」按钮已随内嵌区块一起移除（弹窗太长），
     * 所以本方法目前没有 UI 入口。保留实现是为了随时能接回入口，不要当死代码删掉。
     */
    fun adoptEmbeddedMeta(url: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val item = repo.getByUrl(url) ?: return@launch
            repo.adoptMeta(
                url = url,
                title = item.embTitle.ifBlank { item.title },
                artist = item.embArtist.ifBlank { item.artist },
                album = item.embAlbum.ifBlank { item.album },
                year = if (item.embYear > 0) item.embYear else item.year,
                trackNumber = if (item.embTrackNumber > 0) item.embTrackNumber else item.trackNumber,
                discNumber = if (item.embDiscNumber > 0) item.embDiscNumber else item.discNumber,
                coverUrl = item.embCoverUrl.ifBlank { item.coverUrl },
                source = com.grape.player.data.MediaItem.META_SOURCE_EMBEDDED,
            )
            if (item.embLyric.isNotBlank()) {
                val existing = repo.getLyric(url)
                repo.saveLyric(
                    com.grape.player.data.LyricEntry(
                        url = url,
                        lrc = item.embLyric,
                        translatedLrc = "",
                        offsetMs = existing?.offsetMs ?: 0L,
                        translationFirst = existing?.translationFirst ?: false,
                        source = LyricSource.EMBEDDED,
                        updatedAt = System.currentTimeMillis(),
                    )
                )
            }
            lyricMiss.remove(url)
            player.updateItemMeta(
                url,
                item.embTitle.ifBlank { item.title },
                item.embArtist.ifBlank { item.artist },
                item.embAlbum.ifBlank { item.album },
                item.embCoverUrl.ifBlank { item.coverUrl },
            )
        }
    }

    /** 批量保存：对每首歌只覆盖勾选且有值的字段 */
    suspend fun applyMetaBatch(urls: List<String>, changes: Map<String, String>) = withContext(Dispatchers.IO) {
        // 封面字段同样先落盘（一个 URL 下载一次，多首歌复用同一文件）
        val resolved = changes.mapValues { (field, value) ->
            if (field == "coverUrl" && value.isNotBlank())
                com.grape.player.util.CoverStore.downloadToLocal(app, value.trim()) ?: value.trim()
            else value.trim()
        }
        urls.forEach { url ->
            resolved.forEach { (field, value) ->
                if (value.isNotBlank()) repo.updateMetaField(url, field, value)
            }
            // 手动批量编辑 = 「手动」来源：只写 Using_（Embedded_ 快照不动）
            repo.setMetaSource(url, com.grape.player.data.MediaItem.META_SOURCE_MANUAL)
            // 同步播放器内存项（若该曲在播放列表中）
            val fresh = repo.getByUrl(url)
            if (fresh != null) {
                player.updateItemMeta(url, fresh.title, fresh.artist, fresh.album, fresh.coverUrl)
            }
        }
    }

    /** 批量编辑页：取所选曲目（展示封面用）—— 直接查库，不依赖列表页里可能已过期的快照 */
    suspend fun metaItems(urls: List<String>): List<MediaItem> =
        withContext(Dispatchers.IO) { repo.getByUrls(urls) }

    /**
     * 批量「清空元数据」：把勾选字段清回空值，然后立刻重启一轮补全。
     *
     * 清完必须主动 [com.grape.player.network.MetadataEnricher.start]：补全平时只由
     * 「扫描完成」和「pending=1 续跑」触发，这里不启动的话，用户还得自己再扫一遍才看得到结果，
     * 与按钮的意图（清空 → 重新自动补全）不符。
     * 但补全开关（autoScan）关着时不联网、只清数据 —— 返回 false，由界面说明原因。
     */
    suspend fun clearMetaBatch(urls: List<String>, fields: Set<String>): Boolean =
        withContext(Dispatchers.IO) {
            repo.clearMetaFields(urls, fields)
            if ("lyric" in fields) {
                // 「我手动清空过歌词，别再自动搜」这个标记必须一起清掉，否则清完也不会重新匹配
                urls.forEach { runCatching { repo.setSetting(lyricOffKey(it), "") } }
                // 「已确认无歌词」的内存负缓存同理（库里的那份随 lyrics 行删除一起消失）
                urls.forEach { lyricMiss.remove(it) }
            }
            // 播放器内存项要跟着更新，否则正在播的那首还显示旧艺人 / 封面 / 歌词
            urls.forEach { url ->
                repo.getByUrl(url)?.let {
                    player.updateItemMeta(
                        url, it.title, it.artist, it.album, it.coverUrl,
                        lyric = if ("lyric" in fields) "" else null,
                    )
                }
            }
            val autoEnrich = try {
                settings.autoScan.firstOrNull() ?: true
            } catch (_: Exception) {
                true
            }
            if (!autoEnrich) return@withContext false
            com.grape.player.network.MetadataEnricher.start(
                repo, scraper, app, restart = true, providers = enrichConfig(),
            )
            true
        }

    sealed interface MetaEditState {
        data object Closed : MetaEditState
        data class Single(val url: String) : MetaEditState
        data class Batch(val urls: List<String>) : MetaEditState
    }
    companion object {
        private const val KEY_ACCENT = "accent_color"
        private const val KEY_DYNAMIC_COLOR = "dynamic_color"
        private const val KEY_PLAY_MODE = "play_mode"

        /**
         * 播放记忆：上次的队列（url 用 \n 连接）/ 当前下标 / 播放位置（毫秒）。
         * 存 Room 的 setting 表，跟播放模式同一处；见 persistPlayback / restoreLastPlayback。
         */
        private const val KEY_LAST_QUEUE = "last_play_queue"
        private const val KEY_LAST_INDEX = "last_play_index"
        private const val KEY_LAST_POS = "last_play_pos"

        /** 「已确认无歌词」负缓存的有效期：过期后自动重试一次内嵌 + 在线（应对网络瞬断误标 / 数据源补齐） */
        private const val LYRIC_MISS_TTL_MS = 24L * 60 * 60 * 1000

        /** 「这次没拿到」的退避时间。与 [LYRIC_MISS_TTL_MS] 分开：
         * 网络失败只值一次短退避（防止切歌抖动时瞬间打满三个源），不许升级成 24h 黑名单。
         */
        private const val LYRIC_RETRY_DELAY_MS = 60L * 1000

        /**
         * 记「最近播放」前要求的最小播放进度。
         * 有它才能把「真的听了」和「划过去看一眼」区分开：流打不开的歌（进度恒为 0）
         * 也不会占着最近播放的位置。
         */
        private const val RECENT_PLAY_MIN_MS = 2_000L

    }

    override fun onCleared() {
        player.disconnect()
        lyricScope.cancel()
        super.onCleared()
    }

    /** 保存服务器设置并立刻刷新播放层使用的认证快照，无需重启 */
    /** 保存整个 WebDAV 源列表（新增 / 编辑 / 删除都走这里），并立即同步给播放侧凭据快照 */
    suspend fun saveServer(sources: List<WebDavSource>) {
        settings.setWebDavSources(sources)
        com.grape.player.data.WebDavCredentials.update(sources.filter { it.isValid })
    }

    /**
     * 扫描：先快速建库（几秒级），完成后把元数据补全丢到后台跑。
     * 后台任务按 url 逐首填空，Room 的 Flow 会让界面实时刷新。
     *
     * 多源：逐个源扫描并把结果合并进同一个库。单个源「删差集」会把别的源的条目当成多余项，
     * 所以每个源都传 deleteStale = false，全部扫完再用全量 url 统一清理 ——
     * 而且只有在「所有源都成功」时才清，某个源临时连不上不至于把它的歌全删掉。
     */
    fun startScan(sources: List<WebDavSource>) {
        val usable = sources.filter { it.isValid }
        if (usable.isEmpty()) {
            _scanState.value = ScanUiState.Error("还没有可用的 WebDAV 源，请先在服务器设置里添加")
            return
        }
        if (_scanState.value is ScanUiState.Scanning) return
        // 扫描期间必须先让补全停下来：补全逐首写库，扫描按批 REPLACE 整行覆盖，
        // 两条线并跑时扫描会用「启动时读到的旧快照」把补全刚写好的字段盖回去。
        // 在途的那几首直接作废（扫完 start(restart = true) 会重新补，不差这一轮）；
        // 扫描被用户取消时不重启，pending 保留，下次启动 resumeIfNeeded 会接着补。
        com.grape.player.network.MetadataEnricher.cancel(discardInFlight = true)
        scanCancelRequested = false
        // 手动扫也算「本进程已经扫过」：之后再回到前台不必再自动扫一遍
        launchScanDone = true
        com.grape.player.data.WebDavCredentials.update(usable)
        scanJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val autoEnrich = settings.autoScan.firstOrNull() ?: true
                val providers = enrichConfig()
                var total = 0
                var music = 0
                var video = 0
                var failed = 0
                var failedSources = 0
                var cancelled = false
                val allUrls = HashSet<String>()
                for ((index, src) in usable.withIndex()) {
                    if (scanCancelRequested) {
                        cancelled = true
                        break
                    }
                    // 多源时在阶段文案前标上「第几个源」，用户才知道现在扫到哪儿了
                    val prefix = if (usable.size > 1) "[${index + 1}/${usable.size}] " else ""
                    val result = try {
                        scanner.scan(
                            webdavUrl = src.url,
                            user = src.user,
                            pass = src.pass,
                            davRoot = src.rootPath,
                            onProgress = { progress ->
                                _scanState.value = ScanUiState.Scanning(
                                    prefix + progress.phase,
                                    progress.current,
                                    progress.total,
                                )
                            },
                            // 用户点了「取消扫描」→ 扫描器停止枚举、保存已扫到的条目后正常返回
                            shouldStop = { scanCancelRequested },
                            deleteStale = false,
                        )
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // 单个源失败（地址写错 / 网盘掉线）不该拖垮整次扫描，记下来继续下一个
                        failedSources++
                        android.util.Log.e(
                            "AppViewModel",
                            "scan source failed: ${src.displayAddress()}",
                            e,
                        )
                        continue
                    }
                    total += result.total
                    music += result.music
                    video += result.video
                    failed += result.failed
                    allUrls += result.urls
                    if (result.cancelled) {
                        cancelled = true
                        break
                    }
                }
                if (allUrls.isEmpty() && failedSources == usable.size) {
                    _scanState.value = ScanUiState.Error("所有 WebDAV 源都扫描失败，请检查地址与账号")
                    return@launch
                }
                if (!cancelled && failedSources == 0 && allUrls.isNotEmpty()) {
                    _scanState.value = ScanUiState.Scanning("正在清理已移除的文件", 0, 1)
                    repo.deleteStaleMedia(allUrls)
                }
                if (cancelled) {
                    // 用户主动取消：已扫到的条目已由扫描器 upsert 进库，这里只切状态、不启动补全
                    _scanState.value = ScanUiState.Cancelled(total)
                    return@launch
                }
                _scanState.value = ScanUiState.Done(total, music, video)
                if (autoEnrich) {
                    // restart=true：重扫后旧任务处理的 url 已失效，必须换新一批
                    com.grape.player.network.MetadataEnricher.start(
                        repo, scraper, app, restart = true, providers = providers,
                    )
                }
            } catch (e: CancellationException) {
                // 用户退出 / 停止应用（硬停止）：什么都不写库，恢复 Idle 后继续向上抛
                _scanState.value = ScanUiState.Idle
                throw e
            } catch (e: Exception) {
                // 失败：只把错误显示在本会话；下次启动要不要重扫由「下次启动继续扫描」决定
                _scanState.value = ScanUiState.Error(e.message ?: "扫描失败")
            }
        }
    }

    /**
     * 取消扫描 —— 立即停止枚举，并把已经扫到的条目保存进库（设置页「取消扫描」按钮）。
     *
     * 注意这里**不是**直接 scanJob.cancel()：硬取消会抛 CancellationException，
     * 枚举结果全丢、库还是老的，下次启动还得整个重扫一遍。
     * 所以改成软停止：扫描器在下一个目录检查点跳出枚举，把半成品 upsert 进库（不清库，
     * 没扫到的旧条目原样保留），然后以 cancelled 正常返回。
     * 取消后不启动元数据补全 —— 用户要的就是「现在别干了」。
     */
    fun cancelScan() {
        if (_scanState.value !is ScanUiState.Scanning) return
        android.util.Log.i("AppViewModel", "cancelScan: 用户取消扫描，保存已扫描的内容")
        scanCancelRequested = true
    }

    /**
     * 停止后台元数据补全（设置页「停止补全」按钮）。
     *
     * 停止语义（用户拍板）：
     *  - **本次运行**：立刻停，且不再自动续跑 —— stopByUser 的进程内标记拦住
     *    onStart 触发的 resumeIfNeeded（裸 cancel 保留断点的话，切后台再切回
     *    就会被原地重启，表现为「停不掉」）；
     *  - **下次冷启动**：也不自动续跑 —— 同时把 enrich_pending 清成 0；
     *  - 想再补只有两条路：「下次启动继续扫描」开关开着（每次启动的自动扫描
     *    结束后会重新触发补全），或再点一次「扫描网盘媒体库」。
     *
     * 待补清单本身无损失：它每次都从库里「缺字段的歌」现查，不依赖 pending 标记。
     */
    fun cancelEnrich() {
        // 清持久断点（异步落库即可；进程内的 userStopped 标记已先拦住本会话的续跑竞态）
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { repo.setSetting(com.grape.player.network.MetadataEnricher.KEY_PENDING, "0") }
        }
        com.grape.player.network.MetadataEnricher.stopByUser()
    }

    /**
     * 停止一切后台取数任务（扫描 + 元数据补全）。
     * 这是「应用退出」用的硬停止：直接取消协程，网络请求立刻断开。
     * 与 [cancelScan] 的区别：这里不写库（半成品由下次启动的扫描重新覆盖），
     * 下次启动是否继续扫描由设置里的「下次启动继续扫描」决定。
     */
    fun stopBackgroundWork() {
        android.util.Log.i("AppViewModel", "stopBackgroundWork: 停止扫描与补全")
        scanJob?.cancel()
        scanJob = null
        if (_scanState.value is ScanUiState.Scanning) _scanState.value = ScanUiState.Idle
        com.grape.player.network.MetadataEnricher.cancel()
    }

    /**
     * 维护操作（清除数据库 / 删除所有在线数据）前的硬停止扫描。
     *
     * 与 [cancelScan] 的软停止不同：软停止要「把已经扫到的条目存进库」，
     * 而这里用户马上要清掉数据，扫描协程再写下去就是把刚清掉的东西灌回来
     * （它的 upsert 还带着扫描启动时读到内存的旧行快照，连内嵌字段一起复活）。
     * 取消后要等它真的退出 —— 它可能正好卡在 `saveMedia` 那一批（300 条）里，
     * 不等就会在清库之后把这一批提交进去。网络请求是阻塞式的、取消只能等它自己返回，
     * 所以等待设上限：超时说明它卡在枚举阶段，那里之后没有写库点，可以放心继续。
     */
    private suspend fun stopScanForMaintenance() {
        val job = scanJob
        scanJob = null
        if (job != null && job.isActive) {
            android.util.Log.i("AppViewModel", "维护操作：硬停止扫描协程")
            job.cancel()
            withTimeoutOrNull(3_000) { job.join() }
        }
        // 状态回 Idle：否则界面会继续显示一个和实际库对不上的进度，最后还报「完成 N 首」
        if (_scanState.value is ScanUiState.Scanning) _scanState.value = ScanUiState.Idle
    }

    /**
     * 界面回到前台时调用（MainActivity.onStart）：续跑上次没做完的事。
     *  - 元数据补全：设置里「扫描时补全元数据」开着 → 接着补；
     *  - 扫描：设置里「下次启动继续扫描」开着 → 用保存的配置扫一遍（每个进程只自动扫一次）。
     *
     * 幂等：补全靠 [MetadataEnricher.start] 的 isActive 检查，扫描靠 launchScanDone +
     * scanJob/状态判断，所以每次 onStart 都不会重复开跑。也不能只放在 onCreate ——
     * 用户从最近任务划掉后再点开时 Activity 常被复用，onCreate 不会重跑。
     */
    fun resumeBackgroundTasks() {
        viewModelScope.launch(Dispatchers.IO) {
            // ⓪ 封面自愈：引用的文件丢了先清引用/回退快照，让补全队列重新接管
            runCatching { healCoverRefs() }

            val autoEnrich = try {
                settings.autoScan.firstOrNull() ?: true
            } catch (_: Exception) {
                true
            }

            // ① 元数据补全：上次被中断就接着补（沿用当前设置里启用的数据源）。
            //    只受「扫描时补全元数据」开关控制，不再顺便把扫描一起挡掉 —— 两个开关各管各的。
            if (autoEnrich) {
                val providers = enrichConfig()
                com.grape.player.network.MetadataEnricher.resumeIfNeeded(
                    repo, scraper, app, providers = providers,
                )
            }

            // ② 扫描：受「下次启动继续扫描」开关控制（默认关）。
            //    开 = 每次打开应用都用保存的配置扫一遍（上次被打断没扫完的也在这里接上）；
            //    关 = 启动不自动扫描，只能手动点「扫描网盘媒体库」。
            //    onStart 每次回前台都会调用，launchScanDone 保证一个进程里只自动扫一次。
            val scanOnLaunch = try {
                settings.scanOnLaunch.firstOrNull() ?: false
            } catch (_: Exception) {
                false
            }
            if (!scanOnLaunch) return@launch
            if (launchScanDone) return@launch
            // 正在扫、本会话刚失败过（Error）都不再自动重试，避免反复打 OpenList
            val state = _scanState.value
            if (state is ScanUiState.Scanning || state is ScanUiState.Error) return@launch
            if (scanJob?.isActive == true) return@launch
            val sources = settings.webDavSources.firstOrNull().orEmpty().filter { it.isValid }
            if (sources.isEmpty()) return@launch
            startScan(sources)
        }
    }

    /**
     * 强制重新补全已移除（设置页不再提供入口）。
     */

    /** 读取启用的元数据源（qq/kugou/netease；优先级固定 QQ → 酷狗 → 网易云，与存储顺序无关） */
    private suspend fun enrichConfig(): List<String> {
        val raw = try {
            settings.enabledProviders.firstOrNull() ?: "qq,kugou,netease"
        } catch (_: Exception) {
            "qq,kugou,netease"
        }
        return raw.split(',').map { it.trim() }.filter { it.isNotBlank() }
    }

    /**
     * 歌词匹配用的源 —— 与 [enrichConfig] 的区别是**永远不会为空**。
     *
     * 那个设置项本意是「后台补全元数据时用哪些源」，UI 允许把三个全关掉、存成空串。
     * 但歌词是播放刚需：源全关后 [enrichConfig] 返回空列表，
     * `matchLyric` 一个请求都不发就直接失败 ——
     * 现象就是「播放页永远没有歌词，而元数据弹窗里手动搜能搜到」
     * （手动搜索用的是另一套 manual_search_providers）。
     *
     * 所以这里做两级兜底：元数据源 → 手动搜索源 → 默认三源。
     * 真要彻底不要联网歌词，那是播放设置里「在线搜索」总闸的事，不是靠清空源列表。
     */
    private suspend fun lyricProviders(): List<String> {
        val main = enrichConfig()
        if (main.isNotEmpty()) return main
        val manual = try {
            settings.manualSearchProviders.firstOrNull().orEmpty()
        } catch (_: Exception) {
            ""
        }
        val fromManual = manual.split(',').map { it.trim() }.filter { it.isNotBlank() }
        return fromManual.ifEmpty {
            listOf(
                MetadataScraper.SOURCE_QQ,
                MetadataScraper.SOURCE_KUGOU,
                MetadataScraper.SOURCE_NETEASE,
            )
        }
    }

    /**
     * 一次性作废旧版本误写的「无歌词」负缓存。
     *
     * 旧逻辑把「设置里一个源都没启用」当成「确认没有歌词」，于是连续播过的每首歌
     * 都被打上 missAt、24 小时内不再重试 —— 用户在设置里把源关掉之后，
     * 这个黑名单会一直跟着走，改回设置也不会自动解除。
     * 这批记录不可信，升级后清一次；清完靠标记位避免每次启动都跑。
     */
    private suspend fun resetStaleLyricMissOnce() {
        val key = "lyric_miss_reset_v1"
        if (runCatching { repo.getSetting(key) }.getOrNull() == "1") return
        runCatching {
            repo.resetLyricMiss()
            repo.setSetting(key, "1")
        }
    }

    /**
     * 一次性清理按旧格式命名的派生设置键（细节见 SettingDao.deleteLegacyDerivedKeys）：
     *  - `stream_gain_*`：只写不读的增益缓存，播一首留一行；
     *  - `accent_url_*` / `lyric_off_*`：`String.hashCode()` 做的键，换成 MD5 后旧行已无人读取。
     *
     * 代价（可接受）：旧 `accent_url_*` 只是取色缓存，下次播放重算一次即可；
     * 旧 `lyric_off_*` 是「手动清空歌词后别再自动搜」的标记，丢失意味着那几首会重新搜一次，
     * 找不到词也不会留下错误数据。相比 settings 表无限长，这点代价更划算。
     */
    private suspend fun clearLegacyKeyedSettingsOnce() {
        val key = "derived_keys_reset_v1"
        if (runCatching { repo.getSetting(key) }.getOrNull() == "1") return
        repo.deleteLegacyDerivedKeys()
        repo.setSetting(key, "1")
    }

    fun resetScanState() {
        _scanState.value = ScanUiState.Idle
    }

    sealed interface ScanUiState {
        data object Idle : ScanUiState
        data class Scanning(val phase: String, val current: Int, val total: Int) : ScanUiState
        data class Done(val total: Int, val music: Int, val video: Int) : ScanUiState

        /** 用户主动取消：已把扫到的 [total] 条存进库（没扫到的旧条目保留） */
        data class Cancelled(val total: Int) : ScanUiState
        data class Error(val message: String) : ScanUiState
    }
}

/**
 * 上次播放的存档（从 setting 表读出来的原始三件套）。
 * 只存 url：恢复时再按 url 回库换成最新条目。
 */
private data class RestoredPlayback(
    val urls: List<String>,
    val index: Int,
    val positionMs: Long,
)

/**
 * Flow 转 StateFlow 的小工具。
 * [throttleMs] > 0 时对发射做合并（conflate + 窗口延时）：后台补全每 120ms 写一次库，
 * 不节流会让整表查询 + 列表重组反复占主线程（手动搜索就会像点不动）。
 */
private fun <T> kotlinx.coroutines.flow.Flow<T>.toStateFlow(
    scope: kotlinx.coroutines.CoroutineScope,
    initial: T,
    throttleMs: Long = 0L,
): StateFlow<T> {
    val state = MutableStateFlow(initial)
    scope.launch {
        if (throttleMs <= 0L) {
            collect { state.value = it }
        } else {
            conflate().collect { value ->
                state.value = value
                delay(throttleMs)
            }
        }
    }
    return state.asStateFlow()
}
