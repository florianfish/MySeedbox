package fr.florian.seedbox

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

@Composable
fun SettingsScreen(
    configs: Map<Service, ServiceConfig>,
    onSave: (Service, ServiceConfig) -> Unit,
    onImport: (Map<Service, ServiceConfig>) -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "Mêmes adresses que dans Jellyseerr (avec le port et l'éventuelle URL de base). " +
                "Clés API et mot de passe sont chiffrés sur le téléphone.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Service.entries.forEach { s ->
            // key : l'état mémorisé (replié / déplié) reste attaché à son service
            key(s) { ServiceForm(s, configs[s] ?: ServiceConfig(), onSave) }
        }
        BackupSection(configs, onImport)
        Text(
            "MySeedbox ${BuildConfig.VERSION_NAME}",
            Modifier.fillMaxWidth().padding(vertical = 8.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** Partie hôte de l'adresse, pour le résumé d'un service replié */
private fun String.host(): String = trim().substringAfter("://").trimEnd('/')

@Composable
private fun ServiceForm(service: Service, saved: ServiceConfig, onSave: (Service, ServiceConfig) -> Unit) {
    var url by remember(saved) { mutableStateOf(saved.url) }
    var user by remember(saved) { mutableStateOf(saved.user) }
    var secret by remember(saved) { mutableStateOf(saved.secret) }
    var showSecret by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var testing by remember { mutableStateOf(false) }
    // Service déjà réglé : replié, pour voir tous les services d'un coup d'œil
    var expanded by rememberSaveable { mutableStateOf(!saved.configured) }
    val scope = rememberCoroutineScope()
    val current = ServiceConfig(url, user, secret)
    val dirty = current != saved

    fun test(cfg: ServiceConfig) {
        testing = true
        scope.launch {
            result = try {
                val v = withContext(Dispatchers.IO) {
                    if (service == Service.QBIT) QBittorrent(cfg).version() else Arr(service, cfg).version()
                }
                true to "Connecté : ${service.label} ${v.removePrefix("v")}"
            } catch (e: Exception) {
                false to e.friendly()
            }
            testing = false
        }
    }

    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(service.icon, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(service.label, style = MaterialTheme.typography.titleMedium)
                Text(
                    if (saved.configured) saved.url.host() else "Non configuré",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // Résultat du dernier test, visible même replié
            result?.let { (ok, _) ->
                Icon(
                    if (ok) Icons.Filled.CheckCircle else Icons.Filled.Warning,
                    if (ok) "Connecté" else "Échec de connexion",
                    Modifier.padding(end = 8.dp).size(20.dp),
                    tint = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
            }
            Icon(AppIcons.ExpandMore, if (expanded) "Replier" else "Déplier", Modifier.rotate(if (expanded) 180f else 0f))
        }
        if (expanded) Column(
            Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                url, { url = it; result = null },
                Modifier.fillMaxWidth(),
                label = { Text("Adresse") },
                placeholder = { Text("https://cluster1k.seedbox.fr:port") },
                isError = url.trim().startsWith("http://", ignoreCase = true),
                supportingText = if (url.trim().startsWith("http://", ignoreCase = true)) {
                    { Text("HTTP non chiffré : clé et mot de passe passeraient en clair. Utiliser https://") }
                } else null,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
            if (!service.usesApiKey) {
                OutlinedTextField(
                    user, { user = it; result = null },
                    Modifier.fillMaxWidth(),
                    label = { Text("Utilisateur") },
                    singleLine = true,
                )
            }
            OutlinedTextField(
                secret, { secret = it; result = null },
                Modifier.fillMaxWidth(),
                label = { Text(if (service.usesApiKey) "Clé API" else "Mot de passe") },
                supportingText = if (service.usesApiKey) {
                    { Text("${service.label} : Paramètres › Général › Sécurité › Clé API") }
                } else null,
                singleLine = true,
                visualTransformation = if (showSecret) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    IconButton(onClick = { showSecret = !showSecret }) {
                        Icon(AppIcons.Visibility, if (showSecret) "Masquer" else "Afficher")
                    }
                },
            )
            result?.let { (ok, msg) ->
                Text(
                    msg,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(
                    enabled = current.configured && !testing,
                    onClick = { test(current) },
                ) { Text(if (testing) "Test…" else "Tester") }
                // Enregistrer teste aussi la connexion, pour savoir tout de suite si c'est bon
                Button(enabled = dirty && !testing, onClick = {
                    onSave(service, current)
                    if (current.configured) test(current)
                }) { Text("Enregistrer") }
            }
        }
    }
}

// ======================= Sauvegarde de la configuration =======================

@Composable
private fun BackupSection(configs: Map<Service, ServiceConfig>, onImport: (Map<Service, ServiceConfig>) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<Pair<Boolean, String>?>(null) }

    // Export : mot de passe d'abord, puis choix de l'emplacement (Google Drive, téléphone…)
    var askExport by remember { mutableStateOf(false) }
    var exportPassword by remember { mutableStateOf<CharArray?>(null) }
    var exporting by remember { mutableStateOf(false) }
    val createDoc = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(Backup.MIME)) { uri: Uri? ->
        val pwd = exportPassword
        exportPassword = null
        if (uri == null || pwd == null) {
            pwd?.fill('\u0000')
            return@rememberLauncherForActivityResult
        }
        exporting = true
        status = null
        scope.launch {
            status = try {
                withContext(Dispatchers.IO) {
                    val bytes = Backup.encrypt(configs, pwd)
                    context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                        ?: throw IOException("Impossible d'écrire le fichier")
                }
                true to "Configuration exportée et chiffrée"
            } catch (e: Exception) {
                false to e.friendly()
            } finally {
                pwd.fill('\u0000')
                exporting = false
            }
        }
    }

    // Import : choix du fichier, vérification, mot de passe, puis confirmation
    var envelope by remember { mutableStateOf<Backup.Envelope?>(null) }
    var decrypting by remember { mutableStateOf(false) }
    var importError by remember { mutableStateOf<String?>(null) }
    var decrypted by remember { mutableStateOf<Map<Service, ServiceConfig>?>(null) }
    val openDoc = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        status = null
        scope.launch {
            try {
                envelope = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { Backup.read(it) }
                        ?: throw IOException("Fichier illisible")
                }
            } catch (e: Exception) {
                status = false to e.friendly()
            }
        }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Sauvegarde", style = MaterialTheme.typography.titleMedium)
            Text(
                "Exporte adresses, clés API et mot de passe qBittorrent dans un fichier chiffré par un mot de passe, " +
                    "à enregistrer où tu veux (Google Drive…). Sans ce mot de passe, la sauvegarde est inutilisable.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (exporting) LinearProgressIndicator(Modifier.fillMaxWidth())
            status?.let { (ok, msg) ->
                Text(
                    msg,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    enabled = !exporting && configs.values.any { it.url.isNotBlank() },
                    onClick = { askExport = true },
                ) { Text("Exporter…") }
                OutlinedButton(enabled = !exporting, onClick = { openDoc.launch(arrayOf("*/*")) }) { Text("Importer…") }
            }
        }
    }

    if (askExport) {
        PasswordDialog(
            title = "Exporter la configuration",
            intro = "Choisis un mot de passe pour chiffrer la sauvegarde et range-le dans ton gestionnaire de mots de passe : " +
                "il n'est enregistré nulle part et sera demandé à l'import.",
            newPassword = true,
            busy = false,
            error = null,
            onConfirm = { pwd ->
                exportPassword?.fill('\u0000')
                exportPassword = pwd
                askExport = false
                createDoc.launch(Backup.suggestedName())
            },
            onDismiss = { askExport = false },
        )
    }

    val env = envelope
    if (env != null && decrypted == null) {
        PasswordDialog(
            title = "Importer une configuration",
            intro = "Mot de passe choisi lors de l'export.",
            newPassword = false,
            busy = decrypting,
            error = importError,
            onConfirm = { pwd ->
                decrypting = true
                importError = null
                scope.launch {
                    try {
                        // PBKDF2 : calcul volontairement lent (une à quelques secondes)
                        decrypted = withContext(Dispatchers.Default) { Backup.decrypt(env, pwd) }
                    } catch (e: Exception) {
                        importError = e.friendly()
                    } finally {
                        pwd.fill('\u0000')
                        decrypting = false
                    }
                }
            },
            onDismiss = { envelope = null; importError = null },
        )
    }

    decrypted?.let { found ->
        val close = { decrypted = null; envelope = null }
        AlertDialog(
            onDismissRequest = close,
            title = { Text("Remplacer la configuration ?") },
            text = {
                Text(
                    "La sauvegarde contient : ${found.keys.sortedBy { it.ordinal }.joinToString(", ") { it.label }}. " +
                        "Les réglages actuels de ces services seront remplacés."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onImport(found)
                    close()
                    status = true to "Configuration importée"
                }) { Text("Remplacer") }
            },
            dismissButton = { TextButton(onClick = close) { Text("Annuler") } },
        )
    }
}

@Composable
private fun PasswordDialog(
    title: String,
    intro: String,
    newPassword: Boolean,
    busy: Boolean,
    error: String?,
    onConfirm: (CharArray) -> Unit,
    onDismiss: () -> Unit,
) {
    var pwd by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var show by remember { mutableStateOf(false) }
    val tooShort = newPassword && pwd.length < Backup.MIN_PASSWORD
    val mismatch = newPassword && confirm.isNotEmpty() && confirm != pwd
    val ready = pwd.isNotEmpty() && !tooShort && (!newPassword || confirm == pwd) && !busy
    val transformation = if (show) VisualTransformation.None else PasswordVisualTransformation()

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(intro, style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    pwd, { pwd = it },
                    Modifier.fillMaxWidth(),
                    label = { Text("Mot de passe") },
                    singleLine = true,
                    enabled = !busy,
                    visualTransformation = transformation,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    isError = newPassword && pwd.isNotEmpty() && tooShort,
                    supportingText = if (newPassword) {
                        { Text("${Backup.MIN_PASSWORD} caractères minimum") }
                    } else null,
                    trailingIcon = {
                        IconButton(onClick = { show = !show }) {
                            Icon(AppIcons.Visibility, if (show) "Masquer" else "Afficher")
                        }
                    },
                )
                if (newPassword) {
                    OutlinedTextField(
                        confirm, { confirm = it },
                        Modifier.fillMaxWidth(),
                        label = { Text("Confirmer le mot de passe") },
                        singleLine = true,
                        visualTransformation = transformation,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        isError = mismatch,
                        supportingText = if (mismatch) {
                            { Text("Les deux mots de passe sont différents") }
                        } else null,
                    )
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(enabled = ready, onClick = { onConfirm(pwd.toCharArray()) }) {
                Text(if (newPassword) "Continuer" else "Déchiffrer")
            }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Annuler") } },
    )
}
