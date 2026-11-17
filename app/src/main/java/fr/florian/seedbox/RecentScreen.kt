package fr.florian.seedbox

import androidx.compose.foundation.layout.*
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

// ======================= Récents (derniers films / séries importés, avec pochette) =======================

private enum class RecentKind(val label: String, val service: Service, val empty: String) {
    MOVIES("Films", Service.RADARR, "Aucun film importé récemment"),
    SHOWS("Séries", Service.SONARR, "Aucun épisode importé récemment"),
}

private const val COLUMNS = 3

@Composable
fun RecentScreen(configs: Map<Service, ServiceConfig>, onOpenSettings: () -> Unit) {
    val arrs = rememberArrs(configs, Service.RADARR, Service.SONARR)
    if (arrs.isEmpty()) return NotConfigured("Ni Radarr ni Sonarr", onOpenSettings)
    val kinds = RecentKind.entries.filter { k -> arrs.any { it.service == k.service } }
    var selected by rememberSaveable { mutableStateOf(kinds.first()) }
    val kind = if (selected in kinds) selected else kinds.first()
    val loader = rememberLoader(arrs) { gather(arrs) { it.recent() } }

    LoadedList(loader) { g ->
        g.errors.forEach { item { ErrorCard(it) } }
        if (kinds.size > 1) item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                kinds.forEach { k ->
                    FilterChip(selected = kind == k, onClick = { selected = k }, label = { Text(k.label) })
                }
            }
        }
        val shown = g.items.filter { it.service == kind.service }
        if (shown.isEmpty()) item { Empty(kind.empty) }
        // Grille de 3 colonnes dans la liste (garde « tirer pour actualiser » de LoadedList)
        shown.chunked(COLUMNS).forEach { row ->
            item(key = row.first().poster.key) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { RecentCard(it, Modifier.weight(1f)) }
                    repeat(COLUMNS - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun RecentCard(r: RecentItem, modifier: Modifier) {
    Column(modifier) {
        Poster(r.poster, Modifier.fillMaxWidth())
        Text(
            r.title,
            Modifier.padding(top = 4.dp),
            style = MaterialTheme.typography.labelLarge,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        r.detail?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Text(ago(r.date), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
    }
}

private val SHORT_DATE = DateTimeFormatter.ofPattern("d MMM", Locale.FRENCH)

/** « Aujourd'hui », « Hier », « Il y a 3 j », puis la date */
private fun ago(i: Instant): String {
    val d = i.atZone(ZoneId.systemDefault()).toLocalDate()
    val days = ChronoUnit.DAYS.between(d, LocalDate.now())
    return when {
        days <= 0 -> "Aujourd'hui"
        days == 1L -> "Hier"
        days < 7 -> "Il y a $days j"
        else -> d.format(SHORT_DATE)
    }
}
