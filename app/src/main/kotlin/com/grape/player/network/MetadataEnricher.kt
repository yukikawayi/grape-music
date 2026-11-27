package com.grape.player.network

import android.content.Context
import com.grape.player.data.Repository
import com.grape.player.util.CoverStore
import com.grape.player.util.LyricQuality
import com.grape.player.util.LyricSource
import com.grape.player.util.MediaPathUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * 元数据后台补全 —— 扫描只负责「把库建起来」，联网刮削放在这里慢慢做。
 *
 * 设计要点：
 * - 独立于 ViewModel 的协程作用域，退出页面/锁屏都继续跑；
 * - 每首刮到结果就立刻写库（只填空不覆盖），Room 的 Flow 会让界面实时刷新，不用重启；
 * - 「未完成」标记落在 settings 表里，被系统杀掉进程后下次启动自动续跑；
 * - 并发 4 只用于本地 IO（内嵌标签读取 / 封面落盘）；对第三方接口的真实速率由
 *   [SourceRateGuard] 全局把关（同一源全局最小间隔 + 连续失败熔断），
 *   4 个 worker 各自的 120ms 间隔只是兜底，不再决定对外请求频率。
 */
object MetadataEnricher {

    data class State(
        val running: Boolean = false,
        val done: Int = 0,
        val total: Int = 0,
        val current: String = "",
        val finishedOnce: Boolean = false,
    )

    /**
     * 待处理任务。
     *
     * [snapshotOnly] = true 表示这首只是「内嵌快照还没读过」（embTitle 为空），
     * 处理时只读内嵌标签写 Embedded_ 快照 + 填 Using_ 的空缺，**不联网刮削、也不改标题**。
     * 老版本只按「Using_ 有缺口」挑任务，字段早被在线刮削填满的歌永远进不了队列，
     * 内嵌快照就一直空着，内嵌元数据窗口里全是「（无）」。
     */
    private data class Task(val item: com.grape.player.data.MediaItem, val snapshotOnly: Boolean)

    /** 存在 settings 表里的续跑标记 */
    const val KEY_PENDING = "enrich_pending"

    private const val CONCURRENCY = 4
    private const val REQUEST_GAP_MS = 120L

    /**
     * 在线补全「一无所获」后的退避时长。
     *
     * 上游对一部分歌**永远**给不出某些字段（最典型的是年份，很多老歌三家音乐库都不带），
     * 而待补清单只看「字段是否为空」—— 没有退避时这些歌每次扫描结束都要重新搜一遍，
     * 请求白烧，界面上还看不出来（每轮都算 done）。
     *
     * 72 小时是个折中：源数据变化很慢，但用户换了在线源、或上游补了资料之后也不至于等太久。
     * 用户主动「清空元数据」/「删除所有在线数据」会把退避归零，立刻重试。
     */
    private const val META_MISS_TTL_MS = 72L * 60 * 60 * 1000

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    /**
     * 写库代际。只有 [cancel] 带 discardInFlight 时自增。
     *
     * 协程取消是协作式的：`job.cancel()` 之后，已经走到「读标签 / 下载封面完成、还没写库」
     * 的那几首仍可能在被取消前把事务提交掉。清库 / 删在线数据这类操作必须连这部分结果
     * 一起丢掉（否则刚清掉的数据被在途任务写回来），所以用一个代际号做最后一道闸：
     * 任务开工时记下代际，真正写库前对比，不一致就直接放弃这首、连 worker 一起退出。
     */
    @Volatile private var epoch = 0

    /**
     * 用户在本进程内主动停止过补全（设置页「停止补全」→ AppViewModel.cancelEnrich）。
     *
     * 停止语义（用户拍板）：本进程内不自动续跑，**下次冷启动也不续** ——
     * cancelEnrich 同时会把 enrich_pending 清成 0（持久），想再补只能靠
     * 「下次启动继续扫描」开关（自动扫描完成后重新触发）或再点一次手动扫描。
     *
     * 这个进程内标记的剩余职责是堵本会话的竞态：cancelEnrich 清 pending 是异步落库，
     * 若用户点完立刻切后台再切回，onStart 的 resumeIfNeeded 可能赶在落库前读到旧的
     * pending=1 —— 有标记就不会误启动。任何显式 start() 都会清掉标记。
     */
    @Volatile private var userStopped = false

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /**
     * 开始（或续跑）补全。
     * [restart] = true 时先取消既有任务（例如刚重扫完库，旧任务处理的 url 已经失效）。
     * 流程：① 内嵌标签 → Embedded_ 快照整体覆盖 + Using_ 只填空复制；
     *       ② 启用的网络源按固定优先级（QQ → 酷狗 → 网易云）给 Using_ 补缺失字段，
     *          在线结果不再写快照（Online_ 已移除），刮到的封面落 OnlineCovers/。
     */
    fun start(
        repo: Repository,
        scraper: MetadataScraper,
        context: Context,
        restart: Boolean = false,
        /** 启用的数据源（qq/kugou/netease 子集）；优先级固定 QQ → 酷狗 → 网易云，一首歌只取第一个有结果的源 */
        providers: List<String> = emptyList(),
    ) {
        if (restart) {
            job?.cancel()
            job = null
        } else if (job?.isActive == true) {
            return
        }
        // 显式开跑 = 用户重新要补了，解除「主动停止」的暂停
        userStopped = false

        job = scope.launch {
            val myEpoch = epoch
            try {
                // ① 元数据不完整（artist / album / coverUrl / year 有空缺）→ 内嵌标签 + 在线兜底。
                //    近期已经被在线源「明确回答过没有」的歌先跳过，别每轮都白搜一次
                val pendingMeta = repo.getNeedingMeta(
                    retryBefore = System.currentTimeMillis() - META_MISS_TTL_MS,
                )
                // ② 内嵌快照还没读过 → 只读标签，不联网。和 ① 重叠的以 ① 为准（避免同一首做两遍）
                val metaUrls = pendingMeta.mapTo(HashSet()) { it.url }
                val pendingSnapshot = repo.getNeedingEmbedded().filterNot { it.url in metaUrls }
                val tasks = buildList {
                    pendingSnapshot.forEach { add(Task(it, snapshotOnly = true)) }
                    pendingMeta.forEach { add(Task(it, snapshotOnly = false)) }
                }
                android.util.Log.i(
                    TAG,
                    "start: 内嵌快照回填 ${pendingSnapshot.size} 首，元数据补全 ${pendingMeta.size} 首",
                )
                if (tasks.isEmpty()) {
                    _state.value = State(running = false, finishedOnce = true)
                    repo.setSetting(KEY_PENDING, "0")
                    return@launch
                }

                repo.setSetting(KEY_PENDING, "1")
                val total = tasks.size
                val done = AtomicInteger(0)
                _state.value = State(running = true, done = 0, total = total)

                val queue = ConcurrentLinkedQueue(tasks)
                val appCtx = context.applicationContext
                coroutineScope {
                    repeat(CONCURRENCY) {
                        launch {
                            while (true) {
                                val task = queue.poll() ?: break
                                // 代际变了（清库 / 删在线数据）：这首以及后面排队的全部作废，worker 直接收工
                                if (myEpoch != epoch) break
                                val item = task.item
                                val snapshotOnly = task.snapshotOnly
                                _state.update { it.copy(current = item.title) }

                                // ① 内嵌标签优先：Range 拉文件头到 cacheDir 读取（视频跳过）。
                                //    Embedded_ 快照整体覆盖（封面落 EmbeddedCovers/）；
                                //    Using_ 只填空：标题 / 艺人 / 专辑 / 年份 / 音轨号 / 碟号 / 时长 / 封面 / 歌词
                                var title = item.title
                                var artist = item.artist
                                var album = item.album
                                var year = item.year
                                var trackNumber = item.trackNumber
                                var discNumber = item.discNumber
                                var coverUrl = item.coverUrl
                                var durationMs = item.durationMs
                                // 标题只有在还是「文件名推导的原样」时才允许被覆盖，
                                // 用户手动改过的标题（≠ 推导值）永远不碰
                                val derivedTitle = MediaPathUtil.titleFromPath(item.url)
                                // 内嵌阶段是否真的向 Using_ 填入了字段（决定 metaSource 记不记「内嵌」=1）
                                var embeddedFilled = false
                                if (!item.isVideo) {
                                    try {
                                        val tags = EmbeddedTagReader.read(appCtx, item.url)
                                        if (tags != null) {
                                            // 内嵌封面字节落 EmbeddedCovers/（saveBytes 已按内容 MD5 去重）；
                                            // Using_ 封面与快照同源，提前算好，不让磁盘 IO 进数据库事务
                                            val embCover = if (tags.artwork.isNotEmpty())
                                                CoverStore.saveBytes(appCtx, tags.artwork).orEmpty()
                                            else ""
                                            // 同一份字节不必再调一次 saveBytes：embCover 就是它的落盘结果
                                            // （第二次调用要做一遍 MD5 + 再切一次 IO 线程，纯浪费）
                                            if (coverUrl.isBlank() && embCover.isNotBlank()) coverUrl = embCover
                                            // Using_ 只填空
                                            // 纯快照回填时不碰标题：那批歌的标题多来自在线刮削，
                                            // 一旦恰好等于文件名推导值就会被内嵌值覆盖，改动面太大且不可预期。
                                            // 想看内嵌标题可以去「内嵌元数据」窗口对照，需要时一键采用。
                                            if (!snapshotOnly && tags.title.isNotBlank() && title == derivedTitle) {
                                                title = tags.title
                                            }
                                            if (artist.isBlank() && tags.artist.isNotBlank()) artist = tags.artist
                                            if (album.isBlank() && tags.album.isNotBlank()) album = tags.album
                                            if (year == 0 && tags.year > 0) year = tags.year
                                            if (trackNumber == 0 && tags.trackNumber > 0) trackNumber = tags.trackNumber
                                            if (discNumber == 0 && tags.discNumber > 0) discNumber = tags.discNumber
                                            if (durationMs <= 0 && tags.durationMs > 0) durationMs = tags.durationMs
                                            // 快照覆盖 + 读完标记 + 歌词落库合并为**单事务**：
                                            // 每首歌对 Room 的失效通知从 3 次降到 1 次，
                                            // 补全期间列表 Flow 的重发频率大幅下降（补全时列表卡顿的主源头）
                                            // 代际被换过（清库）就放弃这首：封面字节已落盘，只是无人引用的孤儿
                                            if (myEpoch != epoch) break
                                            repo.inTransaction {
                                                // Embedded_ 快照：文件是唯一事实来源，整体覆盖
                                                repo.saveEmbeddedMeta(
                                                    url = item.url,
                                                    embTitle = tags.title,
                                                    embArtist = tags.artist,
                                                    embAlbum = tags.album,
                                                    embYear = tags.year,
                                                    embTrackNumber = tags.trackNumber,
                                                    embDiscNumber = tags.discNumber,
                                                    embCoverUrl = embCover,
                                                    embLyric = tags.lyric,
                                                )
                                                // 读过就置位：文件无标签时上面全是空值，靠这个标记避免下次再下一遍文件头；
                                                // 读取失败（tags == null）不会走到这里，留给下次补全重试
                                                repo.markEmbRead(item.url)
                                                // 内嵌歌词落 Using（lyrics 表，只填空）：播放时的「内嵌 → 在线」顺序靠它实现，
                                                // DB 里已有内嵌歌词，播放阶段就不用再联网。
                                                // 用事务内的 fillLyricIfBlank：与播放页/切歌预取的写入互不覆盖。
                                                // 没有时间轴的纯文本歌词不落库 —— 落了就是「有歌词」，
                                                // 播放页会显示空白且再也不会去问在线源
                                                if (tags.lyric.isNotBlank() && LyricQuality.isUsable(tags.lyric)) {
                                                    repo.fillLyricIfBlank(
                                                        com.grape.player.data.LyricEntry(
                                                            url = item.url,
                                                            lrc = tags.lyric,
                                                            translatedLrc = "",
                                                            source = LyricSource.EMBEDDED,
                                                        )
                                                    )
                                                }
                                            }
                                            // 内嵌是否真的填了 Using_ 的元字段（时长不算元数据来源）
                                            embeddedFilled = title != item.title ||
                                                artist != item.artist || album != item.album ||
                                                year != item.year ||
                                                trackNumber != item.trackNumber || discNumber != item.discNumber ||
                                                coverUrl != item.coverUrl
                                        }
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (_: Exception) {
                                        // 内嵌读取失败不算致命，继续走在线
                                    }
                                }

                                // ② 仍有缺失 → 在线刮削兜底（按设置里启用的源；优先级固定 QQ → 酷狗 → 网易云）。
                                //    artist / title 若已被内嵌填上，搜索关键词更准；封面仍优先本地落盘。
                                //    在线结果直接只填空写进 Using_，不再写 Online_ 快照（v6 起快照已移除）。
                                var onlineFilled = false
                                // 这一首到底有没有「真的问过在线源」、以及问的过程有没有出错。
                                // 三者的区别决定要不要记失败退避：
                                //   没问（全源熔断/视频/快照任务）→ 不记，下次照常重试
                                //   问了但抛异常（网络/解析）　　→ 不记，属于「这次没问成」
                                //   问了、源也正常回答，却一个字段都没填上 → 记，TTL 内不再白搜
                                var onlineAttempted = false
                                var onlineErrored = false
                                // 视频不进在线分支：音乐源（QQ / 酷狗 / 网易云）拿文件名搜歌，
                                // 匹配到的艺人/专辑是别人的，时长还会被歌曲时长顶掉（之后再也修不回来）。
                                // getNeedingMeta 已经排除了视频，这里是第二道闸，防以后改查询又漏进来。
                                // 熔断中的源先剔掉：全被熔断时整段跳过，不能让 search() 的静默 null
                                // 被当成「上游确认没有」而退避三天
                                val liveProviders = if (snapshotOnly || item.isVideo) {
                                    emptyList()
                                } else {
                                    providers.filter { !SourceRateGuard.isOpen(it) }
                                }
                                if (liveProviders.isNotEmpty() &&
                                    (artist.isBlank() || album.isBlank() || year == 0 || coverUrl.isBlank())
                                ) {
                                    onlineAttempted = true
                                    val titleBeforeOnline = title
                                    val artistBeforeOnline = artist
                                    val albumBeforeOnline = album
                                    val yearBeforeOnline = year
                                    val trackBeforeOnline = trackNumber
                                    val discBeforeOnline = discNumber
                                    val coverBeforeOnline = coverUrl
                                    val meta = try {
                                        // 传真实时长参与打分：库里的 durationMs 多半是 0（要首次播放才回填），
                                        // 但内嵌标签填过时就能用它区分录音室版与现场版
                                        scraper.search(
                                            title = title,
                                            artist = artist,
                                            providers = liveProviders,
                                            durationMs = durationMs,
                                        )
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (_: Exception) {
                                        // 网络/解析失败：这次不算「上游没有」，不记退避
                                        onlineErrored = true
                                        null
                                    }
                                    if (meta != null) {
                                        // 在线封面下载落 OnlineCovers/（与手动编辑下载封面共用目录）
                                        val onlineCover = if (meta.coverUrl.isNotBlank())
                                            CoverStore.downloadToLocal(appCtx, meta.coverUrl).orEmpty()
                                        else ""
                                        // Using_ 只填空
                                        if (meta.title.isNotBlank() && title == derivedTitle) title = meta.title
                                        if (artist.isBlank() && meta.artist.isNotBlank()) artist = meta.artist
                                        if (album.isBlank() && meta.album.isNotBlank()) album = meta.album
                                        if (year == 0 && meta.year > 0) year = meta.year
                                        if (trackNumber == 0 && meta.trackNumber > 0) trackNumber = meta.trackNumber
                                        if (discNumber == 0 && meta.discNumber > 0) discNumber = meta.discNumber
                                        if (durationMs <= 0 && meta.durationMs > 0) durationMs = meta.durationMs
                                        if (coverUrl.isBlank() && onlineCover.isNotBlank()) coverUrl = onlineCover
                                        // 只和进入在线阶段之前的快照比：内嵌填的字段不算「在线」
                                        onlineFilled = title != titleBeforeOnline ||
                                            artist != artistBeforeOnline || album != albumBeforeOnline ||
                                            year != yearBeforeOnline ||
                                            trackNumber != trackBeforeOnline || discNumber != discBeforeOnline ||
                                            coverUrl != coverBeforeOnline
                                    }
                                }

                                // ③④ 统一只填空写库 + 标题条件覆盖，合并为**单事务**（同上，减少失效通知）。
                                //    onlineFilled 把来源记为「在线」=2，embeddedFilled 且未定源记「内嵌」=1；
                                //    标题 WHERE title = 文件名推导值，并发/手动编辑都安全
                                val needFill = onlineFilled || embeddedFilled || artist != item.artist ||
                                    album != item.album || year != item.year || coverUrl != item.coverUrl ||
                                    trackNumber != item.trackNumber || discNumber != item.discNumber ||
                                    durationMs != item.durationMs
                                // 问过、源也正常作答、却一个字段都没补上 → 这一首对当前在线源来说就是「补不到」，
                                // 记上时间戳安静一个 TTL（同一事务里写，省掉一次额外的失效通知）
                                val onlineMiss = onlineAttempted && !onlineErrored && !onlineFilled
                                if (needFill || title != item.title || onlineMiss) {
                                    if (myEpoch != epoch) break
                                    repo.inTransaction {
                                        if (needFill) {
                                            repo.fillBlankMeta(
                                                url = item.url,
                                                artist = artist,
                                                album = album,
                                                year = year,
                                                coverUrl = coverUrl,
                                                durationMs = durationMs,
                                                trackNumber = trackNumber,
                                                discNumber = discNumber,
                                                onlineFilled = onlineFilled,
                                                embeddedFilled = embeddedFilled,
                                            )
                                        }
                                        // ④ 标题条件覆盖
                                        if (title != item.title) {
                                            repo.updateTitleIfMatches(item.url, title, item.title)
                                        }
                                        if (onlineAttempted && !onlineErrored) {
                                            if (onlineMiss) repo.markMetaMiss(item.url)
                                            else repo.clearMetaMiss(item.url)
                                        }
                                    }
                                }
                                _state.update { it.copy(done = done.incrementAndGet()) }
                                delay(REQUEST_GAP_MS)
                            }
                        }
                    }
                }

                _state.value = State(running = false, done = total, total = total, finishedOnce = true)
                repo.setSetting(KEY_PENDING, "0")
                android.util.Log.i(TAG, "done: 已处理 $total 首")
            } catch (e: CancellationException) {
                // 主动取消（重扫/关闭开关）：保留 pending=1，下次还能续
                _state.update { it.copy(running = false) }
                throw e
            } catch (_: Exception) {
                // 网络整体异常：保留 pending=1，下次启动续跑
                _state.update { it.copy(running = false) }
            }
        }
    }

    /** 启动时续跑：上次没跑完就接着来（补全配置与上次一致，由调用方从设置读出传入） */
    fun resumeIfNeeded(
        repo: Repository,
        scraper: MetadataScraper,
        context: Context,
        providers: List<String> = emptyList(),
    ) {
        scope.launch {
            // 用户在本进程里主动停过：不再自动续跑（否则「停止补全」→ 切后台 →
            // 切回前台触发 onStart → 原地重启，永远停不掉）。冷启动后标记消失，照常续。
            if (userStopped) return@launch
            val pending = try {
                repo.getSetting(KEY_PENDING) == "1"
            } catch (_: Exception) {
                false
            }
            if (pending) start(repo, scraper, context, providers = providers)
        }
    }

    /**
     * 停止补全。
     *
     * [discardInFlight] = false（默认，「停止补全」按钮 / 关闭补全开关）：断点保留，
     * 在途的那几首照样写库 —— 用户只是不想继续跑，已经刮出来的结果没必要扔。
     * [discardInFlight] = true（清库 / 删在线数据）：自增代际，让在途任务在写库前放弃，
     * 否则刚清掉的数据会被这几首写回来。
     */
    fun cancel(discardInFlight: Boolean = false) {
        if (discardInFlight) epoch++
        job?.cancel()
        job = null
        _state.update { it.copy(running = false) }
        android.util.Log.i(
            TAG,
            if (discardInFlight) "cancel: 已停止补全并作废在途结果"
            else "cancel: 已停止补全（断点保留，下次打开应用续跑）",
        )
    }

    /**
     * 用户主动停止（设置页「停止补全」）：进程内标记拦住本会话的自动续跑；
     * enrich_pending 的持久清理由调用方（AppViewModel.cancelEnrich）负责。
     * startScan / 清库等内部取消仍走 [cancel]，不能挡住后续的自动补全。
     */
    fun stopByUser() {
        userStopped = true
        cancel()
    }

    private const val TAG = "MetadataEnricher"
}
