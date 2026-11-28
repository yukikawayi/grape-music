package com.grape.player.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.grape.player.util.CoverStore
import java.io.File
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.runBlocking

/**
 * 数据存放目录 —— **固定为外部应用目录** /storage/emulated/0/Android/data/<pkg>/files/
 * （getExternalFilesDir）。应用对自己的 Android/data 目录有天然读写权限，
 * 不需要任何存储授权，普通文件管理器可以直接看到、备份数据。
 * 数据一律不落在 /data/data/ 私有目录里（除了无外部存储的极端回退）。
 *
 * 迁移时机：[init] 在 Application.onCreate 里、Room 打开之前同步执行 ——
 * 此刻数据库文件没有任何进程持有，复制/删除都是安全的。
 */
object DataDirManager {

    private const val DB_NAME = "media.db"

    /** 歌曲缓存目录名（与三个封面目录平级；可再生的临时数据，迁移时不搬） */
    const val CACHE_DIR_NAME = "Cache"

    private val COVER_DIRS = listOf("cover", "EmbeddedCovers", "OnlineCovers")
    // onlineCoverUrl 列已在 v6 移除（Online_ 快照不再保留），路径重写只覆盖仍存在的两列
    private val COVER_COLUMNS = listOf("coverUrl", "embCoverUrl")

    /** 当前生效的数据根目录（init 后可用；迁移失败时为旧目录） */
    @Volatile
    var effectiveBase: File? = null
        private set

    /** 数据根目录：外部应用目录；设备无外部存储时回退应用私有目录 */
    fun resolveBase(context: Context): File =
        context.getExternalFilesDir(null) ?: context.filesDir

    /**
     * 处理历史遗留位置：dataDir 设置指向旧私有目录或用户曾选的其他文件夹时，
     * 把数据库 + 三个封面目录整体迁到 resolveBase。返回实际生效的数据根目录。
     */
    fun init(context: Context, settings: SettingsManager): File {
        val target = resolveBase(context)
        val current = runBlocking { settings.dataDir.firstOrNull().orEmpty().trim() }
        val oldBase = when {
            current.isEmpty() -> context.filesDir
            File(current).absolutePath != target.absolutePath -> File(current)
            else -> null
        }
        var effective = target
        if (oldBase != null && oldBase.absolutePath != target.absolutePath) {
            try {
                migrate(context, oldBase, target)
                runBlocking { settings.setDataDir(target.absolutePath) }
            } catch (_: Exception) {
                // 迁移失败：数据留在原地（无损），继续用旧目录跑
                effective = oldBase
            }
        }
        CoverStore.externalBase = effective
        effectiveBase = effective
        return effective
    }

    /** 数据库（含 wal/shm 残留）+ 三个封面目录整体迁移，成功后删除旧位置（move 语义） */
    private fun migrate(context: Context, oldBase: File, newBase: File) {
        newBase.mkdirs()
        val oldDb = if (oldBase == context.filesDir) context.getDatabasePath(DB_NAME) else File(oldBase, DB_NAME)
        val newDb = File(newBase, DB_NAME)
        if (oldDb.exists()) {
            copyFile(oldDb, newDb)
            for (suffix in listOf("-wal", "-shm")) {
                val f = File(oldDb.absolutePath + suffix)
                if (f.exists()) copyFile(f, File(newDb.absolutePath + suffix))
            }
            // 库里存的封面 file:// 路径前缀要跟着换，否则迁移完封面全裂
            rewriteCoverPaths(newDb, oldBase, newBase)
        }
        for (name in COVER_DIRS) copyDir(File(oldBase, name), File(newBase, name))

        // 校验通过才删旧位置；任何一步失败都会抛出并由 init 保持旧目录
        if (oldDb.exists() && newDb.length() <= 0L) throw IllegalStateException("数据库复制失败")
        oldDb.delete()
        File(oldDb.absolutePath + "-wal").delete()
        File(oldDb.absolutePath + "-shm").delete()
        for (name in COVER_DIRS) File(oldBase, name).deleteRecursively()
        // 缓存是可再生数据，不搬（几十 GB 复制一次代价太大），旧位置直接清掉省空间
        File(oldBase, CACHE_DIR_NAME).deleteRecursively()
    }

    /** 把库内三列封面路径里指向旧目录的前缀替换成新目录（file:// 前缀不动，只换中间路径段） */
    private fun rewriteCoverPaths(dbFile: File, oldBase: File, newBase: File) {
        val db = SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE)
        try {
            for (col in COVER_COLUMNS) {
                for (dirName in COVER_DIRS) {
                    val oldPrefix = File(oldBase, dirName).absolutePath
                    val newPrefix = File(newBase, dirName).absolutePath
                    db.execSQL(
                        "UPDATE media_items SET $col = replace($col, ?, ?) WHERE $col LIKE ?",
                        arrayOf(oldPrefix, newPrefix, "$oldPrefix%"),
                    )
                }
            }
        } finally {
            db.close()
        }
    }

    private fun copyFile(src: File, dst: File) {
        src.copyTo(dst, overwrite = true)
    }

    private fun copyDir(src: File, dst: File) {
        if (!src.exists()) return
        dst.mkdirs()
        src.listFiles()?.forEach { f ->
            if (f.isFile) copyFile(f, File(dst, f.name))
        }
    }
}
