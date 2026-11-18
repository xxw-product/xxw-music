package com.musicdl.android

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.musicdl.core.model.SongInfo

@Composable
internal fun PlaylistEntry(state: MusicUiState, onOpen: () -> Unit) {
    Surface(onClick = onOpen, color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.LibraryMusic, null, Modifier.size(32.dp))
            Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                Text("Плейлисты", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("${quantity(state.playlists.size, "коллекция", "коллекции", "коллекций")}", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Rounded.ChevronRight, "Открыть плейлисты")
        }
    }
}

/** Settings are part of the library now: one row above playlists instead of a sixth tab. */
@Composable
internal fun SettingsEntry(onOpen: () -> Unit) {
    Surface(onClick = onOpen, color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Settings, null, Modifier.size(28.dp))
            Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                Text("Настройки", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("Источники, скачивание, перенос и волна", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Rounded.ChevronRight, "Открыть настройки")
        }
    }
}

@Composable
internal fun PlaylistLibrary(state: MusicUiState, model: SearchViewModel, onClose: () -> Unit) {
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    val playlist = state.playlists.firstOrNull { it.id == selected }
    var nameDialog by remember { mutableStateOf(false) }
    var linkDialog by remember { mutableStateOf(false) }
    var link by rememberSaveable { mutableStateOf("") }
    var downloadAfterImport by rememberSaveable { mutableStateOf(true) }
    var rename by remember { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    var delete by remember { mutableStateOf(false) }
    var chooseTracks by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BackHandler(playlist != null) { selected = null }
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { if (playlist != null) selected = null else onClose() }) {
                        Icon(Icons.Rounded.ArrowBack, "Назад")
                    }
                    Text(playlist?.name ?: "Плейлисты", Modifier.weight(1f), maxLines = 1,
                        overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.headlineSmall)
                    if (playlist == null) {
                        IconButton(onClick = { link = ""; linkDialog = true }) {
                            Icon(Icons.Rounded.Link, "Плейлист по ссылке")
                        }
                    }
                    IconButton(onClick = { rename = playlist != null; name = playlist?.name.orEmpty(); nameDialog = true },
                        enabled = playlist?.imported != true) {
                        Icon(if (playlist == null) Icons.Rounded.Add else Icons.Rounded.Edit, if (playlist == null) "Создать плейлист" else "Переименовать")
                    }
                }
                if (playlist == null) {
                    if (state.playlists.isEmpty()) {
                        Column(Modifier.fillMaxWidth().padding(vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Rounded.LibraryMusic, null, Modifier.size(48.dp))
                            Text("Своя музыка, свой порядок", Modifier.padding(top = 16.dp), style = MaterialTheme.typography.titleLarge)
                            Text("Создайте плейлист и добавьте любимые треки", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 20.dp)) {
                        itemsIndexed(state.playlists, key = { _, it -> it.id }) { _, item ->
                            Surface(onClick = { selected = item.id }, shape = RoundedCornerShape(16.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.size(64.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
                                        CoverImage(item.tracks.firstOrNull()?.coverUrl, Modifier.fillMaxSize()) { Icon(Icons.Rounded.QueueMusic, null) }
                                    }
                                    Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                                        Text(item.name, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                                        Text("${trackCount(item.tracks.size)}${if (item.imported) "" else ""}", style = MaterialTheme.typography.bodySmall)
                                    }
                                    Icon(Icons.Rounded.ChevronRight, null)
                                }
                            }
                        }
                    }
                } else {
                    Text(trackCount(playlist.tracks.size),
                        Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { model.playAll(playlist.tracks) }, enabled = playlist.tracks.isNotEmpty()) {
                            Icon(Icons.Rounded.PlayArrow, null); Text("Слушать")
                        }
                        IconButton(onClick = { playlist.tracks.forEach { track -> model.save(track) } },
                            enabled = playlist.tracks.isNotEmpty()) { Icon(Icons.Rounded.Download, "Скачать плейлист") }
                        if (!playlist.imported) IconButton(onClick = { chooseTracks = true }) { Icon(Icons.Rounded.Add, "Добавить треки") }
                        IconButton(onClick = { model.copyPlaylist(playlist.id) }) { Icon(Icons.Rounded.ContentCopy, "Создать копию") }
                        IconButton(onClick = { delete = true }) { Icon(Icons.Rounded.DeleteOutline, "Удалить плейлист") }
                    }
                    if (playlist.tracks.isEmpty()) Text("Добавьте треки кнопкой + или через меню трека «В плейлист».", Modifier.padding(vertical = 32.dp))
                    LazyColumn(contentPadding = PaddingValues(vertical = 12.dp)) {
                        itemsIndexed(playlist.tracks, key = { index, it -> "$index:${it.key()}" }) { index, song ->
                            var menu by remember { mutableStateOf(false) }
                            Row(Modifier.fillMaxWidth().clickable { model.playFromPlaylist(playlist.id, index) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                CoverImage(song.coverUrl, Modifier.size(48.dp).clip(RoundedCornerShape(8.dp))) { Icon(Icons.Rounded.MusicNote, null) }
                                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                    Text(song.songName.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(song.singers.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                if (!playlist.imported) Box {
                                    IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "Действия с треком") }
                                    DropdownMenu(menu, onDismissRequest = { menu = false }) {
                                        DropdownMenuItem(text = { Text("Выше") }, enabled = index > 0,
                                            onClick = { model.moveInPlaylist(playlist.id, song.key(), -1); menu = false })
                                        DropdownMenuItem(text = { Text("Ниже") }, enabled = index < playlist.tracks.lastIndex,
                                            onClick = { model.moveInPlaylist(playlist.id, song.key(), 1); menu = false })
                                        DropdownMenuItem(text = { Text("Убрать из плейлиста") },
                                            onClick = { model.removeFromPlaylist(playlist.id, song.key()); menu = false })
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        if (chooseTracks && playlist != null) {
            val available = (state.likes + state.results + state.wave).distinctBy { it.key() }
            AlertDialog(onDismissRequest = { chooseTracks = false }, title = { Text("Добавить треки") }, text = {
                if (available.isEmpty()) Text("Сначала найдите музыку в поиске") else LazyColumn(Modifier.heightIn(max = 400.dp)) {
                    itemsIndexed(available) { _, track ->
                        val added = playlist.tracks.any { it.key() == track.key() }
                        TextButton(onClick = { model.addToPlaylist(playlist.id, track) }, enabled = !added) {
                            Text((if (added) "✓ " else "+ ") + track.songName.orEmpty() + " · " + track.singers.orEmpty())
                        }
                    }
                }
            }, confirmButton = { TextButton(onClick = { chooseTracks = false }) { Text("Готово") } })
        }
        if (linkDialog) AlertDialog(
            onDismissRequest = { if (!state.isImporting) linkDialog = false },
            title = { Text("Плейлист по ссылке") },
            text = {
                Column {
                    OutlinedTextField(link, { link = it.take(500) }, singleLine = true,
                        label = { Text("Ссылка на плейлист") }, enabled = !state.isImporting)
                    Text("Яндекс: публичные ссылки без токена. Spotify: перенос по доступным метаданным. Файлы найдём автоматически.",
                        Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = downloadAfterImport, enabled = !state.isImporting,
                            onCheckedChange = { downloadAfterImport = it })
                        Text("Сразу скачать доступные треки", style = MaterialTheme.typography.bodySmall)
                    }
                    state.linkImportResult?.let { Text(it, Modifier.padding(top = 8.dp)) }
                    if (state.isImporting) {
                        Text(state.importProgress, Modifier.padding(top = 12.dp),
                            style = MaterialTheme.typography.bodySmall)
                        LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = link.isNotBlank() && !state.isImporting, onClick = {
                    keyboard?.hide()
                    model.importPlaylistLink(link, download = downloadAfterImport)
                }) { Text(if (downloadAfterImport) "Перенести и скачать" else "Перенести") }
            },
            dismissButton = { TextButton(enabled = !state.isImporting, onClick = { linkDialog = false }) { Text("Закрыть") } },
        )
        if (nameDialog) AlertDialog(onDismissRequest = { nameDialog = false }, title = { Text(if (rename) "Название плейлиста" else "Новый плейлист") },
            text = { OutlinedTextField(name, onValueChange = { name = it.take(100) }, singleLine = true, label = { Text("Название") }) },
            confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = {
                keyboard?.hide()
                if (rename && playlist != null) model.renamePlaylist(playlist.id, name) else model.createPlaylist(name)
                nameDialog = false
            }) { Text("Сохранить") } }, dismissButton = { TextButton(onClick = { nameDialog = false }) { Text("Отмена") } })
        if (delete && playlist != null) AlertDialog(onDismissRequest = { delete = false }, title = { Text("Удалить плейлист?") },
            text = { Text("«${playlist.name}» исчезнет из приложения. Скачанные файлы сохранятся.") },
            confirmButton = { TextButton(onClick = { model.deletePlaylist(playlist.id); selected = null; delete = false }) { Text("Удалить") } },
            dismissButton = { TextButton(onClick = { delete = false }) { Text("Отмена") } })
    }
}

@Composable
internal fun PlaylistPicker(playlists: List<MusicPlaylist>, song: SongInfo, onCreate: (String) -> Unit, onAdd: (String) -> Unit, onClose: () -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onClose, title = { Text("В плейлист") }, text = {
        Column {
            Text(song.songName.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis)
            LazyColumn(Modifier.heightIn(max = 280.dp).padding(vertical = 12.dp)) {
                itemsIndexed(playlists.filterNot { it.imported }) { _, playlist ->
                    TextButton(onClick = { onAdd(playlist.id) }, modifier = Modifier.fillMaxWidth()) { Text(playlist.name) }
                }
            }
            OutlinedTextField(name, onValueChange = { name = it.take(100) }, singleLine = true, label = { Text("Новый плейлист") })
            TextButton(enabled = name.isNotBlank(), onClick = { onCreate(name); name = "" }) { Text("Создать") }
        }
    }, confirmButton = { TextButton(onClick = onClose) { Text("Закрыть") } })
}
