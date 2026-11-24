package ru.kadr.app.ui.components

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import ru.kadr.app.data.BASE_URL
import ru.kadr.app.ui.theme.parseColor

/** Постеры фильмов: грузятся с сервера один раз и хранятся в памяти (ключ — фильм и версия постера). */
object PosterCache {
    private val http = OkHttpClient()
    private val cache = HashMap<String, ImageBitmap>()

    suspend fun load(filmId: Int, version: Int): ImageBitmap? {
        val key = "$filmId:$version"
        cache[key]?.let { return it }
        return withContext(Dispatchers.IO) {
            try {
                val req = Request.Builder().url("${BASE_URL}api/films/$filmId/poster?v=$version").build()
                http.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) return@use null
                    val bytes = r.body()?.bytes() ?: return@use null
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()?.also { cache[key] = it }
                }
            } catch (e: Exception) {
                null
            }
        }
    }

    fun put(filmId: Int, version: Int, image: ImageBitmap) {
        cache["$filmId:$version"] = image
    }
}

/** Постер фильма; пока его нет (или не загружен) — плашка цвета фильма. */
@Composable
fun FilmPoster(filmId: Int, posterV: Int, color: String, modifier: Modifier) {
    var img by remember(filmId, posterV) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(filmId, posterV) {
        if (posterV > 0) img = PosterCache.load(filmId, posterV)
    }
    Box(modifier.clip(RoundedCornerShape(12.dp)).background(parseColor(color))) {
        img?.let { Image(it, contentDescription = "Постер", contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize()) }
    }
}

