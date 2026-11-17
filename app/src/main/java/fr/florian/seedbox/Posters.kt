package fr.florian.seedbox

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.Collections

/**
 * Où trouver une pochette. Classe simple (pas data class) : la clé API des en-têtes
 * ne doit pas finir dans un toString() ; l'égalité se fait sur [key].
 */
class PosterRef(val key: String, val url: String, val headers: Map<String, String>, val fallback: String?) {
    override fun equals(other: Any?) = other is PosterRef && other.key == key
    override fun hashCode() = key.hashCode()
}

/** Chargement des pochettes : cache mémoire, 4 téléchargements à la fois, image réduite au décodage */
object Posters {
    private val cache = object : LruCache<String, Bitmap>(32 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    /** Échecs déjà constatés : pas de nouvel essai à chaque défilement (vidé au redémarrage) */
    private val failed = Collections.synchronizedSet(HashSet<String>())
    private val permits = Semaphore(4)

    fun cached(ref: PosterRef): Bitmap? = cache.get(ref.key)

    suspend fun load(ref: PosterRef): Bitmap? {
        cached(ref)?.let { return it }
        if (ref.key in failed) return null
        return permits.withPermit {
            withContext(Dispatchers.IO) {
                val bmp = fetch(ref.url, ref.headers) ?: ref.fallback?.let { fetch(it, emptyMap()) }
                if (bmp != null) cache.put(ref.key, bmp) else failed.add(ref.key)
                bmp
            }
        }
    }

    private fun fetch(url: String, headers: Map<String, String>): Bitmap? = try {
        decode(Http.bytes(url, headers))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    /** Réduit les grandes images (l'original TMDB fait ~2000 px) à ~300 px de large */
    private fun decode(bytes: ByteArray, targetWidth: Int = 300): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= targetWidth) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }
}

/** Pochette au format 2:3 ; fond neutre pendant le chargement ou si elle est introuvable */
@Composable
fun Poster(ref: PosterRef, modifier: Modifier = Modifier) {
    var bitmap by remember(ref) { mutableStateOf(Posters.cached(ref)) }
    LaunchedEffect(ref) { if (bitmap == null) bitmap = Posters.load(ref) }
    Box(
        modifier
            .aspectRatio(2f / 3f)
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        bitmap?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
    }
}
