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

data class TorrentProps(
    val savePath: String,
    val addedOn: Long,
    val completedOn: Long,
    val createdOn: Long,
    val seedingTime: Long,
    val elapsed: Long,
    val uploaded: Long,
    val downloaded: Long,
    val uploadedSession: Long,
    val downloadedSession: Long,
    val wasted: Long,
    val seeds: Int,
    val seedsTotal: Int,
    val peers: Int,
    val peersTotal: Int,
    val connections: Int,
    val dlAvg: Long,
    val upAvg: Long,
    val piecesHave: Int,
    val piecesNum: Int,
    val pieceSize: Long,
    val comment: String,
    val createdBy: String,
    val isPrivate: Boolean?,
)

data class TorrentFile(val index: Int, val name: String, val size: Long, val progress: Double, val priority: Int) {
    val skipped get() = priority == 0
}

data class Tracker(val url: String, val status: Int, val seeds: Int, val peers: Int, val leeches: Int, val message: String) {
    val statusLabel get() = when (status) {
        0 -> "Désactivé"
        1 -> "Pas encore contacté"
        2 -> "Fonctionne"
        3 -> "Mise à jour…"
        4 -> "Ne fonctionne pas"
        else -> "État $status"
    }
}

data class Peer(val address: String, val client: String, val country: String, val progress: Double, val dlSpeed: Long, val upSpeed: Long)

data class Category(val name: String, val savePath: String)

/** Torrent à ajouter : lien (magnet ou URL d'un .torrent) ou fichier */
class NewTorrent(
    val link: String?,
    val file: FilePart?,
    val savePath: String,
    val category: String,
    val paused: Boolean,
)

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

    private fun call(path: String, form: Map<String, String>? = null, multipart: Multipart? = null): String {
        if (cookies[sessionKey] == null) login()
        val method = if (form != null || multipart != null) "POST" else "GET"
        var r = Http.request("$base$path", method, headers(), form, multipart)
        if (r.code == 403 || r.code == 401) { // session expirée
            login()
            r = Http.request("$base$path", method, headers(), form, multipart)
        }
        return Http.check(r)
    }

    fun version(): String = call("/api/v2/app/version").trim()

    fun torrents(): List<Torrent> = parseTorrents(call("/api/v2/torrents/info?sort=added_on&reverse=true"))

    /** Un torrent, null s'il a été supprimé */
    fun torrent(hash: String): Torrent? = parseTorrents(call("/api/v2/torrents/info?hashes=$hash")).firstOrNull()

    private fun parseTorrents(json: String): List<Torrent> {
        val a = JSONArray(json)
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

    fun properties(hash: String): TorrentProps {
        val o = JSONObject(call("/api/v2/torrents/properties?hash=$hash"))
        return TorrentProps(
            savePath = o.optString("save_path"),
            addedOn = o.optLong("addition_date"),
            completedOn = o.optLong("completion_date"),
            createdOn = o.optLong("creation_date"),
            seedingTime = o.optLong("seeding_time"),
            elapsed = o.optLong("time_elapsed"),
            uploaded = o.optLong("total_uploaded"),
            downloaded = o.optLong("total_downloaded"),
            uploadedSession = o.optLong("total_uploaded_session"),
            downloadedSession = o.optLong("total_downloaded_session"),
            wasted = o.optLong("total_wasted"),
            seeds = o.optInt("seeds"),
            seedsTotal = o.optInt("seeds_total"),
            peers = o.optInt("peers"),
            peersTotal = o.optInt("peers_total"),
            connections = o.optInt("nb_connections"),
            dlAvg = o.optLong("dl_speed_avg"),
            upAvg = o.optLong("up_speed_avg"),
            piecesHave = o.optInt("pieces_have"),
            piecesNum = o.optInt("pieces_num"),
            pieceSize = o.optLong("piece_size"),
            comment = o.optString("comment"),
            createdBy = o.optString("created_by"),
            isPrivate = if (o.has("is_private")) o.optBoolean("is_private") else null,
        )
    }

    fun files(hash: String): List<TorrentFile> {
        val a = JSONArray(call("/api/v2/torrents/files?hash=$hash"))
        return (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            TorrentFile(o.optInt("index", i), o.optString("name"), o.optLong("size"), o.optDouble("progress", 0.0), o.optInt("priority", 1))
        }
    }

    /** Trackers réels (sans les pseudo-trackers DHT, PeX et LSD) */
    fun trackers(hash: String): List<Tracker> {
        val a = JSONArray(call("/api/v2/torrents/trackers?hash=$hash"))
        return (0 until a.length()).map { a.getJSONObject(it) }
            .filterNot { it.optString("url").startsWith("** [") }
            .map { Tracker(it.optString("url"), it.optInt("status"), it.optInt("num_seeds"), it.optInt("num_peers"), it.optInt("num_leeches"), it.optString("msg")) }
    }

    fun peers(hash: String): List<Peer> {
        val o = JSONObject(call("/api/v2/sync/torrentPeers?hash=$hash&rid=0")).optJSONObject("peers") ?: return emptyList()
        return o.keys().asSequence().mapNotNull { k ->
            val p = o.optJSONObject(k) ?: return@mapNotNull null
            Peer(k, p.optString("client"), p.optString("country"), p.optDouble("progress", 0.0), p.optLong("dl_speed"), p.optLong("up_speed"))
        }.sortedByDescending { it.dlSpeed + it.upSpeed }.toList()
    }

    fun recheck(hash: String) { call("/api/v2/torrents/recheck", mapOf("hashes" to hash)) }
    fun reannounce(hash: String) { call("/api/v2/torrents/reannounce", mapOf("hashes" to hash)) }
    fun setLocation(hash: String, path: String) { call("/api/v2/torrents/setLocation", mapOf("hashes" to hash, "location" to path)) }

    /** Priorité de fichiers : 0 = ne pas télécharger, 1 = normale */
    fun setFilePriority(hash: String, indexes: List<Int>, priority: Int) {
        call("/api/v2/torrents/filePrio", mapOf("hash" to hash, "id" to indexes.joinToString("|"), "priority" to priority.toString()))
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

    fun defaultSavePath(): String = call("/api/v2/app/defaultSavePath").trim()

    /** Catégories, avec leur dossier rendu absolu (un chemin relatif part du dossier par défaut) */
    fun categories(defaultPath: String): List<Category> {
        val o = JSONObject(call("/api/v2/torrents/categories"))
        return o.keys().asSequence().map { name ->
            val sp = o.optJSONObject(name)?.optString("savePath").orEmpty().trim()
            Category(name, if (sp.isBlank() || sp.startsWith("/")) sp else joinPath(defaultPath, sp))
        }.sortedBy { it.name.lowercase() }.toList()
    }

    /** Sous-dossiers d'un dossier de la seedbox (qBittorrent 5+), chemins absolus triés */
    fun directories(path: String): List<String> {
        val body = try {
            call("/api/v2/app/getDirectoryContent?dirPath=${Http.enc(path)}&mode=dirs")
        } catch (e: HttpException) {
            when (e.code) {
                405 -> call("/api/v2/app/getDirectoryContent", mapOf("dirPath" to path, "mode" to "dirs"))
                404 -> throw HttpException(404, "Dossier introuvable : $path")
                else -> throw e
            }
        }
        val a = JSONArray(body)
        return (0 until a.length()).map { i ->
            val p = a.getString(i).trimEnd('/')
            if (p.startsWith("/")) p else joinPath(path, p)
        }.sortedBy { it.lowercase() }
    }

    fun add(t: NewTorrent) {
        val fields = buildMap {
            t.link?.let { put("urls", it) }
            if (t.savePath.isNotBlank()) {
                put("savepath", t.savePath)
                put("autoTMM", "false") // sinon la catégorie impose son dossier
            }
            if (t.category.isNotBlank()) put("category", t.category)
            put("paused", t.paused.toString())  // qBittorrent 4
            put("stopped", t.paused.toString()) // qBittorrent 5
        }
        val body = call("/api/v2/torrents/add", multipart = Multipart(fields, listOfNotNull(t.file)))
        if (body.trim() == "Fails.") throw HttpException(409, "Torrent refusé : déjà présent ou invalide")
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

fun joinPath(dir: String, name: String) = dir.trimEnd('/') + "/" + name.trimStart('/')

/** Dossier parent, null à la racine */
fun parentPath(path: String): String? =
    if (path == "/" || path.isBlank()) null else path.trimEnd('/').substringBeforeLast('/').ifBlank { "/" }
