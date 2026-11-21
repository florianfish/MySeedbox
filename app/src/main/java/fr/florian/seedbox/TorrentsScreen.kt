package fr.florian.seedbox

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.Normalizer
import java.util.Locale

// ======================= Torrents (qBittorrent) =======================

private enum class TorrentFilter(val label: String, val keep: (Torrent) -> Boolean) {
    ALL("Tous", { true }),
    ACTIVE("En cours", { !it.finished }),
    DONE("Terminés", { it.finished }),
    ERRORS("Erreurs", { it.errored }),
}

/** Tri, dans son sens naturel (récents, plus gros, plus rapides… d'abord) ; « Ordre inverse » le retourne */
private enum class TorrentSort(val label: String, val comparator: Comparator<Torrent>) {
    ADDED("Date d'ajout", compareByDescending { it.addedOn }),
    NAME("Nom", compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }),
    SIZE("Taille", compareByDescending { it.size }),
    PROGRESS("Progression", compareByDescending { it.progress }),
    RATIO("Ratio", compareByDescending { it.ratio }),
    SPEED("Vitesse", compareByDescending { it.dlSpeed + it.upSpeed }),
}

/** Tri mémorisé entre les lancements */
private class SortPref(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("seedbox", Context.MODE_PRIVATE)
    var sort by mutableStateOf(
        TorrentSort.entries.firstOrNull { it.name == prefs.getString(KEY_SORT, null) } ?: TorrentSort.ADDED
    )
        private set
    var reverse by mutableStateOf(prefs.getBoolean(KEY_REVERSE, false))
        private set

    fun set(sort: TorrentSort, reverse: Boolean) {
        this.sort = sort
        this.reverse = reverse
        prefs.edit().putString(KEY_SORT, sort.name).putBoolean(KEY_REVERSE, reverse).apply()
    }

    fun comparator(): Comparator<Torrent> {
        // À égalité (vitesses nulles…), les plus récents d'abord
        val c = sort.comparator.thenByDescending { it.addedOn }
        return if (reverse) c.reversed() else c
    }

    private companion object {
        const val KEY_SORT = "torrents_sort"
        const val KEY_REVERSE = "torrents_sort_reverse"
    }
}

/** Minuscules, sans accents, ponctuation (points des noms de release…) remplacée par des espaces */
private fun normalize(s: String): String =
    Normalizer.normalize(s, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()

/** Tous les mots cherchés apparaissent dans le nom, dans n'importe quel ordre */
private fun matches(t: Torrent, words: List<String>) = words.isEmpty() || normalize(t.name).let { n -> words.all { it in n } }

@Composable
fun TorrentsScreen(cfg: ServiceConfig, onOpenSettings: () -> Unit, onOpen: (String) -> Unit) {
    if (!cfg.configured) return NotConfigured("qBittorrent", onOpenSettings)
    val client = remember(cfg) { QBittorrent(cfg) }
    val loader = rememberLoader(client, autoRefreshMs = 5_000) { client.torrents() to client.transfer() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val sortPref = remember { SortPref(context) }

    var filter by rememberSaveable { mutableStateOf(TorrentFilter.ALL) }
    var query by rememberSaveable { mutableStateOf("") }
    // null = toutes, "" = sans catégorie / sans étiquette
    var category by rememberSaveable { mutableStateOf<String?>(null) }
    var tag by rememberSaveable { mutableStateOf<String?>(null) }
    // Hashes sélectionnés (appui long) ; non vide = mode sélection
    var selected by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var dialog by rememberSaveable { mutableStateOf<GroupDialog?>(null) }

    val torrents = loader.data?.first.orEmpty()
    // Un torrent sélectionné qui disparaît (supprimé ailleurs) sort de la sélection
    LaunchedEffect(torrents) {
        if (loader.data != null && selected.isNotEmpty()) {
            val present = torrents.mapTo(HashSet()) { it.hash }
            if (!selected.all { it in present }) selected = selected.filter { it in present }
        }
    }
    val selection = remember(torrents, selected) { selected.toSet().let { s -> torrents.filter { it.hash in s } } }
    BackHandler(enabled = selected.isNotEmpty()) { selected = emptyList() }

    fun toggle(t: Torrent) {
        selected = if (t.hash in selected) selected - t.hash else selected + t.hash
    }

    /** Action sur le serveur ; [done] : message de confirmation, la sélection est vidée en cas de succès */
    fun act(done: String? = null, clear: Boolean = false, block: () -> Unit) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) { block() }
                if (clear) selected = emptyList()
                done?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
                loader.refresh()
            } catch (e: Exception) {
                Toast.makeText(context, e.friendly(), Toast.LENGTH_LONG).show()
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        if (selected.isNotEmpty()) {
            SelectionBar(
                count = selection.size,
                onClear = { selected = emptyList() },
                onPause = { act(clear = true) { client.pause(hashes(selection)) } },
                onResume = { act(clear = true) { client.resume(hashes(selection)) } },
                onSelectAll = {
                    // Tout ce qui est affiché, filtres compris
                    selected = (selected + visible(torrents, filter, query, category, tag).map { it.hash }).distinct()
                },
                onCategory = { dialog = GroupDialog.CATEGORY },
                onMove = { dialog = GroupDialog.MOVE },
                onDelete = { dialog = GroupDialog.DELETE },
            )
        }
        Box(Modifier.weight(1f)) {
            // Marge du bas : le bouton « + » ne cache pas la dernière carte
            LoadedList(loader, bottomSpace = 88.dp) { (all, transfer) ->
                item { SpeedSummary(transfer.dlSpeed, transfer.upSpeed) }
                item(key = "search") {
                    SearchRow(
                        query, onQuery = { query = it },
                        sort = sortPref.sort, reverse = sortPref.reverse, onSort = sortPref::set,
                    )
                }
                // Les compteurs des puces d'état tiennent compte de la recherche, de la catégorie et de l'étiquette
                val base = refine(all, query, category, tag)
                item(key = "filters") {
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TorrentFilter.entries.forEach { f ->
                            val n = base.count(f.keep)
                            if (f == TorrentFilter.ERRORS && n == 0 && filter != f) return@forEach
                            FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text("${f.label} ($n)") })
                        }
                        val categories = all.map { it.category }.filter { it.isNotEmpty() }.distinct().sortedBy { it.lowercase() }
                        if (categories.isNotEmpty() || category != null) {
                            ChoiceChip(
                                "Catégorie", AppIcons.Folder, category, categories,
                                none = "Sans catégorie",
                                count = { c -> all.count { it.category == c } },
                                onPick = { category = it },
                            )
                        }
                        val tags = all.flatMap { it.tags }.distinct().sortedBy { it.lowercase() }
                        if (tags.isNotEmpty() || tag != null) {
                            ChoiceChip(
                                "Étiquette", AppIcons.Label, tag, tags,
                                none = "Sans étiquette",
                                count = { g -> all.count { if (g.isEmpty()) it.tags.isEmpty() else g in it.tags } },
                                onPick = { tag = it },
                            )
                        }
                    }
                }
                val shown = base.filter(filter.keep).sortedWith(sortPref.comparator())
                if (shown.isEmpty()) item {
                    when {
                        all.isEmpty() -> Empty("Aucun torrent", AppIcons.Download, "Appuie sur + pour en ajouter un")
                        query.isNotBlank() || category != null || tag != null ->
                            Empty("Aucun torrent ne correspond", AppIcons.Search, "Modifie la recherche ou les filtres")
                        else -> Empty("Aucun torrent dans ce filtre", AppIcons.Download)
                    }
                }
                items(shown, key = { it.hash }) { t ->
                    val selecting = selected.isNotEmpty()
                    TorrentCard(
                        t,
                        selecting = selecting,
                        selected = t.hash in selected,
                        onClick = { if (selecting) toggle(t) else onOpen(t.hash) },
                        onLongClick = { toggle(t) },
                        onToggle = { act { if (t.paused) client.resume(t.hash) else client.pause(t.hash) } },
                    )
                }
            }
        }
    }

    // Les dialogues ne s'ouvrent que si la sélection n'est pas vide (elle a pu se vider entre-temps)
    if (selection.isEmpty()) return
    when (dialog) {
        GroupDialog.CATEGORY -> CategoryDialog(
            client,
            current = selection.map { it.category }.distinct().singleOrNull(),
            onPick = { c ->
                dialog = null
                act(if (c.isEmpty()) "Catégorie retirée" else "Catégorie « $c » appliquée", clear = true) { client.setCategory(hashes(selection), c) }
            },
            onDismiss = { dialog = null },
        )
        GroupDialog.MOVE -> FolderPicker(
            client,
            // Dossier commun, sinon celui du premier
            start = selection.map { it.savePath }.distinct().singleOrNull() ?: selection.first().savePath.ifBlank { "/" },
            title = if (selection.size == 1) "Déplacer vers…" else "Déplacer ${selection.size} torrents vers…",
            onPick = { path ->
                dialog = null
                act("Déplacement lancé", clear = true) { client.setLocation(hashes(selection), path) }
            },
            onDismiss = { dialog = null },
        )
        GroupDialog.DELETE -> DeleteTorrentDialog(
            selection,
            onConfirm = { withFiles ->
                dialog = null
                val n = selection.size
                act(if (n == 1) "Torrent supprimé" else "$n torrents supprimés", clear = true) {
                    client.delete(hashes(selection), withFiles)
                }
            },
            onDismiss = { dialog = null },
        )
        null -> {}
    }
}

private enum class GroupDialog { CATEGORY, MOVE, DELETE }

/** Recherche, catégorie et étiquette (sans le filtre d'état) */
private fun refine(all: List<Torrent>, query: String, category: String?, tag: String?): List<Torrent> {
    val words = normalize(query).split(' ').filter { it.isNotEmpty() }
    return all.filter { t ->
        (category == null || t.category == category) &&
            (tag == null || if (tag.isEmpty()) t.tags.isEmpty() else tag in t.tags) &&
            matches(t, words)
    }
}

private fun visible(all: List<Torrent>, filter: TorrentFilter, query: String, category: String?, tag: String?) =
    refine(all, query, category, tag).filter(filter.keep)

/** Barre de la sélection multiple : actions groupées */
@Composable
private fun SelectionBar(
    count: Int,
    onClear: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onSelectAll: () -> Unit,
    onCategory: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClear) { Icon(Icons.Filled.Close, "Annuler la sélection") }
            Text(
                if (count > 1) "$count sélectionnés" else "$count sélectionné",
                Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
            )
            IconButton(onClick = onPause) { Icon(AppIcons.Pause, "Mettre en pause") }
            IconButton(onClick = onResume) { Icon(Icons.Filled.PlayArrow, "Reprendre") }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Autres actions") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Tout sélectionner") }, onClick = { menu = false; onSelectAll() })
                    DropdownMenuItem(
                        text = { Text("Catégorie…") },
                        leadingIcon = { Icon(AppIcons.Folder, null) },
                        onClick = { menu = false; onCategory() },
                    )
                    DropdownMenuItem(
                        text = { Text("Déplacer…") },
                        leadingIcon = { Icon(AppIcons.Folder, null) },
                        onClick = { menu = false; onMove() },
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
}

/** Champ de recherche et menu de tri */
@Composable
private fun SearchRow(
    query: String,
    onQuery: (String) -> Unit,
    sort: TorrentSort,
    reverse: Boolean,
    onSort: (TorrentSort, Boolean) -> Unit,
) {
    val focus = LocalFocusManager.current
    var menu by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            query,
            onQuery,
            Modifier.weight(1f),
            placeholder = { Text("Rechercher") },
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            trailingIcon = {
                if (query.isNotEmpty()) IconButton(onClick = { onQuery("") }) { Icon(Icons.Filled.Clear, "Effacer") }
            },
            singleLine = true,
            shape = MaterialTheme.shapes.extraLarge,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
        )
        Box {
            IconButton(onClick = { menu = true }) { Icon(AppIcons.Sort, "Trier : ${sort.label}") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                TorrentSort.entries.forEach { s ->
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
}

/** Puce à liste déroulante : null = tous, "" = [none], sinon la valeur choisie */
@Composable
private fun ChoiceChip(
    label: String,
    icon: ImageVector,
    value: String?,
    options: List<String>,
    none: String,
    count: (String) -> Int,
    onPick: (String?) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Box {
        FilterChip(
            selected = value != null,
            onClick = { menu = true },
            label = {
                Text(
                    when (value) { null -> label; "" -> none; else -> value },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 160.dp),
                )
            },
            leadingIcon = { Icon(icon, null, Modifier.size(18.dp)) },
            trailingIcon = { Icon(AppIcons.ExpandMore, null, Modifier.size(18.dp)) },
        )
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("Toutes") }, onClick = { menu = false; onPick(null) })
            (listOf("") + options).forEach { o ->
                DropdownMenuItem(
                    text = { Text("${o.ifEmpty { none }} (${count(o)})") },
                    leadingIcon = { if (o == value) Icon(Icons.Filled.Check, null) else Spacer(Modifier.size(24.dp)) },
                    onClick = { menu = false; onPick(o) },
                )
            }
        }
    }
}

/** Choix de la catégorie d'une sélection, parmi celles définies dans qBittorrent */
@Composable
private fun CategoryDialog(client: QBittorrent, current: String?, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val loader = rememberLoader(client) { client.categories() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Catégorie") },
        text = {
            val list = loader.data
            when {
                list != null -> Column(Modifier.verticalScroll(rememberScrollState())) {
                    (listOf("") + list.map { it.name }).forEach { c ->
                        Row(
                            Modifier.fillMaxWidth().clickable { onPick(c) }.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = c == current, onClick = { onPick(c) })
                            Text(c.ifEmpty { "Aucune catégorie" })
                        }
                    }
                }
                loader.error != null -> Text(loader.error!!, color = MaterialTheme.colorScheme.error)
                else -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
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
 * Appui long : sélection multiple ; en mode sélection, un appui coche / décoche.
 */
@Composable
private fun TorrentCard(
    t: Torrent,
    selecting: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onToggle: () -> Unit,
) {
    // Bordure plutôt qu'un fond coloré : les pastilles d'état garderaient sinon la couleur de la carte
    Card(
        Modifier.fillMaxWidth(),
        colors = if (selected) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest)
        else CardDefaults.cardColors(),
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                // vibration de l'appui long gérée par combinedClickable
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
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
                if (t.tags.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { t.tags.take(4).forEach { OutlinedPill(it) } }
                }
            }
            if (selecting) {
                Checkbox(checked = selected, onCheckedChange = { onClick() })
            } else {
                IconButton(onClick = onToggle) {
                    if (t.paused) Icon(Icons.Filled.PlayArrow, "Reprendre") else Icon(AppIcons.Pause, "Mettre en pause")
                }
            }
        }
    }
}
