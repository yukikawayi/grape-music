package com.grape.player.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * 响度归一化 AudioProcessor —— 把 Web 版 AudioWorklet 的 LUFS 测量逻辑搬到 PCM 数据流。
 *
 * 实现 BS.1770 的简化版：
 *  - K 加权（高频架式滤波器，近似）
 *  - 100ms 块的均方能量 → LUFS
 *  - 双门控（绝对门 -70 LUFS + 相对门 -10 LU）
 *  - 增益 = 目标响度 - 集成响度，含 2dB 真峰值余量
 *
 * 处理策略：先测量再应用增益。测量阶段累计能量块，达到足够样本后计算增益；
 * 播放中实时把 gain 应用到 PCM（int16）。
 *
 * 音频线程约束（本类所有热路径都跑在播放线程上，杜绝高频分配）：
 *  - 能量块用 [FloatArray] 环形缓冲，替代装箱的 ArrayList + removeAt(0) 的 O(n) 搬移；
 *  - 集成响度改为对环形缓冲的原地双趟扫描，零分配、零装箱；
 *  - 增益重算从每 100ms 一次降到每 1s 一次；
 *  - 分析满 60s（环形缓冲写满）即视为收敛，停止测量（K 加权随之旁路）。
 */
class LoudnessAudioProcessor(
    private var targetLufs: Int = -16,
) : AudioProcessor {

    private var inputFormat = AudioProcessor.AudioFormat.NOT_SET
    private var outputFormat = AudioProcessor.AudioFormat.NOT_SET
    private var pendingFormat = AudioProcessor.AudioFormat.NOT_SET

    private var replacementBuffer: ByteBuffer = AudioProcessor.EMPTY_BUFFER
    private var gain = 1.0f

    // 测量状态：环形缓冲，最多保留 60 秒（600 个 100ms 块）
    private val energyRing = FloatArray(MAX_BLOCKS)
    private var ringSize = 0 // 有效块数
    private var ringHead = 0 // 最旧元素下标（环形写满后随写入前移）
    private var blockSamples = 0
    private var blockSumSq = 0.0
    private var peak = 0f
    private var sampleRate = 48000
    private var channels = 2
    private var measuring = true
    private var kWeightedState = 0f
    /** 距上次增益重算累计的 100ms 块数（重算降到 1s 一次） */
    private var blocksSinceRecompute = 0
    /**
     * 非 PCM16（24bit / float / DSD 转出的高精度格式）走直通：
     * 这类格式不参与响度处理，但仍要把数据原样交给 AudioTrack，
     * 否则 configure 抛 UnhandledAudioFormatException 会让整首歌直接播放失败。
     */
    private var passthrough = false
    /** 累计分析过的 100ms 块数（用于计算覆盖率，不因环形覆盖而减少） */
    private var analyzedBlocks = 0
    /**
     * 输入端 EOS 标记。ExoPlayer 自然播完一首时走「排空」流程：
     * queueEndOfStream 后必须等 isEnded()==true 才会认为音频链结束、正常切到下一首。
     * 之前 isEnded() 恒为 false，排空永远完不成，新歌的 PCM 一个字节都写不进
     * AudioTrack（AudioFlinger 持续 underrun），表现为自动切歌后反复卡在开头。
     */
    @Volatile private var inputEnded = false

    /**
     * 切歌重置请求。LoudnessStore.onTrackStart 在主线程触发，而 Media3 约定 flush()
     * 只能在播放线程调用（本类方法都是 @Synchronized，主线程 flush 会和音频线程的
     * queueInput 争锁，切歌瞬间可能掉帧）。所以这里只置 volatile 标记，
     * 音频线程在 queueInput 开头自行重置并应用 ReplayGain。
     */
    @Volatile private var pendingTrackReset = false
    @Volatile private var pendingReplayGainDb = 0f

    // 所有入口加锁：flush()/reset() 等由 ExoPlayer 在播放线程调用，
    // 与 queueInput 同线程，加锁只为防外部桥接调用的竞态。
    @Synchronized
    override fun configure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        passthrough = inputAudioFormat.encoding != C.ENCODING_PCM_16BIT
        inputFormat = inputAudioFormat
        sampleRate = inputAudioFormat.sampleRate
        channels = inputAudioFormat.channelCount
        pendingFormat = inputAudioFormat
        outputFormat = inputAudioFormat
        if (!passthrough) {
            // 挂到桥接单例，让 UI 层能设置目标响度、读取测量结果
            LoudnessStore.attach(this, LoudnessStore.targetLufs)
        }
        return outputFormat
    }

    override fun isActive(): Boolean = true

    @Synchronized
    override fun queueInput(inputBuffer: ByteBuffer) {
        if (pendingTrackReset) applyPendingTrackReset()
        if (!inputBuffer.hasRemaining()) return

        if (passthrough) {
            val bytes = inputBuffer.remaining()
            if (replacementBuffer.capacity() < bytes) {
                replacementBuffer = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())
            } else {
                replacementBuffer.clear()
                replacementBuffer.order(ByteOrder.nativeOrder())
            }
            replacementBuffer.put(inputBuffer)
            replacementBuffer.flip()
            return
        }

        // PCM16 是小端序，而 ByteBuffer.allocateDirect 默认是大端：
        // 两处必须显式统一成 nativeOrder，否则每个采样点高低字节被对调，听感就是纯粹的沙沙噪声。
        inputBuffer.order(ByteOrder.nativeOrder())
        val shortCount = inputBuffer.remaining() / 2
        if (shortCount <= 0) return
        val byteCount = shortCount * 2

        if (replacementBuffer.capacity() < byteCount) {
            replacementBuffer = ByteBuffer.allocateDirect(byteCount).order(ByteOrder.nativeOrder())
        } else {
            replacementBuffer.clear()
            replacementBuffer.order(ByteOrder.nativeOrder())
        }
        replacementBuffer.limit(byteCount)

        // 通过 ShortBuffer 视图读写，避免逐样本手工拼字节
        val dst = replacementBuffer.asShortBuffer()
        val blockSize = (sampleRate / 10).coerceAtLeast(1) // 100ms
        val gainNow = gain
        val measuringNow = measuring
        var state = kWeightedState
        var sumSq = blockSumSq
        var samples = blockSamples
        var peakNow = peak
        while (inputBuffer.remaining() >= 2) {
            val sample = inputBuffer.short.toInt()
            // 近似 K 加权：一阶高通 + 高频提升。
            // 只在测量期开启；收敛（写满 60s）或 ReplayGain 模式下整个分支旁路，省掉逐样本浮点开销。
            if (measuringNow) {
                val normalized = sample / 32768f
                state = 0.97f * state + 0.03f * normalized
                val weighted = normalized - state
                sumSq += weighted.toDouble() * weighted
                samples++
                if (abs(normalized) > peakNow) peakNow = abs(normalized)
            }

            // 应用增益并写回
            val gained = (sample * gainNow).toInt().coerceIn(-32768, 32767)
            dst.put(gained.toShort())
        }
        kWeightedState = state
        blockSumSq = sumSq
        blockSamples = samples
        peak = peakNow

        if (measuring && blockSamples >= blockSize) {
            val ms = (blockSumSq / blockSamples).toFloat()
            if (ms > 0f) pushEnergy(ms)
            analyzedBlocks++
            blockSumSq = 0.0
            blockSamples = 0
            blocksSinceRecompute++
            if (blocksSinceRecompute >= RECOMPUTE_INTERVAL_BLOCKS) {
                blocksSinceRecompute = 0
                recomputeGain()
            }
            // 收敛停测：环形缓冲写满即已覆盖 60s，继续测只会来回抖动，停掉省电省 GC
            if (ringSize >= MAX_BLOCKS && measuring) {
                measuring = false
                recomputeGain()
            }
        }
        replacementBuffer.position(byteCount)
        replacementBuffer.flip()
    }

    @Synchronized
    override fun getOutput(): ByteBuffer = replacementBuffer

    @Synchronized
    override fun queueEndOfStream() {
        measuring = false
        inputEnded = true
    }

    @Synchronized
    override fun isEnded(): Boolean = inputEnded && !replacementBuffer.hasRemaining()

    /**
     * flush 必须把 [inputEnded] 复位 —— 这是 AudioProcessor 的约定
     * （media3 自己的 BaseAudioProcessor.flush() 第一件事就是 inputEnded = false）。
     *
     * 缺席的后果是「上一首的结束标记粘住」：一首歌自然播完把 inputEnded 置 true，
     * 切歌时的 flush 不清它，新曲目的 isEnded() 从一开始就撒谎 —— 音频链被当成
     * 「已结束」处理，排水/切歌的时序全乱，表现就是播完一首之后不再自动切歌、
     * 后面再点也放不出声（历史上同一处已经因此出过「切歌卡在开头」的故障）。
     */
    @Synchronized
    override fun flush() {
        inputEnded = false
        resetMeasurement()
        replacementBuffer = AudioProcessor.EMPTY_BUFFER
    }

    @Synchronized
    override fun reset() {
        flush()
        passthrough = false
        inputFormat = AudioProcessor.AudioFormat.NOT_SET
        outputFormat = AudioProcessor.AudioFormat.NOT_SET
        pendingFormat = AudioProcessor.AudioFormat.NOT_SET
    }

    /**
     * 主线程切歌入口（LoudnessStore.onTrackStart）：只置标记不碰内部状态，
     * 真正的重置在音频线程 queueInput 开头执行，避免主线程等 @Synchronized 锁。
     */
    fun requestTrackReset(replayGainDb: Float) {
        pendingReplayGainDb = replayGainDb
        pendingTrackReset = true
    }

    /** 由外部注入 ReplayGain 标签增益（dB），直接换算成线性增益（音频线程调用） */
    fun setReplayGainGain(db: Float) {
        if (db != 0f) {
            gain = dbToLinear(db)
            measuring = false
        }
    }

    /** 目标响度只写字段；增益重算交给音频线程的周期任务，避免跨线程读测量状态 */
    fun setTarget(target: Int) {
        targetLufs = target
    }

    /** 当前测得的集成响度（LUFS），供覆盖率达标后写库 */
    fun currentLufs(): Float? = integratedLufs()

    fun currentPeakDb(): Float = if (peak > 0) (20 * log10(peak.toDouble())).toFloat() else Float.NEGATIVE_INFINITY

    /** 已分析的音频时长（毫秒），供 UI 层计算覆盖率 */
    fun analyzedMs(): Long = analyzedBlocks * 100L

    private fun applyPendingTrackReset() {
        pendingTrackReset = false
        resetMeasurement()
        val db = pendingReplayGainDb
        if (db != 0f) {
            gain = dbToLinear(db)
            measuring = false
        }
    }

    private fun resetMeasurement() {
        ringSize = 0
        ringHead = 0
        blockSumSq = 0.0
        blockSamples = 0
        blocksSinceRecompute = 0
        peak = 0f
        gain = 1.0f
        measuring = true
        analyzedBlocks = 0
        kWeightedState = 0f
    }

    private fun pushEnergy(ms: Float) {
        if (ringSize < MAX_BLOCKS) {
            energyRing[(ringHead + ringSize) % MAX_BLOCKS] = ms
            ringSize++
        } else {
            // 写满后覆盖最旧的块，O(1)
            energyRing[ringHead] = ms
            ringHead = (ringHead + 1) % MAX_BLOCKS
        }
    }

    private fun recomputeGain() {
        if (!measuring || ringSize < MIN_BLOCKS_FOR_GAIN) return
        val lufs = integratedLufs() ?: return
        val target = targetLufs.toFloat()
        var gainDb = (target - lufs).coerceIn(-12f, 12f)
        val peakDb = currentPeakDb()
        if (peakDb.isFinite()) {
            val protectedPeak = peakDb + 2f
            if (gainDb + protectedPeak > -1f) gainDb = -1f - protectedPeak
        }
        gain = dbToLinear(gainDb)
        // 回填给 UI 层（仅在非 ReplayGain 模式，实测结果才有写库价值）
        if (!LoudnessStore.usingReplayGain) {
            LoudnessStore.report(lufs, peak)
        }
    }

    /**
     * 双门控集成响度：对环形缓冲做两次原地扫描（绝对门 + 相对门），
     * 全程零分配、零装箱。每块最多 600 个 float，扫描成本可忽略。
     */
    private fun integratedLufs(): Float? {
        var sum = 0.0
        var count = 0
        for (i in 0 until ringSize) {
            val ms = energyRing[(ringHead + i) % MAX_BLOCKS]
            if (ms > 0f && msToLufs(ms) > ABSOLUTE_GATE_LUFS) {
                sum += ms
                count++
            }
        }
        if (count == 0) return null
        val ungated = msToLufs((sum / count).toFloat())
        if (!ungated.isFinite()) return null
        val relativeGate = ungated - 10f
        var gatedSum = 0.0
        var gatedCount = 0
        for (i in 0 until ringSize) {
            val ms = energyRing[(ringHead + i) % MAX_BLOCKS]
            if (ms <= 0f) continue
            val lufs = msToLufs(ms)
            if (lufs > ABSOLUTE_GATE_LUFS && lufs > relativeGate) {
                gatedSum += ms
                gatedCount++
            }
        }
        if (gatedCount == 0) return null
        return msToLufs((gatedSum / gatedCount).toFloat())
    }

    private fun dbToLinear(db: Float): Float = sqrt(10f.pow(db / 10f))

    companion object {
        /** 最多保留 600 个 100ms 块 = 60 秒分析窗口 */
        private const val MAX_BLOCKS = 600
        /** 增益重算间隔：10 个块 = 1 秒 */
        private const val RECOMPUTE_INTERVAL_BLOCKS = 10
        /** 重算前至少积累的块数（1 秒） */
        private const val MIN_BLOCKS_FOR_GAIN = 10
        /** BS.1770 绝对门 */
        private const val ABSOLUTE_GATE_LUFS = -70f

        private fun msToLufs(ms: Float): Float =
            if (ms <= 0f) Float.NEGATIVE_INFINITY else (-0.691f + 10 * log10(ms.toDouble())).toFloat()
    }
}

private fun Float.pow(exp: Float): Float = Math.pow(this.toDouble(), exp.toDouble()).toFloat()
