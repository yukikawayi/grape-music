package com.grape.player.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.grape.player.data.MediaItemLite as MediaItem
import com.grape.player.network.LibraryScanner

internal fun formatDuration(ms: Long): String {
    val totalSec = ms / 1000
    return "%d:%02d".format(totalSec / 60, totalSec % 60)
}

/**
 * 歌曲信息弹层（竖三点入口）：展示当前采用的元数据
 * （Using_：歌名 / 歌手 / 专辑 / 年份 / 音轨号·碟号 / 封面位置 / 网盘路径）。
 *
 * 内嵌快照（Embedded_）**不在这里展示**：那套值和 Using_ 一起铺开会把弹窗撑长，
 * 用户得先往下滑才能点到「编辑元数据」。想看或采用内嵌值，走编辑元数据页顶部的尖括号窗口。
 *
 * 歌名 / 歌手 / 专辑 / 封面位置 / 网盘路径均点击即复制；歌手与专辑可跳转详情页。
 * 「编辑元数据」按钮固定在弹窗最下方（在滚动区之外），字段再多也不用滑。
 * [onGoto] 收到 (type, value)，type 为 "artist" / "album"，由调用方接到导航。
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun SongInfoSheet(
    item: MediaItem,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onGoto: (String, String) -> Unit = { _, _ -> },
) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    fun copy(label: String, value: String) {
        clipboard.setText(AnnotatedString(value))
        android.widget.Toast.makeText(context, "已复制$label", android.widget.Toast.LENGTH_SHORT).show()
    }

    // 封面位置：本地落盘的显示真实路径，远程的提示未落盘
    val coverDisplay: String
    val coverCopy: String
    when {
        item.coverUrl.isBlank() -> {
            coverDisplay = "（未设置封面）"
            coverCopy = ""
        }
        item.coverUrl.startsWith("file:") -> {
            coverCopy = java.net.URLDecoder.decode(item.coverUrl.removePrefix("file://"), "UTF-8")
            coverDisplay = coverCopy
        }
        else -> {
            coverCopy = item.coverUrl
            coverDisplay = "尚未保存到本地（远程）：$coverCopy"
        }
    }
    // 网盘完整路径：从 /dav 直链反解出真实路径（URL 解码），失败则原样显示直链
    val davPath = remember(item.url) {
        LibraryScanner.pathKeyOf(item.url) ?: item.url
    }

    // 两套元数据的音轨号 / 碟号合并显示
    fun trackDisc(track: Int, disc: Int): String {
        val t = if (track > 0) "音轨 $track" else ""
        val d = if (disc > 0) "碟 $disc" else ""
        return listOf(t, d).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "（空）" }
    }
    val usingTrackDisc = trackDisc(item.trackNumber, item.discNumber)

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                // 手势条 / 导航条避让：「编辑元数据」按钮不再贴着屏幕底缘（手势导航的手机上
                // 原来只有固定 12dp，按钮会被系统手势条压住），底部按系统 inset 再留出安全距离
                .windowInsetsPadding(WindowInsets.navigationBars),
        ) {
            // 只有这块内容滚动（上限 360dp，够放封面 + 6 行信息，超长才出滚动条），
            // 下面的按钮放在滚动区外面 —— 它在任何情况下都贴在弹窗最下方，不用先往下滑。
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                // 封面 + 歌名
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(88.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (item.coverUrl.isBlank()) {
                            Icon(
                                Icons.Filled.MusicNote,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(32.dp),
                            )
                        } else {
                            coil.compose.AsyncImage(
                                model = item.coverUrl,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                    Spacer(Modifier.size(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            item.title.ifBlank { "（空）" },
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.clickable { copy("歌名", item.title) },
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            formatDuration(item.durationMs) + " · " +
                                android.text.format.Formatter.formatShortFileSize(context, item.sizeBytes),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                // 信息行：值不可点，右侧统一的复制按钮；歌手 / 专辑额外带「详情 ›」跳转
                InfoRow(
                    label = "歌手",
                    value = item.artist.ifBlank { "（空）" },
                    copyEnabled = item.artist.isNotBlank(),
                    jump = item.artist.isNotBlank(),
                    onCopy = { copy("歌手名", item.artist) },
                    onJump = { onGoto("artist", item.artist) },
                )
                InfoRow(
                    label = "专辑",
                    value = item.album.ifBlank { "（空）" },
                    copyEnabled = item.album.isNotBlank(),
                    jump = item.album.isNotBlank(),
                    onCopy = { copy("专辑名", item.album) },
                    onJump = { onGoto("album", item.album) },
                )
                InfoRow(
                    label = "年份",
                    value = if (item.year > 0) item.year.toString() else "（空）",
                    copyEnabled = item.year > 0,
                    onCopy = { copy("年份", item.year.toString()) },
                )
                InfoRow(
                    label = "音轨 / 碟号",
                    value = usingTrackDisc,
                    copyEnabled = item.trackNumber > 0 || item.discNumber > 0,
                    onCopy = { copy("音轨/碟号", usingTrackDisc) },
                )
                InfoRow(
                    label = "封面位置",
                    value = coverDisplay,
                    copyEnabled = coverCopy.isNotBlank(),
                    onCopy = { copy("封面位置", coverCopy) },
                )
                InfoRow(
                    label = "网盘路径",
                    value = davPath,
                    copyEnabled = davPath.isNotBlank(),
                    onCopy = { copy("网盘路径", davPath) },
                )
            }
            Spacer(Modifier.height(8.dp))
            // 「编辑元数据」固定在弹窗底部（滚动容器之外），内容再长也不影响它可见
            Button(
                onClick = onEdit,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("编辑元数据")
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}


/**
 * 信息行：label + 值 + 右侧操作按钮。
 *
 * 三处细节都是为了「所有行看起来一样高、间距一致」：
 *  - 行本身 `heightIn(min = 40.dp)`，不管右边有没有按钮都占同样的高度；
 *  - 「详情 ›」用带 padding 的 Text 而不是 TextButton —— TextButton 自带 40dp 最小高度，
 *    会把带跳转的行撑得比其它行高，之前歌手/专辑两行间距明显大于下面几项就是这个原因；
 *  - 复制按钮用 32dp 的 IconButton + 16dp 图标，不引入 Material 默认的 48dp 触摸区高度。
 *
 * 值文本不再可点（以前点一下就复制，很容易误触），复制统一走右侧按钮；
 * 值为空时按钮置灰不可用，避免复制出一串空字符串。
 */
@Composable
private fun InfoRow(
    label: String,
    value: String,
    copyEnabled: Boolean = true,
    jump: Boolean = false,
    onCopy: () -> Unit,
    onJump: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(width = 72.dp, height = 20.dp),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (jump && onJump != null) {
            Text(
                "详情 ›",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clickable(onClick = onJump)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            )
        }
        IconButton(
            onClick = onCopy,
            enabled = copyEnabled,
            modifier = Modifier.size(32.dp),
        ) {
            Icon(
                Icons.Filled.ContentCopy,
                contentDescription = "复制$label",
                tint = if (copyEnabled) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
                modifier = Modifier.size(16.dp),
            )
        }
    }
}
