package com.grape.player.data

import kotlinx.serialization.Serializable

/**
 * 一个 WebDAV（其实是 OpenList / CloudDrive2 的 /dav 入口）源。
 *
 * 以前全应用只有一份 url/user/pass/root（四个独立的设置键），改多源后统一存成
 * `List<WebDavSource>` 的 JSON（见 [SettingsManager.webDavSources]），旧数据在读取时迁移成一条。
 *
 * [id] 只用于列表定位（编辑 / 删除哪一条）。老数据迁移时给固定值，新源用 UUID。
 */
@Serializable
data class WebDavSource(
    val id: String = "",
    val url: String = "",
    val user: String = "",
    val pass: String = "",
    val root: String = "/",
) {
    /** 是否填了合法的 http(s) 地址（决定能不能保存 / 参与扫描） */
    val isValid: Boolean
        get() {
            val u = url.trim()
            return u.startsWith("http://", true) || u.startsWith("https://", true)
        }

    /** 扫描用的根目录，空则视为 /（扫全部挂载） */
    val rootPath: String get() = root.trim().ifBlank { "/" }

    /**
     * WebDAV 根地址，统一以 /dav 结尾。
     * 兼容用户填 http://127.0.0.1:5244、http://127.0.0.1:5244/、.../dav、.../dav/ 四种写法。
     */
    fun davBase(): String {
        var base = url.trim()
        if (base.isEmpty()) return ""
        if (!base.startsWith("http://", true) && !base.startsWith("https://", true)) return ""
        base = base.trimEnd('/')
        return if (base.endsWith("/dav", true)) base else "$base/dav"
    }

    /** 列表里显示的名字：地址本身（去掉尾部斜杠，避免同一地址两种写法看起来像两条） */
    fun displayAddress(): String = url.trim().trimEnd('/')

    companion object {
        /** 旧版单源配置迁移过来的那条，id 固定，保证每次读都稳定 */
        const val LEGACY_ID = "legacy"

        fun fromLegacy(url: String, user: String, pass: String, root: String): WebDavSource = WebDavSource(
            id = LEGACY_ID,
            url = url.trim(),
            user = user,
            pass = pass,
            root = root.trim().ifBlank { "/" },
        )
    }
}
