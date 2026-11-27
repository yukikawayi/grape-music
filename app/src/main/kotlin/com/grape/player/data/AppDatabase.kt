package com.grape.player.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        MediaItem::class,
        LyricEntry::class,
        Playlist::class,
        PlaylistItem::class,
        RecentPlay::class,
        SettingEntry::class,
        MetaCache::class,
    ],
    version = 12,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun mediaDao(): MediaDao
    abstract fun lyricDao(): LyricDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun recentPlayDao(): RecentPlayDao
    abstract fun settingDao(): SettingDao
    abstract fun metaCacheDao(): MetaCacheDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        /** v2：media_items 增加音轨号 / 碟号两列 */
        private val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE media_items ADD COLUMN trackNumber INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE media_items ADD COLUMN discNumber INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** v3：lyrics 增加「已确认无歌词」负缓存标记列 */
        private val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE lyrics ADD COLUMN missAt INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * v5：元数据三套分离 + 重置负缓存标记。
         * 1. media_items 增加 Embedded_/Online_ 快照列（16 列）与来源标记列 metaSource
         *    （0=未补全/1=内嵌/2=在线/3=手动）；
         *    内嵌封面落 EmbeddedCovers/、在线封面落 OnlineCovers/，Using_ 仍是显示列；
         * 2. lyrics 重置 missAt —— 修复前 EmbeddedTagReader 缓存文件无扩展名 →
         *    jaudiotagger 按扩展名分发失败 → 内嵌读取 100% 静默失败，
         *    此期间写入的 missAt 全是误标（内嵌其实有歌词）。
         */
        private val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("UPDATE lyrics SET missAt = 0")
            }
        }

        /** v4→v5：Embedded_/Online_ 快照列 + metaSource（v5 未曾发布，全部并入本迁移） */
        private val MIGRATION_4_5 = object : androidx.room.migration.Migration(4, 5) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                fun addCol(name: String, type: String) {
                    db.execSQL("ALTER TABLE media_items ADD COLUMN $name $type NOT NULL DEFAULT ${if (type == "INTEGER") 0 else "''"}")
                }
                addCol("metaSource", "INTEGER")
                addCol("embTitle", "TEXT"); addCol("embArtist", "TEXT"); addCol("embAlbum", "TEXT")
                addCol("embYear", "INTEGER"); addCol("embTrackNumber", "INTEGER"); addCol("embDiscNumber", "INTEGER")
                addCol("embCoverUrl", "TEXT"); addCol("embLyric", "TEXT")
                addCol("onlineTitle", "TEXT"); addCol("onlineArtist", "TEXT"); addCol("onlineAlbum", "TEXT")
                addCol("onlineYear", "INTEGER"); addCol("onlineTrackNumber", "INTEGER"); addCol("onlineDiscNumber", "INTEGER")
                addCol("onlineCoverUrl", "TEXT"); addCol("onlineLyric", "TEXT")
            }
        }

        /**
         * v6：移除 Online_ 快照列 —— 在线刮削结果不再留快照，直接只填空写进 Using_。
         * SQLite 不支持 DROP COLUMN（兼容旧版本），按「建新表 → 拷数据 → 换名」重建 media_items。
         * Embedded_ 快照列与 metaSource 保留；曲目、歌词、歌单数据全部无损迁移。
         */
        private val MIGRATION_5_6 = object : androidx.room.migration.Migration(5, 6) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE media_items_new (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        url TEXT NOT NULL,
                        title TEXT NOT NULL,
                        artist TEXT NOT NULL,
                        album TEXT NOT NULL,
                        year INTEGER NOT NULL,
                        durationMs INTEGER NOT NULL,
                        sizeBytes INTEGER NOT NULL,
                        folder TEXT NOT NULL,
                        coverUrl TEXT NOT NULL,
                        lyric TEXT NOT NULL,
                        replayGainGain REAL NOT NULL,
                        replayGainPeak REAL NOT NULL,
                        lufs REAL NOT NULL,
                        peak REAL NOT NULL,
                        loudnessTarget INTEGER NOT NULL,
                        coverage REAL NOT NULL,
                        isVideo INTEGER NOT NULL,
                        trackNumber INTEGER NOT NULL,
                        discNumber INTEGER NOT NULL,
                        metaSource INTEGER NOT NULL,
                        embTitle TEXT NOT NULL,
                        embArtist TEXT NOT NULL,
                        embAlbum TEXT NOT NULL,
                        embYear INTEGER NOT NULL,
                        embTrackNumber INTEGER NOT NULL,
                        embDiscNumber INTEGER NOT NULL,
                        embCoverUrl TEXT NOT NULL,
                        embLyric TEXT NOT NULL
                    )""",
                )
                db.execSQL(
                    """INSERT INTO media_items_new (
                        id,url,title,artist,album,year,durationMs,sizeBytes,folder,coverUrl,lyric,
                        replayGainGain,replayGainPeak,lufs,peak,loudnessTarget,coverage,isVideo,
                        trackNumber,discNumber,metaSource,
                        embTitle,embArtist,embAlbum,embYear,embTrackNumber,embDiscNumber,embCoverUrl,embLyric
                    ) SELECT
                        id,url,title,artist,album,year,durationMs,sizeBytes,folder,coverUrl,lyric,
                        replayGainGain,replayGainPeak,lufs,peak,loudnessTarget,coverage,isVideo,
                        trackNumber,discNumber,metaSource,
                        embTitle,embArtist,embAlbum,embYear,embTrackNumber,embDiscNumber,embCoverUrl,embLyric
                    FROM media_items""",
                )
                db.execSQL("DROP TABLE media_items")
                db.execSQL("ALTER TABLE media_items_new RENAME TO media_items")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_media_items_url ON media_items(url)")
            }
        }

        /** v7：media_items 增加 embReadAt（内嵌快照读取时间，0 = 还没读过，用于只回填一次） */
        private val MIGRATION_6_7 = object : androidx.room.migration.Migration(6, 7) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE media_items ADD COLUMN embReadAt INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * v8：修复网易云封面 URL 缺少协议头的老数据（无 schema 变化，只做数据修补）。
         * 旧代码 removePrefix("http://") 后没有补回 https://，库里存成 "p2.music.126.net/…"，
         * Coil 按本地文件路径解析必然加载失败。新代码已修正，这里把历史行原地补全。
         */
        private val MIGRATION_7_8 = object : androidx.room.migration.Migration(7, 8) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "UPDATE media_items SET coverUrl = 'https://' || coverUrl " +
                        "WHERE coverUrl LIKE '%.music.126.net/%'"
                )
            }
        }

        /**
         * v9：新增 recent_plays 表（「最近播放」虚拟歌单的数据源）。
         * 独立成表的原因见 [RecentPlay]：它需要永远排在歌单列表第一位且不可编辑。
         * 表内最多 100 行，不建索引（Room 会校验 schema，索引需与实体注解一致）。
         */
        private val MIGRATION_8_9 = object : androidx.room.migration.Migration(8, 9) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS recent_plays (" +
                        "url TEXT NOT NULL, " +
                        "playedAt INTEGER NOT NULL, " +
                        "PRIMARY KEY(url))"
                )
            }
        }

        /**
         * v10：lyrics 增加出处与写入时间两列（「这份词是哪家给的、什么时候写的」）。
         *
         * 老行没有办法回溯出处，只能留空：source = ''、updatedAt = 0，
         * UI 上显示「来源未记录（旧数据）」，而不是按当前匹配结果瞎猜一个。
         * 两列都带 NOT NULL DEFAULT，纯 ALTER 即可，不必重建表。
         */
        private val MIGRATION_9_10 = object : androidx.room.migration.Migration(9, 10) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE lyrics ADD COLUMN source TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE lyrics ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * v11：media_items 增加 metaMissAt（上次在线补全一无所获的时间，0 = 没失败过）。
         *
         * 解决的是一类「永远补不上」的歌反复白搜：上游对某些歌从来不给年份/专辑，
         * 而 getNeedingMeta 只看「字段是否为空」，于是每次扫描结束都重新搜一遍。
         * 老行补 0 表示「没失败过」，升级后这些歌会照常再被搜一次 ——
         * 结果仍然拿不到的会被记上时间戳，之后按 TTL 退避，正是想要的行为。
         */
        private val MIGRATION_10_11 = object : androidx.room.migration.Migration(10, 11) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE media_items ADD COLUMN metaMissAt INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * v12：给 media_items 的 isVideo / folder 补索引。
         *
         * 列表查询按 isVideo 过滤（音乐/视频分流）、按 folder 排序与分组，大库下
         * 没有索引就是整表扫。加索引是纯增益、无数据迁移，两条 CREATE INDEX 即可。
         */
        private val MIGRATION_11_12 = object : androidx.room.migration.Migration(11, 12) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS index_media_items_isVideo ON media_items(isVideo)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_media_items_folder ON media_items(folder)")
            }
        }

        /**
         * [dbName] 传文件名（media.db = 应用私有 databases/）或绝对路径
         * （数据存放位置设在用户选择的文件夹时，SQLiteOpenHelper 对绝对路径按原样打开）。
         */
        fun get(context: Context, dbName: String = "media.db"): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    dbName,
                ).addMigrations(
                        MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7,
                        MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12,
                    ).build().also { instance = it }
            }
    }
}
