package com.grape.player.playback

import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.grape.player.BuildConfig
import com.grape.player.data.MediaItemLite as DbMediaItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 播放控制器 —— UI 通过它操作 MediaController（跨进程连接到 PlaybackService）。
 * 维护播放列表、播放模式、当前曲目等状态。
 */
class PlayerController(private val context: Context) {

    enum class PlayMode { SEQUENCE, LOOP, RANDOM }

    data class PlayerState(
        val isPlaying: Boolean = false,
        val positionMs: Long = 0,
        val durationMs: Long = 0,
        val currentIndex: Int = -1,
        val playMode: PlayMode = PlayMode.SEQUENCE,
        val volume: Float = 1f,
        /**
         * 队列版本号 —— 每次 setPlaylist / 队列增删改都 +1。
         * 仅仅 currentIndex 相同（例如换列表后仍从第 0 首开始）时也要让 UI 重新读取队列，
         * 否则迷你播放栏会一直显示上一份列表的曲目。
         */
        val queueVersion: Int = 0,
        /**
         * 缓冲停滞：BUFFERING 态下连续 [STALL_TIMEOUT_MS] 没有任何数据推进
         * （bufferedPosition 不动）。UI 据此显示「网络缓慢」提示，
         * 避免网络劣化时表现为「无缘无故卡住」。由 [checkBufferStall] 周期更新。
         */
        val bufferStalled: Boolean = false,
        /**
         * 最近一次播放错误（null = 正常）。短文案，供迷你栏直接显示。
         *
         * 为什么必须进 state：ExoPlayer 的错误是「静默」的 —— 出错后播放器退回 IDLE，
         * 既不自动切下一首、也不再出声，而所有 play()/seekToXxx() 只改意图不重新加载。
         * 没有这个字段时，用户看到的就是「这首播完就再也不动了」，连一句提示都没有。
         */
        val playbackError: String? = null,
    )

    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    private var controllerFuture: ListenableFuture<MediaController>? = null
    var controller: MediaController? = null
        private set

    /** MediaController 的所有方法只能在主线程调用（否则抛 IllegalStateException 直接崩） */
    private val mainHandler = Handler(Looper.getMainLooper())

    private var playlist: List<DbMediaItem> = emptyList()

    /**
     * 控制器还没连上时按下的播放请求 —— 先记下来，连上后立刻执行。
     * 否则冷启动后手快点了歌，会被 `controller ?: return` 静默丢掉（表现为「点了没反应、播放栏也不出现」）。
     */
    private var pendingPlay: Pair<List<DbMediaItem>, Int>? = null

    /**
     * 「队列只在 UI 侧、播放器里还是空的」——启动时恢复上次播放就是这种状态。
     *
     * 有它是因为：一旦把队列灌进 ExoPlayer 并 prepare，就会立刻从网盘拉流缓冲；
     * 用户可能只是打开 App 看一眼列表，不该替他耗流量。所以恢复时只落 UI，
     * 等真正按播放 / 切歌 / 拖进度条时才 materialize。
     */
    private var needsMaterialize = false

    /** 恢复出来的播放位置（materialize 时作为起点；用户还没开播就拖进度条也会改它） */
    private var resumePositionMs = 0L

    /**
     * 连上播放服务后发现它里面已经有队列（上次播放没停、进程被回收但播放服务还活着，
     * 或 Activity 重建）：回调 url 列表 + 当前下标，由 VM 换成库里的条目再 [adoptQueue]。
     * 不接这个回调的话，迷你栏会显示「未播放」而音频其实在响。
     */
    var onSessionQueueFound: ((urls: List<String>, currentIndex: Int) -> Unit)? = null

    /** 连接到前台播放服务 */
    fun connect() {
        if (controllerFuture != null) return
        val sessionToken = SessionToken(
            context,
            ComponentName(context, PlaybackService::class.java),
        )
        controllerFuture = MediaController.Builder(context, sessionToken).buildAsync()
        controllerFuture?.addListener({
            controller = try {
                controllerFuture?.get()
            } catch (_: Exception) {
                null
            }
            controller?.addListener(playerListener)
            controller?.let { ctl ->
                // 把 UI 侧记录的模式同步给播放器。
                // 冷启动 / 进程重建 / 服务重连后，ExoPlayer 的 repeatMode 可能是 OFF，
                // 而界面上还显示着单曲循环 —— 只改 UI 不改播放器，就是「模式不起作用」。
                applyPlayMode(ctl, _state.value.playMode)
                val pending = pendingPlay
                pendingPlay = null
                if (pending != null) {
                    applyPlaylist(ctl, pending.first, pending.second)
                } else if (ctl.mediaItemCount > 0 && (playlist.isEmpty() || needsMaterialize)) {
                    // 服务里还留着上次的队列：把 url 与下标交给 VM 去库里换成条目再接管。
                    // needsMaterialize 也走这条路：说明 UI 侧现在这份是「从存档恢复出来的副本」，
                    // 而服务里那份才是真在响的，以它为准。
                    val urls = (0 until ctl.mediaItemCount).mapNotNull { ctl.getMediaItemAt(it).mediaId }
                    onSessionQueueFound?.invoke(urls, ctl.currentMediaItemIndex)
                }
            }
        }, MoreExecutors.directExecutor())
    }

    fun disconnect() {
        controller?.removeListener(playerListener)
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        controller = null
    }

    /** 设置播放列表并从指定位置开始播放（控制器未就绪时排队，连上后补跑） */
    fun setPlaylist(items: List<DbMediaItem>, startIndex: Int = 0) {
        if (items.isEmpty()) return
        val safeIndex = startIndex.coerceIn(0, items.lastIndex)
        playlist = items
        errorRetryKey = null
        _state.update {
            it.copy(
                currentIndex = safeIndex,
                queueVersion = it.queueVersion + 1,
                playbackError = null,
            )
        }
        val ctl = controller
        if (ctl == null) {
            pendingPlay = items to safeIndex
            return
        }
        applyPlaylist(ctl, items, safeIndex)
    }

    private fun applyPlaylist(ctl: MediaController, items: List<DbMediaItem>, startIndex: Int) {
        ctl.setMediaItems(items.map { it.toMedia3Item() }, startIndex, 0L)
        ctl.prepare()
        ctl.play()
        needsMaterialize = false
        resumePositionMs = 0L
    }

    /**
     * 恢复出来的队列第一次要发声时，才真正灌进播放器。
     *
     * @return true = 播放器里已经有队列，可以继续执行本次操作；false = 没有可播的队列
     */
    private fun materializeIfNeeded(ctl: MediaController): Boolean {
        if (!needsMaterialize) return true
        needsMaterialize = false
        if (playlist.isEmpty()) return false
        val idx = _state.value.currentIndex.coerceIn(0, playlist.lastIndex)
        // 起点用恢复出来的位置：上次听到一半关掉，再点播放从这里接上
        ctl.setMediaItems(playlist.map { it.toMedia3Item() }, idx, resumePositionMs)
        ctl.prepare()
        resumePositionMs = 0L
        return true
    }

    /**
     * 播放器掉进 IDLE（源错误 / 加载失败）后，seekToXxx / play() 都只是「改意图」，
     * 不会重新建 MediaPeriod —— 必须再调一次 prepare() 才会真正重新加载。
     *
     * 缺这一步的实测表现就是本次的 bug：一首歌播完、切下一首时直链打不开，
     * 播放器进 IDLE 并带着 playerError；此后点「下一首」「播放」「队列里的歌」
     * 全都毫无反应（命令被接受，但没有网络请求、没有声音），只能重启 App。
     *
     * 正常播放（BUFFERING/READY/ENDED）时 playbackState != IDLE，这里完全不介入，
     * 不会打断正在跑的播放。
     */
    private fun ensurePrepared(ctl: MediaController) {
        if (ctl.playbackState == Player.STATE_IDLE && ctl.mediaItemCount > 0) {
            ctl.prepare()
        }
    }

    /**
     * 以队列里的第 [index] 首为起点加载并开播 —— 恢复出来的队列首次发声用这条路径。
     *
     * 为什么不「先 materialize 再 seekToXxx」：MediaController 的 setMediaItems 需要等服务回包，
     * 立刻读 mediaItemCount / hasNextMediaItem 还是旧的（0 首），切歌会看起来没反应。
     * 这里直接把目标下标交给播放器，一次到位。
     */
    private fun startFrom(ctl: MediaController, items: List<DbMediaItem>, index: Int, positionMs: Long) {
        if (items.isEmpty()) return
        needsMaterialize = false
        resumePositionMs = 0L
        val idx = index.coerceIn(0, items.lastIndex)
        ctl.setMediaItems(items.map { it.toMedia3Item() }, idx, positionMs)
        ctl.prepare()
        ctl.play()
        // 界面先指到目标曲目：等服务回包的 transition 有几十毫秒，期间迷你栏会还显示上一首
        _state.update {
            it.copy(
                currentIndex = idx,
                positionMs = positionMs,
                durationMs = items[idx].durationMs,
                queueVersion = it.queueVersion + 1,
            )
        }
    }

    fun play() {
        val ctl = controller ?: return
        materializeIfNeeded(ctl)
        ensurePrepared(ctl)
        ctl.play()
    }

    fun pause() = controller?.pause()

    fun togglePlayPause() {
        val ctl = controller ?: return
        // 恢复出来的队列（只在 UI 侧）：先灌进播放器 —— 否则 play() 面对空队列等于什么都没发生
        materializeIfNeeded(ctl)
        ensurePrepared(ctl)
        if (ctl.isPlaying) ctl.pause() else ctl.play()
    }

    /**
     * 下一首。
     *
     * - 单曲循环（REPEAT_MODE_ONE）：界面上就是「这一首循环」，所以点下一首/上一首都只把当前这首从头再来，
     *   而不是换歌 —— 之前用 seekToNextMediaItem() 会真的跳到下一首，看起来像「模式没生效」。
     * - 其它模式：正常切下一首；顺序模式到队尾则不动，避免把最后一首重新播起来。
     * - `seekToNextMediaItem()` 不改 playWhenReady，暂停态切歌会停在 0 秒不出声，
     *   所以切完补一次 play()。
     */
    fun next() {
        val ctl = controller ?: return
        // 恢复出来的队列：播放器里还没有队列，seekToNextMediaItem() 无从下手
        // （MediaController 的 setMediaItems 要等服务回包，这里不能「先灌队列再取当前下标」），
        // 所以直接按目标曲目加载。
        if (needsMaterialize) {
            val items = playlist
            if (items.isEmpty()) return
            val cur = _state.value.currentIndex.coerceIn(0, items.lastIndex)
            val loop = _state.value.playMode == PlayMode.LOOP
            val target = if (loop) cur else (cur + 1).coerceAtMost(items.lastIndex)
            // 顺序模式已经在队尾：不动（与下面 hasNextMediaItem() 的判断保持一致）
            if (_state.value.playMode == PlayMode.SEQUENCE && cur >= items.lastIndex) return
            startFrom(ctl, items, target, 0L)
            return
        }
        val count = ctl.mediaItemCount
        if (count <= 0) return
        // release 版不打诊断日志：Log.i 的字符串拼接在调用前就执行完了，
        // 这几处都在切歌/点按的热路径上，长期留在线上只会增加主线程开销
        if (BuildConfig.DEBUG) {
            android.util.Log.i(
                "PlayerCtl",
                "next() tap: ctlIdx=${ctl.currentMediaItemIndex} hasNext=${ctl.hasNextMediaItem()} " +
                    "mode=${_state.value.playMode} ctlCount=$count playlistSize=${playlist.size} " +
                    "repeat=${ctl.repeatMode} shuffle=${ctl.shuffleModeEnabled}",
            )
        }
        if (_state.value.playMode == PlayMode.LOOP) {
            ctl.seekTo(ctl.currentMediaItemIndex.coerceIn(0, count - 1), 0L)
        } else {
            if (!ctl.hasNextMediaItem()) return
            ctl.seekToNextMediaItem()
        }
        // 上一次切歌就是因为源打不开而失败（IDLE）：这里必须重新 prepare，否则切了也不出声
        ensurePrepared(ctl)
        if (!ctl.isPlaying) ctl.play()
    }

    /** 上一首：单曲循环下同样是「当前这首从头来」 */
    fun previous() {
        val ctl = controller ?: return
        // 同 next()：恢复出来的队列直接按目标曲目加载
        if (needsMaterialize) {
            val items = playlist
            if (items.isEmpty()) return
            val cur = _state.value.currentIndex.coerceIn(0, items.lastIndex)
            val target = if (_state.value.playMode == PlayMode.LOOP) cur else (cur - 1).coerceAtLeast(0)
            startFrom(ctl, items, target, 0L)
            return
        }
        val count = ctl.mediaItemCount
        if (count <= 0) return
        if (_state.value.playMode == PlayMode.LOOP) {
            ctl.seekTo(ctl.currentMediaItemIndex.coerceIn(0, count - 1), 0L)
        } else {
            ctl.seekToPreviousMediaItem()
        }
        ensurePrepared(ctl)
        if (!ctl.isPlaying) ctl.play()
    }

    /** 下一首直链（供切歌预热用）；随机模式下也按随机序列的下一项返回 */
    fun nextItemUrl(): String? = nextItem()?.url

    /**
     * 下一首条目（供**歌词**预热用）。
     * 与 [nextItemUrl] 同源，免得两处各算一遍「下一首」，随机模式下算出不同的歌。
     */
    fun nextItem(): DbMediaItem? {
        val ctl = controller
        val idx = _state.value.currentIndex
        if (ctl == null || idx < 0) return null
        val nextIdx = when (state.value.playMode) {
            PlayMode.RANDOM -> if (playlist.size > 1) (idx + 1 + (Math.random() * (playlist.size - 1)).toInt()) % playlist.size else -1
            else -> if (idx + 1 < playlist.size) idx + 1 else if (state.value.playMode == PlayMode.SEQUENCE) -1 else 0
        }
        return playlist.getOrNull(nextIdx)
    }

    fun seekTo(positionMs: Long) {
        val ctl = controller ?: return
        // 还没加载（恢复出来的队列）：只记下目标位置，等真正开播时作为起点
        if (needsMaterialize) {
            resumePositionMs = positionMs.coerceAtLeast(0L)
            _state.update { it.copy(positionMs = resumePositionMs) }
            return
        }
        ctl.seekTo(positionMs)
    }

    /** 当前播放队列（播放页列表弹层展示用；返回不可变快照） */
    fun queue(): List<DbMediaItem> = playlist.toList()

    /**
     * 从当前播放队列里移除第 [index] 首（只动队列，不删媒体库里的歌）。
     *
     * 注意：播放页「播放列表」弹层每行的 ⋮ 菜单已按用户要求移除，
     * 所以本方法目前没有 UI 入口（保留实现，将来要做滑动删除/长按菜单时可直接接回）。
     *
     * 两个队列必须同步：MediaController 里的 mediaItems 和本地 playlist 快照。
     * currentIndex 也要跟着修正，否则迷你栏/播放页会指到错误的歌上。
     */
    fun removeFromQueue(index: Int) {
        if (index !in playlist.indices) return
        // 只剩一首：等同于清空队列（避免留下「0 首但仍在播」的空态）
        if (playlist.size <= 1) {
            clearQueue()
            return
        }
        val ctl = controller ?: return
        val cur = _state.value.currentIndex
        if (index < ctl.mediaItemCount) ctl.removeMediaItem(index)

        val list = playlist.toMutableList().apply { removeAt(index) }
        playlist = list
        // 删的是当前曲：播放器会自动接上原本的下一首（下标不变），队尾删则退到前一首
        val newIndex = when {
            index < cur -> cur - 1
            index == cur -> index.coerceAtMost(list.lastIndex)
            else -> cur
        }
        _state.update {
            it.copy(currentIndex = newIndex, queueVersion = it.queueVersion + 1)
        }
    }

    /**
     * 队列内调序：把第 [from] 首移到 [to]（0-based，含两端）。
     *
     * 同 [removeFromQueue]：⋮ 菜单移除后暂无 UI 入口，实现保留。
     */
    fun moveInQueue(from: Int, to: Int) {
        if (from == to) return
        if (from !in playlist.indices || to !in playlist.indices) return
        val ctl = controller ?: return
        if (from < ctl.mediaItemCount && to < ctl.mediaItemCount) ctl.moveMediaItem(from, to)

        val list = playlist.toMutableList()
        val moved = list.removeAt(from)
        list.add(to, moved)
        playlist = list

        val cur = _state.value.currentIndex
        val newIndex = when {
            cur == from -> to
            from < cur && to >= cur -> cur - 1
            from > cur && to <= cur -> cur + 1
            else -> cur
        }
        _state.update {
            it.copy(currentIndex = newIndex, queueVersion = it.queueVersion + 1)
        }
    }

    /**
     * 清空队列并停止播放（清除数据库后调用）。
     * 不然队列里还留着已经不在库里的曲目，点一下就会去请求失效直链。
     */
    fun clearQueue() {
        pendingPlay = null
        playlist = emptyList()
        needsMaterialize = false
        resumePositionMs = 0L
        controller?.let { ctl ->
            ctl.stop()
            ctl.clearMediaItems()
        }
        _state.value = _state.value.copy(
            currentIndex = -1,
            positionMs = 0,
            durationMs = 0,
            isPlaying = false,
            // 队列都没了，停滞提示立刻撤掉，别等下一次 checkBufferStall（最多 1s）
            bufferStalled = false,
            playbackError = null,
            queueVersion = _state.value.queueVersion + 1,
        )
    }

    /** 跳转到队列中的第 [index] 首并从头播放 */
    fun seekToIndex(index: Int) {
        val ctl = controller ?: return
        if (index !in playlist.indices) return
        if (needsMaterialize) {
            // 恢复出来的队列：直接以这一首为起点加载（还没有队列可 seek）
            startFrom(ctl, playlist, index, 0L)
            return
        }
        ctl.seekTo(index, 0L)
        // 暂停态点队列里的歌，同样是「我要听这首」，需要真的开始播
        ensurePrepared(ctl)
        if (!ctl.isPlaying) ctl.play()
    }

    fun setPlayMode(mode: PlayMode) {
        _state.value = _state.value.copy(playMode = mode)
        controller?.let { applyPlayMode(it, mode) }
    }

    private fun applyPlayMode(ctl: MediaController, mode: PlayMode) {
        when (mode) {
            PlayMode.SEQUENCE -> {
                ctl.repeatMode = Player.REPEAT_MODE_OFF
                ctl.shuffleModeEnabled = false
            }
            PlayMode.LOOP -> {
                ctl.repeatMode = Player.REPEAT_MODE_ONE
                ctl.shuffleModeEnabled = false
            }
            PlayMode.RANDOM -> {
                ctl.repeatMode = Player.REPEAT_MODE_OFF
                ctl.shuffleModeEnabled = true
            }
        }
    }

    fun setVolume(volume: Float) {
        controller?.volume = volume
        _state.value = _state.value.copy(volume = volume)
    }

    /** 当前播放项（供歌词、封面等使用） */
    fun currentItem(): DbMediaItem? = playlist.getOrNull(_state.value.currentIndex)

    /**
     * 启动时恢复上次的播放队列 —— **只落到 UI 侧，不碰播放器**。
     *
     * 为什么不等现在就把队列灌进 ExoPlayer：setMediaItems + prepare 会立刻去网盘拉流缓冲，
     * 用户只是打开 App 看列表，不该替他消耗流量。等到按播放/切歌时才 materialize。
     * 状态里带上上次的时长与位置，迷你栏的进度条一打开就是「上次听到哪儿」的样子。
     */
    fun restoreQueue(items: List<DbMediaItem>, startIndex: Int, positionMs: Long = 0L) {
        if (items.isEmpty()) return
        val idx = startIndex.coerceIn(0, items.lastIndex)
        val duration = items[idx].durationMs
        val pos = when {
            duration > 0 -> positionMs.coerceIn(0L, duration)
            else -> positionMs.coerceAtLeast(0L)
        }
        playlist = items
        needsMaterialize = true
        resumePositionMs = pos
        _state.update {
            it.copy(
                currentIndex = idx,
                positionMs = pos,
                durationMs = duration,
                isPlaying = false,
                queueVersion = it.queueVersion + 1,
            )
        }
    }

    /**
     * 接管播放服务里还活着的队列（进程被回收后服务没死 / Activity 重建）。
     * 与 [restoreQueue] 相反：播放器里本来就有队列，这里只是把 UI 侧补齐，
     * 所以不置 needsMaterialize —— 否则一按播放会把正在响的队列重新 setMediaItems 打断。
     */
    fun adoptQueue(items: List<DbMediaItem>, startIndex: Int) {
        if (items.isEmpty()) return
        val idx = startIndex.coerceIn(0, items.lastIndex)
        playlist = items
        needsMaterialize = false
        resumePositionMs = 0L
        val liveDuration = controller?.duration ?: 0L
        _state.update {
            it.copy(
                currentIndex = idx,
                positionMs = controller?.currentPosition ?: 0L,
                durationMs = if (liveDuration > 0) liveDuration else items[idx].durationMs,
                isPlaying = controller?.isPlaying ?: false,
                queueVersion = it.queueVersion + 1,
            )
        }
    }

    /**
     * 元数据编辑保存后，同步播放器内存中的对应项并刷新通知栏/锁屏。
     *
     * 队列快照已改成 [MediaItemLite]（不含歌词正文），歌词的同步不再经由这里：
     * 歌词正文在 `media_items.lyric` 与 `lyrics` 表里都由编辑流程同步写好，
     * 歌词视图靠 queueVersion 触发重载、按 url 回表读最新歌词（见 [LyricViewer]）。
     * [lyric] 参数保留签名以兼容调用方，但不再写入队列快照（lite 无该字段）。
     */
    fun updateItemMeta(
        url: String,
        title: String,
        artist: String,
        album: String,
        coverUrl: String,
        @Suppress("UNUSED_PARAMETER") lyric: String? = null,
    ) {
        // 保存入口跑在 Dispatchers.IO 上，MediaController 必须回到主线程
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { updateItemMeta(url, title, artist, album, coverUrl, lyric) }
            return
        }
        val idx = playlist.indexOfFirst { it.url == url }
        if (idx < 0) return
        val updated = playlist[idx].copy(
            title = title,
            artist = artist,
            album = album,
            coverUrl = coverUrl,
        )
        playlist = playlist.toMutableList().also { it[idx] = updated }
        // 通知 UI 重新读取队列项（迷你栏 / 播放页标题、封面）
        _state.update { it.copy(queueVersion = it.queueVersion + 1) }

        // 替换 MediaController 中的对应项：mediaId 与 uri 都未变（还是同一个 url），
        // ExoPlayer 会复用当前播放位置、不重新缓冲，但会把新 metadata 推送到通知栏/锁屏。
        controller?.let { ctl ->
            if (idx < ctl.mediaItemCount) {
                ctl.replaceMediaItem(idx, updated.toMedia3Item())
            }
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            // 用 update{} 做原子读改写：updatePosition 轮询与这里会先后写同一份状态，
            // 「读两次 _state.value + copy」会在中间被插进来时把新值覆盖回旧快照
            _state.update { it.copy(isPlaying = isPlaying) }
        }

        /**
         * 播放错误 —— 之前这里一个监听都没有，错误被彻底吞掉。
         *
         * 这个缺口正好造出「播放器像死了一样」的现象：切下一首时源打不开
         * （直链过期 / 401 / 连接被拒），ExoPlayer 退回 IDLE 并挂着 playerError，
         * 既不会自动切歌，也不会再出声；而 play()/seekToXxx() 只改意图不重新加载，
         * 于是「点什么都放不出来」，只能重启 App。这里补两件事：
         *  1) 错误进 state（迷你栏直接显示）+ 无条件 Log.e，release 也要能看到原因；
         *  2) 同一首只自动重试一次：重新 prepare 当前曲目，多数瞬时抖动一次就过去。
         */
        override fun onPlayerError(error: PlaybackException) {
            handlePlaybackError(error)
        }

        override fun onPlayerErrorChanged(error: PlaybackException?) {
            // Media3 恢复成功后会回调 null：顺手把提示撤掉
            if (error != null) handlePlaybackError(error)
            else _state.update { if (it.playbackError == null) it else it.copy(playbackError = null) }
        }

        /**
         * 恢复出来的队列（[needsMaterialize]）只在 UI 侧，ExoPlayer 里一条 mediaItem 都没有。
         *
         * 这时从**通知栏 / 耳机线控 / 锁屏**发来的 play 由 MediaSession 直接转给播放器，
         * 走不到 UI 的 [`play`][this@PlayerController.play]（那条路径才有 materialize），
         * 对空队列 play 等于什么都没发生 —— 表现就是「按耳机播放键没反应，得进 App 点一下」。
         * playWhenReady 变 true 正是「真要出声了」的信号，在这里补一次灌队列即可。
         */
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (!playWhenReady || !needsMaterialize) return
            val ctl = controller ?: return
            materializeIfNeeded(ctl)
        }

        /**
         * 切歌必须用 mediaId（= 直链）回查本地 playlist 的真实下标，不能直接抄
         * `currentMediaItemIndex`：随机模式开了 shuffleModeEnabled 后，它返回的是
         * 洗牌后顺序里的下标，而 currentItem() 用它去索引自然顺序的 playlist，
         * 结果就是迷你栏/播放页显示成另一首歌（洗牌序恰好重合时正常，错开时
         * 「切歌了但信息不变」）。查不到再退回 currentMediaItemIndex 兜底。
         *
         * 同时每次切歌都 bump queueVersion：单循环 next() 是同一首（index 不变），
         * 其余字段值也相同的话 StateFlow 会按相等合并、UI 收不到重组信号，
         * 靠 queueVersion 强制重读一遍。
         */
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        val url = mediaItem?.mediaId
        val resolved = url?.let { u -> playlist.indexOfFirst { it.url == u } }
        val idx = if (resolved != null && resolved >= 0) resolved
                  else controller?.currentMediaItemIndex ?: -1
        // release 不打日志：这条日志里的 playlist.count { } 是 O(队列长度)，
        // 三千首的队列上每次切歌都要白扫一遍，只为拼一条没人看的字符串
        if (BuildConfig.DEBUG) {
            android.util.Log.i(
                "PlayerCtl",
                "transition reason=$reason mediaId=${url?.takeLast(40)} resolved=$resolved " +
                    "ctlIdx=${controller?.currentMediaItemIndex} playlistSize=${playlist.size} " +
                    "dup=${playlist.count { it.url == url }} ->idx=$idx",
            )
        }
            _state.update {
                it.copy(
                    currentIndex = idx,
                    // 切歌瞬间上一首的进度/时长已失效，归零等 STATE_READY 再填，
                    // 避免迷你栏进度条短暂显示上一首的残留值
                    positionMs = 0,
                    durationMs = 0,
                    // 换歌了，上一首的错误提示与「已重试过」标记一并作废
                    playbackError = null,
                    queueVersion = it.queueVersion + 1,
                )
            }
            errorRetryKey = null
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY) {
                // 某一首真的放出来了：说明链路是通的，连着跳过的计数归零
                consecutiveErrorSkips = 0
                // 原子更新：这里与 onIsPlayingChanged 相邻触发，非原子写法会互相覆盖
                _state.update {
                    it.copy(
                        durationMs = controller?.duration ?: 0,
                        playbackError = null,
                    )
                }
            }
        }
    }

    /**
     * 统一的错误处理：记日志、写 state、限次自动重试，重试仍失败则跳下一首。
     *
     * 只对「同一首」重试一次 —— 直链真的失效时每次重试都白跑一轮，
     * 无限重试会变成对网盘的高频无效请求；换歌后 [errorRetryKey] 复位，下一首仍有重试机会。
     */
    private fun handlePlaybackError(error: PlaybackException) {
        val item = currentItem()
        val brief = buildString {
            append(error.errorCodeName)
            error.message?.takeIf { it.isNotBlank() }?.let { append(": ").append(it.take(120)) }
        }
        // 错误不是热路径，release 也必须留：线上出问题只有这条日志能定位
        android.util.Log.e(
            "PlayerCtl",
            "playback error idx=${_state.value.currentIndex} url=${item?.url?.takeLast(60)} ($brief)",
            error,
        )
        _state.update { it.copy(isPlaying = false, playbackError = brief) }

        val key = item?.url ?: return
        if (errorRetryKey == key) {
            // 同一首已经重试过一次还是失败：别让一个坏文件把整个队列卡死
            scheduleSkipAfterError(key)
            return
        }
        errorRetryKey = key
        // 稍等一下再重试：瞬时故障（DNS、连接复用失败）立刻重试往往还是同一个错误
        mainHandler.postDelayed({
            if (currentItem()?.url == key && _state.value.playbackError != null) {
                android.util.Log.i("PlayerCtl", "playback error retry: ${key.takeLast(60)}")
                controller?.let { ctl ->
                    ctl.prepare()
                    ctl.play()
                }
            }
        }, 800L)
    }

    /**
     * 重试仍失败的曲目：自动跳下一首。
     *
     * 为什么要有：网盘里混进一个坏文件（下载 404 / 文件损坏解码失败）时，队列会停在这一首，
     * 看起来像「播放器又死了」。跳过去比停在原地有用得多。
     *
     * 但要防「整库都放不出来」（断网、密码被改）时疯狂连跳：
     * 连续跳过 [MAX_ERROR_SKIPS] 首就停下，把错误留在迷你栏上等人处理。
     * 计数器在任意一首成功进入 READY 时归零。
     */
    private fun scheduleSkipAfterError(key: String) {
        mainHandler.postDelayed({
            if (currentItem()?.url != key) return@postDelayed
            val ctl = controller ?: return@postDelayed
            val atQueueEnd = state.value.playMode == PlayMode.SEQUENCE && !ctl.hasNextMediaItem()
            if (atQueueEnd) {
                // 队尾没有下一首：保留错误提示，让用户知道是这首坏了
                android.util.Log.w("PlayerCtl", "playback error at queue end, keep error visible")
                return@postDelayed
            }
            if (consecutiveErrorSkips >= MAX_ERROR_SKIPS) {
                android.util.Log.w(
                    "PlayerCtl",
                    "skip aborted: $consecutiveErrorSkips consecutive failures, likely network/credentials",
                )
                return@postDelayed
            }
            consecutiveErrorSkips++
            android.util.Log.w("PlayerCtl", "skip broken track (#$consecutiveErrorSkips): ${key.takeLast(60)}")
            next()
        }, 1200L)
    }

    /** UI 轮询调用，更新进度 */
    private var lastPollIdx = -2

    /** 已经自动重试过的曲目 url（同一首只重试一次，换歌复位） */
    private var errorRetryKey: String? = null

    /** 连续「出错 → 跳下一首」的次数：防止断网时把整个队列一路跳完 */
    private var consecutiveErrorSkips = 0

    fun updatePosition() {
        // 恢复出来、还没真正加载的队列：播放器里什么都没有（position/duration 都是 0），
        // 照抄会把界面上恢复出来的「上次听到哪儿」冲掉，这里直接跳过
        if (needsMaterialize) return
        controller?.let {
            // update{} 原子读改写：原来「两次 _state.value 读 + copy」在事件回调并发写状态时
            // 会用旧快照覆盖掉新值（丢更新竞态）
            _state.update { st ->
                st.copy(
                    positionMs = it.currentPosition,
                    durationMs = it.duration.coerceAtLeast(0),
                )
            }
            // 诊断日志只在 debug 版走：release 下连这次字符串拼接都不该发生
            if (BuildConfig.DEBUG) {
                val cur = _state.value
                if (cur.currentIndex != lastPollIdx) {
                    lastPollIdx = cur.currentIndex
                    android.util.Log.i(
                        "PlayerCtl",
                        "poll: idx=${cur.currentIndex} ctlIdx=${it.currentMediaItemIndex} " +
                            "state=${it.playbackState} self=${System.identityHashCode(this)}",
                    )
                }
            }
        }
    }

    // ===== 缓冲停滞检测（「网络缓慢」提示）=====

    /** BUFFERING 态下 bufferedPosition 连续这么久没有推进，就视为网络停滞 */
    private var stallLastBufferedMs = -1L
    private var stallSinceMs = 0L

    /**
     * 缓冲停滞检测，由 UI 的轮询循环每 250ms（暂停时 1s 心跳）调用一次。
     *
     * 判定：playWhenReady 且 playbackState==BUFFERING 时，若 bufferedPosition
     * 连续 [STALL_TIMEOUT_MS] 毫秒没有推进，置 state.bufferStalled = true；
     * 数据恢复推进 / 进入 READY / 用户暂停时自动清除。
     *
     * 为什么不在 listener 里做：Media3 的 BUFFERING 事件本身不带"卡了多久"的信息，
     * 必须有人按时间轴观察 bufferedPosition 是否还在动；AppViewModel 的全局轮询
     * 正好是现成的心跳（暂停分支的 1s 心跳也在，恰好覆盖 BUFFERING 时 isPlaying=false
     * 走不进进度轮询的场景）。本方法与 listener / updatePosition 一样只在主线程跑，无并发。
     */
    fun checkBufferStall() {
        val ctl = controller ?: return
        val bufferingForPlayback = ctl.playbackState == Player.STATE_BUFFERING && ctl.playWhenReady
        if (!bufferingForPlayback) {
            if (_state.value.bufferStalled) _state.update { it.copy(bufferStalled = false) }
            stallLastBufferedMs = -1L
            stallSinceMs = 0L
            return
        }
        val buffered = ctl.bufferedPosition
        if (buffered != stallLastBufferedMs) {
            // 数据仍在推进（哪怕很慢）：不提示，重置计时
            stallLastBufferedMs = buffered
            stallSinceMs = System.currentTimeMillis()
            if (_state.value.bufferStalled) _state.update { it.copy(bufferStalled = false) }
            return
        }
        if (stallSinceMs == 0L) stallSinceMs = System.currentTimeMillis()
        if (System.currentTimeMillis() - stallSinceMs >= STALL_TIMEOUT_MS && !_state.value.bufferStalled) {
            _state.update { it.copy(bufferStalled = true) }
        }
    }

    private fun DbMediaItem.toMedia3Item(): MediaItem = MediaItem.Builder()
        .setUri(url)
        .setMediaId(url)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist)
                .setAlbumTitle(album)
                .setArtworkUri(if (coverUrl.isNotEmpty()) android.net.Uri.parse(coverUrl) else null)
                .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                .apply { if (durationMs > 0) setDurationMs(durationMs) }
                .build(),
        )
        .build()

    companion object {
        /** BUFFERING 态下数据停止推进多久后提示「网络缓慢」 */
        private const val STALL_TIMEOUT_MS = 15_000L

        /**
         * 连续跳过多少首坏曲目后停手。
         * 断网 / 密码被改时每一首都会失败，不设上限就是对着网盘刷几百次请求。
         */
        private const val MAX_ERROR_SKIPS = 3
    }
}
