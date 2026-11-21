package fr.florian.seedbox

import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** Erreur HTTP avec un message lisible */
class HttpException(val code: Int, message: String) : IOException(message)

class Response(val code: Int, val body: String, val cookies: List<String>)

class FilePart(val name: String, val filename: String, val type: String, val bytes: ByteArray)
class Multipart(val fields: Map<String, String>, val files: List<FilePart> = emptyList())

/** Client HTTP bloquant (HttpURLConnection) : à appeler sur Dispatchers.IO */
object Http {
    fun request(
        url: String,
        method: String = "GET",
        headers: Map<String, String> = emptyMap(),
        form: Map<String, String>? = null,
        multipart: Multipart? = null,
    ): Response {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 10_000
            conn.readTimeout = 20_000
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            if (form != null) {
                val data = form.entries.joinToString("&") { enc(it.key) + "=" + enc(it.value) }.toByteArray()
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                conn.outputStream.use { it.write(data) }
            }
            if (multipart != null) writeMultipart(conn, multipart)
            val code = conn.responseCode
            val stream = if (code >= 400) conn.errorStream else conn.inputStream
            val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
            val cookies = conn.headerFields.entries
                .filter { it.key?.equals("Set-Cookie", ignoreCase = true) == true }
                .flatMap { it.value }
            return Response(code, body, cookies)
        } finally {
            conn.disconnect()
        }
    }

    private fun writeMultipart(conn: HttpURLConnection, m: Multipart) {
        val boundary = "----MySeedbox" + System.nanoTime()
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        conn.outputStream.use { out ->
            fun w(s: String) = out.write(s.toByteArray())
            m.fields.forEach { (k, v) ->
                w("--$boundary\r\nContent-Disposition: form-data; name=\"$k\"\r\n\r\n$v\r\n")
            }
            m.files.forEach { f ->
                val name = f.filename.replace("\"", "")
                w("--$boundary\r\nContent-Disposition: form-data; name=\"${f.name}\"; filename=\"$name\"\r\nContent-Type: ${f.type}\r\n\r\n")
                out.write(f.bytes)
                w("\r\n")
            }
            w("--$boundary--\r\n")
        }
    }

    /** Contenu binaire (image…), ou HttpException si le code n'est pas 2xx */
    fun bytes(url: String, headers: Map<String, String> = emptyMap()): ByteArray {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 20_000
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            val code = conn.responseCode
            if (code !in 200..299) throw HttpException(code, httpMessage(code))
            return conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }

    /** Corps de la réponse, ou HttpException si le code n'est pas 2xx */
    fun check(r: Response): String {
        if (r.code !in 200..299) throw HttpException(r.code, httpMessage(r.code))
        return r.body
    }

    fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    private fun httpMessage(code: Int) = when (code) {
        401 -> "Clé API ou identifiants refusés (401)"
        403 -> "Accès refusé (403)"
        404 -> "Introuvable (404) : vérifier l'adresse, le port et l'URL de base"
        409 -> "Refusé (409) : torrent déjà présent ?"
        in 500..599 -> "Erreur du serveur ($code)"
        else -> "Erreur HTTP $code"
    }
}

/** Message d'erreur à afficher */
fun Throwable.friendly(): String = when (this) {
    is HttpException -> message ?: "Erreur HTTP $code"
    is UnknownHostException -> "Serveur introuvable : vérifier l'adresse (ou pas de réseau)"
    is SocketTimeoutException -> "Le serveur ne répond pas (délai dépassé)"
    is SSLException -> "Erreur SSL : vérifier http/https et le port"
    is java.net.ConnectException -> "Connexion refusée : vérifier le port"
    is java.net.MalformedURLException -> "Adresse invalide"
    is org.json.JSONException -> "Réponse inattendue : est-ce le bon service sur ce port ?"
    else -> if (message?.contains("Cleartext", ignoreCase = true) == true) {
        "HTTP non chiffré refusé : utiliser une adresse en https://"
    } else {
        message ?: javaClass.simpleName
    }
}

/** « ma-seedbox.exemple.com:8080/ » → « https://ma-seedbox.exemple.com:8080 » */
fun String.asBaseUrl(): String {
    val s = trim().trimEnd('/')
    return if (s.startsWith("http://") || s.startsWith("https://")) s else "https://$s"
}
