package com.grape.player

import android.app.Application
import com.grape.player.data.AppDatabase
import com.grape.player.data.DataDirManager
import com.grape.player.data.MediaCache
import com.grape.player.data.Repository
import com.grape.player.data.SettingsManager
import com.grape.player.data.WebDavCredentials
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class WebDAVPlayerApp : Application() {
    lateinit var database: AppDatabase
        private set
    lateinit var settings: SettingsManager
        private set
    lateinit var repo: Repository
        private set

    /**
     * 应用级后台作用域（只做轻量的设置同步 / 磁盘清理，随进程存活，不需要取消）。
     */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 停止后台取数任务（扫描 + 元数据补全）的回调，由 MainActivity 注册。
     * 播放服务在 onTaskRemoved（用户从最近任务划掉应用）时用它叫停扫描 ——
     * 那个时机 Activity 往往还没销毁，只能由服务侧触发。
     */
    @Volatile
    var backgroundWorkStopper: (() -> Unit)? = null

    override fun onCreate() {
        super.onCreate()
        // 数据根目录固定在外部应用目录 Android/data/<pkg>/files/（设置文件也在这，需先于迁移确定）
        val dataBase = DataDirManager.resolveBase(this)
        settings = SettingsManager(this, dataBase)
        // 迁移旧位置数据（私有目录 / 用户曾选的其他文件夹）必须在打开 Room 之前完成
        val effective = DataDirManager.init(this, settings)
        database = AppDatabase.get(this, File(effective, "media.db").absolutePath)
        repo = Repository(database)
        // 播放服务靠全局凭据快照给每个请求补 Basic 认证，启动即同步一次
        WebDavCredentials.attach(settings)

        // 歌曲缓存上限：播放线程（DataSource）只读同步值，这里把设置持续同步过去。
        // 改上限 / 关缓存（0）时同时按新上限淘汰或清空 —— 用户不必等下一次播放才生效。
        appScope.launch {
            settings.cacheLimitMb.collect { mb -> MediaCache.applyLimit(mb) }
        }

        // 注意：这里不再自动续跑扫描/元数据补全。
        // 续跑改由 MainActivity 启动时触发（用户真正打开应用才跑），
        // 这样播放服务被系统拉起、或进程残留在后台时，不会偷偷继续扫描。
    }
}
