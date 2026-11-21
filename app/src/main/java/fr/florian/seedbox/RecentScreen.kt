package fr.florian.seedbox

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONObject
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

/** Enregistrements d'historique demandés à chaque page */
private const val PAGE_SIZE = 100

/** Historique des imports d'un service, chargé page par page (un seul chargement à la fois, grâce au Loader) */
private class ImportFeed(val arr: Arr) {
    private var records: List<JSONObject> = emptyList()
    private val ids = HashSet<Int>()
    private var pages = 0
    private var total = 0
    var items: List<RecentItem> = emptyList()
        private set
    var error: String? = null
        private set

    /** Tout l'historique est chargé */
    val complete get() = pages > 0 && pages * PAGE_SIZE >= total

    /**
     * Première page : les nouveaux imports s'ajoutent en tête sans perdre les pages déjà chargées.
     * Sans recoupement avec ce qu'on a (trop de nouveautés d'un coup), on repart de zéro.
     */
    fun refresh() = attempt {
        val p = arr.importsPage(1, PAGE_SIZE)
        val fresh = p.records.filter { it.optInt("id") !in ids }
        if (pages == 0 || fresh.size == p.records.size) {
            records = emptyList()
            ids.clear()
            pages = 1
            append(p.records)
        } else {
            records = fresh + records
            fresh.forEach { ids += it.optInt("id") }
        }
        total = p.total
    }

    /** Page suivante. Les nouveautés arrivées entre-temps décalent les pages : les doublons sont écartés. */
    fun more() = attempt {
        if (complete) return@attempt
        val p = arr.importsPage(pages + 1, PAGE_SIZE)
        append(p.records)
        pages++
        total = if (p.records.isEmpty()) pages * PAGE_SIZE else p.total
    }

    private fun append(list: List<JSONObject>) {
        records = records + list.filter { ids.add(it.optInt("id")) }
    }

    /** L'erreur reste affichée à côté de ce qui est déjà chargé */
    private fun attempt(block: () -> Unit) {
        error = try {
            block()
            null
        } catch (e: Exception) {
            "${arr.service.label} : ${e.friendly()}"
        }
        items = arr.groupImports(records)
    }

    fun status() = FeedStatus(
        complete,
        error,
        oldest = records.lastOrNull()?.optString("date")?.let { runCatching { Instant.parse(it) }.getOrNull() },
    )
}

private class FeedStatus(val complete: Boolean, val error: String?, val oldest: Instant?)

private class RecentData(val items: List<RecentItem>, val status: Map<Service, FeedStatus>)

/** Historiques de Radarr et Sonarr : un rafraîchissement recharge la tête de chacun, [requestMore] la suite d'un seul */
private class RecentFeeds(arrs: List<Arr>) {
    private val feeds = arrs.map { ImportFeed(it) }

    @Volatile
    private var more: Service? = null

    fun requestMore(s: Service) {
        more = s
    }

    suspend fun fetch(): RecentData = coroutineScope {
        val m = more
        more = null
        feeds.map { f ->
            async(Dispatchers.IO) {
                when {
                    m == null -> f.refresh()
                    f.arr.service == m -> f.more()
                }
            }
        }.awaitAll()
        RecentData(feeds.flatMap { it.items }, feeds.associate { it.arr.service to it.status() })
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RecentScreen(configs: Map<Service, ServiceConfig>, onOpenSettings: () -> Unit) {
    val arrs = rememberArrs(configs, Service.RADARR, Service.SONARR)
    if (arrs.isEmpty()) return NotConfigured("Ni Radarr ni Sonarr", onOpenSettings)
    val kinds = RecentKind.entries.filter { k -> arrs.any { it.service == k.service } }
    var selected by rememberSaveable { mutableStateOf(kinds.first()) }
    val kind = if (selected in kinds) selected else kinds.first()
    val feeds = remember(arrs) { RecentFeeds(arrs) }
    val loader = rememberLoader(feeds) { feeds.fetch() }
    // Fiche ouverte : on garde la clé (survit à la rotation et suit les actualisations)
    var openKey by rememberSaveable { mutableStateOf<String?>(null) }
    // Filtres (null = tous)
    var query by rememberSaveable { mutableStateOf("") }
    var resolution by rememberSaveable { mutableStateOf<String?>(null) }
    var language by rememberSaveable { mutableStateOf<String?>(null) }
    var genre by rememberSaveable { mutableStateOf<String?>(null) }
    var year by rememberSaveable { mutableStateOf<String?>(null) }
    var sort by rememberSaveable { mutableStateOf(RecentSort.IMPORT) }
    var reverse by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val focus = LocalFocusManager.current

    fun loadMore() {
        // Un chargement en cours ignorerait la demande, qui resterait en attente pour le prochain rafraîchissement
        if (loader.loading) return
        feeds.requestMore(kind.service)
        loader.refresh()
    }

    // Suite chargée à l'approche du bas de la grille ; se répète tant que les filtres laissent peu de cartes
    val status = loader.data?.status?.get(kind.service)
    val nearEnd by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: return@derivedStateOf false
            last >= info.totalItemsCount - 1 - COLUMNS
        }
    }
    LaunchedEffect(nearEnd, loader.data, loader.loading, kind) {
        if (nearEnd && status != null && !status.complete && status.error == null && !loader.loading) loadMore()
    }

    LoadedList(loader, state = listState) { d ->
        val all = d.items.filter { it.service == kind.service }
        item(key = "filters") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        query,
                        { query = it },
                        Modifier.weight(1f),
                        placeholder = { Text("Rechercher un titre") },
                        leadingIcon = { Icon(Icons.Filled.Search, null) },
                        trailingIcon = {
                            if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Clear, "Effacer") }
                        },
                        singleLine = true,
                        shape = MaterialTheme.shapes.extraLarge,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
                    )
                    SortMenu(sort, reverse) { s, r -> sort = s; reverse = r }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (kinds.size > 1) kinds.forEach { k ->
                        FilterChip(
                            selected = kind == k,
                            onClick = {
                                // Résolutions, langues et genres ne sont pas les mêmes côté films et côté séries
                                if (k != kind) {
                                    resolution = null
                                    language = null
                                    genre = null
                                    year = null
                                }
                                selected = k
                            },
                            label = { Text(k.label) },
                        )
                    }
                    val resolutions = all.mapNotNull { it.resolution }.distinct().sortedByDescending(::resolutionRank)
                    if (resolutions.isNotEmpty() || resolution != null) {
                        ChoiceChip(
                            "Résolution", AppIcons.Tv, resolution, resolutions, none = null,
                            count = { v -> all.count { it.resolution == v } },
                            onPick = { resolution = it },
                        )
                    }
                    val languages = all.flatMap(::languagesOf).distinct().sorted()
                    if (languages.isNotEmpty() || language != null) {
                        ChoiceChip(
                            "Langue", AppIcons.Campaign, language, languages, none = null,
                            count = { v -> all.count { v in languagesOf(it) } },
                            onPick = { language = it },
                        )
                    }
                    val years = all.mapNotNull { it.year }.distinct().sortedDescending().map { it.toString() }
                    if (years.isNotEmpty() || year != null) {
                        ChoiceChip(
                            "Année", Icons.Filled.DateRange, year, years, none = null,
                            count = { v -> all.count { it.year?.toString() == v } },
                            onPick = { year = it },
                        )
                    }
                    val genres = all.flatMap { it.genres }.distinct().sorted()
                    if (genres.isNotEmpty() || genre != null) {
                        ChoiceChip(
                            "Genre", AppIcons.Label, genre, genres, none = null, all = "Tous",
                            count = { v -> all.count { v in it.genres } },
                            onPick = { genre = it },
                        )
                    }
                }
            }
        }
        d.status.values.mapNotNull { it.error }.forEach { item { ErrorCard(it) } }

        val words = normalize(query).split(' ').filter { it.isNotEmpty() }
        val shown = all.filter { r ->
            (resolution == null || r.resolution == resolution) &&
                (language == null || language in languagesOf(r)) &&
                (genre == null || genre in r.genres) &&
                (year == null || r.year?.toString() == year) &&
                (words.isEmpty() || normalize(r.title).let { n -> words.all { it in n } })
        }.sortedWith(sort.comparator(reverse))
        val st = d.status[kind.service]
        if (shown.isEmpty() && st?.complete != false) {
            item { Empty(if (all.isEmpty()) kind.empty else "Aucun résultat avec ces filtres") }
        }
        // Grille de 3 colonnes dans la liste (garde « tirer pour actualiser » de LoadedList)
        shown.chunked(COLUMNS).forEach { row ->
            item(key = row.first().poster.key) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { r -> RecentCard(r, Modifier.weight(1f)) { openKey = r.poster.key } }
                    repeat(COLUMNS - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
        if (st != null && all.isNotEmpty()) item(key = "footer") { FeedFooter(st, ::loadMore) }
    }

    val open = openKey?.let { k -> loader.data?.items?.firstOrNull { it.poster.key == k } }
    if (open != null) RecentSheet(open, onDismiss = { openKey = null })
}

/** Tri de la grille ; [desc] : sens par défaut (le plus récent, le plus gros…) */
private enum class RecentSort(val label: String, val desc: Boolean, val key: (RecentItem) -> Comparable<*>?) {
    IMPORT("Date d'import", true, { it.date }),
    SIZE("Taille", true, { it.size }),
    TITLE("Titre", false, { normalize(it.title) }),
    YEAR("Année de sortie", true, { it.year }),
    ;

    /** Valeurs absentes (taille ou année inconnue) toujours à la fin, puis du plus récent import au plus ancien */
    fun comparator(reverse: Boolean): Comparator<RecentItem> {
        val down = desc != reverse
        return Comparator<RecentItem> { a, b ->
            @Suppress("UNCHECKED_CAST")
            val x = key(a) as Comparable<Any>?
            val y = key(b)
            when {
                x == null && y == null -> 0
                x == null -> 1
                y == null -> -1
                down -> -x.compareTo(y)
                else -> x.compareTo(y)
            }
        }.thenByDescending { it.date }
    }
}

/** Menu de tri, avec « Ordre inverse » */
@Composable
private fun SortMenu(sort: RecentSort, reverse: Boolean, onSort: (RecentSort, Boolean) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { menu = true }) { Icon(AppIcons.Sort, "Trier : ${sort.label}") }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            RecentSort.entries.forEach { s ->
                DropdownMenuItem(
                    text = { Text(s.label) },
                    leadingIcon = { if (s == sort) Icon(Icons.Filled.Check, null) else Spacer(Modifier.size(24.dp)) },
                    onClick = { menu = false; onSort(s, reverse) },
                )
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("Ordre inverse") },
                leadingIcon = { Checkbox(checked = reverse, onCheckedChange = null) },
                onClick = { menu = false; onSort(sort, !reverse) },
            )
        }
    }
}

/** Bas de la grille : suite en chargement, erreur avec « Réessayer », ou début de l'historique atteint */
@Composable
private fun FeedFooter(st: FeedStatus, onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        when {
            st.error != null -> OutlinedButton(onClick = onRetry) { Text("Réessayer") }
            !st.complete -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
        }
        val since = st.oldest?.atZone(ZoneId.systemDefault())?.toLocalDate()?.format(FULL_DATE) ?: return@Column
        Text(
            if (st.complete) "Début de l'historique : $since" else "Chargé jusqu'au $since",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Langues importées, en français */
private fun languagesOf(r: RecentItem) = r.languages.map { lang(it).second }

/** « 4K » avant « 1080p » avant « 720p »… */
private fun resolutionRank(label: String) = if (label == "4K") 2160 else label.removeSuffix("p").toIntOrNull() ?: 0

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

private val FULL_DATE = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.FRENCH)
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
