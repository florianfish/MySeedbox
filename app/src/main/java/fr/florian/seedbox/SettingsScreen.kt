package fr.florian.seedbox

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(configs: Map<Service, ServiceConfig>, onSave: (Service, ServiceConfig) -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "Adresse complète de chaque service, avec le port et, s'il y en a une, l'URL de base " +
                "(ex. https://cluster1k.seedbox.fr:45338). Les mêmes valeurs que dans Jellyseerr conviennent. " +
                "Clés API et mot de passe sont chiffrés sur le téléphone.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Service.entries.forEach { s ->
            ServiceForm(s, configs[s] ?: ServiceConfig(), onSave)
        }
    }
}

@Composable
private fun ServiceForm(service: Service, saved: ServiceConfig, onSave: (Service, ServiceConfig) -> Unit) {
    var url by remember(saved) { mutableStateOf(saved.url) }
    var user by remember(saved) { mutableStateOf(saved.user) }
    var secret by remember(saved) { mutableStateOf(saved.secret) }
    var showSecret by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var testing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val current = ServiceConfig(url, user, secret)
    val dirty = current != saved

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(service.label, style = MaterialTheme.typography.titleMedium)
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
                    onClick = {
                        testing = true
                        scope.launch {
                            result = try {
                                val v = withContext(Dispatchers.IO) {
                                    if (service == Service.QBIT) QBittorrent(current).version() else Arr(service, current).version()
                                }
                                true to "Connecté : ${service.label} ${v.removePrefix("v")}"
                            } catch (e: Exception) {
                                false to e.friendly()
                            }
                            testing = false
                        }
                    },
                ) { Text(if (testing) "Test…" else "Tester") }
                Button(enabled = dirty, onClick = { onSave(service, current) }) { Text("Enregistrer") }
            }
        }
    }
}
