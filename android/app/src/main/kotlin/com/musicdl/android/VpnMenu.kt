package com.musicdl.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

private enum class VpnMenuPage { MAIN, SERVERS }

/**
 * A shield button that opens a dropdown with the VPN controls: status, connect/disconnect, server
 * choice with ping, and a shortcut to the full VPN settings. A coloured dot on the shield shows the
 * state at a glance (green: connected, amber: connecting, red: error).
 */
@Composable
fun VpnMenuButton(onOpenSettings: () -> Unit, tint: Color, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val repository = remember(context) { VpnSubscriptionRepository(context) }
    val prefs = remember(context) { context.getSharedPreferences(MusicVpnService.PREFS, Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()
    var profiles by remember { mutableStateOf(emptyList<VpnProfile>()) }
    var selectedId by remember { mutableStateOf("") }
    var state by remember { mutableStateOf(prefs.getString(MusicVpnService.KEY_STATE, MusicVpnService.STATE_DISCONNECTED).orEmpty()) }
    var status by remember { mutableStateOf(prefs.getString(MusicVpnService.KEY_MESSAGE, "").orEmpty()) }
    val pings = remember { mutableStateMapOf<String, Long>() }
    var pinging by remember { mutableStateOf(false) }
    var autoSwitch by remember { mutableStateOf(prefs.getBoolean(MusicVpnService.KEY_AUTO_SWITCH, false)) }
    var expanded by remember { mutableStateOf(false) }
    var page by remember { mutableStateOf(VpnMenuPage.MAIN) }

    fun reload() {
        autoSwitch = prefs.getBoolean(MusicVpnService.KEY_AUTO_SWITCH, false)
        profiles = runCatching { repository.getProfiles() }.getOrDefault(emptyList())
        selectedId = (prefs.getString(MusicVpnService.KEY_SELECTED_PROFILE, null)
            ?: prefs.getString(MusicVpnService.KEY_ACTIVE_PROFILE, null)).orEmpty()
            .takeIf { id -> profiles.any { it.id == id } } ?: profiles.firstOrNull()?.id.orEmpty()
    }
    LaunchedEffect(Unit) { reload() }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == android.app.Activity.RESULT_OK && selectedId.isNotBlank()) MusicVpnService.start(context, selectedId)
        else status = "Разрешение Android на VPN не предоставлено"
    }
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                if (intent?.action == MusicVpnService.ACTION_STATE) {
                    state = intent.getStringExtra(MusicVpnService.EXTRA_STATE) ?: state
                    status = intent.getStringExtra(MusicVpnService.EXTRA_MESSAGE) ?: status
                }
            }
        }
        val filter = IntentFilter(MusicVpnService.ACTION_STATE)
        if (android.os.Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") context.registerReceiver(receiver, filter)
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }

    val connected = state == MusicVpnService.STATE_CONNECTED
    val connecting = state == MusicVpnService.STATE_CONNECTING
    val selected = profiles.firstOrNull { it.id == selectedId }
    val dot = when {
        connected -> Color(0xFF4CAF50)
        connecting -> Color(0xFFFFB300)
        state == MusicVpnService.STATE_ERROR -> MaterialTheme.colorScheme.error
        else -> null
    }

    fun connectOrDisconnect() {
        expanded = false
        if (connected || connecting) { MusicVpnService.stop(context); return }
        if (selectedId.isBlank()) { onOpenSettings(); return }
        val intent = VpnService.prepare(context)
        if (intent == null) MusicVpnService.start(context, selectedId) else permissionLauncher.launch(intent)
    }

    fun measure() {
        if (pinging || connected || profiles.isEmpty()) return
        pinging = true
        profiles.forEach { pings[it.id] = PING_MEASURING }
        scope.launch {
            ServerPing.pingAll(profiles) { id, ms -> pings[id] = ms }
            pinging = false
        }
    }

    Box(modifier) {
        IconButton(onClick = { reload(); page = VpnMenuPage.MAIN; expanded = true }) {
            Box {
                Icon(if (connected) Icons.Rounded.VpnLock else Icons.Rounded.Shield, "Меню VPN", tint = tint)
                dot?.let { Box(Modifier.align(Alignment.TopEnd).size(9.dp).clip(CircleShape).background(it)) }
            }
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            shape = RoundedCornerShape(22.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.widthIn(min = 280.dp, max = 330.dp),
        ) {
            when (page) {
                VpnMenuPage.MAIN -> {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            when { connected -> "VPN включён"; connecting -> "Подключаемся…"; else -> "VPN выключен" },
                            style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
                        )
                        val line = if (status.isNotBlank()) status else "Через VPN идёт только трафик XXW Music"
                        Text(line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    if (profiles.isEmpty()) {
                        MenuRow("Добавить подписку", Icons.Rounded.Add) { expanded = false; onOpenSettings() }
                    } else {
                        MenuRow(
                            if (connected || connecting) "Отключить VPN" else "Подключить VPN",
                            if (connected || connecting) Icons.Rounded.PowerSettingsNew else Icons.Rounded.Shield,
                            danger = connected || connecting, enabled = true,
                        ) { connectOrDisconnect() }
                        MenuRow("Сервер: ${selected?.name ?: "не выбран"}", Icons.Rounded.Dns, enabled = !connected && !connecting,
                            trailing = { Icon(Icons.Rounded.ChevronRight, null) }) { page = VpnMenuPage.SERVERS }
                        MenuRow(if (pinging) "Проверяем пинг…" else "Проверить пинг", Icons.Rounded.Speed,
                            enabled = !pinging && !connected && !connecting) { measure(); page = VpnMenuPage.SERVERS }
                    }
                    if (profiles.isNotEmpty()) {
                        MenuRow("Автопереключение серверов", Icons.Rounded.SwapHoriz,
                            trailing = { androidx.compose.material3.Switch(checked = autoSwitch, onCheckedChange = null) }) {
                            autoSwitch = !autoSwitch
                            prefs.edit().putBoolean(MusicVpnService.KEY_AUTO_SWITCH, autoSwitch).apply()
                        }
                    }
                    MenuRow("Настройки VPN", Icons.Rounded.Settings) { expanded = false; onOpenSettings() }
                }
                VpnMenuPage.SERVERS -> {
                    MenuRow("Серверы", Icons.Rounded.ChevronLeft, bold = true) { page = VpnMenuPage.MAIN }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    MenuRow(if (pinging) "Проверяем пинг…" else "Проверить пинг", Icons.Rounded.Speed,
                        enabled = !pinging && !connected) { measure() }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Column(Modifier.widthIn(max = 330.dp)) {
                        profiles.forEach { profile ->
                            val ping = pings[profile.id]
                            DropdownMenuItem(
                                leadingIcon = {
                                    Icon(if (profile.id == selectedId) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked, null,
                                        tint = if (profile.id == selectedId) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                                },
                                text = { Text(profile.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                trailingIcon = ping?.let { ms -> { val (label, color) = pingLabel(ms); Text(label.removePrefix("пинг не поддерживается ").ifBlank { "—" }, color = color, style = MaterialTheme.typography.labelMedium) } },
                                enabled = !connected && !connecting,
                                onClick = {
                                    selectedId = profile.id
                                    prefs.edit().putString(MusicVpnService.KEY_SELECTED_PROFILE, profile.id).apply()
                                    page = VpnMenuPage.MAIN
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MenuRow(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    enabled: Boolean = true,
    danger: Boolean = false,
    bold: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    val color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    DropdownMenuItem(
        text = { Text(title, color = color, fontWeight = if (bold || danger) FontWeight.Bold else FontWeight.SemiBold,
            maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = { Icon(icon, null, tint = if (danger) color else MaterialTheme.colorScheme.onSurfaceVariant) },
        trailingIcon = trailing,
        enabled = enabled,
        onClick = onClick,
    )
}
