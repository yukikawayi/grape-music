package com.grape.player.ui

import android.net.Uri
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.grape.player.ui.library.DetailScreen
import com.grape.player.ui.library.LibraryScreen
import com.grape.player.ui.meta.MetaEditDialog
import com.grape.player.ui.player.MiniPlayerBar
import com.grape.player.ui.player.PlayerScreen
import com.grape.player.ui.player.VideoPlayerScreen
import com.grape.player.ui.playlist.PlaylistScreen
import com.grape.player.ui.search.SearchScreen
import com.grape.player.ui.settings.SettingsScreen

/** 导航项 */
private data class NavItem(
    val route: String,
    val label: String,
    val icon: @Composable () -> Unit,
)

private val navItems = listOf(
    NavItem("library", "音乐库") { Icon(Icons.Filled.LibraryMusic, contentDescription = null) },
    NavItem("playlist", "歌单") { Icon(Icons.Filled.Home, contentDescription = null) },
    NavItem("search", "搜索") { Icon(Icons.Filled.Search, contentDescription = null) },
    NavItem("settings", "设置") { Icon(Icons.Filled.Settings, contentDescription = null) },
)

/**
 * 应用根组件 —— 导航 + 页面路由。
 * 响应式：窄屏（手机）用底部 NavigationBar；宽屏（平板 / 折叠屏展开）用左侧 NavigationRail，
 * 内容区填满剩余宽度。详情页 / 播放器 / 视频页在宽屏下仍占满内容区。
 */
@Composable
fun AppRoot(vm: AppViewModel) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination
    val isWide = LocalConfiguration.current.screenWidthDp >= 600

    // 宽屏双栏左选中的分组（type, value）；窄屏不用，仍走详情路由
    var selectedGroup by remember { mutableStateOf<Pair<String, String>?>(null) }

    // 播放页不再走导航路由，改成这里的顶层覆盖层：
    //  - 打开：直接叠在当前页面之上，音乐库/歌单的组合原封不动（不会因导航被销毁）；
    //  - 收起：动画结束后置 false 移除覆盖层 —— 没有 pop、没有导航转场、底层零重建，
    //    这是之前「收起卡顿 + 空白 1~2 秒」的根治。
    var playerVisible by remember { mutableStateOf(false) }

    // 这里曾经有一个「重组活性探针」：每 300ms 写一次 recomposeTick + SideEffect 打日志。
    // 它每 300ms 让 AppRoot 整个函数体失效，连带 NavHost 与当前页面一起重组（每秒 3.3 次、
    // 持续不断），把下面所有「减少重组」的优化抵掉一大半；而它只在排查「重组调度死亡」
    // 那一次用得上。已删除 —— 真要判断 Recomposer 是否活着，用 Layout Inspector 的
    // recomposition 计数或临时加一段 debug-only 探针，不要长期留在主链路上。

    // 点击歌曲开始播放时是否自动打开播放页（设置页「播放」区开关）。
    // 只影响「点歌」这个入口；迷你播放栏是显式的打开动作，始终直接进播放页
    val openPlayerOnPlay by vm.settings.playerOpenOnPlay.collectAsState(initial = true)
    val openPlayerOnSongClick: () -> Unit = { if (openPlayerOnPlay) playerVisible = true }

    // 迷你播放栏：有播放项就常驻；播放覆盖层打开或视频页时隐藏（避免重复/透出）
    val currentRoute = currentDestination?.route ?: ""
    val showMiniBar = !playerVisible && !currentRoute.startsWith("video")

    Box(modifier = Modifier.fillMaxSize()) {
        if (isWide) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(modifier = Modifier.fillMaxSize().weight(1f)) {
                    NavigationRail {
                        navItems.forEach { item ->
                            NavigationRailItem(
                                icon = item.icon,
                                label = { Text(item.label) },
                                selected = currentDestination?.hierarchy?.any { it.route == item.route } == true,
                                // 播放覆盖层开着时点导航：切 tab 的同时把覆盖层收掉，
                                // 不然页面在底下切了、播放页还挡在前面
                                onClick = {
                                    playerVisible = false
                                    navigateToTab(navController, item.route)
                                },
                            )
                        }
                    }
                    // 宽屏：播放覆盖层只盖内容区，左侧导航 Rail 保持可见（和原路由版一致）
                    Box(modifier = Modifier.weight(1f)) {
                        AppNavHost(
                            vm = vm,
                            navController = navController,
                            modifier = Modifier.fillMaxSize(),
                            isWide = true,
                            selectedGroup = selectedGroup,
                            onSelectGroup = { selectedGroup = it },
                            onOpenPlayer = openPlayerOnSongClick,
                        )
                        if (playerVisible) {
                            PlayerScreen(
                                vm = vm,
                                onBack = { playerVisible = false },
                                onGoto = { type, value ->
                                    playerVisible = false
                                    navController.navigate("detail?type=${Uri.encode(type)}&value=${Uri.encode(value)}")
                                },
                            )
                        }
                    }
                }
                if (showMiniBar) {
                    MiniPlayerBar(
                        vm = vm,
                        onOpenPlayer = { playerVisible = true },
                        onBrowseLibrary = { navigateToTab(navController, "library") },
                    )
                }
            }
        } else {
            Scaffold(
                bottomBar = {
                    Column {
                        // 迷你播放栏固定在四个导航选项上方，所有页面可见
                        if (showMiniBar) {
                            MiniPlayerBar(
                                vm = vm,
                                onOpenPlayer = { playerVisible = true },
                                onBrowseLibrary = { navigateToTab(navController, "library") },
                            )
                        }
                        NavigationBar {
                            navItems.forEach { item ->
                                NavigationBarItem(
                                    icon = item.icon,
                                    label = { Text(item.label) },
                                    selected = currentDestination?.hierarchy?.any { it.route == item.route } == true,
                                    // 播放覆盖层开着时点导航：切 tab 的同时把覆盖层收掉
                                    onClick = {
                                        playerVisible = false
                                        navigateToTab(navController, item.route)
                                    },
                                )
                            }
                        }
                    }
                },
            ) { innerPadding ->
                // 窄屏：覆盖层放在 Scaffold 内容区里 —— 底部「音乐库/歌单/搜索/设置」
                // 四个按钮全程可见，和原来路由版播放页的表现一致
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                ) {
                    AppNavHost(
                        vm = vm,
                        navController = navController,
                        modifier = Modifier.fillMaxSize(),
                        isWide = false,
                        selectedGroup = null,
                        onSelectGroup = {},
                        onOpenPlayer = openPlayerOnSongClick,
                    )
                    if (playerVisible) {
                        PlayerScreen(
                            vm = vm,
                            onBack = { playerVisible = false },
                            onGoto = { type, value ->
                                playerVisible = false
                                navController.navigate("detail?type=${Uri.encode(type)}&value=${Uri.encode(value)}")
                            },
                        )
                    }
                }
            }
        }
    }

    // 元数据编辑面板（全局挂一份，Dialog 覆盖全站，不受布局影响）
    MetaEditDialog(vm = vm)
}

/**
 * 切底部 tab。
 *
 * 原来用官方示例的 `popUpTo(start){saveState = true} + restoreState = true`：
 * saveState 保存的是「被弹掉的那一整段回退栈」，restoreState 会把这段原样恢复回来。
 * 于是「设置 → 点迷你栏进播放页 → 再点设置」时，恢复出来的栈是 [设置, 播放页]，
 * 栈顶还是播放页 —— 表现就是点 tab 完全没反应。
 *
 * 改成：目标 tab 还在回退栈里就直接弹回去（播放页/详情页自然被弹掉），
 * 只有目标 tab 不在栈里时才新建。这样既能正确落到底部四个视图，也不会丢 tab 的滚动位置。
 */
private fun navigateToTab(navController: NavHostController, route: String) {
    if (navController.currentDestination?.route == route) return
    if (navController.popBackStack(route, inclusive = false)) return
    navController.navigate(route) {
        popUpTo(navController.graph.findStartDestination().id) { inclusive = false }
        launchSingleTop = true
    }
}

/** 页面路由（宽窄屏共用一份，保证行为一致） */
@Composable
private fun AppNavHost(
    vm: AppViewModel,
    navController: NavHostController,
    modifier: Modifier = Modifier,
    isWide: Boolean = false,
    selectedGroup: Pair<String, String>? = null,
    onSelectGroup: (Pair<String, String>?) -> Unit = {},
    // 播放页已是 AppRoot 顶层覆盖层，不再是路由；点歌进播放页走这个回调
    onOpenPlayer: () -> Unit = {},
) {
    NavHost(
        navController = navController,
        startDestination = "library",
        modifier = modifier,
        // 转场只覆盖剩余路由（详情/视频等）：全局改成快速淡入淡出，避免默认 ~700ms
        // 的淡入淡出拖慢页面切换
        enterTransition = { fadeIn(tween(180)) },
        exitTransition = { fadeOut(tween(180)) },
        popEnterTransition = { fadeIn(tween(180)) },
        popExitTransition = { fadeOut(tween(180)) },
    ) {
        composable("library") {
            if (isWide) {
                // 宽屏：左侧音乐库列表 + 右侧分组详情并排
                Row(modifier = Modifier.fillMaxSize()) {
                    Box(modifier = Modifier.weight(1f)) {
                        LibraryScreen(
                            vm = vm,
                            onSongClick = onOpenPlayer,
                            onVideoClick = { url, title ->
                                navController.navigate("video?url=${Uri.encode(url)}&title=${Uri.encode(title)}")
                            },
                            onGroupClick = { type, value -> onSelectGroup(type to value) },
                        )
                    }
                    Box(modifier = Modifier.weight(1f)) {
                        val group = selectedGroup
                        if (group != null) {
                            DetailScreen(
                                type = group.first,
                                value = group.second,
                                vm = vm,
                                onBack = { onSelectGroup(null) },
                                onSongClick = onOpenPlayer,
                                onGoto = { type, value -> onSelectGroup(type to value) },
                            )
                        } else {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    "点击左侧文件夹 / 专辑 / 艺术家的分组标题\n在此查看详情",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                )
                            }
                        }
                    }
                }
            } else {
                LibraryScreen(
                    vm = vm,
                    onSongClick = onOpenPlayer,
                    onVideoClick = { url, title ->
                        navController.navigate("video?url=${Uri.encode(url)}&title=${Uri.encode(title)}")
                    },
                    onGroupClick = { type, value ->
                        navController.navigate("detail?type=${Uri.encode(type)}&value=${Uri.encode(value)}")
                    },
                )
            }
        }
        composable(
            "detail?type={type}&value={value}",
            arguments = listOf(
                navArgument("type") { type = NavType.StringType },
                navArgument("value") { type = NavType.StringType },
            ),
        ) { backStackEntry ->
            DetailScreen(
                type = backStackEntry.arguments?.getString("type") ?: "",
                value = backStackEntry.arguments?.getString("value") ?: "",
                vm = vm,
                onBack = { navController.popBackStack() },
                onSongClick = onOpenPlayer,
                onGoto = { type, value ->
                    navController.navigate("detail?type=${Uri.encode(type)}&value=${Uri.encode(value)}")
                },
            )
        }
        // 播放页不再是路由：已在 AppRoot 顶层做成覆盖层（见 AppRoot），这里没有 "player"
        composable(
            "video?url={url}&title={title}",
            arguments = listOf(
                navArgument("url") { type = NavType.StringType },
                navArgument("title") { type = NavType.StringType; defaultValue = "" },
            ),
        ) { backStackEntry ->
            VideoPlayerScreen(
                url = backStackEntry.arguments?.getString("url") ?: "",
                title = backStackEntry.arguments?.getString("title") ?: "",
                onBack = { navController.popBackStack() },
            )
        }
        composable("playlist") {
            PlaylistScreen(
                vm = vm,
                onSongClick = onOpenPlayer,
                // 歌单行「更多」弹窗里点歌手 / 专辑的「详情 ›」：跳详情路由
                onGoto = { type, value ->
                    navController.navigate("detail?type=${Uri.encode(type)}&value=${Uri.encode(value)}")
                },
            )
        }
        composable("search") {
            SearchScreen(
                vm = vm,
                onSongClick = onOpenPlayer,
                onGroupClick = { type, value ->
                    if (isWide) {
                        // 宽屏：详情在音乐库右栏展示，切回音乐库页
                        onSelectGroup(type to value)
                        navigateToTab(navController, "library")
                    } else {
                        navController.navigate("detail?type=${Uri.encode(type)}&value=${Uri.encode(value)}")
                    }
                },
            )
        }
        composable("settings") { SettingsScreen(vm = vm) }
    }
}
