package fr.florian.seedbox

import android.util.Base64
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.security.SecureRandom
import java.time.Instant
import java.time.LocalDate
import javax.crypto.BadPaddingException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class BackupException(message: String) : Exception(message)

/**
 * Sauvegarde de la configuration (adresses, utilisateur, clés API, mot de passe qBittorrent),
 * entièrement chiffrée par un mot de passe choisi à l'export :
 * PBKDF2-HMAC-SHA256 (600 000 itérations, sel aléatoire) → clé AES-256 → AES-GCM.
 * GCM authentifie aussi l'en-tête (format, version, itérations) : un fichier modifié est refusé.
 * Le mot de passe n'est enregistré nulle part.
 */
object Backup {
    private const val FORMAT = "myseedbox-backup"
    private const val VERSION = 1
    private const val ITERATIONS = 600_000
    const val MIN_PASSWORD = 10
    const val MIME = "application/octet-stream"
    private const val MAX_SIZE = 1_000_000

    fun suggestedName() = "MySeedbox-${LocalDate.now()}.seedbox"

    class Envelope(val iterations: Int, val salt: ByteArray, val iv: ByteArray, val data: ByteArray)

    fun encrypt(configs: Map<Service, ServiceConfig>, password: CharArray): ByteArray {
        val services = JSONObject()
        configs.filterValues { it.url.isNotBlank() || it.secret.isNotBlank() }.forEach { (s, c) ->
            services.put(s.name, JSONObject().put("url", c.url).put("user", c.user).put("secret", c.secret))
        }
        val plain = JSONObject()
            .put("services", services)
            .put("exportedAt", Instant.now().toString())
            .toString().toByteArray()

        val random = SecureRandom()
        val salt = ByteArray(16).also { random.nextBytes(it) }
        val iv = ByteArray(12).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, deriveKey(password, salt, ITERATIONS), GCMParameterSpec(128, iv))
        cipher.updateAAD(aad(VERSION, ITERATIONS))
        val data = cipher.doFinal(plain)

        return JSONObject()
            .put("format", FORMAT)
            .put("version", VERSION)
            .put("kdf", "PBKDF2WithHmacSHA256")
            .put("iterations", ITERATIONS)
            .put("salt", b64(salt))
            .put("iv", b64(iv))
            .put("data", b64(data))
            .toString(2)
            .toByteArray()
    }

    /** Lit et vérifie l'en-tête, sans déchiffrer (le mot de passe est demandé ensuite) */
    fun read(input: InputStream): Envelope {
        val o = try {
            JSONObject(readLimited(input).decodeToString())
        } catch (e: JSONException) {
            throw BackupException("Ce fichier n'est pas une sauvegarde MySeedbox")
        }
        if (o.optString("format") != FORMAT) throw BackupException("Ce fichier n'est pas une sauvegarde MySeedbox")
        val version = o.optInt("version")
        if (version > VERSION) throw BackupException("Sauvegarde faite par une version plus récente de l'appli : mettre l'appli à jour")
        if (version != VERSION || o.optString("kdf") != "PBKDF2WithHmacSHA256") throw BackupException("Sauvegarde invalide")
        val iterations = o.optInt("iterations")
        if (iterations !in 100_000..10_000_000) throw BackupException("Sauvegarde invalide")
        return try {
            Envelope(iterations, unb64(o.getString("salt")), unb64(o.getString("iv")), unb64(o.getString("data")))
        } catch (e: Exception) {
            throw BackupException("Sauvegarde invalide ou incomplète")
        }
    }

    fun decrypt(env: Envelope, password: CharArray): Map<Service, ServiceConfig> {
        val plain = try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, deriveKey(password, env.salt, env.iterations), GCMParameterSpec(128, env.iv))
            cipher.updateAAD(aad(VERSION, env.iterations))
            cipher.doFinal(env.data)
        } catch (e: BadPaddingException) { // AEADBadTagException : mauvais mot de passe ou fichier altéré
            throw BackupException("Mot de passe incorrect, ou fichier modifié")
        }
        val services = JSONObject(plain.decodeToString()).optJSONObject("services") ?: JSONObject()
        val result = Service.entries.mapNotNull { s ->
            services.optJSONObject(s.name)?.let { c ->
                s to ServiceConfig(c.optString("url"), c.optString("user"), c.optString("secret"))
            }
        }.toMap()
        if (result.isEmpty()) throw BackupException("La sauvegarde ne contient aucun service")
        return result
    }

    private fun deriveKey(password: CharArray, salt: ByteArray, iterations: Int): SecretKey {
        val spec = PBEKeySpec(password, salt, iterations, 256)
        try {
            val raw = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            return SecretKeySpec(raw, "AES")
        } finally {
            spec.clearPassword()
        }
    }

    private fun aad(version: Int, iterations: Int) = "$FORMAT:$version:$iterations".toByteArray()

    /** Une sauvegarde fait quelques Ko : au-delà de 1 Mo, ce n'en est pas une */
    private fun readLimited(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > MAX_SIZE) throw BackupException("Fichier trop gros pour être une sauvegarde MySeedbox")
        }
        return out.toByteArray()
    }

    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
    private fun unb64(s: String) = Base64.decode(s, Base64.NO_WRAP)
}
