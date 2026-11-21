package fr.florian.seedbox

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private const val PAGE = 50

/** Niveau minimal affiché ; la valeur est celle du paramètre `level` de l'API */
private enum class LogLevel(val label: String, val key: String) {
    ERROR("Erreurs", "error"), WARN("Avertissements", "warn"), INFO("Tout", "info"),
}

/** Journaux de Radarr / Sonarr, ouverts depuis leur carte dans l'onglet État */
@Composable
fun LogsScreen(service: Service, cfg: ServiceConfig, onOpenSettings: () -> Unit) {
    if (!cfg.configured) return NotConfigured(service.label, onOpenSettings)
    LogsList(remember(service, cfg) { Arr(service, cfg) })
}

/** Liste des journaux d'un *arr (aussi utilisée par l'onglet Journaux de Prowlarr) */
@Composable
fun LogsList(arr: Arr) {
    var level by rememberSaveable { mutableStateOf(LogLevel.ERROR) }
    // « Afficher plus » agrandit la page : un rafraîchissement recharge tout ce qui est affiché
    var count by rememberSaveable { mutableIntStateOf(PAGE) }
    val loader = rememberLoader(arr, level) { arr.logs(level.key, count) }
    // Entrées dépliées (message complet et exception)
    var open by rememberSaveable { mutableStateOf(emptyList<Int>()) }

    LoadedList(loader) { page ->
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LogLevel.entries.forEach { l ->
                    FilterChip(
                        selected = level == l,
                        onClick = { if (level != l) { level = l; count = PAGE; open = emptyList() } },
                        label = { Text(l.label) },
                    )
                }
            }
        }
        if (page.entries.isEmpty()) {
            item {
                Empty(
                    when (level) {
                        LogLevel.ERROR -> "Aucune erreur dans le journal"
                        LogLevel.WARN -> "Aucun avertissement dans le journal"
                        LogLevel.INFO -> "Journal vide"
                    },
                    AppIcons.Inbox,
                    "${arr.service.label} efface ses journaux au bout de quelques jours",
                )
            }
            return@LoadedList
        }
        items(page.entries, key = { it.id }) { e ->
            val expanded = e.id in open
            LogCard(e, expanded, onClick = { open = if (expanded) open - e.id else open + e.id })
        }
        if (page.total > page.entries.size) item {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                OutlinedButton(
                    onClick = { count += PAGE; loader.refresh() },
                    enabled = !loader.loading,
                ) {
                    Text(if (loader.loading) "Chargement…" else "Afficher plus (${page.entries.size} sur ${page.total})")
                }
            }
        }
    }
}

@Composable
private fun LogCard(e: LogEntry, expanded: Boolean, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val context = LocalContext.current
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                LevelPill(e.level)
                e.logger?.let {
                    Text(
                        it,
                        Modifier.weight(1f, fill = false),
                        style = MaterialTheme.typography.labelSmall,
                        color = c.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.weight(1f))
                e.time?.let { Text(logTime(it), style = MaterialTheme.typography.labelSmall, color = c.onSurfaceVariant) }
            }
            if (!expanded) {
                Text(e.message, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                if (e.exception != null) {
                    Text(
                        "${e.exceptionType ?: "Exception"} · toucher pour afficher",
                        style = MaterialTheme.typography.bodySmall,
                        color = c.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            } else {
                SelectionContainer {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(e.message, style = MaterialTheme.typography.bodyMedium)
                        e.exception?.let {
                            Surface(color = c.surfaceVariant, shape = MaterialTheme.shapes.small) {
                                Text(
                                    it,
                                    Modifier.fillMaxWidth().padding(8.dp),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                )
                            }
                        }
                    }
                }
                Row {
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { copy(context, e) }) { Text("Copier") }
                }
            }
        }
    }
}

@Composable
private fun LevelPill(level: String) {
    val c = MaterialTheme.colorScheme
    when (level) {
        "fatal" -> Pill("Fatal", c.error, c.onError)
        "error" -> Pill("Erreur", c.errorContainer, c.onErrorContainer)
        "warn" -> Pill("Avertissement", c.tertiaryContainer, c.onTertiaryContainer)
        "info" -> OutlinedPill("Info")
        else -> OutlinedPill(level.replaceFirstChar { it.titlecase(Locale.FRANCE) })
    }
}

private val SECONDS = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.FRANCE)

/** « aujourd'hui à 22:14:05 » : la seconde aide à rapprocher des entrées voisines */
private fun logTime(i: Instant) = dateTime(i).substringBeforeLast(" à ") + " à " +
    i.atZone(ZoneId.systemDefault()).format(SECONDS)

/** Message, puis exception, dans le presse-papiers (pour un rapport de bug ou un forum) */
private fun copy(context: Context, e: LogEntry) {
    val text = listOfNotNull(
        listOfNotNull(e.time?.toString(), e.level.uppercase(Locale.ROOT), e.logger).joinToString(" | "),
        e.message,
        e.exception,
    ).joinToString("\n")
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    clipboard.setPrimaryClip(ClipData.newPlainText("Journal", text))
    // Android 13+ affiche déjà sa propre confirmation
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) Toast.makeText(context, "Copié", Toast.LENGTH_SHORT).show()
}
