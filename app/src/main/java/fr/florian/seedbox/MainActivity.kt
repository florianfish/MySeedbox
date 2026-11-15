package fr.florian.seedbox

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.IntentCompat

class MainActivity : ComponentActivity() {
    /** Torrent reçu par un lien magnet, un fichier ouvert ou un partage */
    private var incoming by mutableStateOf<Incoming?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val settings = Settings(this)
        if (savedInstanceState == null) incoming = intent.toIncoming()
        setContent { AppTheme { App(settings, incoming, onIncomingHandled = { incoming = null }) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.toIncoming()?.let { incoming = it }
    }
}

/**
 * Seuls les fichiers content:// sont acceptés : une autre appli pourrait sinon faire lire à celle-ci
 * un de ses fichiers privés (file:///data/data/…) et l'envoyer à qBittorrent.
 */
private fun Uri.asTorrentFile(): Incoming? = if (scheme == "content") Incoming(fileUri = this) else null

private fun Intent.toIncoming(): Incoming? = when (action) {
    Intent.ACTION_VIEW -> data?.let { if (it.scheme == "magnet") Incoming(link = it.toString()) else it.asTorrentFile() }
    Intent.ACTION_SEND -> {
        val text = getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        val link = Regex("""magnet:\?\S+""").find(text)?.value
            ?: text.trim().takeIf { it.startsWith("http://") || it.startsWith("https://") }
        link?.let { Incoming(link = it) }
            ?: IntentCompat.getParcelableExtra(this, Intent.EXTRA_STREAM, Uri::class.java)?.asTorrentFile()
    }
    else -> null
}

@Composable
private fun AppTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

private enum class Tab(val label: String, val icon: ImageVector) {
    TORRENTS("Torrents", AppIcons.Download),
    QUEUE("Imports", Icons.AutoMirrored.Filled.List),
    CALENDAR("Calendrier", Icons.Filled.DateRange),
    STATUS("État", Icons.Filled.Info),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun App(settings: Settings, incoming: Incoming?, onIncomingHandled: () -> Unit) {
    var configs by remember { mutableStateOf(settings.all()) }
    var tab by rememberSaveable { mutableStateOf(Tab.TORRENTS) }
    // Premier lancement : rien de réglé, on ouvre directement les réglages
    var showSettings by rememberSaveable { mutableStateOf(configs.values.none { it.configured }) }
    var showAdd by rememberSaveable { mutableStateOf(false) }
    var detailHash by rememberSaveable { mutableStateOf<String?>(null) }
    val openSettings = { showAdd = false; showSettings = true }
    // Un torrent arrive d'une autre appli : écran d'ajout
    LaunchedEffect(incoming) { if (incoming != null) { showSettings = false; showAdd = true } }
    val closeAdd = { showAdd = false; onIncomingHandled() }
    val main = !showSettings && !showAdd && detailHash == null

    BackHandler(enabled = showSettings) { showSettings = false }
    BackHandler(enabled = showAdd) { closeAdd() }
    BackHandler(enabled = detailHash != null && !showAdd && !showSettings) { detailHash = null }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            when {
                                showSettings -> "Réglages"
                                showAdd -> "Ajouter un torrent"
                                detailHash != null -> "Torrent"
                                else -> "Seedbox"
                            }
                        )
                        if (main) {
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "v${BuildConfig.VERSION_NAME}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                navigationIcon = {
                    if (!main) IconButton(onClick = {
                        when {
                            showAdd -> closeAdd()
                            showSettings -> showSettings = false
                            else -> detailHash = null
                        }
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour")
                    }
                },
                actions = {
                    if (main) IconButton(onClick = openSettings) { Icon(Icons.Filled.Settings, "Réglages") }
                },
            )
        },
        bottomBar = {
            if (main) NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = { Icon(t.icon, null) },
                        label = { Text(t.label) },
                    )
                }
            }
        },
        floatingActionButton = {
            if (main && tab == Tab.TORRENTS) FloatingActionButton(onClick = { showAdd = true }) {
                Icon(Icons.Filled.Add, "Ajouter un torrent")
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).consumeWindowInsets(padding).imePadding()) {
            if (showAdd) {
                AddTorrentScreen(
                    configs.getValue(Service.QBIT),
                    incoming,
                    onDone = { closeAdd(); tab = Tab.TORRENTS },
                    onOpenSettings = openSettings,
                )
            } else if (showSettings) {
                SettingsScreen(
                    configs,
                    onSave = { s, c ->
                        settings.set(s, c)
                        configs = settings.all()
                    },
                    onImport = { imported ->
                        imported.forEach { (s, c) -> settings.set(s, c) }
                        configs = settings.all()
                    },
                )
            } else if (detailHash != null) {
                TorrentDetailScreen(configs.getValue(Service.QBIT), detailHash!!, onClose = { detailHash = null })
            } else when (tab) {
                Tab.TORRENTS -> TorrentsScreen(configs.getValue(Service.QBIT), openSettings, onOpen = { detailHash = it })
                Tab.QUEUE -> QueueScreen(configs, openSettings)
                Tab.CALENDAR -> CalendarScreen(configs, openSettings)
                Tab.STATUS -> StatusScreen(configs, openSettings)
            }
        }
    }
}
