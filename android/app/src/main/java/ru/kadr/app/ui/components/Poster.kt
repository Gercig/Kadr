package ru.kadr.app.ui.components

import android.graphics.BitmapFactory
import android.util.LruCache
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
import ru.kadr.app.data.local.LocalData
import ru.kadr.app.ui.theme.parseColor

/** Изображения хранятся в Room и декодируются по мере открытия экранов. */
object PosterCache {
    private val cache = LruCache<Pair<Int, Int>, ImageBitmap>(16)

    suspend fun load(filmId: Int, version: Int): ImageBitmap? {
        val key = filmId to version
        cache.get(key)?.let { return it }
        val bytes = LocalData.catalog.poster(filmId) ?: return null
        return withContext(Dispatchers.Default) {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()?.also { cache.put(key, it) }
        }
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

