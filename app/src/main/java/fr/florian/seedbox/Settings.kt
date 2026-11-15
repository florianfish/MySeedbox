package fr.florian.seedbox

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

enum class Service(val label: String, val usesApiKey: Boolean) {
    QBIT("qBittorrent", false),
    RADARR("Radarr", true),
    SONARR("Sonarr", true),
    PROWLARR("Prowlarr", true),
}

/** Réglages d'un service. secret = clé API (*arr) ou mot de passe (qBittorrent) */
data class ServiceConfig(val url: String = "", val user: String = "", val secret: String = "") {
    val configured get() = url.isNotBlank() && secret.isNotBlank()
}

/** Adresses en clair dans les préférences, secrets chiffrés avec une clé du Keystore Android */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("seedbox", Context.MODE_PRIVATE)

    fun get(s: Service) = ServiceConfig(
        url = prefs.getString("${s.name}.url", "") ?: "",
        user = prefs.getString("${s.name}.user", "") ?: "",
        secret = Crypto.decrypt(prefs.getString("${s.name}.secret", null)) ?: "",
    )

    fun set(s: Service, c: ServiceConfig) {
        prefs.edit()
            .putString("${s.name}.url", c.url.trim())
            .putString("${s.name}.user", c.user.trim())
            .putString("${s.name}.secret", Crypto.encrypt(c.secret.trim()))
            .apply()
    }

    fun all() = Service.entries.associateWith { get(it) }
}

private object Crypto {
    private const val ALIAS = "seedbox-secrets"
    private const val IV = 12

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return gen.generateKey()
    }

    fun encrypt(plain: String): String {
        if (plain.isEmpty()) return ""
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        return Base64.encodeToString(c.iv + c.doFinal(plain.toByteArray()), Base64.NO_WRAP)
    }

    /** null si absent ou illisible (clé du Keystore perdue) : le secret est alors à ressaisir */
    fun decrypt(stored: String?): String? {
        if (stored.isNullOrEmpty()) return null
        return runCatching {
            val b = Base64.decode(stored, Base64.NO_WRAP)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, b, 0, IV))
            String(c.doFinal(b, IV, b.size - IV))
        }.getOrNull()
    }
}
