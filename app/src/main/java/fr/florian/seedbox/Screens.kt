package fr.florian.seedbox

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
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
import java.time.temporal.ChronoUnit
import java.util.Locale

// ======================= Torrents (qBittorrent) =======================

private enum class TorrentFilter(val label: String, val keep: (Torrent) -> Boolean) {
    ALL("Tous", { true }),
    ACTIVE("En cours", { !it.finished }),
    DONE("Terminés", { it.finished }),
    ERRORS("Erreurs", { it.errored }),
}

@Composable
fun TorrentsScreen(cfg: ServiceConfig, onOpenSettings: () -> Unit, onOpen: (String) -> Unit) {
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

    // Marge du bas : le bouton « + » ne cache pas la dernière carte
    LoadedList(loader, bottomSpace = 88.dp) { (torrents, transfer) ->
        item { SpeedSummary(transfer.dlSpeed, transfer.upSpeed) }
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
        if (shown.isEmpty()) item {
            if (filter == TorrentFilter.ALL) Empty("Aucun torrent", AppIcons.Download, "Appuie sur + pour en ajouter un")
            else Empty("Aucun torrent dans ce filtre", AppIcons.Download)
        }
        items(shown, key = { it.hash }) { t ->
            TorrentCard(
                t,
                onClick = { onOpen(t.hash) },
                onToggle = { act { if (t.paused) client.resume(t.hash) else client.pause(t.hash) } },
                onDelete = { toDelete = t },
            )
        }
    }

    toDelete?.let { t ->
        DeleteTorrentDialog(
            t,
            onConfirm = { withFiles ->
                toDelete = null
                act { client.delete(t.hash, withFiles) }
            },
            onDismiss = { toDelete = null },
        )
    }
}

/** Vitesses globales, avec unité, mises en avant seulement quand ça transfère */
@Composable
private fun SpeedSummary(dl: Long, up: Long) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SpeedValue(AppIcons.ArrowDown, "Réception", dl)
        SpeedValue(AppIcons.ArrowUp, "Envoi", up)
    }
}

@Composable
private fun SpeedValue(icon: ImageVector, label: String, value: Long) {
    val active = value > 0
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            icon, label, Modifier.size(20.dp),
            tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(4.dp))
        Text(
            speed(value),
            style = MaterialTheme.typography.titleMedium,
            color = if (active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** État du torrent en pastille colorée : erreur, en pause, en cours, terminé */
@Composable
private fun StatePill(t: Torrent) {
    val c = MaterialTheme.colorScheme
    val (bg, fg) = when {
        t.errored -> c.errorContainer to c.onErrorContainer
        t.paused -> c.surfaceVariant to c.onSurfaceVariant
        !t.finished -> c.primaryContainer to c.onPrimaryContainer
        else -> c.secondaryContainer to c.onSecondaryContainer
    }
    Pill(t.stateLabel, bg, fg)
}

/**
 * Carte compacte : la barre n'apparaît que tant que le torrent n'est pas terminé.
 * Appui long : menu (pause / suppression) ; la suppression est aussi dans le détail.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TorrentCard(t: Torrent, onClick: () -> Unit, onToggle: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Box {
            Row(
                Modifier
                    .fillMaxWidth()
                    .combinedClickable(
                        onClick = onClick,
                        onLongClick = { menu = true }, // vibration gérée par combinedClickable
                    )
                    .padding(start = 16.dp, top = 12.dp, end = 4.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(t.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (!t.finished || t.errored) {
                        Bar(
                            t.progress.toFloat(),
                            color = if (t.errored) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatePill(t)
                        val parts = buildList {
                            if (!t.finished) add(percent(t.progress))
                            add(bytes(t.size))
                            if (t.dlSpeed > 0) add("↓ ${speed(t.dlSpeed)}")
                            if (t.upSpeed > 0) add("↑ ${speed(t.upSpeed)}")
                            if (!t.finished && !t.paused) duration(t.eta)?.let { add("reste $it") }
                            if (t.finished) add("ratio %.2f".format(Locale.FRANCE, t.ratio))
                        }
                        Text(
                            parts.joinToString(" · "),
                            Modifier.weight(1f, fill = false),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (t.category.isNotBlank()) OutlinedPill(t.category)
                    }
                }
                IconButton(onClick = onToggle) {
                    if (t.paused) Icon(Icons.Filled.PlayArrow, "Reprendre") else Icon(AppIcons.Pause, "Mettre en pause")
                }
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text(if (t.paused) "Reprendre" else "Mettre en pause") },
                    leadingIcon = { Icon(if (t.paused) Icons.Filled.PlayArrow else AppIcons.Pause, null) },
                    onClick = { menu = false; onToggle() },
                )
                DropdownMenuItem(
                    text = { Text("Supprimer…") },
                    leadingIcon = { Icon(Icons.Filled.Delete, null) },
                    onClick = { menu = false; onDelete() },
                )
            }
        }
    }
}

// ======================= Imports (files d'attente Radarr + Sonarr) =======================

internal class Gathered<T>(val items: List<T>, val errors: List<String>)

/** Interroge chaque service ; l'échec de l'un n'empêche pas d'afficher les autres */
internal suspend fun <T> gather(arrs: List<Arr>, fetch: (Arr) -> List<T>): Gathered<T> = coroutineScope {
    val results = arrs.map { arr -> async(Dispatchers.IO) { arr to runCatching { fetch(arr) } } }.awaitAll()
    Gathered(
        results.flatMap { it.second.getOrDefault(emptyList()) },
        results.mapNotNull { (arr, r) -> r.exceptionOrNull()?.let { "${arr.service.label} : ${it.friendly()}" } },
    )
}

@Composable
internal fun rememberArrs(configs: Map<Service, ServiceConfig>, vararg services: Service): List<Arr> =
    remember(configs) { services.filter { configs[it]?.configured == true }.map { Arr(it, configs.getValue(it)) } }

@Composable
fun QueueScreen(configs: Map<Service, ServiceConfig>, onOpenSettings: () -> Unit) {
    val arrs = rememberArrs(configs, Service.RADARR, Service.SONARR)
    if (arrs.isEmpty()) return NotConfigured("Ni Radarr ni Sonarr", onOpenSettings)
    val loader = rememberLoader(arrs, autoRefreshMs = 15_000) { gather(arrs) { it.queue() } }
    LoadedList(loader) { g ->
        g.errors.forEach { item { ErrorCard(it) } }
        val merged = mergeQueue(g.items)
        if (merged.isEmpty()) item {
            Empty(
                "Rien en cours de téléchargement ou d'import",
                AppIcons.Inbox,
                "Les films et séries demandés apparaissent ici pendant leur téléchargement et leur import.",
            )
        }
        items(merged) { QueueCard(it) }
    }
}

/** Un pack de saison apparaît une fois par épisode dans Sonarr : une seule carte par téléchargement */
private fun mergeQueue(items: List<QueueItem>): List<QueueItem> =
    items.groupBy { it.service to (it.downloadId ?: (it.title + it.detail)) }.values.map { group ->
        val first = group.first()
        if (group.size == 1) return@map first
        val codes = group.mapNotNull { it.detail?.substringBefore(" · ") }.distinct().sorted()
        if (codes.size < 2) first else first.copy(detail = "${codes.size} épisodes : ${codes.first()} → ${codes.last()}")
    }

@Composable
private fun QueueCard(q: QueueItem) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp)) {
            ServiceIcon(q.service, Modifier.padding(top = 2.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(q.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                q.detail?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Bar(q.progress.toFloat(), Modifier.padding(vertical = 4.dp))
                val line = listOfNotNull(q.state, percent(q.progress), bytes(q.size), q.timeLeft?.let { "reste $it" })
                Text(line.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                q.problem?.let {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Warning, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.width(6.dp))
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

/** Film (Radarr) ou série (Sonarr) : une icône plutôt qu'un badge texte sur chaque ligne */
@Composable
private fun ServiceIcon(s: Service, modifier: Modifier = Modifier) {
    Icon(s.icon, s.label, modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
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
        if (g.items.isEmpty()) item { Empty("Aucune sortie prévue dans les 30 prochains jours", Icons.Filled.DateRange) }
        // Une carte par jour, une ligne par sortie
        g.items.sortedWith(compareBy({ it.date }, { it.title })).groupBy { it.date }.forEach { (date, list) ->
            item(key = date.toString()) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    DayHeader(date)
                    Card(Modifier.fillMaxWidth()) {
                        list.forEachIndexed { i, c ->
                            if (i > 0) HorizontalDivider(Modifier.padding(start = 52.dp), color = MaterialTheme.colorScheme.outlineVariant)
                            CalendarRow(c)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DayHeader(date: LocalDate) {
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.Bottom) {
        Text(dayLabel(date), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        val days = ChronoUnit.DAYS.between(LocalDate.now(), date)
        if (days > 1) Text("dans $days j", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun CalendarRow(c: CalendarItem) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        ServiceIcon(c.service)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(c.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(c.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (c.hasFile) {
            Spacer(Modifier.width(8.dp))
            Pill("Disponible", MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
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
    /** qBittorrent : n'est affiché que si Radarr/Sonarr ne donnent pas déjà les disques */
    val freeSpace: Long? = null,
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
                s.globalRatio?.let { "Ratio global : $it" },
            ),
            freeSpace = s.freeSpace,
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
fun StatusScreen(configs: Map<Service, ServiceConfig>, onOpenSettings: () -> Unit, onOpenProwlarr: () -> Unit) {
    val configured = remember(configs) { configs.filterValues { it.configured } }
    if (configured.isEmpty()) return NotConfigured("Aucun service", onOpenSettings)
    val loader = rememberLoader(configured) {
        coroutineScope { configured.map { (s, c) -> async(Dispatchers.IO) { statusOf(s, c) } }.awaitAll() }
    }
    LoadedList(loader) { statuses ->
        // Radarr et Sonarr voient souvent les mêmes disques : un seul bloc, sans doublons
        // Plusieurs chemins sur le même volume (mêmes tailles) : regroupés sur une ligne
        val disks = statuses.flatMap { it.disks }.distinctBy { it.path }
            .groupBy { it.free to it.total }.values
            .map { same -> same.first().copy(path = same.joinToString(" · ") { it.path }) }
        if (disks.isNotEmpty()) item { DisksCard(disks) }
        items(statuses, key = { it.service.name }) {
            StatusCard(it, showFreeSpace = disks.isEmpty(), onOpen = onOpenProwlarr.takeIf { _ -> it.service == Service.PROWLARR })
        }
    }
}

@Composable
private fun DisksCard(disks: List<Disk>) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(AppIcons.Storage, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(12.dp))
                Text("Disques", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            }
            disks.forEach { d ->
                val used = if (d.total > 0) 1f - d.free.toFloat() / d.total else 0f
                // Seuils : orange à 80 %, rouge à 90 %
                val color = when {
                    used > 0.9f -> MaterialTheme.colorScheme.error
                    used > 0.8f -> MaterialTheme.colorScheme.tertiary
                    else -> MaterialTheme.colorScheme.primary
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(d.path, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${(used * 100).toInt()} %", style = MaterialTheme.typography.labelMedium, color = color)
                    }
                    Bar(used, color = color)
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
private fun StatusCard(s: ServiceStatus, showFreeSpace: Boolean, onOpen: (() -> Unit)? = null) {
    // 0 = tout va bien, 1 = avertissement / information (mise à jour…), 2 = erreur
    val level = when {
        s.error != null || s.health.any { it.isError } -> 2
        s.health.isNotEmpty() -> 1
        else -> 0
    }
    val c = MaterialTheme.colorScheme
    // Prowlarr : la carte ouvre son écran dédié
    val modifier = Modifier.fillMaxWidth()
    val body: @Composable ColumnScope.() -> Unit = {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    when (level) { 2 -> Icons.Filled.Warning; 1 -> Icons.Filled.Info; else -> Icons.Filled.CheckCircle },
                    when (level) { 2 -> "En erreur"; 1 -> "À surveiller"; else -> "Opérationnel" },
                    Modifier.size(20.dp),
                    tint = when (level) { 2 -> c.error; 1 -> c.tertiary; else -> c.primary },
                )
                Spacer(Modifier.width(12.dp))
                Text(s.service.label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                s.version?.let { Text("v${it.removePrefix("v")}", style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant) }
            }
            s.error?.let { Text(it, color = c.error, style = MaterialTheme.typography.bodySmall) }
            s.lines.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
            if (showFreeSpace) s.freeSpace?.let { Text("Espace libre : ${bytes(it)}", style = MaterialTheme.typography.bodySmall) }
            if (s.error == null && s.health.isEmpty() && s.service != Service.QBIT) {
                Text("Aucun problème signalé", style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
            }
            s.health.forEach { h ->
                Row {
                    Icon(
                        if (h.isError) Icons.Filled.Warning else Icons.Filled.Info,
                        null,
                        Modifier.padding(top = 1.dp).size(16.dp),
                        tint = if (h.isError) c.error else c.tertiary,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(h.message, style = MaterialTheme.typography.bodySmall, color = if (h.isError) c.error else c.onSurfaceVariant)
                }
            }
            if (onOpen != null) {
                Text(
                    "Indexeurs, statistiques et historique ›",
                    Modifier.padding(top = 4.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = c.primary,
                )
            }
        }
    }
    if (onOpen != null) Card(onClick = onOpen, modifier = modifier, content = body) else Card(modifier, content = body)
}
