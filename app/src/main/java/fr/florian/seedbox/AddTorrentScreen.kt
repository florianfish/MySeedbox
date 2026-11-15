package fr.florian.seedbox

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

/** Torrent reçu d'une autre appli : lien magnet / URL, ou fichier .torrent */
data class Incoming(val link: String? = null, val fileUri: Uri? = null)

private fun displayName(context: Context, uri: Uri): String =
    runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "fichier.torrent"

@Composable
fun AddTorrentScreen(cfg: ServiceConfig, incoming: Incoming?, onDone: () -> Unit, onOpenSettings: () -> Unit) {
    if (!cfg.configured) return NotConfigured("qBittorrent", onOpenSettings)
    val client = remember(cfg) { QBittorrent(cfg) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var link by rememberSaveable { mutableStateOf(incoming?.link.orEmpty()) }
    var fileUri by rememberSaveable { mutableStateOf(incoming?.fileUri) }
    // Nouveau torrent reçu alors que l'écran est déjà ouvert (une rotation ne le réapplique pas : incoming est alors null)
    LaunchedEffect(incoming) {
        incoming?.let { link = it.link.orEmpty(); fileUri = it.fileUri }
    }
    val fileName = remember(fileUri) { fileUri?.let { displayName(context, it) } }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) fileUri = uri
    }

    // Dossier par défaut et catégories de qBittorrent
    val meta = rememberLoader(client) {
        val def = client.defaultSavePath()
        def to client.categories(def)
    }
    var category by rememberSaveable { mutableStateOf("") }
    var customPath by rememberSaveable { mutableStateOf<String?>(null) } // null = dossier de la catégorie
    val suggested = meta.data?.let { (def, cats) ->
        cats.firstOrNull { it.name == category }?.savePath?.ifBlank { null } ?: def
    }.orEmpty()
    val dest = customPath ?: suggested
    var paused by rememberSaveable { mutableStateOf(false) }
    var browsing by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val linkOk = link.trim().let { it.startsWith("magnet:") || it.startsWith("http://") || it.startsWith("https://") }
    val ready = (fileUri != null || linkOk) && !sending

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // ---- Source ----
        Text("Torrent", style = MaterialTheme.typography.titleMedium)
        if (fileUri != null) {
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(fileName.orEmpty(), Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    IconButton(onClick = { fileUri = null }) { Icon(Icons.Filled.Clear, "Retirer le fichier") }
                }
            }
        } else {
            OutlinedTextField(
                link, { link = it; error = null },
                Modifier.fillMaxWidth(),
                label = { Text("Lien magnet ou URL du .torrent") },
                minLines = 2,
                maxLines = 4,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                isError = link.isNotBlank() && !linkOk,
            )
            OutlinedButton(onClick = { picker.launch(arrayOf("application/x-bittorrent", "application/octet-stream")) }) {
                Text("Choisir un fichier .torrent")
            }
        }

        // ---- Catégorie ----
        val cats = meta.data?.second.orEmpty()
        if (cats.isNotEmpty()) {
            Text("Catégorie", style = MaterialTheme.typography.titleMedium)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = category == "", onClick = { category = ""; customPath = null }, label = { Text("Aucune") })
                cats.forEach { c ->
                    FilterChip(selected = category == c.name, onClick = { category = c.name; customPath = null }, label = { Text(c.name) })
                }
            }
            if (category.isNotBlank()) {
                Text(
                    "Radarr et Sonarr suivent leur catégorie : un ajout manuel y apparaîtra dans Imports, à importer à la main.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // ---- Destination ----
        Text("Destination", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            dest, { customPath = it },
            Modifier.fillMaxWidth(),
            label = { Text("Dossier sur la seedbox") },
            supportingText = { Text("Un dossier qui n'existe pas encore sera créé") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = meta.data != null, onClick = { browsing = true }) { Text("Parcourir…") }
            if (customPath != null) TextButton(onClick = { customPath = null }) { Text("Dossier par défaut") }
        }
        meta.error?.let { ErrorCard(it) }

        // ---- Options ----
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Ajouter en pause", Modifier.weight(1f))
            Switch(checked = paused, onCheckedChange = { paused = it })
        }

        error?.let { ErrorCard(it) }
        Button(
            enabled = ready,
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                sending = true
                error = null
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            val file = fileUri?.let { uri ->
                                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                                    ?: throw IOException("Fichier .torrent illisible")
                                FilePart("torrents", fileName ?: "fichier.torrent", "application/x-bittorrent", bytes)
                            }
                            client.add(NewTorrent(if (file == null) link.trim() else null, file, dest.trim(), category, paused))
                        }
                        Toast.makeText(context, "Torrent ajouté", Toast.LENGTH_SHORT).show()
                        onDone()
                    } catch (e: Exception) {
                        error = e.friendly()
                    } finally {
                        sending = false
                    }
                }
            },
        ) { Text(if (sending) "Ajout…" else "Ajouter") }
    }

    if (browsing) {
        FolderPicker(
            client,
            start = dest.ifBlank { meta.data?.first.orEmpty() },
            onPick = { customPath = it; browsing = false },
            onDismiss = { browsing = false },
        )
    }
}

/** Navigateur de dossiers de la seedbox, plein écran */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FolderPicker(client: QBittorrent, start: String, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    var current by remember { mutableStateOf(start.trimEnd('/').ifBlank { "/" }) }
    val loader = rememberLoader(current) { client.directories(current) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.safeDrawingPadding()) {
                TopAppBar(
                    title = { Text("Choisir un dossier") },
                    navigationIcon = { IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, "Fermer") } },
                )
                Text(
                    current,
                    Modifier.padding(horizontal = 16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(vertical = 8.dp)) {
                    parentPath(current)?.let { parent ->
                        item {
                            ListItem(
                                headlineContent = { Text("Dossier parent") },
                                leadingContent = { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) },
                                modifier = Modifier.clickable { current = parent },
                            )
                        }
                    }
                    loader.error?.let { item { Box(Modifier.padding(12.dp)) { ErrorCard(it) } } }
                    val dirs = loader.data
                    when {
                        dirs == null && loader.error == null -> item {
                            Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                        }
                        dirs != null && dirs.isEmpty() -> item { Empty("Aucun sous-dossier") }
                        dirs != null -> items(dirs, key = { it }) { dir ->
                            ListItem(
                                headlineContent = { Text(dir.substringAfterLast('/'), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                leadingContent = { Icon(AppIcons.Folder, null) },
                                modifier = Modifier.clickable { current = dir },
                            )
                        }
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                ) {
                    TextButton(onClick = onDismiss) { Text("Annuler") }
                    Button(enabled = loader.error == null, onClick = { onPick(current) }) { Text("Choisir ce dossier") }
                }
            }
        }
    }
}
