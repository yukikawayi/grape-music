package com.grape.player.network

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * 全局共享 OkHttpClient —— 连接池 / DNS 缓存 / 线程池全应用复用。
 *
 * 之前 WebDavClient（每次扫描 new 一个）、CoverStore、EmbeddedTagReader、
 * MetadataScraper 各建各的实例，补全 4 并发时对同一域名无法复用 keep-alive
 * 连接，TLS 握手反复做，延迟和电量都浪费。
 *
 * 用法：各消费方用 [base].newBuilder() 只改超时 —— newBuilder 共享底层连接池，
 * 且各自拿到独立的超时配置。注意：需要请求队列隔离（独立 Dispatcher）的场景，
 * 如 MetadataScraper.interactive()（前台手动搜索不能排在后台补全后面等），
 * 仍然全新构造实例，不要从这里拿。
 */
object HttpClients {

    /** 默认超时取「最常用」档：扫描 PROPFIND / Range 拉文件头都在这个量级 */
    val base: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()
}
