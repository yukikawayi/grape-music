package com.grape.player.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

class SettingsManager(private val context: Context, storageOverride: File? = null) {

    /**
     * 设置文件存放在数据根目录下（Android/data/…/files/datastore/），
     * 不再放 /data/data 私有目录。首次在新位置打开时把旧私有目录里的
     * settings.preferences_pb 拷贝过来，WebDAV 配置等无缝继承。
     */
    private val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(produceFile = {
        val dir = (storageOverride ?: context.filesDir).resolve("datastore")
        dir.mkdirs()
        val target = File(dir, "settings.preferences_pb")
        if (!target.exists() && storageOverride != null && storageOverride.absolutePath != context.filesDir.absolutePath) {
            val legacy = File(context.filesDir, "datastore/settings.preferences_pb")
            if (legacy.exists() && legacy.length() > 0) {
                try {
                    legacy.copyTo(target, overwrite = true)
                } catch (_: Exception) {
                    target.delete()
                }
            }
        }
        target
    })

    companion object {
        private val KEY_WEBDAV_URL = stringPreferencesKey("webdav_url")
        private val KEY_WEBDAV_USER = stringPreferencesKey("webdav_user")
        private val KEY_WEBDAV_PASS = stringPreferencesKey("webdav_pass")
        private val KEY_DAV_ROOT = stringPreferencesKey("dav_root")

        /**
         * 多个 WebDAV 源（JSON 数组）。四个旧键（webdav_url 等）保留只读：
         * 老版本装过一次就有值，读取时迁移成一条，用户下次保存列表后自然落成新格式。
         */
        private val KEY_SOURCES = stringPreferencesKey("webdav_sources")

        // 旧版本把网盘根目录存在 cloud_root（网盘直扫）、strm 根目录存在 strm_root，这里只读来做兼容
        private val KEY_LEGACY_CLOUD_ROOT = stringPreferencesKey("cloud_root")
        private val KEY_TARGET_LUFS = intPreferencesKey("target_lufs")
        private val KEY_AUTO_SCAN = booleanPreferencesKey("auto_scan")
        private val KEY_SCAN_ON_LAUNCH = booleanPreferencesKey("scan_on_launch")
        private val KEY_ENABLED_PROVIDERS = stringPreferencesKey("enabled_providers")

        // 手动搜索（更改元数据弹窗）启用的源（qq/kugou/netease 子集），与后台补全的 enabledProviders 分开
        private val KEY_MANUAL_PROVIDERS = stringPreferencesKey("manual_search_providers")

        /** 数据存放目录（绝对路径 = Android/data 下的外部应用目录） */
        private val KEY_DATA_DIR = stringPreferencesKey("data_dir")

        // 播放页封面：大小（dp）与竖直位置（0=顶部 0.5=居中 1=底部）
        private val KEY_PLAYER_COVER_SIZE = intPreferencesKey("player_cover_size")
        private val KEY_PLAYER_COVER_Y = floatPreferencesKey("player_cover_y")

        // 播放页标题/艺术家水平居中显示（默认开，关则左对齐）
        private val KEY_PLAYER_TITLE_CENTER = booleanPreferencesKey("player_title_center")

        // 点击歌曲开始播放时是否自动打开播放页（默认开 = 保持原有行为，关则留在当前页）
        private val KEY_PLAYER_OPEN_ON_PLAY = booleanPreferencesKey("player_open_on_play")

        // 歌词：字号（sp）、对齐（默认居中）、卡拉OK扫光（默认开）、全屏显示（默认关）
        private val KEY_LYRIC_FONT_SIZE = intPreferencesKey("lyric_font_size")
        private val KEY_LYRIC_ALIGN_CENTER = booleanPreferencesKey("lyric_align_center")
        private val KEY_LYRIC_KARAOKE = booleanPreferencesKey("lyric_karaoke")
        private val KEY_LYRIC_FULLSCREEN = booleanPreferencesKey("lyric_fullscreen")

        // 歌词下方译文行是否显示（默认显示）
        private val KEY_LYRIC_TRANSLATION = booleanPreferencesKey("lyric_translation")

        // 歌曲列表是否显示封面缩略图（默认关：纯文字列表更紧凑）
        private val KEY_SONG_LIST_COVER = booleanPreferencesKey("song_list_cover")

        /** 歌曲缓存上限（MB）：0 = 不缓存；缓存目录是数据目录下的 Cache/（与 OnlineCovers 同级） */
        private val KEY_CACHE_LIMIT_MB = intPreferencesKey("cache_limit_mb")

        /**
         * 各视图排序设置的 key 前缀，完整 key = "sort_" + 视图 key
         * （sort_all / sort_album / sort_artist / sort_folder / sort_video）。
         * 每个视图各存一份，互不覆盖 —— 以前只有一个 sortBy 状态，切视图不支持就回落标题，
         * 再切回来上次选的排序就没了。
         */
        private const val SORT_KEY_PREFIX = "sort_"

        /**
         * 音乐库「页面设置」：上次停在哪个视图（LibraryView.key）、文件夹视图用层级还是平铺。
         *
         * 存 key / 枚举名而不是序号或中文：以后加视图或改文案，已存的值依然解析得出来。
         * 空字符串 = 从没选过，用代码里的默认值（全部 / 层级）。
         */
        private val KEY_LIBRARY_VIEW = stringPreferencesKey("library_view")
        private val KEY_FOLDER_BROWSE_MODE = stringPreferencesKey("folder_browse_mode")
    }

    /**
     * 已配置的 WebDAV 源（可以多个，扫描时逐个合并，播放时按 url 匹配认证头）。
     *
     * 老版本只有一份 url/user/pass/root：这里在读取时迁移成一条（id 固定为 [WebDavSource.LEGACY_ID]），
     * 不写回 —— 用户没打开设置页时也能正常播放和扫描；一旦在设置页保存过列表就落成新格式。
     */
    val webDavSources: Flow<List<WebDavSource>> = dataStore.data.map { prefs ->
        val raw = prefs[KEY_SOURCES]
        if (!raw.isNullOrBlank()) {
            val parsed = runCatching {
                Json.decodeFromString<List<WebDavSource>>(raw)
            }.getOrNull()
            if (parsed != null) return@map parsed.filter { it.url.isNotBlank() }
        }
        val legacyUrl = prefs[KEY_WEBDAV_URL].orEmpty()
        if (legacyUrl.isBlank()) {
            emptyList()
        } else {
            listOf(
                WebDavSource.fromLegacy(
                    url = legacyUrl,
                    user = prefs[KEY_WEBDAV_USER].orEmpty(),
                    pass = prefs[KEY_WEBDAV_PASS].orEmpty(),
                    // 与旧版 davRoot 的读取顺序一致：dav_root → cloud_root → /quark
                    root = prefs[KEY_DAV_ROOT] ?: prefs[KEY_LEGACY_CLOUD_ROOT] ?: "/quark",
                ),
            )
        }
    }

    val targetLufs: Flow<Int> = dataStore.data.map { it[KEY_TARGET_LUFS] ?: -16 }
    /** 启用的元数据源（qq/kugou/netease 子集）：后台补全时按固定优先级联网补 Using_ 缺失字段 */
    val enabledProviders: Flow<String> = dataStore.data.map {
        it[KEY_ENABLED_PROVIDERS] ?: "qq,kugou,netease"
    }

    /** 手动搜索启用的源（qq/kugou/netease 子集，默认全开）：更改元数据弹窗里点搜索时同时查询这些源 */
    val manualSearchProviders: Flow<String> = dataStore.data.map {
        it[KEY_MANUAL_PROVIDERS] ?: "qq,kugou,netease"
    }

    /** 扫描时是否补全元数据（读内嵌标签入 Using_ + 按源优先级联网补齐，默认开） */
    val autoScan: Flow<Boolean> = dataStore.data.map { it[KEY_AUTO_SCAN] ?: true }

    /**
     * 下次启动是否继续扫描（**默认关**）。
     * 开：每次打开应用都用保存的配置扫一遍（含上次没扫完的接着扫）；
     * 关：启动不自动扫描，需要时手动点「扫描网盘媒体库」。
     *
     * 默认关是有意为之：整库扫描会把库里所有行重建一遍，大库要跑很久，
     * 不该在用户只想听歌时每次启动都触发。
     */
    val scanOnLaunch: Flow<Boolean> = dataStore.data.map { it[KEY_SCAN_ON_LAUNCH] ?: false }

    /** 播放页封面大小（dp，默认 240） */
    val playerCoverSize: Flow<Int> = dataStore.data.map { it[KEY_PLAYER_COVER_SIZE] ?: 240 }

    /** 播放页封面竖直位置（0=顶部 0.5=居中 1=底部，默认居中） */
    val playerCoverY: Flow<Float> = dataStore.data.map { it[KEY_PLAYER_COVER_Y] ?: 0.5f }

    /** 播放页标题/艺术家水平居中（默认开；关则左对齐） */
    val playerTitleCenter: Flow<Boolean> = dataStore.data.map { it[KEY_PLAYER_TITLE_CENTER] ?: true }

    /** 点击歌曲开始播放时自动打开播放页（默认开 = 原有行为；关则留在当前页，用迷你栏进播放页） */
    val playerOpenOnPlay: Flow<Boolean> = dataStore.data.map { it[KEY_PLAYER_OPEN_ON_PLAY] ?: true }

    /** 歌词字号（sp，默认 16；当前行 +2、译文 -3） */
    val lyricFontSize: Flow<Int> = dataStore.data.map { it[KEY_LYRIC_FONT_SIZE] ?: 16 }

    /** 歌词水平居中（默认开；关则左对齐） */
    val lyricAlignCenter: Flow<Boolean> = dataStore.data.map { it[KEY_LYRIC_ALIGN_CENTER] ?: true }

    /** 卡拉OK逐字扫光（默认开） */
    val lyricKaraoke: Flow<Boolean> = dataStore.data.map { it[KEY_LYRIC_KARAOKE] ?: true }

    /** 歌词全屏显示：歌词页隐藏进度条与播放按钮（默认关） */
    val lyricFullscreen: Flow<Boolean> = dataStore.data.map { it[KEY_LYRIC_FULLSCREEN] ?: false }

    /** 歌曲列表显示封面缩略图（默认关） */
    val songListCover: Flow<Boolean> = dataStore.data.map { it[KEY_SONG_LIST_COVER] ?: false }

    /**
     * 歌曲缓存上限（MB，默认 4GB）。
     * 播放过的歌在本地留一份，再次播放直接读本地；超上限按最久未播放淘汰。
     */
    val cacheLimitMb: Flow<Int> = dataStore.data.map {
        it[KEY_CACHE_LIMIT_MB] ?: MediaCache.DEFAULT_LIMIT_MB
    }

    /** 歌词译文行显示（默认开） */
    val lyricTranslation: Flow<Boolean> = dataStore.data.map { it[KEY_LYRIC_TRANSLATION] ?: true }

    /** 数据存放目录（Android/data 下的外部应用目录） */
    val dataDir: Flow<String> = dataStore.data.map { it[KEY_DATA_DIR] ?: "" }

    /**
     * 音乐库上次停留的视图（LibraryView.key：folder/all/album/artist/video，空串 = 没选过）。
     *
     * 以前这个状态是 `remember`，只要跳出过一次页面（比如点歌进播放页再返回）就没了，
     * 回来总是落在「全部」—— 现在持久化，页面内进出与重启都能回到上次那个视图。
     */
    val libraryView: Flow<String> = dataStore.data.map { it[KEY_LIBRARY_VIEW] ?: "" }

    /** 文件夹视图的浏览方式（HIERARCHY 层级 / FLAT 平铺，默认层级） */
    val folderBrowseMode: Flow<String> = dataStore.data.map {
        it[KEY_FOLDER_BROWSE_MODE] ?: "HIERARCHY"
    }

    /**
     * 所有视图的排序设置：Map<视图 key, SortBy.name>。
     *
     * 存枚举名（TITLE/YEAR/…）而不是中文 label：以后改文案（比如「标题」→「按标题」）
     * 已保存的值依然能解析出来，不会静默回落成默认排序。
     * 一次读全量而不是每个视图各建一个 Flow，方便页面用同一份快照驱动当前视图的排序。
     */
    val sortPrefs: Flow<Map<String, String>> = dataStore.data.map { prefs ->
        prefs.asMap().entries.mapNotNull { (key, value) ->
            val name = key.name
            if (!name.startsWith(SORT_KEY_PREFIX)) return@mapNotNull null
            name.removePrefix(SORT_KEY_PREFIX) to (value as? String).orEmpty()
        }.toMap()
    }

    /** 保存整个 WebDAV 源列表（新增 / 编辑 / 删除都是整表覆盖，条目量很小） */
    suspend fun setWebDavSources(sources: List<WebDavSource>) {
        dataStore.edit { prefs ->
            prefs[KEY_SOURCES] = Json.encodeToString(sources)
            // 顺手把旧键清掉：迁移只在「没有新键」时生效，留着旧 url 会让删除最后一个源后又冒出来
            prefs.remove(KEY_WEBDAV_URL)
            prefs.remove(KEY_WEBDAV_USER)
            prefs.remove(KEY_WEBDAV_PASS)
            prefs.remove(KEY_DAV_ROOT)
            prefs.remove(KEY_LEGACY_CLOUD_ROOT)
        }
    }

    suspend fun setTargetLufs(value: Int) {
        dataStore.edit { it[KEY_TARGET_LUFS] = value }
    }

    suspend fun setAutoScan(enabled: Boolean) {
        dataStore.edit { it[KEY_AUTO_SCAN] = enabled }
    }

    suspend fun setScanOnLaunch(enabled: Boolean) {
        dataStore.edit { it[KEY_SCAN_ON_LAUNCH] = enabled }
    }

    suspend fun setEnabledProviders(value: String) {
        dataStore.edit { it[KEY_ENABLED_PROVIDERS] = value }
    }

    suspend fun setManualSearchProviders(value: String) {
        dataStore.edit { it[KEY_MANUAL_PROVIDERS] = value }
    }

    suspend fun setPlayerCoverSize(value: Int) {
        dataStore.edit { it[KEY_PLAYER_COVER_SIZE] = value }
    }

    suspend fun setPlayerCoverY(value: Float) {
        dataStore.edit { it[KEY_PLAYER_COVER_Y] = value }
    }

    suspend fun setPlayerTitleCenter(enabled: Boolean) {
        dataStore.edit { it[KEY_PLAYER_TITLE_CENTER] = enabled }
    }

    suspend fun setPlayerOpenOnPlay(enabled: Boolean) {
        dataStore.edit { it[KEY_PLAYER_OPEN_ON_PLAY] = enabled }
    }

    suspend fun setLyricFontSize(value: Int) {
        dataStore.edit { it[KEY_LYRIC_FONT_SIZE] = value }
    }

    suspend fun setLyricAlignCenter(enabled: Boolean) {
        dataStore.edit { it[KEY_LYRIC_ALIGN_CENTER] = enabled }
    }

    suspend fun setLyricKaraoke(enabled: Boolean) {
        dataStore.edit { it[KEY_LYRIC_KARAOKE] = enabled }
    }

    suspend fun setLyricFullscreen(enabled: Boolean) {
        dataStore.edit { it[KEY_LYRIC_FULLSCREEN] = enabled }
    }

    suspend fun setSongListCover(enabled: Boolean) {
        dataStore.edit { it[KEY_SONG_LIST_COVER] = enabled }
    }

    /** 设置歌曲缓存上限（MB）：0 = 关闭缓存并清空已缓存文件 */
    suspend fun setCacheLimitMb(mb: Int) {
        dataStore.edit { it[KEY_CACHE_LIMIT_MB] = mb }
    }

    suspend fun setLyricTranslation(enabled: Boolean) {
        dataStore.edit { it[KEY_LYRIC_TRANSLATION] = enabled }
    }

    suspend fun setDataDir(value: String) {
        dataStore.edit { it[KEY_DATA_DIR] = value }
    }

    /** 记住某个视图的排序（[viewKey] 取 LibraryView.key，[sort] 传 SortBy.name） */
    suspend fun setSort(viewKey: String, sort: String) {
        dataStore.edit { it[stringPreferencesKey(SORT_KEY_PREFIX + viewKey)] = sort }
    }

    /** 记住音乐库当前停留的视图（[viewKey] 取 LibraryView.key） */
    suspend fun setLibraryView(viewKey: String) {
        dataStore.edit { it[KEY_LIBRARY_VIEW] = viewKey }
    }

    /** 记住文件夹视图的浏览方式（[mode] 传 FolderBrowseMode.name） */
    suspend fun setFolderBrowseMode(mode: String) {
        dataStore.edit { it[KEY_FOLDER_BROWSE_MODE] = mode }
    }

    suspend fun clear() {
        dataStore.edit { it.clear() }
    }
}
