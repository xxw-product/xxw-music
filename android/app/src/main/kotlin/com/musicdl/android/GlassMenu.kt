package com.musicdl.android

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.PersonRemove
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.QueuePlayNext
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.musicdl.core.model.SongInfo

private val MenuShape = RoundedCornerShape(26.dp)
private val Danger = Color(0xFFFF6B6B)

/** Panel that grows out of its anchor: scale, fade and a short blur that clears as it settles. */
@Composable
private fun MorphPanel(visible: Boolean, origin: TransformOrigin, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit): Boolean {
    val t by animateFloatAsState(if (visible) 1f else 0f, spring(dampingRatio = 0.74f, stiffness = 420f), label = "glassMenu")
    if (!visible && t < 0.01f) return false
    Column(
        modifier
            .graphicsLayer {
                val scale = 0.3f + 0.7f * t
                scaleX = scale; scaleY = scale
                alpha = (t * 1.8f).coerceIn(0f, 1f)
                transformOrigin = origin
                if (android.os.Build.VERSION.SDK_INT >= 31 && Appearance.uiAnimations) {
                    val radius = (1f - t).coerceIn(0f, 1f) * 14.dp.toPx()
                    renderEffect = if (radius > 0.5f) BlurEffect(radius, radius, TileMode.Decal) else null
                }
            }
            .shadow(24.dp, MenuShape, ambientColor = Color.Black, spotColor = Color.Black)
            .glassSurface(MenuShape, MaterialTheme.colorScheme.surfaceContainerHigh, true, opacity = 0.9f),
        content = content,
    )
    return true
}

private class BelowAnchor(private val gapPx: Int) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val margin = 12
        val x = (anchorBounds.right - popupContentSize.width).coerceIn(margin, (windowSize.width - popupContentSize.width - margin).coerceAtLeast(margin))
        var y = anchorBounds.bottom + gapPx
        if (y + popupContentSize.height > windowSize.height - margin) y = (anchorBounds.top - gapPx - popupContentSize.height).coerceAtLeast(margin)
        return IntOffset(x, y)
    }
}

/** A compact floating menu that opens from its anchor, with grouped rows. */
@Composable
internal fun GlassMenuPopup(expanded: Boolean, onDismiss: () -> Unit, width: Dp = 280.dp, content: @Composable ColumnScope.() -> Unit) {
    val gap = with(androidx.compose.ui.platform.LocalDensity.current) { 8.dp.roundToPx() }
    val shown = rememberMenuHold(expanded)
    if (!expanded && !shown) return
    Popup(popupPositionProvider = BelowAnchor(gap), onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
        MorphPanel(expanded, TransformOrigin(1f, 0f), Modifier.padding(12.dp).width(width)) {
            Column(Modifier.padding(vertical = 8.dp).verticalScroll(rememberScrollState()), content = content)
        }
    }
}

@Composable
private fun rememberMenuHold(expanded: Boolean): Boolean {
    // Keeps the popup in the tree while the closing animation plays.
    val t by animateFloatAsState(if (expanded) 1f else 0f, spring(dampingRatio = 0.74f, stiffness = 420f), label = "glassMenuHold")
    return expanded || t >= 0.01f
}

@Composable
internal fun GlassMenuItem(
    label: String,
    icon: ImageVector? = null,
    danger: Boolean = false,
    enabled: Boolean = true,
    trailing: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    val color = if (danger) Danger else MaterialTheme.colorScheme.onSurface
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (icon != null) Icon(icon, null, Modifier.size(20.dp), tint = (if (danger) Danger else MaterialTheme.colorScheme.onSurfaceVariant).copy(alpha = if (enabled) 1f else 0.4f))
        Text(label, Modifier.weight(1f), color = color.copy(alpha = if (enabled) 1f else 0.4f), fontWeight = FontWeight.Medium,
            style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        trailing?.invoke()
    }
}

@Composable
internal fun GlassMenuDivider() {
    Box(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 6.dp).height(1.dp).background(Color.White.copy(alpha = 0.12f)))
}

// ---- helpers that pick the style, so call sites stay one-liners ----

/** Drop-down menu: the glass popup when "Стеклянные меню" is on, the Material one otherwise. */
@Composable
internal fun AppMenu(expanded: Boolean, onDismiss: () -> Unit, modifier: Modifier = Modifier, width: Dp = 260.dp, content: @Composable ColumnScope.() -> Unit) {
    if (Appearance.glassMenuActive) GlassMenuPopup(expanded, onDismiss, width, content)
    else DropdownMenu(expanded, onDismiss, modifier, content = content)
}

@Composable
internal fun AppMenuItem(label: String, icon: ImageVector? = null, danger: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    if (Appearance.glassMenuActive) GlassMenuItem(label, icon, danger, enabled, onClick = onClick)
    else DropdownMenuItem(
        text = { Text(label, color = if (danger) MaterialTheme.colorScheme.error else Color.Unspecified) },
        leadingIcon = icon?.let { { Icon(it, null) } }, enabled = enabled, onClick = onClick,
    )
}

/** Track actions as a floating glass card instead of a bottom sheet. */
@Composable
internal fun TrackActionsGlass(
    song: SongInfo,
    isLiked: Boolean,
    isFollowed: Boolean,
    isCurrent: Boolean,
    save: SaveState?,
    downloaded: DownloadEntry?,
    onDismiss: () -> Unit,
    onPlay: () -> Unit,
    onPlayNext: () -> Unit,
    onAddToQueue: () -> Unit,
    onLike: () -> Unit,
    onDownload: () -> Unit,
    onDeleteDownload: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onFollow: () -> Unit,
    onLyrics: () -> Unit,
) {
    var open by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(Unit) { open = true }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().clickable(indication = null, interactionSource = androidx.compose.runtime.remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, onClick = onDismiss),
            contentAlignment = Alignment.Center) {
            MorphPanel(open, TransformOrigin(0.5f, 0.6f), Modifier.padding(24.dp).widthIn(max = 340.dp).fillMaxWidth()) {
                Column(Modifier.padding(vertical = 8.dp).verticalScroll(rememberScrollState())) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
                            CoverImage(song.coverUrl, Modifier.fillMaxSize()) { Icon(Icons.Rounded.MusicNote, null, tint = Silver) }
                        }
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text(song.songName ?: "Без названия", fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                listOfNotNull(song.singers, downloaded?.let { "скачано · ${DownloadStore.sizeLabel(it.totalBytes)}" }).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    GlassMenuDivider()
                    GlassMenuItem("Слушать", if (isCurrent) Icons.Rounded.PlaylistPlay else Icons.Rounded.PlayArrow, onClick = onPlay)
                    GlassMenuItem("Проигрывать следующим", Icons.Rounded.QueuePlayNext, onClick = onPlayNext)
                    GlassMenuItem("Добавить в очередь", Icons.Rounded.PlaylistPlay, onClick = onAddToQueue)
                    GlassMenuDivider()
                    GlassMenuItem(if (isLiked) "Убрать из избранного" else "В избранное", if (isLiked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, onClick = onLike)
                    GlassMenuItem("Добавить в плейлист", Icons.Rounded.PlaylistAdd, onClick = onAddToPlaylist)
                    GlassMenuItem(if (isFollowed) "Отписаться от исполнителя" else "Подписаться на исполнителя",
                        if (isFollowed) Icons.Rounded.PersonRemove else Icons.Rounded.PersonAdd, onClick = onFollow)
                    GlassMenuItem("Открыть текст", Icons.Rounded.Lyrics, onClick = onLyrics)
                    GlassMenuDivider()
                    when {
                        downloaded != null -> GlassMenuItem("Удалить скачанный трек", Icons.Rounded.DeleteOutline, danger = true, onClick = onDeleteDownload)
                        save == SaveState.RUNNING -> GlassMenuItem("Скачивается…", Icons.Rounded.Download, enabled = false, onClick = {})
                        save == SaveState.FAILED -> GlassMenuItem("Повторить скачивание", Icons.Rounded.Download, onClick = onDownload)
                        else -> GlassMenuItem("Скачать", Icons.Rounded.Download, onClick = onDownload)
                    }
                }
            }
        }
    }
}
