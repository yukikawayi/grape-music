package com.grape.player

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import com.grape.player.ui.AppRoot
import com.grape.player.ui.AppViewModel
import com.grape.player.ui.theme.WebDAVPlayerTheme

class MainActivity : ComponentActivity() {

    /**
     * Activity 级持有（而不是在 setContent 里 viewModel()）：
     * onDestroy 需要拿到同一个实例去停后台扫描/补全。
     */
    private val vm: AppViewModel by viewModels()

    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* 权限结果由各功能页自行处理 */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNeededPermissions()
        // 从最近任务划掉应用时 Activity 可能还没销毁，让播放服务能叫停扫描/补全
        (application as WebDAVPlayerApp).backgroundWorkStopper = { vm.stopBackgroundWork() }
        setContent {
            val seed by vm.accentSeed.collectAsState()
            val dynamicEnabled by vm.dynamicColorEnabled.collectAsState()
            WebDAVPlayerTheme(seedRgb = seed, dynamicColorEnabled = dynamicEnabled) {
                AppRoot(vm = vm)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // 界面每次回到前台都检查一次：上次被打断的扫描/补全在这里接上。
        // 放 onStart 而不是 onCreate —— 从最近任务划掉后再点开，Activity 常被复用、onCreate 不会重跑。
        vm.resumeBackgroundTasks()
    }

    override fun onDestroy() {
        // isFinishing = 用户主动退出（返回键退出 / 从最近任务划掉），
        // 而不是配置变化（旋转、深色模式切换）导致的正常重建。
        if (isFinishing) vm.stopBackgroundWork()
        (application as WebDAVPlayerApp).backgroundWorkStopper = null
        super.onDestroy()
    }

    private fun requestNeededPermissions() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (permissions.isNotEmpty()) requestPermissions.launch(permissions.toTypedArray())
    }
}
