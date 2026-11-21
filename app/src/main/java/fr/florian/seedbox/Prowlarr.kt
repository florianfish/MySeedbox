package fr.florian.seedbox

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.temporal.ChronoUnit

/** Indexeur Prowlarr, avec la ressource complète (renvoyée telle quelle pour l'activer ou le tester) */
class ProwlarrIndexer(
    val id: Int,
    val name: String,
    val enabled: Boolean,
    /** « torrent » ou « usenet » */
    val protocol: String,
    /** « public », « private » ou « semiPrivate » */
    val privacy: String?,
    val priority: Int,
    /** Désactivé par Prowlarr après des échecs, jusqu'à cette date */
    val disabledTill: Instant?,
    internal val raw: JSONObject,
) {
    val failing get() = disabledTill?.isAfter(Instant.now()) == true
}

class IndexerStat(
    val indexerId: Int,
    val name: String,
    val queries: Int,
    val rss: Int,
    val grabs: Int,
    val failedQueries: Int,
    val failedGrabs: Int,
    /** Temps de réponse moyen des requêtes, en ms (0 si inconnu) */
    val responseMs: Int,
) {
    val failures get() = failedQueries + failedGrabs
}

class ProwlarrEvent(
    val date: Instant,
    val grab: Boolean,
    val successful: Boolean,
    val indexer: String?,
    /** Titre récupéré (grab) ou termes de la recherche */
    val title: String?,
    /** Application à l'origine : « Sonarr », « Radarr »… */
    val source: String?,
    val results: Int?,
    val elapsedMs: Int?,
)

class ProwlarrApp(val name: String, val kind: String?, val syncLevel: String)

/** API v1 de Prowlarr (écran dédié) ; l'onglet État utilise Arr pour le résumé */
class Prowlarr(cfg: ServiceConfig) {
    private val base = cfg.url.asBaseUrl() + "/api/v1"
    private val headers = mapOf("X-Api-Key" to cfg.secret, "Accept" to "application/json")

    private fun get(path: String): String = Http.check(Http.request(base + path, headers = headers))

    /** Envoi JSON ; une erreur de validation (400) renvoie les messages de Prowlarr */
    private fun send(method: String, path: String, body: JSONObject) {
        val r = Http.request(base + path, method = method, headers = headers, json = body.toString())
        if (r.code == 400) validationMessage(r.body)?.let { throw HttpException(400, it) }
        Http.check(r)
    }

    fun indexers(): List<ProwlarrIndexer> {
        val status = JSONArray(get("/indexerstatus")).objects().associateBy { it.optInt("indexerId") }
        return JSONArray(get("/indexer")).objects().map { o ->
            val id = o.optInt("id")
            val s = status[id]
            ProwlarrIndexer(
                id = id,
                name = o.optString("name"),
                enabled = o.optBoolean("enable"),
                protocol = o.optString("protocol"),
                privacy = o.text("privacy"),
                priority = o.optInt("priority"),
                disabledTill = s?.text("disabledTill")?.toInstant(),
                raw = o,
            )
        }.sortedBy { it.name.lowercase() }
    }

    /**
     * Activer / désactiver. Pour désactiver, forceSave évite le test de connexion
     * (on désactive justement souvent un indexeur qui ne répond plus).
     */
    fun setEnabled(indexer: ProwlarrIndexer, enabled: Boolean) {
        val body = JSONObject(indexer.raw.toString()).put("enable", enabled)
        send("PUT", "/indexer/${indexer.id}" + if (enabled) "" else "?forceSave=true", body)
    }

    /** Test de connexion ; lève une exception avec la raison en cas d'échec */
    fun test(indexer: ProwlarrIndexer) = send("POST", "/indexer/test", indexer.raw)

    /** Statistiques depuis [days] jours (null = tout l'historique) */
    fun stats(days: Int?): List<IndexerStat> {
        val query = days?.let {
            val now = Instant.now()
            "?startDate=${now.minus(it.toLong(), ChronoUnit.DAYS)}&endDate=$now"
        } ?: ""
        val list = JSONObject(get("/indexerstats$query")).optJSONArray("indexers") ?: JSONArray()
        return list.objects().map {
            IndexerStat(
                indexerId = it.optInt("indexerId"),
                name = it.optString("indexerName"),
                queries = it.optInt("numberOfQueries"),
                rss = it.optInt("numberOfRssQueries"),
                grabs = it.optInt("numberOfGrabs"),
                failedQueries = it.optInt("numberOfFailedQueries") + it.optInt("numberOfFailedRssQueries"),
                failedGrabs = it.optInt("numberOfFailedGrabs"),
                responseMs = it.optInt("averageResponseTime"),
            )
        }
    }

    /** Recherches et grabs récents (sans les requêtes RSS, trop nombreuses), du plus récent au plus ancien */
    fun history(grabsOnly: Boolean, failedOnly: Boolean, names: Map<Int, String>): List<ProwlarrEvent> {
        val types = if (grabsOnly) "&eventType=1" else "&eventType=1&eventType=2"
        val failed = if (failedOnly) "&successful=false" else ""
        val records = JSONObject(get("/history?page=1&pageSize=100&sortKey=date&sortDirection=descending$types$failed"))
            .optJSONArray("records") ?: JSONArray()
        return records.objects().mapNotNull { o ->
            val date = o.text("date")?.toInstant() ?: return@mapNotNull null
            val data = o.optJSONObject("data") ?: JSONObject()
            // eventType : nom (« releaseGrabbed ») ou numéro selon la version
            val type = o.optString("eventType")
            val grab = type.equals("releaseGrabbed", ignoreCase = true) || type == "1"
            ProwlarrEvent(
                date = date,
                grab = grab,
                successful = o.optBoolean("successful", true),
                indexer = names[o.optInt("indexerId")],
                title = if (grab) data.ci("grabTitle") else data.ci("query"),
                source = data.ci("source"),
                results = data.ci("queryResults")?.toIntOrNull(),
                elapsedMs = data.ci("elapsedTime")?.toIntOrNull()?.takeIf { it > 0 },
            )
        }
    }

    fun applications(): List<ProwlarrApp> = JSONArray(get("/applications")).objects().map {
        ProwlarrApp(it.optString("name"), it.text("implementationName"), it.optString("syncLevel"))
    }.sortedBy { it.name.lowercase() }
}

/** Messages d'une réponse de validation : [{"propertyName": …, "errorMessage": …}] */
private fun validationMessage(body: String): String? = runCatching {
    JSONArray(body).objects().mapNotNull { it.text("errorMessage") }.distinct().joinToString("\n").ifBlank { null }
}.getOrNull()

private fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }
private fun String.toInstant(): Instant? = runCatching { Instant.parse(this) }.getOrNull()
private fun JSONObject.text(key: String): String? = if (isNull(key)) null else optString(key).ifBlank { null }

/** Clé sans tenir compte de la casse (les données de l'historique sont « Query » ou « query » selon la version) */
private fun JSONObject.ci(key: String): String? =
    keys().asSequence().firstOrNull { it.equals(key, ignoreCase = true) }?.let { text(it) }
