package fr.florian.seedbox

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/** Chargement d'une donnée sur Dispatchers.IO, avec erreur et rafraîchissement manuel ou périodique */
class Loader<T>(private val scope: CoroutineScope, private val fetch: suspend () -> T) {
    var data by mutableStateOf<T?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    /** Rafraîchissement demandé par l'utilisateur (indicateur de « tirer pour actualiser ») */
    var manualRefresh by mutableStateOf(false)
        private set
    private var job: Job? = null

    fun refresh(manual: Boolean = false) {
        if (manual) manualRefresh = true
        if (job?.isActive == true) return
        job = scope.launch {
            try {
                data = withContext(Dispatchers.IO) { fetch() }
                error = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.friendly()
            } finally {
                manualRefresh = false
            }
        }
    }
}

/** Charge à l'affichage, puis toutes les [autoRefreshMs] ms tant que l'appli est au premier plan */
@Composable
fun <T> rememberLoader(vararg keys: Any?, autoRefreshMs: Long = 0, fetch: suspend () -> T): Loader<T> {
    val scope = rememberCoroutineScope()
    val loader = remember(*keys) { Loader(scope, fetch) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(loader) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                loader.refresh()
                if (autoRefreshMs <= 0) break
                delay(autoRefreshMs)
            }
        }
    }
    return loader
}

/** Liste avec « tirer pour actualiser », chargement et message d'erreur */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> LoadedList(loader: Loader<T>, bottomSpace: Dp = 12.dp, content: LazyListScope.(T) -> Unit) {
    PullToRefreshBox(
        isRefreshing = loader.manualRefresh,
        onRefresh = { loader.refresh(manual = true) },
        modifier = Modifier.fillMaxSize(),
    ) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 12.dp, top = 12.dp, end = 12.dp, bottom = bottomSpace),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            loader.error?.let { item { ErrorCard(it) } }
            val d = loader.data
            if (d != null) content(d)
            else if (loader.error == null) item {
                Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }
        }
    }
}

@Composable
fun ErrorCard(message: String) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Text(
            message,
            Modifier.fillMaxWidth().padding(12.dp),
            color = MaterialTheme.colorScheme.onErrorContainer,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
fun Empty(text: String) {
    Text(
        text,
        Modifier.fillMaxWidth().padding(32.dp),
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Écran affiché quand le service n'est pas encore réglé */
@Composable
fun NotConfigured(what: String, onOpenSettings: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("$what n'est pas configuré", style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        Button(onClick = onOpenSettings) { Text("Ouvrir les réglages") }
    }
}

// ---- Mise en forme ----

private val UNITS = listOf("o", "Ko", "Mo", "Go", "To", "Po")

fun bytes(n: Long): String {
    if (n < 1024) return "$n o"
    var v = n.toDouble()
    var i = 0
    while (v >= 1024 && i < UNITS.lastIndex) { v /= 1024; i++ }
    return String.format(Locale.FRANCE, if (v < 10) "%.1f %s" else "%.0f %s", v, UNITS[i])
}

fun speed(n: Long) = if (n <= 0) "0" else bytes(n) + "/s"

/** Durée en secondes ; qBittorrent renvoie 8640000 pour « inconnue » */
fun duration(seconds: Long): String? = when {
    seconds <= 0 || seconds >= 8_640_000 -> null
    seconds < 60 -> "< 1 min"
    seconds < 3600 -> "${seconds / 60} min"
    seconds < 86_400 -> "${seconds / 3600} h %02d".format(seconds % 3600 / 60)
    else -> "${seconds / 86_400} j ${seconds % 86_400 / 3600} h"
}

fun percent(p: Double) = "${(p * 100).toInt()} %"

// ---- Icônes absentes de material-icons-core ----

private fun icon(name: String, path: String) = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
    .addPath(addPathNodes(path), fill = SolidColor(Color.Black))
    .build()

object AppIcons {
    val Pause = icon("Pause", "M6 19h4V5H6v14zm8-14v14h4V5h-4z")
    val Download = icon("Download", "M19 9h-4V3H9v6H5l7 7 7-7zM5 18v2h14v-2H5z")
    val Visibility = icon(
        "Visibility",
        "M12 4.5C7 4.5 2.73 7.61 1 12c1.73 4.39 6 7.5 11 7.5s9.27-3.11 11-7.5c-1.73-4.39-6-7.5-11-7.5zM12 17c-2.76 0-5-2.24-5-5s2.24-5 5-5 5 2.24 5 5-2.24 5-5 5zm0-8c-1.66 0-3 1.34-3 3s1.34 3 3 3 3-1.34 3-3-1.34-3-3-3z",
    )
    val Folder = icon("Folder", "M10 4H4c-1.1 0-1.99.9-1.99 2L2 18c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V8c0-1.1-.9-2-2-2h-8l-2-2z")
    val Storage = icon("Storage", "M2 20h20v-4H2v4zm2-3h2v2H4v-2zM2 4v4h20V4H2zm4 3H4V5h2v2zm-4 7h20v-4H2v4zm2-3h2v2H4v-2z")
}
