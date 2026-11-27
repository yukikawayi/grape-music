package com.grape.player.network

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * 第三方音乐源速率护栏 —— 防止后台批量补全把 QQ 音乐这类无鉴权公开接口打挂。
 *
 * 背景（2026-11 排查）：
 * - 后台补全 4 并发、每个 worker 逐首间隔 120ms，但这个间隔是**每个 worker 各自计的**，
 *   不是全局限流，等效请求速率可达每秒 10~30 次；
 * - QQ 的 client_search_cp 是无鉴权公开接口，按 IP 限流，持续高频轰几小时就会被封；
 * - 被限流时接口返回 HTTP 200 但 code != 0，老代码把「空列表」当「没搜到」继续全速轰，
 *   限流迟迟解不开；前台 matchLyric 还会把限流响应误判成「确认没有歌词」，
 *   写进 24h 负缓存 —— 这就是「补全跑完之后播放一直匹配不到歌词」的直接原因。
 *
 * 机制：
 * - [acquire]：同一源的全局最小请求间隔（后台 4 个 worker + 前台搜索/歌词共用一道闸，
 *   串行放行，全局速率 = minIntervalMs 的倒数，与 worker 数无关）；
 * - 熔断：连续 [FAIL_THRESHOLD] 次失败（网络异常 / 接口 code != 0）→ 冷却期内该源直接
 *   fast-fail（[call] 抛 [RateLimitedException]），不再发注定失败的请求；
 *   冷却从 [COOLDOWN_START_MS] 起，每触发一次翻倍，封顶 [COOLDOWN_MAX_MS]。
 *   任何一次成功响应（哪怕是业务上的「没搜到」）都把失败计数清零。
 */
object SourceRateGuard {

    /**
     * 源被限流/熔断时抛出。
     * strict 调用方（首播自动匹配 matchLyric）据此走 LyricFetch.Failed（短退避、下次重试），
     * 而不是 NotFound（24h 负缓存）—— 一次限流绝不能让一首歌一天没词。
     */
    class RateLimitedException(message: String) : RuntimeException(message)

    /** 各源的全局最小请求间隔。QQ 的 client_search_cp 无鉴权、按 IP 限流最狠，给最大的间隔 */
    private fun minIntervalMs(key: String): Long = when (key) {
        "qq" -> 600L
        "kugou" -> 200L
        "netease" -> 250L
        else -> 150L
    }

    /** 连续失败多少次触发熔断 */
    private const val FAIL_THRESHOLD = 3

    /** 熔断冷却：从 1 分钟起，每触发一次翻倍，封顶 30 分钟 */
    private const val COOLDOWN_START_MS = 60_000L
    private const val COOLDOWN_MAX_MS = 30L * 60 * 1000

    private val gates = ConcurrentHashMap<String, Gate>()

    private class Gate {
        val mutex = Mutex()
        var lastAt = 0L
        var failures = 0
        var openUntil = 0L
        var cooldownMs = COOLDOWN_START_MS
    }

    private fun gate(key: String) = gates.computeIfAbsent(key) { Gate() }

    /** 熔断是否生效中 */
    fun isOpen(key: String): Boolean = System.currentTimeMillis() < gate(key).openUntil

    /** 熔断剩余毫秒（调试/日志用） */
    fun cooldownRemainingMs(key: String): Long =
        (gate(key).openUntil - System.currentTimeMillis()).coerceAtLeast(0L)

    /**
     * 全局最小间隔：同一源的所有请求（后台补全的 4 个 worker、前台搜索、歌词匹配）
     * 在这里串行过闸，全局速率被压到 minIntervalMs 的倒数，而不是各 worker 各睡各的。
     */
    suspend fun acquire(key: String) {
        val g = gate(key)
        g.mutex.withLock {
            val wait = g.lastAt + minIntervalMs(key) - System.currentTimeMillis()
            if (wait > 0) delay(wait)
            g.lastAt = System.currentTimeMillis()
        }
    }

    /** 请求成功（哪怕是业务上的「没搜到」）：失败计数清零，冷却中的源不因此提前恢复 */
    fun onSuccess(key: String) {
        gate(key).failures = 0
    }

    /** 请求失败（网络异常 / 接口 code != 0）：累计到阈值就开熔断，冷却时间翻倍 */
    fun onFailure(key: String) {
        val g = gate(key)
        g.failures += 1
        if (g.failures >= FAIL_THRESHOLD && !isOpen(key)) {
            g.openUntil = System.currentTimeMillis() + g.cooldownMs
            android.util.Log.w(
                "SourceRateGuard",
                "$key 连续失败 ${g.failures} 次，熔断 ${g.cooldownMs / 1000}s（下次 ${g.cooldownMs * 2 / 1000}s）",
            )
            g.cooldownMs = (g.cooldownMs * 2).coerceAtMost(COOLDOWN_MAX_MS)
        }
    }

    /**
     * 带护栏执行：熔断中直接抛 [RateLimitedException]（fast-fail，不再往挂掉的接口发请求）；
     * 执行成功记 [onSuccess]，异常记 [onFailure] 后原样抛出（CancellationException 除外）。
     * block 里的业务空结果（如「没搜到候选」）按成功算，不会触发熔断。
     */
    suspend fun <T> call(key: String, block: () -> T): T {
        if (isOpen(key)) {
            throw RateLimitedException("$key 熔断中，剩余 ${cooldownRemainingMs(key) / 1000}s")
        }
        acquire(key)
        return try {
            val result = block()
            onSuccess(key)
            result
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onFailure(key)
            throw e
        }
    }

    /** 同 [call]，但失败时返回 [fallback] 而不是抛出（歌词直取这类「失败就跳过」的场景） */
    suspend fun <T> callOr(key: String, fallback: T, block: () -> T): T = try {
        call(key, block)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        fallback
    }
}
