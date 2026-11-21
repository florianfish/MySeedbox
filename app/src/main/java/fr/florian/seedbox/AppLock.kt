package fr.florian.seedbox

import android.content.Context
import android.os.SystemClock
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/**
 * Verrouillage de l'appli par empreinte / visage, avec repli sur le code de l'appareil.
 * Actif par défaut ; sans verrouillage d'écran configuré sur le téléphone, il est ignoré
 * (sinon l'appli resterait inaccessible).
 */
class AppLock(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("seedbox", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean("lock.enabled", true)
        set(v) = prefs.edit().putBoolean("lock.enabled", v).apply()

    /** Délai en arrière-plan avant de redemander le déverrouillage, en secondes */
    var delay: Int
        get() = prefs.getInt("lock.delay", DEFAULT_DELAY)
        set(v) = prefs.edit().putInt("lock.delay", v).apply()

    /** Empreinte, visage ou code / schéma / mot de passe de l'appareil disponible */
    val available get() = BiometricManager.from(app).canAuthenticate(AUTHENTICATORS) == BiometricManager.BIOMETRIC_SUCCESS

    val active get() = enabled && available

    /** Verrouillé (état du processus : survit à une rotation, reverrouille après sa mort) */
    val locked get() = state.locked

    /** Erreur du dernier essai (hors annulation), affichée sur l'écran de verrouillage */
    val error get() = state.error

    /** L'activité passe en arrière-plan (hors rotation et hors invite système de déverrouillage) */
    fun onStop(changingConfigurations: Boolean) {
        if (!changingConfigurations && !state.authenticating) state.backgroundSince = SystemClock.elapsedRealtime()
    }

    fun onStart() {
        // Retour de l'invite (code de l'appareil sur Android < 11) ou invite abandonnée en quittant l'appli
        state.authenticating = false
        if (state.backgroundSince == 0L) return
        val away = (SystemClock.elapsedRealtime() - state.backgroundSince) / 1000
        state.backgroundSince = 0L
        if (active && away >= delay) lock()
    }

    /** Verrou désactivé dans les réglages ou plus de verrouillage d'écran : on libère l'appli */
    fun refresh() {
        if (!active) { state.locked = false; state.error = null }
    }

    private fun lock() {
        state.locked = true
        state.prompted = false
        state.error = null
    }

    /** Invite automatique, une seule fois par verrouillage (pas de boucle si l'utilisateur annule) */
    fun autoPrompt(activity: FragmentActivity) {
        if (state.locked && !state.prompted) prompt(activity)
    }

    fun prompt(activity: FragmentActivity) {
        // Une invite déjà ouverte n'est pas dupliquée par BiometricPrompt
        if (!state.locked) return
        if (!active) { refresh(); return }
        state.prompted = true
        state.authenticating = true
        state.error = null
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                state.authenticating = false
                state.locked = false
            }

            override fun onAuthenticationError(code: Int, message: CharSequence) {
                state.authenticating = false
                val cancelled = code == BiometricPrompt.ERROR_USER_CANCELED ||
                    code == BiometricPrompt.ERROR_CANCELED ||
                    code == BiometricPrompt.ERROR_NEGATIVE_BUTTON
                if (!cancelled) state.error = message.toString()
            }
            // onAuthenticationFailed (doigt non reconnu) : l'invite système reste ouverte et l'indique elle-même
        }
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Déverrouiller MySeedbox")
            .setSubtitle("Empreinte, visage ou code de l'appareil")
            .setAllowedAuthenticators(AUTHENTICATORS)
            .build()
        BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), callback).authenticate(info)
    }

    private class State(lockedAtStart: Boolean) {
        var locked by mutableStateOf(lockedAtStart)
        var error by mutableStateOf<String?>(null)
        var backgroundSince = 0L
        var authenticating = false
        var prompted = false
    }

    /** Partagé par toutes les instances du processus : verrouillé au lancement si le verrou est actif */
    private val state = shared ?: State(active).also { shared = it }

    companion object {
        private var shared: State? = null

        // BIOMETRIC_WEAK + DEVICE_CREDENTIAL : la seule combinaison acceptée d'Android 8 à 14
        private const val AUTHENTICATORS = BIOMETRIC_WEAK or DEVICE_CREDENTIAL
        const val DEFAULT_DELAY = 60

        /** Choix proposés dans les réglages : délai en secondes → libellé */
        val DELAYS = listOf(0 to "Immédiatement", 60 to "1 minute", 300 to "5 minutes", 900 to "15 minutes")
    }
}

/** Écran affiché par-dessus l'appli tant qu'elle est verrouillée */
@Composable
fun LockScreen(error: String?, onUnlock: () -> Unit) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(Icons.Filled.Lock, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(16.dp))
            Text("MySeedbox est verrouillée", style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
            error?.let {
                Spacer(Modifier.height(8.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.height(24.dp))
            Button(onClick = onUnlock) { Text("Déverrouiller") }
        }
    }
}

/** Section « Verrouillage » des réglages */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LockSection(lock: AppLock) {
    var enabled by remember { mutableStateOf(lock.enabled) }
    var delay by remember { mutableIntStateOf(lock.delay) }
    val available = remember { lock.available }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Verrouillage", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (available) "Empreinte, visage ou code de l'appareil pour ouvrir l'appli"
                        else "Aucun verrouillage d'écran configuré sur le téléphone : le verrou est inactif",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (available) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                    )
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = {
                        enabled = it
                        lock.enabled = it
                        lock.refresh()
                    },
                )
            }
            if (enabled && available) {
                Text("Redemander après", style = MaterialTheme.typography.labelMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppLock.DELAYS.forEach { (seconds, label) ->
                        FilterChip(
                            selected = delay == seconds,
                            onClick = { delay = seconds; lock.delay = seconds },
                            label = { Text(label) },
                        )
                    }
                }
            }
        }
    }
}
