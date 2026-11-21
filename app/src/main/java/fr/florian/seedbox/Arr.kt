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

/** Entrée du journal d'un *arr ; level en minuscules : « fatal », « error », « warn », « info »… */
data class LogEntry(
    val id: Int,
    val time: Instant?,
    val level: String,
    val logger: String?,
    val message: String,
    val exception: String?,
    val exceptionType: String?,
)

class LogPage(val entries: List<LogEntry>, val total: Int)

/** Un import de l'historique : un film, ou un épisode */
data class ImportEvent(
    /** « S01E02 · Titre » (Sonarr) */
    val episode: String?,
    /** Nom de la release téléchargée */
    val release: String?,
    /** « WEBDL-1080p », « Bluray-2160p »… */
    val quality: String?,
    val resolution: Int,
    /** Noms anglais renvoyés par les *arr (« French », « English »…) */
    val languages: List<String>,
    /** Formats personnalisés reconnus (« MULTI », « VFF »…) */
    val formats: List<String>,
    val size: Long?,
    val group: String?,
    val client: String?,
    val date: Instant?,
)

/** Film ou série importé récemment (une carte par série, avec ses derniers épisodes) */
data class RecentItem(
    val service: Service,
    val mediaId: Int,
    val title: String,
    val detail: String?,
    val date: Instant,
    val poster: PosterRef,
    /** « 2024 · Drame, Thriller · 2 h 05 » ou « 2019 · HBO · Drame » */
    val info: String? = null,
    val rating: Double? = null,
    val overview: String? = null,
    /** Imports du plus récent au plus ancien */
    val events: List<ImportEvent> = emptyList(),
    /** Radarr : taille du film sur le disque, si l'historique ne la donne pas */
    val sizeOnDisk: Long? = null,
) {
    /** Meilleure résolution importée : « 4K », « 1080p » */
    val resolution: String? get() = events.maxOfOrNull { it.resolution }?.takeIf { it > 0 }?.let(::resolutionLabel)

    /** Somme des fichiers (le plus récent par épisode : une mise à niveau ne compte pas double) */
    val size: Long? get() = events.distinctBy { it.episode }.mapNotNull { it.size }.takeIf { it.isNotEmpty() }?.sum() ?: sizeOnDisk

    val languages: List<String> get() = events.flatMap { it.languages }.distinct()
}

fun resolutionLabel(r: Int) = if (r >= 2160) "4K" else "${r}p"

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

    /**
     * Derniers imports (historique, eventType 3 = downloadFolderImported), du plus récent au plus ancien.
     * Radarr : un film par carte. Sonarr : une carte par série, avec les épisodes arrivés.
     */
    fun recent(limit: Int = 30): List<RecentItem> {
        val include = if (service == Service.SONARR) "includeSeries=true&includeEpisode=true" else "includeMovie=true"
        val records = JSONObject(get("/history?page=1&pageSize=150&sortKey=date&sortDirection=descending&eventType=3&$include"))
            .optJSONArray("records") ?: JSONArray()
        val idKey = if (service == Service.SONARR) "seriesId" else "movieId"
        // LinkedHashMap : garde l'ordre du plus récent
        val groups = LinkedHashMap<Int, MutableList<JSONObject>>()
        records.objects().forEach { o ->
            val id = o.optInt(idKey)
            if (id > 0) groups.getOrPut(id) { mutableListOf() }.add(o)
        }
        return groups.entries.take(limit).mapNotNull { (id, list) ->
            val first = list.first()
            val date = first.optString("date").toInstant() ?: return@mapNotNull null
            val media = first.optJSONObject(if (service == Service.SONARR) "series" else "movie")
            val year = media?.optInt("year")?.takeIf { it > 0 }
            val (title, detail) = if (service == Service.SONARR) {
                (media?.optString("title")?.ifBlank { null } ?: first.optString("sourceTitle")) to episodesLabel(list)
            } else {
                (media?.optString("title")?.ifBlank { null } ?: first.optString("sourceTitle")) to year?.toString()
            }
            RecentItem(
                service, id, title, detail, date, posterOf(id, media),
                info = media?.let { mediaInfo(it) },
                rating = media?.let { ratingOf(it) },
                overview = media?.text("overview"),
                events = list.map { importEvent(it) },
                sizeOnDisk = if (service == Service.RADARR) media?.optLong("sizeOnDisk")?.takeIf { it > 0 } else null,
            )
        }
    }

    /** Qualité, langues, taille, groupe, client et release d'un enregistrement de l'historique */
    private fun importEvent(o: JSONObject): ImportEvent {
        val q = o.optJSONObject("quality")?.optJSONObject("quality")
        val data = o.optJSONObject("data")
        // Radarr et Sonarr v4 : « languages » ; Sonarr v3 : « language »
        val langs = (o.optJSONArray("languages")?.objects() ?: listOfNotNull(o.optJSONObject("language")))
            .mapNotNull { it.text("name") }
            .filter { it != "Unknown" }
            .distinct()
        val ep = o.optJSONObject("episode")
        return ImportEvent(
            episode = ep?.let {
                listOfNotNull("S%02dE%02d".format(it.optInt("seasonNumber"), it.optInt("episodeNumber")), it.text("title")).joinToString(" · ")
            },
            release = o.text("sourceTitle"),
            quality = q?.text("name")?.takeIf { it != "Unknown" },
            resolution = q?.optInt("resolution") ?: 0,
            languages = langs,
            formats = o.optJSONArray("customFormats")?.objects()?.mapNotNull { it.text("name") }.orEmpty(),
            size = data?.text("size")?.toLongOrNull()?.takeIf { it > 0 },
            group = data?.text("releaseGroup"),
            client = data?.text("downloadClientName") ?: data?.text("downloadClient"),
            date = o.optString("date").toInstant(),
        )
    }

    /** « 2024 · Drame, Thriller · 2 h 05 » (film) ou « 2019 · HBO · Drame » (série) */
    private fun mediaInfo(m: JSONObject): String? {
        val year = m.optInt("year").takeIf { it > 0 }?.toString()
        val genres = m.optJSONArray("genres")?.strings()?.take(2)?.joinToString(", ") { genreFr(it) }?.ifBlank { null }
        val parts = if (service == Service.SONARR) {
            listOf(year, m.text("network"), genres)
        } else {
            val runtime = m.optInt("runtime").takeIf { it > 0 }?.let { if (it < 60) "$it min" else "%d h %02d".format(it / 60, it % 60) }
            listOf(year, genres, runtime)
        }
        return parts.filterNotNull().joinToString(" · ").ifBlank { null }
    }

    /** Note sur 10 : TMDB puis IMDb pour Radarr, la note de la série pour Sonarr */
    private fun ratingOf(m: JSONObject): Double? {
        val r = m.optJSONObject("ratings") ?: return null
        val values = if (service == Service.SONARR) listOf(r.optDouble("value"))
        else listOf("tmdb", "imdb").map { r.optJSONObject(it)?.optDouble("value") ?: Double.NaN }
        return values.firstOrNull { !it.isNaN() && it > 0 }
    }

    /** « S02E05 · Titre », « S02E05, S02E06 » ou « 4 épisodes : S01E01 → S01E04 » */
    private fun episodesLabel(list: List<JSONObject>): String? {
        val eps = list.mapNotNull { it.optJSONObject("episode") }
            .distinctBy { it.optInt("seasonNumber") to it.optInt("episodeNumber") }
        val codes = eps.map { "S%02dE%02d".format(it.optInt("seasonNumber"), it.optInt("episodeNumber")) }.sorted()
        return when {
            codes.isEmpty() -> null
            codes.size == 1 -> listOf(codes[0], eps[0].optString("title")).filter { it.isNotBlank() }.joinToString(" · ")
            codes.size <= 3 -> codes.joinToString(", ")
            else -> "${codes.size} épisodes : ${codes.first()} → ${codes.last()}"
        }
    }

    /**
     * Pochette réduite servie par Radarr/Sonarr eux-mêmes (poster-250, avec la clé API),
     * et en secours l'image d'origine (TMDB / TVDB), qui ne reçoit pas la clé.
     */
    private fun posterOf(id: Int, media: JSONObject?): PosterRef {
        val remote = media?.optJSONArray("images")?.objects()
            ?.firstOrNull { it.optString("coverType") == "poster" }
            ?.optString("remoteUrl")?.takeIf { it.startsWith("https://") }
        return PosterRef(
            key = "${service.name}-$id",
            url = "$base$api/mediacover/$id/poster-250.jpg",
            headers = mapOf("X-Api-Key" to key),
            fallback = remote,
        )
    }

    fun indexers(): List<Indexer> {
        val failing = JSONArray(get("/indexerstatus")).objects().map { it.optInt("indexerId") }.toSet()
        return JSONArray(get("/indexer")).objects().map {
            Indexer(it.optString("name"), it.optBoolean("enable"), it.optInt("id") in failing)
        }
    }

    /**
     * Journaux, du plus récent au plus ancien : les [count] premières entrées au niveau [level] ou plus grave
     * (« error » inclut « fatal », « warn » inclut les erreurs…). `level` est le filtre des versions actuelles,
     * filterKey / filterValue celui des plus anciennes ; chacune ignore l'autre.
     */
    fun logs(level: String, count: Int): LogPage {
        val o = JSONObject(get("/log?page=1&pageSize=$count&sortKey=time&sortDirection=descending&level=$level&filterKey=level&filterValue=$level"))
        val records = o.optJSONArray("records") ?: JSONArray()
        return LogPage(
            entries = records.objects().map {
                LogEntry(
                    id = it.optInt("id"),
                    time = it.text("time")?.toInstant(),
                    level = it.optString("level").lowercase(),
                    logger = it.text("logger"),
                    message = it.optString("message"),
                    exception = it.text("exception"),
                    exceptionType = it.text("exceptionType"),
                )
            },
            total = o.optInt("totalRecords", records.length()),
        )
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

/** Valeur texte, null si absente, nulle (optString renverrait « null ») ou vide */
private fun JSONObject.text(key: String): String? = if (isNull(key)) null else optString(key).ifBlank { null }

/** Genres TMDB / TVDB (renvoyés en anglais) */
private val GENRES = mapOf(
    "Adventure" to "Aventure", "Comedy" to "Comédie", "Crime" to "Policier", "Documentary" to "Documentaire",
    "Drama" to "Drame", "Family" to "Familial", "Fantasy" to "Fantastique", "History" to "Histoire",
    "Horror" to "Horreur", "Music" to "Musique", "Mystery" to "Mystère", "Science Fiction" to "Science-fiction",
    "Science-Fiction" to "Science-fiction", "War" to "Guerre", "TV Movie" to "Téléfilm", "Reality" to "Téléréalité",
    "Mini-Series" to "Mini-série", "Children" to "Jeunesse", "Food" to "Cuisine", "Game Show" to "Jeu télévisé",
    "News" to "Actualités", "Soap" to "Feuilleton", "Sport" to "Sport", "Suspense" to "Suspense",
)

private fun genreFr(g: String) = GENRES[g] ?: g
