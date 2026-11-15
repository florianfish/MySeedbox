package fr.florian.seedbox

import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
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
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private class Detail(
    val torrent: Torrent,
    val props: TorrentProps,
    val files: List<TorrentFile>,
    val trackers: List<Tracker>,
    val peers: List<Peer>,
)

/** Résultat d'un chargement : detail = null si le torrent n'existe plus */
private class Found(val detail: Detail?)

private enum class DetailTab(val label: String) { INFOS("Infos"), FILES("Fichiers"), TRACKERS("Trackers"), PEERS("Pairs") }

private val DATE = DateTimeFormatter.ofPattern("d MMM yyyy 'à' HH:mm", Locale.FRENCH)

/** Date en secondes epoch ; qBittorrent renvoie -1 ou 0 quand elle est inconnue */
private fun date(epoch: Long): String? =
    if (epoch <= 0) null else Instant.ofEpochSecond(epoch).atZone(ZoneId.systemDefault()).format(DATE)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TorrentDetailScreen(cfg: ServiceConfig, hash: String, onClose: () -> Unit) {
    val client = remember(cfg) { QBittorrent(cfg) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tab by rememberSaveable { mutableStateOf(DetailTab.INFOS) }
    val loader = rememberLoader(client, hash, autoRefreshMs = 5_000) {
        val t = client.torrent(hash)
        Found(t?.let { Detail(it, client.properties(hash), client.files(hash), client.trackers(hash), client.peers(hash)) })
    }
    // Torrent supprimé (ici ou ailleurs) : retour à la liste
    val gone = loader.data != null && loader.data?.detail == null
    LaunchedEffect(gone) { if (gone) onClose() }
    var moving by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }

    fun act(done: String? = null, block: () -> Unit) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) { block() }
                done?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
                loader.refresh()
            } catch (e: Exception) {
                Toast.makeText(context, e.friendly(), Toast.LENGTH_LONG).show()
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        val d = loader.data?.detail
        if (d != null) {
            Header(
                d,
                onToggle = { act { if (d.torrent.paused) client.resume(hash) else client.pause(hash) } },
                onRecheck = { act("Vérification lancée") { client.recheck(hash) } },
                onReannounce = { act("Annonce envoyée aux trackers") { client.reannounce(hash) } },
                onMove = { moving = true },
                onDelete = { deleting = true },
            )
            PrimaryScrollableTabRow(selectedTabIndex = tab.ordinal, edgePadding = 12.dp) {
                DetailTab.entries.forEach { t ->
                    val count = when (t) {
                        DetailTab.FILES -> d.files.size
                        DetailTab.TRACKERS -> d.trackers.size
                        DetailTab.PEERS -> d.peers.size
                        else -> null
                    }
                    Tab(
                        selected = tab == t,
                        onClick = { tab = t },
                        text = { Text(if (count != null) "${t.label} ($count)" else t.label) },
                    )
                }
            }
        }
        Box(Modifier.weight(1f)) {
            LoadedList(loader) { found ->
                val detail = found.detail ?: return@LoadedList
                when (tab) {
                    DetailTab.INFOS -> infos(detail)
                    DetailTab.FILES -> files(detail.files) { file ->
                        act { client.setFilePriority(hash, listOf(file.index), if (file.skipped) 1 else 0) }
                    }
                    DetailTab.TRACKERS -> trackers(detail.trackers)
                    DetailTab.PEERS -> peers(detail.peers)
                }
            }
        }
    }

    val d = loader.data?.detail
    if (moving && d != null) {
        FolderPicker(
            client,
            start = d.props.savePath,
            title = "Déplacer vers…",
            onPick = { path ->
                moving = false
                act("Déplacement lancé") { client.setLocation(hash, path) }
            },
            onDismiss = { moving = false },
        )
    }
    if (deleting && d != null) {
        DeleteTorrentDialog(
            d.torrent,
            onConfirm = { withFiles ->
                deleting = false
                act { client.delete(hash, withFiles) }
            },
            onDismiss = { deleting = false },
        )
    }
}

@Composable
private fun Header(
    d: Detail,
    onToggle: () -> Unit,
    onRecheck: () -> Unit,
    onReannounce: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit,
) {
    val t = d.torrent
    Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SelectionContainer { Text(t.name, style = MaterialTheme.typography.titleMedium) }
        LinearProgressIndicator(
            progress = { t.progress.toFloat() },
            modifier = Modifier.fillMaxWidth(),
            color = if (t.errored) MaterialTheme.colorScheme.error else ProgressIndicatorDefaults.linearColor,
        )
        val line = buildList {
            add(t.stateLabel)
            add(percent(t.progress))
            if (t.dlSpeed > 0) add("↓ ${speed(t.dlSpeed)}")
            if (t.upSpeed > 0) add("↑ ${speed(t.upSpeed)}")
            if (!t.finished && !t.paused) duration(t.eta)?.let { add("reste $it") }
        }
        Text(
            line.joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = if (t.errored) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(
                onClick = onToggle,
                label = { Text(if (t.paused) "Reprendre" else "Pause") },
                leadingIcon = { Icon(if (t.paused) Icons.Filled.PlayArrow else AppIcons.Pause, null, Modifier.size(18.dp)) },
            )
            AssistChip(onClick = onRecheck, label = { Text("Vérifier") }, leadingIcon = { Icon(Icons.Filled.Refresh, null, Modifier.size(18.dp)) })
            AssistChip(onClick = onReannounce, label = { Text("Réannoncer") })
            AssistChip(onClick = onMove, label = { Text("Déplacer") }, leadingIcon = { Icon(AppIcons.Folder, null, Modifier.size(18.dp)) })
            AssistChip(
                onClick = onDelete,
                label = { Text("Supprimer") },
                leadingIcon = { Icon(Icons.Filled.Delete, null, Modifier.size(18.dp)) },
            )
        }
    }
}

// ---- Onglets ----

private fun LazyListScope.infos(d: Detail) {
    val p = d.props
    val t = d.torrent
    val rows = listOfNotNull(
        "Dossier" to p.savePath,
        "Taille" to bytes(t.size),
        "Catégorie" to t.category.ifBlank { null },
        "Ratio" to "%.2f".format(Locale.FRANCE, t.ratio),
        "Envoyé" to "${bytes(p.uploaded)} (session : ${bytes(p.uploadedSession)})",
        "Reçu" to "${bytes(p.downloaded)} (session : ${bytes(p.downloadedSession)})",
        ("Gaspillé" to bytes(p.wasted)).takeIf { p.wasted > 0 },
        "Sources" to "${p.seeds} connectées sur ${p.seedsTotal}",
        "Pairs" to "${p.peers} connectés sur ${p.peersTotal}",
        "Connexions" to p.connections.toString(),
        "Vitesse moyenne" to "↓ ${speed(p.dlAvg)}   ↑ ${speed(p.upAvg)}",
        "Actif depuis" to duration(p.elapsed),
        "En partage depuis" to duration(p.seedingTime),
        "Ajouté le" to date(p.addedOn),
        "Terminé le" to date(p.completedOn),
        "Créé le" to date(p.createdOn),
        "Pièces" to "${p.piecesHave} / ${p.piecesNum} × ${bytes(p.pieceSize)}",
        "Privé" to p.isPrivate?.let { if (it) "Oui" else "Non" },
        "Créé par" to p.createdBy.ifBlank { null },
        "Commentaire" to p.comment.ifBlank { null },
        "Empreinte" to t.hash,
    ).mapNotNull { (k, v) -> v?.let { k to it } }

    item {
        Card(Modifier.fillMaxWidth()) {
            SelectionContainer {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    rows.forEach { (label, value) ->
                        Column {
                            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(value, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }
}

private fun LazyListScope.files(files: List<TorrentFile>, onToggle: (TorrentFile) -> Unit) {
    if (files.isEmpty()) item { Empty("Fichiers pas encore connus (métadonnées en cours)") }
    if (files.size > 1) item {
        Text(
            "Décocher un fichier pour ne pas le télécharger.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    items(files.sortedBy { it.name.lowercase() }, key = { it.index }) { f ->
        Card(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    val folder = f.name.substringBeforeLast('/', "")
                    Text(
                        f.name.substringAfterLast('/'),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        color = if (f.skipped) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    )
                    if (folder.isNotBlank()) {
                        Text(folder, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    LinearProgressIndicator(progress = { f.progress.toFloat() }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
                    Text(
                        if (f.skipped) "Ignoré · ${bytes(f.size)}" else "${percent(f.progress)} · ${bytes(f.size)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (files.size > 1) Checkbox(checked = !f.skipped, onCheckedChange = { onToggle(f) })
            }
        }
    }
}

private fun LazyListScope.trackers(trackers: List<Tracker>) {
    if (trackers.isEmpty()) item { Empty("Aucun tracker (DHT uniquement)") }
    items(trackers, key = { it.url }) { tr ->
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(tr.url, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    tr.statusLabel,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = when (tr.status) {
                        2 -> MaterialTheme.colorScheme.primary
                        4 -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                // -1 = inconnu
                val counts = listOf("sources" to tr.seeds, "pairs" to tr.peers, "téléchargeurs" to tr.leeches)
                    .filter { it.second >= 0 }.joinToString(" · ") { "${it.second} ${it.first}" }
                if (counts.isNotBlank()) Text(counts, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (tr.message.isNotBlank()) Text(tr.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun LazyListScope.peers(peers: List<Peer>) {
    if (peers.isEmpty()) item { Empty("Aucun pair connecté") }
    items(peers, key = { it.address }) { p ->
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(p.client.ifBlank { "Client inconnu" }, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(percent(p.progress), style = MaterialTheme.typography.bodySmall)
                }
                val line = listOfNotNull(
                    p.country.ifBlank { null },
                    p.address,
                    "↓ ${speed(p.dlSpeed)}".takeIf { p.dlSpeed > 0 },
                    "↑ ${speed(p.upSpeed)}".takeIf { p.upSpeed > 0 },
                )
                Text(line.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Confirmation de suppression, avec option « fichiers aussi » */
@Composable
fun DeleteTorrentDialog(t: Torrent, onConfirm: (withFiles: Boolean) -> Unit, onDismiss: () -> Unit) {
    var withFiles by remember(t.hash) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
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
        confirmButton = { TextButton(onClick = { onConfirm(withFiles) }) { Text("Supprimer") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}
