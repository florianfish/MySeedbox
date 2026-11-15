package fr.florian.seedbox

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

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val settings = Settings(this)
        setContent { AppTheme { App(settings) } }
    }
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
private fun App(settings: Settings) {
    var configs by remember { mutableStateOf(settings.all()) }
    var tab by rememberSaveable { mutableStateOf(Tab.TORRENTS) }
    // Premier lancement : rien de réglé, on ouvre directement les réglages
    var showSettings by rememberSaveable { mutableStateOf(configs.values.none { it.configured }) }
    val openSettings = { showSettings = true }

    BackHandler(enabled = showSettings) { showSettings = false }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(if (showSettings) "Réglages" else "Seedbox")
                        if (!showSettings) {
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
                    if (showSettings) IconButton(onClick = { showSettings = false }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour")
                    }
                },
                actions = {
                    if (!showSettings) IconButton(onClick = openSettings) { Icon(Icons.Filled.Settings, "Réglages") }
                },
            )
        },
        bottomBar = {
            if (!showSettings) NavigationBar {
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
    ) { padding ->
        Box(Modifier.padding(padding).consumeWindowInsets(padding).imePadding()) {
            if (showSettings) {
                SettingsScreen(configs) { s, c ->
                    settings.set(s, c)
                    configs = settings.all()
                }
            } else when (tab) {
                Tab.TORRENTS -> TorrentsScreen(configs.getValue(Service.QBIT), openSettings)
                Tab.QUEUE -> QueueScreen(configs, openSettings)
                Tab.CALENDAR -> CalendarScreen(configs, openSettings)
                Tab.STATUS -> StatusScreen(configs, openSettings)
            }
        }
    }
}
