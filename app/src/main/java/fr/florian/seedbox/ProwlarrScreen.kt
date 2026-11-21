package fr.florian.seedbox

import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

private enum class ProwlarrTab(val label: String) {
    INDEXERS("Indexeurs"), STATS("Statistiques"), HISTORY("Historique"), APPS("Applications"),
}

/** Écran Prowlarr, ouvert depuis sa carte dans l'onglet État */
@Composable
fun ProwlarrScreen(cfg: ServiceConfig, onOpenSettings: () -> Unit) {
    if (!cfg.configured) return NotConfigured("Prowlarr", onOpenSettings)
    val client = remember(cfg) { Prowlarr(cfg) }
    var tab by rememberSaveable { mutableStateOf(ProwlarrTab.INDEXERS) }
    Column(Modifier.fillMaxSize()) {
        PrimaryScrollableTabRow(selectedTabIndex = tab.ordinal, edgePadding = 12.dp) {
            ProwlarrTab.entries.forEach { t ->
                Tab(selected = tab == t, onClick = { tab = t }, text = { Text(t.label) })
            }
        }
        Box(Modifier.weight(1f)) {
            when (tab) {
                ProwlarrTab.INDEXERS -> IndexersTab(client)
                ProwlarrTab.STATS -> StatsTab(client)
                ProwlarrTab.HISTORY -> HistoryTab(client)
                ProwlarrTab.APPS -> AppsTab(client)
            }
        }
    }
}

private fun count(n: Int) = String.format(Locale.FRANCE, "%,d", n)

private fun plural(n: Int, word: String) = "${count(n)} $word" + if (n > 1) "s" else ""

// ---- Indexeurs ----

@Composable
private fun IndexersTab(client: Prowlarr) {
    val loader = rememberLoader(client) { client.indexers() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    // Indexeurs en cours de modification ou de test : leurs boutons sont grisés
    var busy by remember { mutableStateOf(emptySet<Int>()) }

    fun act(i: ProwlarrIndexer, done: String, block: () -> Unit) {
        busy = busy + i.id
        scope.launch {
            try {
                withContext(Dispatchers.IO) { block() }
                Toast.makeText(context, done, Toast.LENGTH_SHORT).show()
                loader.refresh()
            } catch (e: Exception) {
                Toast.makeText(context, e.friendly(), Toast.LENGTH_LONG).show()
            } finally {
                busy = busy - i.id
            }
        }
    }

    LoadedList(loader) { list ->
        if (list.isEmpty()) {
            item { Empty("Aucun indexeur", AppIcons.Search, "Ajoute-les depuis l'interface web de Prowlarr") }
            return@LoadedList
        }
        item {
            val active = list.count { it.enabled }
            val failing = list.count { it.enabled && it.failing }
            Text(
                listOfNotNull(
                    "${count(active)} actif" + if (active > 1) "s" else "",
                    failing.takeIf { it > 0 }?.let { "$it en échec" },
                    (list.size - active).takeIf { it > 0 }?.let { "$it désactivé" + if (it > 1) "s" else "" },
                ).joinToString(" · "),
                Modifier.padding(horizontal = 4.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        items(list, key = { it.id }) { i ->
            IndexerCard(
                i,
                busy = i.id in busy,
                onToggle = { on -> act(i, if (on) "${i.name} activé" else "${i.name} désactivé") { client.setEnabled(i, on) } },
                onTest = { act(i, "${i.name} : test réussi") { client.test(i) } },
            )
        }
    }
}

@Composable
private fun IndexerCard(i: ProwlarrIndexer, busy: Boolean, onToggle: (Boolean) -> Unit, onTest: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    i.name,
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (i.enabled) c.onSurface else c.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Switch(checked = i.enabled, onCheckedChange = onToggle, enabled = !busy)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedPill(if (i.protocol == "usenet") "Usenet" else "Torrent")
                privacyLabel(i.privacy)?.let { OutlinedPill(it) }
                OutlinedPill("Priorité ${i.priority}")
            }
            when {
                !i.enabled -> Text("Désactivé", style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                i.failing -> Text(
                    // Prowlarr met l'indexeur en pause après des échecs et le réessaie à cette date
                    "En échec : nouvel essai ${dateTime(i.disabledTill!!)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = c.error,
                )
            }
            Row {
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onTest, enabled = !busy) { Text(if (busy) "Patiente…" else "Tester") }
            }
        }
    }
}

private fun privacyLabel(p: String?) = when (p) {
    "public" -> "Public"
    "private" -> "Privé"
    "semiPrivate" -> "Semi-privé"
    else -> null
}

// ---- Statistiques ----

private enum class Period(val label: String, val days: Int?) { WEEK("7 jours", 7), MONTH("30 jours", 30), ALL("Tout", null) }

private enum class StatSort(val label: String, val key: (IndexerStat) -> Int) {
    QUERIES("Requêtes", { it.queries + it.rss }),
    GRABS("Grabs", { it.grabs }),
    FAILURES("Échecs", { it.failures }),
    RESPONSE("Temps de réponse", { it.responseMs }),
}

@Composable
private fun StatsTab(client: Prowlarr) {
    var period by rememberSaveable { mutableStateOf(Period.WEEK) }
    var sort by rememberSaveable { mutableStateOf(StatSort.QUERIES) }
    val loader = rememberLoader(client, period) { client.stats(period.days) }
    LoadedList(loader) { stats ->
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Period.entries.forEach { p -> FilterChip(selected = period == p, onClick = { period = p }, label = { Text(p.label) }) }
            }
        }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatSort.entries.forEach { s -> FilterChip(selected = sort == s, onClick = { sort = s }, label = { Text(s.label) }) }
            }
        }
        if (stats.isEmpty()) {
            item { Empty("Aucune activité sur la période", AppIcons.Search) }
            return@LoadedList
        }
        item { TotalsCard(stats) }
        items(stats.sortedByDescending(sort.key), key = { it.indexerId }) { StatCard(it) }
    }
}

@Composable
private fun TotalsCard(stats: List<IndexerStat>) {
    val c = MaterialTheme.colorScheme
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = c.secondaryContainer)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            Total(stats.sumOf { it.queries + it.rss }, "requêtes")
            Total(stats.sumOf { it.grabs }, "grabs")
            Total(stats.sumOf { it.failures }, "échecs")
        }
    }
}

@Composable
private fun Total(n: Int, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(count(n), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun StatCard(s: IndexerStat) {
    val c = MaterialTheme.colorScheme
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(s.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOf(plural(s.queries, "requête"), "${count(s.rss)} RSS", plural(s.grabs, "grab")).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
            )
            if (s.responseMs > 0) {
                Text("Réponse moyenne : ${count(s.responseMs)} ms", style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
            }
            if (s.failures > 0) {
                Text(
                    "Échecs : " + listOfNotNull(
                        s.failedQueries.takeIf { it > 0 }?.let { plural(it, "requête") },
                        s.failedGrabs.takeIf { it > 0 }?.let { plural(it, "grab") },
                    ).joinToString(", "),
                    style = MaterialTheme.typography.bodySmall,
                    color = c.error,
                )
            }
        }
    }
}

// ---- Historique ----

private enum class HistoryFilter(val label: String) { ALL("Tout"), GRABS("Grabs"), FAILED("Échecs") }

@Composable
private fun HistoryTab(client: Prowlarr) {
    var filter by rememberSaveable { mutableStateOf(HistoryFilter.ALL) }
    val loader = rememberLoader(client, filter) {
        val names = client.indexers().associate { it.id to it.name }
        client.history(grabsOnly = filter == HistoryFilter.GRABS, failedOnly = filter == HistoryFilter.FAILED, names)
    }
    LoadedList(loader) { events ->
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HistoryFilter.entries.forEach { f -> FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text(f.label) }) }
            }
        }
        if (events.isEmpty()) item { Empty("Rien dans l'historique", AppIcons.Search) }
        items(events) { EventCard(it) }
    }
}

@Composable
private fun EventCard(e: ProwlarrEvent) {
    val c = MaterialTheme.colorScheme
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (e.grab) Pill("Grab", c.primaryContainer, c.onPrimaryContainer) else OutlinedPill("Recherche")
                if (!e.successful) Pill("Échec", c.errorContainer, c.onErrorContainer)
                Spacer(Modifier.weight(1f))
                Text(dateTime(e.date), style = MaterialTheme.typography.labelSmall, color = c.onSurfaceVariant)
            }
            Text(
                e.title ?: if (e.grab) "Release" else "Recherche sans termes",
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOfNotNull(
                    e.indexer,
                    e.source,
                    e.results?.takeIf { !e.grab }?.let { plural(it, "résultat") },
                    e.elapsedMs?.let { "${count(it)} ms" },
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = c.onSurfaceVariant,
            )
        }
    }
}

// ---- Applications ----

@Composable
private fun AppsTab(client: Prowlarr) {
    val loader = rememberLoader(client) { client.applications() }
    LoadedList(loader) { apps ->
        if (apps.isEmpty()) item { Empty("Aucune application", hint = "Prowlarr ne synchronise ses indexeurs avec aucune appli") }
        items(apps) { a ->
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(a.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        a.kind?.takeIf { it != a.name }?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    val (label, on) = when (a.syncLevel) {
                        "fullSync" -> "Synchro complète" to true
                        "addOnly" -> "Ajout seulement" to true
                        "disabled" -> "Désactivée" to false
                        else -> a.syncLevel to true
                    }
                    if (on) Pill(label) else OutlinedPill(label)
                }
            }
        }
    }
}
