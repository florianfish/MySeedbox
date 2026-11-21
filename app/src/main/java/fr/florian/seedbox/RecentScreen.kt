package fr.florian.seedbox

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
    // Fiche ouverte : on garde la clé (survit à la rotation et suit les actualisations)
    var openKey by rememberSaveable { mutableStateOf<String?>(null) }

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
                    row.forEach { r -> RecentCard(r, Modifier.weight(1f)) { openKey = r.poster.key } }
                    repeat(COLUMNS - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }

    val open = openKey?.let { k -> loader.data?.items?.firstOrNull { it.poster.key == k } }
    if (open != null) RecentSheet(open, onDismiss = { openKey = null })
}

@Composable
private fun RecentCard(r: RecentItem, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.clip(MaterialTheme.shapes.small).clickable(onClick = onClick)) {
        Box {
            Poster(r.poster, Modifier.fillMaxWidth())
            r.resolution?.let { Box(Modifier.align(Alignment.TopEnd).padding(4.dp)) { Pill(it) } }
        }
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
        val tech = listOfNotNull(r.size?.let(::bytes), langCodes(r.languages)).joinToString(" · ")
        if (tech.isNotEmpty()) {
            Text(tech, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(ago(r.date), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
    }
}

// ---- Fiche détaillée ----

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecentSheet(r: RecentItem, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row {
                Poster(r.poster, Modifier.width(96.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(r.title, style = MaterialTheme.typography.titleMedium)
                    r.info?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    r.rating?.let {
                        Text("★ " + String.format(Locale.FRANCE, "%.1f", it), style = MaterialTheme.typography.bodySmall)
                    }
                    Text("Importé ${dateTime(r.date)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
            }
            r.overview?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 8, overflow = TextOverflow.Ellipsis)
            }
            // Une carte par release : un pack de saison regroupe ses épisodes
            r.events.groupBy { it.release ?: it.date.toString() }.values.forEach { ImportCard(it) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ImportCard(events: List<ImportEvent>) {
    val e = events.first()
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val pills = (listOfNotNull(e.quality) + e.languages.map { lang(it).second } + e.formats).distinct()
            if (pills.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    pills.forEach { Pill(it) }
                }
            }
            val size = events.distinctBy { it.episode }.mapNotNull { it.size }.takeIf { it.isNotEmpty() }?.sum()
            InfoLine("Taille", size?.let(::bytes))
            InfoLine("Groupe", e.group)
            InfoLine("Client", e.client)
            InfoLine("Importé", e.date?.let(::dateTime))
            val episodes = events.mapNotNull { it.episode }.distinct().sorted()
            if (episodes.isNotEmpty()) {
                Column {
                    episodes.forEach { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            }
            // Nom de la release, sélectionnable pour le copier
            e.release?.let {
                SelectionContainer {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String?) {
    if (value == null) return
    Row {
        Text(label, Modifier.width(72.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}

// ---- Mise en forme ----

/** Langue des *arr (nom anglais) → code court et nom français */
private val LANGS = mapOf(
    "French" to ("FR" to "Français"),
    "English" to ("EN" to "Anglais"),
    "Japanese" to ("JA" to "Japonais"),
    "Spanish" to ("ES" to "Espagnol"),
    "German" to ("DE" to "Allemand"),
    "Italian" to ("IT" to "Italien"),
    "Korean" to ("KO" to "Coréen"),
    "Portuguese" to ("PT" to "Portugais"),
    "Dutch" to ("NL" to "Néerlandais"),
    "Russian" to ("RU" to "Russe"),
    "Chinese" to ("ZH" to "Chinois"),
    "Original" to ("VO" to "VO"),
)

private fun lang(name: String): Pair<String, String> = LANGS[name] ?: (name.take(2).uppercase(Locale.ROOT) to name)

/** « FR », « FR/EN », ou « MULTI » au-delà de deux langues */
private fun langCodes(names: List<String>): String? = when {
    names.isEmpty() -> null
    names.size > 2 -> "MULTI"
    else -> names.joinToString("/") { lang(it).first }
}

private val SHORT_DATE = DateTimeFormatter.ofPattern("d MMM", Locale.FRENCH)
private val LONG_DATE = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.FRENCH)
private val TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.FRENCH)

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

/** « aujourd'hui à 22:14 », « hier à 08:03 », « le jeudi 12 novembre à 21:40 » */
internal fun dateTime(i: Instant): String {
    val z = i.atZone(ZoneId.systemDefault())
    val day = when (ChronoUnit.DAYS.between(z.toLocalDate(), LocalDate.now())) {
        0L -> "aujourd'hui"
        1L -> "hier"
        else -> "le " + z.format(LONG_DATE)
    }
    return "$day à ${z.format(TIME)}"
}
