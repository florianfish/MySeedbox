package fr.florian.seedbox

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
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
    /** Un chargement est en cours (premier, manuel ou périodique) */
    var loading by mutableStateOf(false)
        private set
    private var job: Job? = null

    fun refresh(manual: Boolean = false) {
        if (manual) manualRefresh = true
        if (job?.isActive == true) return
        loading = true
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
                loading = false
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
fun <T> LoadedList(
    loader: Loader<T>,
    bottomSpace: Dp = 12.dp,
    state: LazyListState = rememberLazyListState(),
    content: LazyListScope.(T) -> Unit,
) {
    PullToRefreshBox(
        isRefreshing = loader.manualRefresh,
        onRefresh = { loader.refresh(manual = true) },
        modifier = Modifier.fillMaxSize(),
    ) {
        LazyColumn(
            Modifier.fillMaxSize(),
            state = state,
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

/** État vide : icône facultative, message et indication */
@Composable
fun Empty(text: String, icon: ImageVector? = null, hint: String? = null) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        icon?.let {
            Icon(it, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
        }
        Text(
            text,
            style = MaterialTheme.typography.titleSmall,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        hint?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Barre de progression plate (sans le point de fin ni l'espace de Material 3) */
@Composable
fun Bar(progress: Float, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary) {
    LinearProgressIndicator(
        progress = { progress.coerceIn(0f, 1f) },
        modifier = modifier.fillMaxWidth().height(6.dp),
        color = color,
        strokeCap = StrokeCap.Round,
        gapSize = 0.dp,
        drawStopIndicator = {},
    )
}

/** Étiquette secondaire (catégorie…) : contour seulement, lisible sur le fond d'une carte */
@Composable
fun OutlinedPill(text: String) {
    Surface(
        shape = RoundedCornerShape(50),
        color = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Text(text, Modifier.padding(horizontal = 8.dp, vertical = 1.dp), style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}

/** Petite étiquette arrondie (état, catégorie…) */
@Composable
fun Pill(
    text: String,
    container: Color = MaterialTheme.colorScheme.secondaryContainer,
    content: Color = MaterialTheme.colorScheme.onSecondaryContainer,
) {
    Surface(shape = RoundedCornerShape(50), color = container, contentColor = content) {
        Text(text, Modifier.padding(horizontal = 8.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}

/** Icône de chaque service, pour reconnaître film / série d'un coup d'œil */
val Service.icon: ImageVector
    get() = when (this) {
        Service.QBIT -> AppIcons.Download
        Service.RADARR -> AppIcons.Movie
        Service.SONARR -> AppIcons.Tv
        Service.PROWLARR -> AppIcons.Search
    }

/** Écran affiché quand le service n'est pas encore réglé */
@Composable
fun NotConfigured(what: String, onOpenSettings: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Filled.Settings, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
        Spacer(Modifier.height(12.dp))
        Text("$what n'est pas configuré", style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(4.dp))
        Text(
            "Renseigne son adresse et sa clé dans les réglages.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
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

fun speed(n: Long) = if (n <= 0) "0 Ko/s" else bytes(n) + "/s"

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
    val Movie = icon("Movie", "M18 4l2 4h-3l-2-4h-2l2 4h-3l-2-4H8l2 4H5L3 4H2v14c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V4h-4z")
    val Storage = icon("Storage", "M2 20h20v-4H2v4zm2-3h2v2H4v-2zM2 4v4h20V4H2zm4 3H4V5h2v2zm-4 7h20v-4H2v4zm2-3h2v2H4v-2z")
    val Tv = icon("Tv", "M21 3H3c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h5v2h8v-2h5c1.1 0 1.99-.9 1.99-2L23 5c0-1.1-.9-2-2-2zm0 14H3V5h18v12z")
    val Search = icon(
        "Search",
        "M15.5 14h-.79l-.28-.27C15.41 12.59 16 11.11 16 9.5 16 5.91 13.09 3 9.5 3S3 5.91 3 9.5 5.91 16 9.5 16c1.61 0 3.09-.59 4.23-1.57l.27.28v.79l5 4.99L20.49 19l-4.99-5zm-6 0C7.01 14 5 11.99 5 9.5S7.01 5 9.5 5 14 7.01 14 9.5 11.99 14 9.5 14z",
    )
    val ArrowDown = icon("ArrowDown", "M20 12l-1.41-1.41L13 16.17V4h-2v12.17l-5.58-5.59L4 12l8 8 8-8z")
    val ArrowUp = icon("ArrowUp", "M4 12l1.41 1.41L11 7.83V20h2V7.83l5.58 5.59L20 12l-8-8-8 8z")
    val ExpandMore = icon("ExpandMore", "M16.59 8.59L12 13.17 7.41 8.59 6 10l6 6 6-6z")
    val Campaign = icon(
        "Campaign",
        "M18 11v2h4v-2h-4zm-2 6.61c.96.71 2.21 1.65 3.2 2.39.4-.53.8-1.07 1.2-1.6-.99-.74-2.24-1.68-3.2-2.4-.4.54-.8 1.08-1.2 1.61zM20.4 5.6c-.4-.53-.8-1.07-1.2-1.6-.99.74-2.24 1.68-3.2 2.4.4.53.8 1.07 1.2 1.6.96-.72 2.21-1.65 3.2-2.4zM4 9c-1.1 0-2 .9-2 2v2c0 1.1.9 2 2 2h1v4h2v-4h1l5 3V6L8 9H4zm11.5 3c0-1.33-.58-2.53-1.5-3.35v6.69c.92-.81 1.5-2.01 1.5-3.34z",
    )
    val Sort = icon("Sort", "M3 18h6v-2H3v2zM3 6v2h18V6H3zm0 7h12v-2H3v2z")
    val Label = icon(
        "Label",
        "M17.63 5.84C17.27 5.33 16.67 5 16 5L5 5.01C3.9 5.01 3 5.9 3 7v10c0 1.1.9 1.99 2 1.99L16 19c.67 0 1.27-.33 1.63-.84L22 12l-4.37-6.16z",
    )
    val Inbox = icon(
        "Inbox",
        "M19 3H4.99c-1.11 0-1.98.89-1.98 2L3 19c0 1.1.88 2 1.99 2H19c1.1 0 2-.9 2-2V5c0-1.11-.9-2-2-2zm0 12h-4c0 1.66-1.35 3-3 3s-3-1.34-3-3H4.99V5H19v10z",
    )
}
