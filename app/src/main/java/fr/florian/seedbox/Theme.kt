package fr.florian.seedbox

import android.content.Context
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

enum class ThemeMode(val label: String) { SYSTEM("Système"), LIGHT("Clair"), DARK("Sombre") }

/** Apparence choisie dans les réglages (thème clair / sombre, couleurs du fond d'écran) */
class Appearance(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("seedbox", Context.MODE_PRIVATE)

    var mode by mutableStateOf(
        runCatching { ThemeMode.valueOf(prefs.getString("theme.mode", null) ?: "") }.getOrDefault(ThemeMode.SYSTEM)
    )
        private set

    /** Couleurs tirées du fond d'écran (Material You), Android 12+ seulement */
    var dynamic by mutableStateOf(prefs.getBoolean("theme.dynamic", true))
        private set

    fun chooseMode(m: ThemeMode) {
        mode = m
        prefs.edit().putString("theme.mode", m.name).apply()
    }

    fun chooseDynamic(v: Boolean) {
        dynamic = v
        prefs.edit().putBoolean("theme.dynamic", v).apply()
    }

    @Composable
    fun isDark(): Boolean = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    companion object {
        val dynamicSupported = Build.VERSION.SDK_INT >= 31
    }
}

@Composable
fun AppTheme(dark: Boolean, dynamic: Boolean, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val scheme = when {
        dynamic && Appearance.dynamicSupported -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

/** Section « Apparence » des réglages */
@Composable
fun AppearanceSection(appearance: Appearance) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Thème", style = MaterialTheme.typography.titleMedium)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                ThemeMode.entries.forEachIndexed { i, m ->
                    SegmentedButton(
                        selected = appearance.mode == m,
                        onClick = { appearance.chooseMode(m) },
                        shape = SegmentedButtonDefaults.itemShape(i, ThemeMode.entries.size),
                        label = { Text(m.label) },
                    )
                }
            }
            if (Appearance.dynamicSupported) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Couleurs du fond d'écran", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Material You : couleurs assorties au fond d'écran du téléphone",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Switch(checked = appearance.dynamic, onCheckedChange = { appearance.chooseDynamic(it) })
                }
            }
        }
    }
}
