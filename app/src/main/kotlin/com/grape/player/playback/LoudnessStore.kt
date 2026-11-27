package com.grape.player.playback

/**
 * 响度测量桥接单例。
 *
 * AudioProcessor 运行在 PlaybackService 的音频渲染线程里，测量结果需要被 UI 层读取并写库。
 * 单进程 App 里用一个线程安全的单例桥接，避免把 Room / Flow 扯进音频线程。
 */
object LoudnessStore {
    /** 当前挂载在播放链上的处理器（切歌会 flush，但实例复用） */
    @Volatile
    var processor: LoudnessAudioProcessor? = null
        private set

    /** 目标响度 LUFS（-14/-16/-18），来自设置 */
    @Volatile
    var targetLufs: Int = -16
        private set

    /** 已测得的集成响度 / 峰值（无 ReplayGain 标签时由实测填充） */
    @Volatile
    var measuredLufs: Float? = null
        private set

    @Volatile
    var measuredPeak: Float = 0f
        private set

    /** 当前音轨是否带 ReplayGain 标签（带标签则不写实测库） */
    @Volatile
    var usingReplayGain: Boolean = false
        private set

    fun attach(processor: LoudnessAudioProcessor, target: Int) {
        this.processor = processor
        this.targetLufs = target
        processor.setTarget(target)
    }

    fun setTarget(target: Int) {
        targetLufs = target
        processor?.setTarget(target)
    }

    /**
     * 切歌：重置测量状态，并注入本曲的 ReplayGain（有标签则直接增益，不再实测）。
     *
     * 注意：这里在主线程调用，绝不能直接调 processor.flush() —— flush 是
     * @Synchronized 的，而 queueInput 持同一把锁跑完整个缓冲区（约 10~20ms），
     * 主线程等锁会在切歌瞬间掉 1~2 帧，且违反 Media3「flush 只在播放线程调用」的约定。
     * 正确做法是置 volatile 标记（requestTrackReset），音频线程在 queueInput
     * 开头自行重置并应用 ReplayGain。
     */
    fun onTrackStart(replayGainDb: Float) {
        measuredLufs = null
        measuredPeak = 0f
        usingReplayGain = replayGainDb != 0f
        processor?.requestTrackReset(replayGainDb)
    }

    /** 由 AudioProcessor 在累计出稳定集成响度后回填 */
    internal fun report(lufs: Float, peak: Float) {
        measuredLufs = lufs
        measuredPeak = peak
    }
}
