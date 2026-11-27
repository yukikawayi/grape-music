package com.grape.player.ui.library

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * 歌曲多选操作栏（音乐库「全部 / 文件夹 / 视频」与专辑 / 艺术家 / 文件夹详情共用一份）。
 *
 * 顺序固定：
 *   全选 · 取消 ·…… 已选 N 首 ·…… 加入歌单 · 编辑元数据
 *
 * 样式沿用音乐库「全部」视图原来的多选栏：不加底色、用 Material3 默认 TextButton、
 * 整条 8dp 内边距 —— 就是那个朴素的两端对齐栏，只是最左边多了「全选」。
 *
 * 几个细节：
 *  - [countText] 传 null 时中间只留弹性空白：详情页的「已选 N / M 首」在标题第二行，
 *    栏里不重复显示计数；
 *  - 计数用 weight(1f) + Ellipsis：窄屏优先保按钮完整，把「已选 12 首」截断，
 *    而不是把「编辑元数据」挤出屏幕外；
 *  - 全选按钮在「已全选」时变「全不选」：否则全选之后想清空只能整段取消重来。
 *
 * 歌单详情（PlaylistScreen）不用这里：那边要多一个「移除」动作（变成 5 个动作），
 * 挤不下，所以保留自己的实现。
 */
@Composable
internal fun MultiSelectBar(
    allSelected: Boolean,
    hasSelection: Boolean,
    onToggleSelectAll: () -> Unit,
    onCancel: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onEditMeta: () -> Unit,
    modifier: Modifier = Modifier,
    countText: String? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 全选当前视图下的全部歌曲；已经全选时变成「全不选」
        TextButton(onClick = onToggleSelectAll) {
            Text(if (allSelected) "全不选" else "全选")
        }
        TextButton(onClick = onCancel) { Text("取消") }
        if (countText != null) {
            Text(
                text = countText,
                // 计数比按钮小一号：多了「全选」这一个按钮后，411dp 屏上「已选 3999 首」
                // 用 16sp 会被挤成「已选 3999…」，14sp 刚好放得下；360dp 屏仍然走省略号兜底。
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        } else {
            Spacer(Modifier.weight(1f))
        }
        TextButton(enabled = hasSelection, onClick = onAddToPlaylist) { Text("加入歌单") }
        TextButton(enabled = hasSelection, onClick = onEditMeta) { Text("编辑元数据") }
    }
}
