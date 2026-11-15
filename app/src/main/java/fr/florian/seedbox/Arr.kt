package fr.florian.seedbox

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class HealthItem(val level: String, val message: String) {
    val isError get() = level == "error"
}

data class Disk(val path: String, val free: Long, val total: Long)

data class QueueItem(
    val service: Service,
    val downloadId: String?,
    val title: String,
    val detail: String?,
    val size: Long,
    val sizeLeft: Long,
    val timeLeft: String?,
    val state: String,
    val problem: String?,
) {
    val progress get() = if (size > 0) (1.0 - sizeLeft.toDouble() / size).coerceIn(0.0, 1.0) else 0.0
}

data class CalendarItem(val date: LocalDate, val service: Service, val title: String, val detail: String, val hasFile: Boolean)

data class Indexer(val name: String, val enabled: Boolean, val failing: Boolean)

/** API de Radarr / Sonarr (v3) et Prowlarr (v1), authentifiée par la clé API */
class Arr(val service: Service, cfg: ServiceConfig) {
    private val base = cfg.url.asBaseUrl()
    private val key = cfg.secret
    private val api = if (service == Service.PROWLARR) "/api/v1" else "/api/v3"

    private fun get(path: String): String =
        Http.check(Http.request("$base$api$path", headers = mapOf("X-Api-Key" to key, "Accept" to "application/json")))

    fun version(): String = JSONObject(get("/system/status")).optString("version")

    fun health(): List<HealthItem> = JSONArray(get("/health")).objects().map {
        HealthItem(it.optString("type"), it.optString("message"))
    }

    fun diskSpace(): List<Disk> = JSONArray(get("/diskspace")).objects().map {
        Disk(it.optString("path"), it.optLong("freeSpace"), it.optLong("totalSpace"))
    }

    fun queue(): List<QueueItem> {
        val include = if (service == Service.SONARR) "includeSeries=true&includeEpisode=true" else "includeMovie=true"
        val records = JSONObject(get("/queue?page=1&pageSize=100&$include")).optJSONArray("records") ?: JSONArray()
        return records.objects().map { o ->
            val (title, detail) = when (service) {
                Service.SONARR -> {
                    val series = o.optJSONObject("series")?.optString("title")
                    val ep = o.optJSONObject("episode")
                    val code = ep?.let { "S%02dE%02d".format(it.optInt("seasonNumber"), it.optInt("episodeNumber")) }
                    (series ?: o.optString("title")) to listOfNotNull(code, ep?.optString("title")?.ifBlank { null }).joinToString(" · ").ifBlank { null }
                }
                else -> {
                    val movie = o.optJSONObject("movie")
                    val name = movie?.let { m -> m.optString("title") + (m.optInt("year").takeIf { it > 0 }?.let { " ($it)" } ?: "") }
                    (name ?: o.optString("title")) to (if (movie != null) o.optString("title") else null)
                }
            }
            val messages = o.optJSONArray("statusMessages")?.objects()
                ?.flatMap { m -> m.optJSONArray("messages")?.strings() ?: emptyList() }
                .orEmpty()
            val error = o.optString("errorMessage").ifBlank { null }
            val status = o.optString("trackedDownloadStatus")
            QueueItem(
                service = service,
                downloadId = o.optString("downloadId").ifBlank { null },
                title = title,
                detail = detail,
                size = o.optLong("size"),
                sizeLeft = o.optLong("sizeleft"),
                timeLeft = parseTimeLeft(o.optString("timeleft"))?.let { duration(it) },
                state = queueState(o.optString("trackedDownloadState"), o.optString("status")),
                problem = error ?: messages.firstOrNull()?.takeIf { status == "warning" || status == "error" },
            )
        }
    }

    fun calendar(start: LocalDate, end: LocalDate): List<CalendarItem> {
        val extra = if (service == Service.SONARR) "&includeSeries=true" else ""
        val a = JSONArray(get("/calendar?start=$start&end=$end$extra"))
        val zone = ZoneId.systemDefault()
        return a.objects().mapNotNull { o ->
            if (service == Service.SONARR) {
                val date = o.optString("airDateUtc").toInstant()?.atZone(zone)?.toLocalDate()
                    ?: o.optString("airDate").toDate() ?: return@mapNotNull null
                val code = "S%02dE%02d".format(o.optInt("seasonNumber"), o.optInt("episodeNumber"))
                CalendarItem(
                    date, service,
                    o.optJSONObject("series")?.optString("title") ?: "Série",
                    listOf(code, o.optString("title")).filter { it.isNotBlank() }.joinToString(" · "),
                    o.optBoolean("hasFile"),
                )
            } else {
                // Radarr : la sortie qui tombe dans la période (numérique, physique ou cinéma)
                val releases = listOf("digitalRelease" to "Sortie numérique", "physicalRelease" to "Sortie physique", "inCinemas" to "Au cinéma")
                    .mapNotNull { (k, label) -> o.optString(k).toInstant()?.atZone(zone)?.toLocalDate()?.let { it to label } }
                    .filter { (d, _) -> !d.isBefore(start) && !d.isAfter(end) }
                val (date, label) = releases.minByOrNull { it.first } ?: return@mapNotNull null
                val year = o.optInt("year").takeIf { it > 0 }?.let { " ($it)" } ?: ""
                CalendarItem(date, service, o.optString("title") + year, label, o.optBoolean("hasFile"))
            }
        }
    }

    fun indexers(): List<Indexer> {
        val failing = JSONArray(get("/indexerstatus")).objects().map { it.optInt("indexerId") }.toSet()
        return JSONArray(get("/indexer")).objects().map {
            Indexer(it.optString("name"), it.optBoolean("enable"), it.optInt("id") in failing)
        }
    }

    private fun queueState(tracked: String, status: String) = when (tracked) {
        "downloading" -> if (status == "paused") "En pause" else "Téléchargement"
        "importBlocked" -> "Import bloqué"
        "importPending" -> "Import en attente"
        "importing" -> "Import en cours"
        "imported" -> "Importé"
        "failedPending", "failed" -> "Échec"
        "ignored" -> "Ignoré"
        else -> when (status) {
            "queued" -> "En file"
            "paused" -> "En pause"
            "completed" -> "Terminé"
            "delay" -> "Différé"
            "warning" -> "Avertissement"
            "failed" -> "Échec"
            else -> status.ifBlank { tracked }
        }
    }
}

/** « 1.02:03:04 » ou « 02:03:04 » (format .NET des *arr) → secondes ; null si vide ou nul */
private fun parseTimeLeft(s: String): Long? {
    val m = Regex("""^(?:(\d+)\.)?(\d+):(\d+):(\d+)""").find(s.trim()) ?: return null
    val (d, h, min, sec) = m.destructured
    val total = (d.toLongOrNull() ?: 0) * 86_400 + h.toLong() * 3600 + min.toLong() * 60 + sec.toLong()
    return total.takeIf { it > 0 }
}

private fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }
private fun JSONArray.strings(): List<String> = (0 until length()).mapNotNull { optString(it).ifBlank { null } }
private fun String.toInstant(): Instant? = if (isBlank()) null else runCatching { Instant.parse(this) }.getOrNull()
private fun String.toDate(): LocalDate? = if (isBlank()) null else runCatching { LocalDate.parse(this) }.getOrNull()
