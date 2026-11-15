package fr.florian.seedbox

import org.json.JSONArray
import org.json.JSONObject

data class Torrent(
    val hash: String,
    val name: String,
    val state: String,
    val progress: Double,
    val size: Long,
    val downloaded: Long,
    val dlSpeed: Long,
    val upSpeed: Long,
    val ratio: Double,
    val eta: Long,
    val category: String,
) {
    /** qBittorrent 4 : pausedDL/pausedUP, qBittorrent 5 : stoppedDL/stoppedUP */
    val paused get() = state.startsWith("paused") || state.startsWith("stopped")
    val finished get() = progress >= 1.0
    val errored get() = state == "error" || state == "missingFiles"

    val stateLabel get() = when (state) {
        "downloading", "forcedDL" -> "Téléchargement"
        "metaDL", "forcedMetaDL" -> "Métadonnées"
        "stalledDL" -> "En attente de pairs"
        "uploading", "forcedUP" -> "Partage"
        "stalledUP" -> "Partagé"
        "pausedDL", "stoppedDL" -> "En pause"
        "pausedUP", "stoppedUP" -> "Terminé"
        "queuedDL", "queuedUP" -> "En file"
        "checkingDL", "checkingUP", "checkingResumeData" -> "Vérification"
        "moving" -> "Déplacement"
        "error" -> "Erreur"
        "missingFiles" -> "Fichiers manquants"
        else -> state
    }
}

data class Transfer(val dlSpeed: Long, val upSpeed: Long, val freeSpace: Long?, val globalRatio: String?)

/** WebUI API v2 de qBittorrent : connexion par cookie de session */
class QBittorrent(private val cfg: ServiceConfig) {
    private val base = cfg.url.asBaseUrl()
    private val sessionKey = "$base|${cfg.user}"

    private fun headers(): Map<String, String> = buildMap {
        // Sans Referer/Origin identiques à l'hôte, qBittorrent refuse les requêtes (protection CSRF)
        put("Referer", base)
        put("Origin", base)
        cookies[sessionKey]?.let { put("Cookie", it) }
    }

    private fun login() {
        cookies.remove(sessionKey)
        val r = Http.request("$base/api/v2/auth/login", "POST", headers(), mapOf("username" to cfg.user, "password" to cfg.secret))
        Http.check(r)
        if (r.body.trim() == "Fails.") throw HttpException(401, "Identifiant ou mot de passe qBittorrent refusé")
        // Le nom du cookie varie selon les versions (SID, QBT_SID_<port>) : on garde tous les couples nom=valeur
        val jar = r.cookies.map { it.substringBefore(';').trim() }.filter { it.contains('=') }
        if (jar.isNotEmpty()) cookies[sessionKey] = jar.joinToString("; ")
    }

    private fun call(path: String, form: Map<String, String>? = null): String {
        if (cookies[sessionKey] == null) login()
        val method = if (form != null) "POST" else "GET"
        var r = Http.request("$base$path", method, headers(), form)
        if (r.code == 403 || r.code == 401) { // session expirée
            login()
            r = Http.request("$base$path", method, headers(), form)
        }
        return Http.check(r)
    }

    fun version(): String = call("/api/v2/app/version").trim()

    fun torrents(): List<Torrent> {
        val a = JSONArray(call("/api/v2/torrents/info?sort=added_on&reverse=true"))
        return (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            Torrent(
                hash = o.getString("hash"),
                name = o.optString("name"),
                state = o.optString("state"),
                progress = o.optDouble("progress", 0.0),
                size = o.optLong("size"),
                downloaded = o.optLong("downloaded"),
                dlSpeed = o.optLong("dlspeed"),
                upSpeed = o.optLong("upspeed"),
                ratio = o.optDouble("ratio", 0.0),
                eta = o.optLong("eta"),
                category = o.optString("category"),
            )
        }
    }

    fun transfer(): Transfer {
        val o = JSONObject(call("/api/v2/transfer/info"))
        return Transfer(o.optLong("dl_info_speed"), o.optLong("up_info_speed"), null, null)
    }

    /** Vitesses + espace libre et ratio global (sync/maindata, plus lourd : pour l'écran État) */
    fun serverState(): Transfer {
        val s = JSONObject(call("/api/v2/sync/maindata")).optJSONObject("server_state") ?: JSONObject()
        return Transfer(
            dlSpeed = s.optLong("dl_info_speed"),
            upSpeed = s.optLong("up_info_speed"),
            freeSpace = if (s.has("free_space_on_disk")) s.optLong("free_space_on_disk") else null,
            globalRatio = s.optString("global_ratio").toDoubleOrNull()?.let { "%.2f".format(java.util.Locale.FRANCE, it) },
        )
    }

    fun pause(hash: String) = action("stop", "pause", hash)
    fun resume(hash: String) = action("start", "resume", hash)

    fun delete(hash: String, deleteFiles: Boolean) {
        call("/api/v2/torrents/delete", mapOf("hashes" to hash, "deleteFiles" to deleteFiles.toString()))
    }

    /** qBittorrent 5 a renommé pause/resume en stop/start : on essaie le nouveau nom, puis l'ancien */
    private fun action(v5: String, v4: String, hash: String) {
        try {
            call("/api/v2/torrents/$v5", mapOf("hashes" to hash))
        } catch (e: HttpException) {
            if (e.code != 404) throw e
            call("/api/v2/torrents/$v4", mapOf("hashes" to hash))
        }
    }

    companion object {
        /** Cookies de session partagés entre les écrans, par serveur + utilisateur */
        private val cookies = java.util.concurrent.ConcurrentHashMap<String, String>()
    }
}
