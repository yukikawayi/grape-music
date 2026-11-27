package com.grape.player.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.grape.player.ui.AppViewModel
import kotlinx.coroutines.launch

/**
 * 「加入歌单」弹层 —— 多选后的目标选择。
 * 每个歌单显示已有曲目数；底部可直接新建歌单并把当前选中歌曲一起放进去。
 *
 * 从 LibraryScreen 抽出来共用：音乐库多选栏与专辑 / 艺术家详情页多选栏都要用，
 * 保持 internal（同包可见），不要重新写一份私有实现。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AddToPlaylistSheet(
    vm: AppViewModel,
    urls: List<String>,
    onDone: () -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val playlists by vm.playlists.collectAsState(initial = emptyList())
    val counts by vm.playlistCounts.collectAsState(initial = emptyMap())
    var newName by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
        ) {
            Text("加入歌单（${urls.size} 首）", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))

            if (playlists.isEmpty()) {
                Text(
                    "还没有歌单，先新建一个",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                playlists.forEach { playlist ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !busy) {
                                busy = true
                                scope.launch {
                                    vm.repo.addToPlaylist(playlist.id, urls)
                                    busy = false
                                    onDone()
                                }
                            }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Filled.QueueMusic,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(22.dp),
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(playlist.name, modifier = Modifier.weight(1f), maxLines = 1)
                        Text(
                            "${counts[playlist.id] ?: 0} 首",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Divider()
            Spacer(Modifier.height(8.dp))

            OutlinedTextField(
                value = newName,
                onValueChange = { newName = it },
                label = { Text("新建歌单并加入") },
                placeholder = { Text("输入歌单名") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Button(
                enabled = newName.isNotBlank() && !busy,
                onClick = {
                    val name = newName.trim()
                    if (name.isEmpty()) return@Button
                    busy = true
                    scope.launch {
                        val id = vm.repo.createPlaylist(name)
                        vm.repo.addToPlaylist(id, urls)
                        busy = false
                        onDone()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("新建并加入") }
        }
    }
}
