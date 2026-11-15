package fr.florian.seedbox

import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

// ======================= Torrents (qBittorrent) =======================

private enum class TorrentFilter(val label: String, val keep: (Torrent) -> Boolean) {
    ALL("Tous", { true }),
    ACTIVE("En cours", { !it.finished }),
    DONE("Terminés", { it.finished }),
    ERRORS("Erreurs", { it.errored }),
}

@Composable
fun TorrentsScreen(cfg: ServiceConfig, onOpenSettings: () -> Unit) {
    if (!cfg.configured) return NotConfigured("qBittorrent", onOpenSettings)
    val client = remember(cfg) { QBittorrent(cfg) }
    val loader = rememberLoader(client, autoRefreshMs = 5_000) { client.torrents() to client.transfer() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var filter by remember { mutableStateOf(TorrentFilter.ALL) }
    var toDelete by remember { mutableStateOf<Torrent?>(null) }

    fun act(block: () -> Unit) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) { block() }
                loader.refresh()
            } catch (e: Exception) {
                Toast.makeText(context, e.friendly(), Toast.LENGTH_LONG).show()
            }
        }
    }

    LoadedList(loader) { (torrents, transfer) ->
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("↓ ${speed(transfer.dlSpeed)}   ↑ ${speed(transfer.upSpeed)}", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                Text("${torrents.size} torrents", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TorrentFilter.entries.forEach { f ->
                    val n = torrents.count(f.keep)
                    if (f == TorrentFilter.ERRORS && n == 0) return@forEach
                    FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text("${f.label} ($n)") })
                }
            }
        }
        val shown = torrents.filter(filter.keep)
        if (shown.isEmpty()) item { Empty("Aucun torrent") }
        items(shown, key = { it.hash }) { t ->
            TorrentCard(
                t,
                onToggle = { act { if (t.paused) client.resume(t.hash) else client.pause(t.hash) } },
                onDelete = { toDelete = t },
            )
        }
    }

    toDelete?.let { t ->
        var withFiles by remember(t) { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Supprimer le torrent ?") },
            text = {
                Column {
                    Text(t.name, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = withFiles, onCheckedChange = { withFiles = it })
                        Text("Supprimer aussi les fichiers")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val files = withFiles
                    toDelete = null
                    act { client.delete(t.hash, files) }
                }) { Text("Supprimer") }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Annuler") } },
        )
    }
}

@Composable
private fun TorrentCard(t: Torrent, onToggle: () -> Unit, onDelete: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 12.dp, top = 12.dp, end = 4.dp, bottom = 4.dp)) {
            Text(
                t.name,
                Modifier.padding(end = 8.dp),
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { t.progress.toFloat() },
                modifier = Modifier.fillMaxWidth().padding(end = 8.dp),
                color = if (t.errored) MaterialTheme.colorScheme.error else ProgressIndicatorDefaults.linearColor,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
                    val color = if (t.errored) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                    Text(
                        "${t.stateLabel} · ${percent(t.progress)} · ${bytes(t.size)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = color,
                    )
                    val parts = buildList {
                        if (t.dlSpeed > 0) add("↓ ${speed(t.dlSpeed)}")
                        if (t.upSpeed > 0) add("↑ ${speed(t.upSpeed)}")
                        add("ratio %.2f".format(Locale.FRANCE, t.ratio))
                        if (!t.finished && !t.paused) duration(t.eta)?.let { add("reste $it") }
                        if (t.category.isNotBlank()) add(t.category)
                    }
                    Text(parts.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = onToggle) {
                    if (t.paused) Icon(Icons.Filled.PlayArrow, "Reprendre") else Icon(AppIcons.Pause, "Mettre en pause")
                }
                IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, "Supprimer") }
            }
        }
    }
}

// ======================= Imports (files d'attente Radarr + Sonarr) =======================

private class Gathered<T>(val items: List<T>, val errors: List<String>)

/** Interroge chaque service ; l'échec de l'un n'empêche pas d'afficher les autres */
private suspend fun <T> gather(arrs: List<Arr>, fetch: (Arr) -> List<T>): Gathered<T> = coroutineScope {
    val results = arrs.map { arr -> async(Dispatchers.IO) { arr to runCatching { fetch(arr) } } }.awaitAll()
    Gathered(
        results.flatMap { it.second.getOrDefault(emptyList()) },
        results.mapNotNull { (arr, r) -> r.exceptionOrNull()?.let { "${arr.service.label} : ${it.friendly()}" } },
    )
}

@Composable
private fun rememberArrs(configs: Map<Service, ServiceConfig>, vararg services: Service): List<Arr> =
    remember(configs) { services.filter { configs[it]?.configured == true }.map { Arr(it, configs.getValue(it)) } }

@Composable
fun QueueScreen(configs: Map<Service, ServiceConfig>, onOpenSettings: () -> Unit) {
    val arrs = rememberArrs(configs, Service.RADARR, Service.SONARR)
    if (arrs.isEmpty()) return NotConfigured("Ni Radarr ni Sonarr", onOpenSettings)
    val loader = rememberLoader(arrs, autoRefreshMs = 15_000) { gather(arrs) { it.queue() } }
    LoadedList(loader) { g ->
        g.errors.forEach { item { ErrorCard(it) } }
        if (g.items.isEmpty()) item { Empty("Rien en cours de téléchargement ou d'import") }
        items(g.items) { QueueCard(it) }
    }
}

@Composable
private fun QueueCard(q: QueueItem) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(q.title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                ServiceBadge(q.service)
            }
            q.detail?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(progress = { q.progress.toFloat() }, modifier = Modifier.fillMaxWidth())
            val line = listOfNotNull(q.state, percent(q.progress), bytes(q.size), q.timeLeft?.let { "reste $it" })
            Text(line.joinToString(" · "), Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            q.problem?.let {
                Text(it, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun ServiceBadge(s: Service) {
    Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.secondaryContainer) {
        Text(s.label, Modifier.padding(horizontal = 6.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall)
    }
}

// ======================= Calendrier =======================

private val DAY = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.FRENCH)

private fun dayLabel(d: LocalDate): String {
    val today = LocalDate.now()
    return when (d) {
        today -> "Aujourd'hui"
        today.plusDays(1) -> "Demain"
        today.minusDays(1) -> "Hier"
        else -> d.format(DAY).replaceFirstChar { it.titlecase(Locale.FRENCH) }
    }
}

@Composable
fun CalendarScreen(configs: Map<Service, ServiceConfig>, onOpenSettings: () -> Unit) {
    val arrs = rememberArrs(configs, Service.SONARR, Service.RADARR)
    if (arrs.isEmpty()) return NotConfigured("Ni Radarr ni Sonarr", onOpenSettings)
    val loader = rememberLoader(arrs) {
        val start = LocalDate.now().minusDays(1)
        val end = LocalDate.now().plusDays(30)
        gather(arrs) { it.calendar(start, end) }
    }
    LoadedList(loader) { g ->
        g.errors.forEach { item { ErrorCard(it) } }
        if (g.items.isEmpty()) item { Empty("Aucune sortie prévue dans les 30 prochains jours") }
        g.items.sortedWith(compareBy({ it.date }, { it.title })).groupBy { it.date }.forEach { (date, list) ->
            item(key = date.toString()) {
                Text(
                    dayLabel(date),
                    Modifier.padding(top = 8.dp),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            items(list) { c ->
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(c.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(c.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (c.hasFile) Icon(Icons.Filled.CheckCircle, "Déjà téléchargé", Modifier.padding(horizontal = 8.dp), tint = MaterialTheme.colorScheme.primary)
                        ServiceBadge(c.service)
                    }
                }
            }
        }
    }
}

// ======================= État (santé, disques, indexeurs) =======================

private class ServiceStatus(
    val service: Service,
    val version: String? = null,
    val error: String? = null,
    val health: List<HealthItem> = emptyList(),
    val lines: List<String> = emptyList(),
    val disks: List<Disk> = emptyList(),
)

private fun statusOf(service: Service, cfg: ServiceConfig): ServiceStatus = try {
    if (service == Service.QBIT) {
        val qb = QBittorrent(cfg)
        val v = qb.version()
        val s = qb.serverState()
        ServiceStatus(
            service, v,
            lines = listOfNotNull(
                "↓ ${speed(s.dlSpeed)}   ↑ ${speed(s.upSpeed)}",
                s.freeSpace?.let { "Espace libre : ${bytes(it)}" },
                s.globalRatio?.let { "Ratio global : $it" },
            ),
        )
    } else {
        val arr = Arr(service, cfg)
        val v = arr.version()
        val health = arr.health()
        if (service == Service.PROWLARR) {
            val idx = arr.indexers()
            val failing = idx.filter { it.failing }
            ServiceStatus(
                service, v, health = health,
                lines = listOf("${idx.count { it.enabled }} indexeurs actifs sur ${idx.size}") +
                    failing.map { "En échec : ${it.name}" },
            )
        } else {
            ServiceStatus(service, v, health = health, disks = arr.diskSpace())
        }
    }
} catch (e: Exception) {
    ServiceStatus(service, error = e.friendly())
}

@Composable
fun StatusScreen(configs: Map<Service, ServiceConfig>, onOpenSettings: () -> Unit) {
    val configured = remember(configs) { configs.filterValues { it.configured } }
    if (configured.isEmpty()) return NotConfigured("Aucun service", onOpenSettings)
    val loader = rememberLoader(configured) {
        coroutineScope { configured.map { (s, c) -> async(Dispatchers.IO) { statusOf(s, c) } }.awaitAll() }
    }
    LoadedList(loader) { statuses ->
        // Radarr et Sonarr voient souvent les mêmes disques : un seul bloc, sans doublons
        val disks = statuses.flatMap { it.disks }.distinctBy { it.path }
        if (disks.isNotEmpty()) item { DisksCard(disks) }
        items(statuses, key = { it.service.name }) { StatusCard(it) }
    }
}

@Composable
private fun DisksCard(disks: List<Disk>) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(AppIcons.Storage, null, Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Disques", style = MaterialTheme.typography.titleSmall)
            }
            disks.forEach { d ->
                val used = if (d.total > 0) 1f - d.free.toFloat() / d.total else 0f
                Column {
                    Text(d.path, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    LinearProgressIndicator(
                        progress = { used },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        color = if (used > 0.9f) MaterialTheme.colorScheme.error else ProgressIndicatorDefaults.linearColor,
                    )
                    Text(
                        "${bytes(d.free)} libres sur ${bytes(d.total)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusCard(s: ServiceStatus) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val ok = s.error == null && s.health.none { it.isError }
                Icon(
                    if (ok) Icons.Filled.CheckCircle else Icons.Filled.Warning,
                    null,
                    Modifier.size(20.dp),
                    tint = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.width(8.dp))
                Text(s.service.label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                s.version?.let { Text("v${it.removePrefix("v")}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            s.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            s.lines.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
            if (s.error == null && s.health.isEmpty() && s.service != Service.QBIT) {
                Text("Aucun problème signalé", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            s.health.forEach { h ->
                Text(
                    "• ${h.message}",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (h.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
