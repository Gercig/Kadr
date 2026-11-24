package ru.kadr.app.ui.staff

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.kadr.app.data.ApiClient
import ru.kadr.app.data.FilmBody
import ru.kadr.app.data.SessionBody
import ru.kadr.app.data.StaffCinema
import ru.kadr.app.data.StaffFilm
import ru.kadr.app.data.StaffHall
import ru.kadr.app.data.StaffSession
import ru.kadr.app.data.userMessage
import ru.kadr.app.ui.components.ErrorText
import ru.kadr.app.ui.components.FilmPoster
import ru.kadr.app.ui.components.KadrField
import ru.kadr.app.ui.components.Loading
import ru.kadr.app.ui.components.PrimaryButton
import ru.kadr.app.ui.components.SecondaryButton
import ru.kadr.app.ui.components.clickableNoRipple
import ru.kadr.app.ui.theme.Kadr
import java.io.ByteArrayOutputStream
import java.time.LocalDate

// ---------------------------------------------------------------- расписание сеансов и цены

@Composable
fun SessionsTab(cinema: StaffCinema?) {
    val days = remember { (0..13).map { LocalDate.now().plusDays(it.toLong()) } }
    var day by remember { mutableStateOf(days.first()) }
    var list by remember { mutableStateOf<List<StaffSession>?>(null) }
    var films by remember { mutableStateOf<List<StaffFilm>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableStateOf(0) }
    var editing by remember { mutableStateOf<StaffSession?>(null) }
    var adding by remember { mutableStateOf(false) }
    LaunchedEffect(day, reload) {
        list = null
        try {
            list = ApiClient.api.staffSessions(day.toString())
            if (films.isEmpty()) films = ApiClient.api.staffFilms().filter { it.active }
            error = null
        } catch (e: Exception) { error = e.userMessage(); list = emptyList() }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Choice(null, days, day, { dayLabel(it) }) { day = it } }
        item { PrimaryButton("Добавить сеанс", onClick = { adding = true }, modifier = Modifier.fillMaxWidth(), enabled = cinema != null) }
        item { ErrorText(error) }
        if (list == null) item { Loading(Modifier.fillMaxWidth().padding(30.dp)) }
        if (list?.isEmpty() == true) item { Note("На этот день сеансов нет") }
        val byHall = list.orEmpty().groupBy { it.hallName }
        byHall.forEach { (hall, sessions) ->
            item { Text(hall, fontSize = 14.sp, color = Kadr.Muted, modifier = Modifier.padding(top = 6.dp)) }
            items(sessions) { s ->
                Card(Modifier.clickableNoRipple { editing = s }) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Title("${s.startsAt.takeLast(5)}–${s.endsAt} · ${s.title}")
                            Note("${s.format} · ${s.age} · ${s.durationMin} мин")
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(rub(s.price), fontSize = 15.sp, color = Kadr.Text)
                            Note("продано ${s.sold} из ${s.capacity}")
                        }
                    }
                }
            }
        }
    }
    if (adding && cinema != null) SessionDialog(null, cinema.halls.orEmpty(), films, day, onDone = { adding = false; reload++ }, onDismiss = { adding = false })
    editing?.let { s -> SessionDialog(s, cinema?.halls.orEmpty(), films, day, onDone = { editing = null; reload++ }, onDismiss = { editing = null }) }
}

private val FORMATS = listOf("2D", "3D", "IMAX", "IMAX 3D")

@Composable
private fun SessionDialog(s: StaffSession?, halls: List<StaffHall>, films: List<StaffFilm>, day: LocalDate, onDone: () -> Unit, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var hall by remember { mutableStateOf(halls.firstOrNull { it.id == s?.hallId } ?: halls.firstOrNull()) }
    var film by remember { mutableStateOf(films.firstOrNull { it.id == s?.filmId } ?: films.firstOrNull()) }
    var date by remember { mutableStateOf(s?.startsAt?.take(10) ?: day.toString()) }
    var time by remember { mutableStateOf(s?.startsAt?.takeLast(5) ?: "19:00") }
    var format by remember { mutableStateOf(s?.format ?: "2D") }
    var price by remember { mutableStateOf(s?.price?.toString() ?: "400") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var askDelete by remember { mutableStateOf(false) }
    FormDialog(if (s == null) "Новый сеанс" else "Сеанс", error, busy, onDismiss = onDismiss, onConfirm = {
        scope.launch {
            busy = true
            error = try {
                val body = SessionBody(hall?.id ?: 0, film?.id ?: 0, "$date $time", format, price.toIntOrNull() ?: 0)
                if (s == null) ApiClient.api.addSession(body) else ApiClient.api.editSession(s.id, body)
                null
            } catch (e: Exception) { e.userMessage() }
            busy = false
            if (error == null) onDone()
        }
    }) {
        if (s == null) {
            Choice("Зал", halls, hall, { it.name }) { hall = it }
            Choice("Фильм", films, film, { it.title }) { film = it }
        } else {
            Note("${s.hallName} · ${s.title} · продано ${s.sold}")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            KadrField(date, { date = it }, "Дата (ГГГГ-ММ-ДД)", Modifier.weight(1.3f))
            KadrField(time, { time = it }, "Время", Modifier.weight(1f))
        }
        Choice("Формат", FORMATS, format, { it }) { format = it }
        KadrField(price, { price = it.filter(Char::isDigit) }, "Цена билета, ₽", keyboard = KeyboardType.Number)
        Note("Между сеансами в зале — не меньше 15 минут на уборку. Льготный билет дешевле на 100 ₽, детский — на 150 ₽.")
        if (s != null) {
            if (!askDelete) SecondaryButton("Удалить сеанс", onClick = { askDelete = true }, modifier = Modifier.fillMaxWidth())
            else SecondaryButton("Точно удалить?", onClick = {
                scope.launch {
                    busy = true
                    error = try { ApiClient.api.deleteSession(s.id); null } catch (e: Exception) { e.userMessage() }
                    busy = false
                    if (error == null) onDone()
                }
            }, modifier = Modifier.fillMaxWidth())
        }
    }
}

// ---------------------------------------------------------------- фильмы и постеры

@Composable
fun FilmsTab() {
    var list by remember { mutableStateOf<List<StaffFilm>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableStateOf(0) }
    var editing by remember { mutableStateOf<StaffFilm?>(null) }
    var adding by remember { mutableStateOf(false) }
    LaunchedEffect(reload) {
        list = try { ApiClient.api.staffFilms() } catch (e: Exception) { error = e.userMessage(); emptyList() }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { PrimaryButton("Добавить фильм", onClick = { adding = true }, modifier = Modifier.fillMaxWidth()) }
        item { ErrorText(error) }
        if (list == null) item { Loading(Modifier.fillMaxWidth().padding(30.dp)) }
        items(list.orEmpty()) { f ->
            Card(Modifier.clickableNoRipple { editing = f }) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FilmPoster(f.id, f.posterV, f.color, Modifier.width(56.dp).height(82.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Title(f.title)
                        Note("${f.genre} · ${f.age} · ${f.durationMin} мин")
                        if (!f.active) Badge("снят с проката", Kadr.Muted)
                        if (!f.hasPoster) Note("Нет постера", Kadr.AccentSoft)
                    }
                }
            }
        }
    }
    if (adding) FilmDialog(null, onDone = { adding = false; reload++ }, onDismiss = { adding = false })
    editing?.let { f -> FilmDialog(f, onDone = { editing = null; reload++ }, onDismiss = { editing = null }) }
}

private val AGES = listOf("0+", "6+", "12+", "16+", "18+")
private val COLORS = listOf("#3A2E4F", "#23414A", "#4A2F2B", "#2F3F2A", "#4A3A1F", "#1F2F4A")

@Composable
private fun FilmDialog(f: StaffFilm?, onDone: () -> Unit, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var title by remember { mutableStateOf(f?.title ?: "") }
    var genre by remember { mutableStateOf(f?.genre ?: "") }
    var age by remember { mutableStateOf(f?.age ?: "12+") }
    var duration by remember { mutableStateOf(f?.durationMin?.toString() ?: "110") }
    var description by remember { mutableStateOf(f?.description ?: "") }
    var color by remember { mutableStateOf(f?.color ?: COLORS.first()) }
    var active by remember { mutableStateOf(f?.active ?: true) }
    var poster by remember { mutableStateOf<Bitmap?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    // постер из галереи: уменьшаем до 700 px по высоте и сжимаем в JPEG
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch {
            poster = withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }?.let { b ->
                    val k = minOf(1f, 700f / b.height)
                    Bitmap.createScaledBitmap(b, (b.width * k).toInt().coerceAtLeast(1), (b.height * k).toInt().coerceAtLeast(1), true)
                }
            }
        }
    }
    FormDialog(if (f == null) "Новый фильм" else "Фильм", error, busy, onDismiss = onDismiss, onConfirm = {
        scope.launch {
            busy = true
            error = try {
                val b64 = poster?.let { bmp ->
                    val out = ByteArrayOutputStream()
                    bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
                    Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
                }
                val body = FilmBody(title, age, genre, duration.toIntOrNull() ?: 0, description, color, active, b64)
                if (f == null) ApiClient.api.addFilm(body) else ApiClient.api.editFilm(f.id, body)
                null
            } catch (e: Exception) { e.userMessage() }
            busy = false
            if (error == null) onDone()
        }
    }) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(70.dp).height(100.dp)) {
                val p = poster
                if (p != null) Image(p.asImageBitmap(), "Постер", contentScale = ContentScale.Crop, modifier = Modifier.size(70.dp, 100.dp).clip(RoundedCornerShape(12.dp)))
                else if (f != null) FilmPoster(f.id, f.posterV, color, Modifier.size(70.dp, 100.dp))
                else FilmPoster(0, 0, color, Modifier.size(70.dp, 100.dp))
            }
            SecondaryButton(if (poster != null || f?.hasPoster == true) "Сменить постер" else "Выбрать постер", onClick = { pick.launch("image/*") }, modifier = Modifier.weight(1f))
        }
        KadrField(title, { title = it }, "Название")
        KadrField(genre, { genre = it }, "Жанр")
        Choice("Возраст", AGES, age, { it }) { age = it }
        KadrField(duration, { duration = it.filter(Char::isDigit) }, "Длительность, мин", keyboard = KeyboardType.Number)
        KadrField(description, { description = it }, "Описание")
        Choice("Цвет карточки (пока нет постера)", COLORS, color, { "■ " + it }) { color = it }
        Choice("Прокат", listOf(true, false), active, { if (it) "В прокате" else "Снят с проката" }) { active = it }
    }
}

// ---------------------------------------------------------------- залы своего кинотеатра

@Composable
fun HallsTab(cinema: StaffCinema?, refresh: () -> Unit) {
    var editing by remember { mutableStateOf<StaffHall?>(null) }
    var adding by remember { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { PrimaryButton("Добавить зал", onClick = { adding = true }, modifier = Modifier.fillMaxWidth(), enabled = cinema != null) }
        items(cinema?.halls.orEmpty()) { h ->
            Card(Modifier.clickableNoRipple { editing = h }) {
                Title(h.name)
                Note("${h.rows} рядов × ${h.seatsPerRow} мест = ${h.rows * h.seatsPerRow} мест")
                Note(HALL_MODELS.firstOrNull { it.first == h.modelUrl }?.second ?: "")
            }
        }
    }
    if (cinema != null) {
        if (adding) HallDialog(cinema.id, null, onDone = { adding = false; refresh() }, onDismiss = { adding = false })
        editing?.let { h -> HallDialog(cinema.id, h, onDone = { editing = null; refresh() }, onDismiss = { editing = null }) }
    }
}
