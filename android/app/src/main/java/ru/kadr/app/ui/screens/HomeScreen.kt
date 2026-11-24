package ru.kadr.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.kadr.app.AppViewModel
import ru.kadr.app.R
import ru.kadr.app.data.local.LocalData
import ru.kadr.app.data.FilmWithSessions
import ru.kadr.app.data.Schedule
import ru.kadr.app.data.SessionShort
import ru.kadr.app.data.userMessage
import ru.kadr.app.ui.components.AgeBadge
import ru.kadr.app.ui.components.Chip
import ru.kadr.app.ui.components.ErrorText
import ru.kadr.app.ui.components.Loading
import ru.kadr.app.ui.components.LogoMark
import ru.kadr.app.ui.components.PrimaryButton
import ru.kadr.app.ui.components.SecondaryButton
import ru.kadr.app.ui.components.clickableNoRipple
import ru.kadr.app.ui.theme.Kadr
import ru.kadr.app.ui.theme.parseColor
import ru.kadr.app.ui.components.FilmPoster
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.TextStyle
import java.util.Locale

private val RU = Locale.forLanguageTag("ru")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(vm: AppViewModel, onSession: (Int) -> Unit, onLogin: (Int) -> Unit, onRegister: (Int) -> Unit) {
    val today = remember { LocalDate.now() }
    var dayIndex by remember { mutableIntStateOf(0) }
    var filter by remember { mutableStateOf("Все") }
    var schedule by remember { mutableStateOf<Schedule?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var pickCinema by remember { mutableStateOf(false) }
    var gateSession by remember { mutableStateOf<Pair<FilmWithSessions, SessionShort>?>(null) }
    val day = today.plusDays(dayIndex.toLong())
    val cinema = vm.cinema

    LaunchedEffect(cinema?.id, dayIndex) {
        if (cinema == null) {
            error = vm.loadCinemas()
            loading = false
            return@LaunchedEffect
        }
        loading = true
        error = null
        try {
            schedule = LocalData.catalog.schedule(cinema.id, day.toString())
        } catch (e: Exception) {
            error = e.userMessage()
        }
        loading = false
    }

    Column(modifier = Modifier.fillMaxSize().background(Kadr.Bg).statusBarsPadding()) {
        // Шапка: знак и выбор кинотеатра сети
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            LogoMark(size = 30.dp)
            Column(modifier = Modifier.clickableNoRipple { pickCinema = true }.padding(vertical = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(cinema?.name ?: "Выберите кинотеатр", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Kadr.Text)
                    Icon(painterResource(R.drawable.ic_chevron_down), contentDescription = "Сменить кинотеатр", tint = Kadr.Muted, modifier = Modifier.size(18.dp))
                }
                if (cinema != null) Text("${cinema.city}, ${cinema.address}", fontSize = 12.sp, color = Kadr.Muted)
            }
        }

        // Лента дат
        LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(7) { i ->
                val d = today.plusDays(i.toLong())
                val on = i == dayIndex
                val weekend = d.dayOfWeek.value >= 6
                Column(
                    modifier = Modifier
                        .width(if (i == 0) 80.dp else 48.dp)
                        .height(60.dp)
                        .background(if (on) Kadr.Accent else Kadr.Surface2, RoundedCornerShape(14.dp))
                        .clickableNoRipple { dayIndex = i },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    val top = if (i == 0) "сегодня" else d.dayOfWeek.getDisplayName(TextStyle.SHORT, RU)
                    Text(top, fontSize = 12.sp, color = if (on) Color.White else if (weekend) Kadr.AccentSoft else Kadr.Text)
                    Text("${d.dayOfMonth}", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = if (on) Color.White else Kadr.Text)
                }
            }
        }

        Row(modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Все", "2D", "3D", "VIP").forEach { f -> Chip(f, filter == f) { filter = f } }
        }

        when {
            loading -> Loading(Modifier.fillMaxSize())
            error != null -> Column(Modifier.padding(20.dp)) { ErrorText(error) }
            else -> {
                val films = schedule?.films.orEmpty()
                    .map { f -> f.copy(sessions = f.sessions.filter { filter == "Все" || it.posterFormat == filter }) }
                    .filter { it.sessions.isNotEmpty() }
                if (films.isEmpty()) {
                    Text("На этот день сеансов нет", color = Kadr.Muted, modifier = Modifier.padding(20.dp))
                }
                LazyColumn(
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    items(films, key = { it.id }) { film ->
                        FilmCard(film, isToday = dayIndex == 0) { s ->
                            if (vm.user == null) gateSession = film to s else onSession(s.id)
                        }
                    }
                }
            }
        }
    }

    if (pickCinema) {
        ModalBottomSheet(onDismissRequest = { pickCinema = false }, sheetState = rememberModalBottomSheetState(), containerColor = Kadr.Surface) {
            CinemaPicker(vm) { pickCinema = false }
        }
    }

    gateSession?.let { (film, s) ->
        ModalBottomSheet(onDismissRequest = { gateSession = null }, sheetState = rememberModalBottomSheetState(), containerColor = Kadr.Surface) {
            Column(modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth().background(Kadr.Bg, RoundedCornerShape(14.dp)).padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.width(36.dp).height(52.dp).background(parseColor(film.color), RoundedCornerShape(8.dp)))
                    Column {
                        Text("${film.title} · ${s.time}", fontWeight = FontWeight.SemiBold, color = Kadr.Text)
                        Text("${s.hallName} · от ${s.price} ₽", fontSize = 12.sp, color = Kadr.Muted)
                    }
                }
                Text("Войдите, чтобы купить билет", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Kadr.Text)
                Text(
                    "Билеты продаются только зарегистрированным зрителям: так мы сохраним их в вашем профиле и начислим бонусы за покупку.",
                    fontSize = 14.sp, color = Color(0xFFC9C5D1), lineHeight = 20.sp,
                )
                PrimaryButton("Войти", onClick = { gateSession = null; onLogin(s.id) }, modifier = Modifier.fillMaxWidth())
                SecondaryButton("Зарегистрироваться", onClick = { gateSession = null; onRegister(s.id) }, modifier = Modifier.fillMaxWidth())
                Text(
                    "Продолжить смотреть афишу",
                    color = Kadr.Muted, fontSize = 14.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().clickableNoRipple { gateSession = null }.padding(4.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilmCard(film: FilmWithSessions, isToday: Boolean, onSession: (SessionShort) -> Unit) {
    val nowTime = remember { LocalTime.now() }
    Row(
        modifier = Modifier.fillMaxWidth().background(Kadr.Surface, RoundedCornerShape(18.dp)).padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(modifier = Modifier.width(84.dp).height(124.dp), contentAlignment = Alignment.Center) {
            if (film.posterV > 0) {
                FilmPoster(film.id, film.posterV, film.color, Modifier.width(84.dp).height(124.dp))
            } else {
                Box(Modifier.width(84.dp).height(124.dp).background(parseColor(film.color), RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                    LogoMark(size = 34.dp, accent = Color.White.copy(alpha = 0.35f), play = Color.White.copy(alpha = 0.35f))
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(film.title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Kadr.Text, modifier = Modifier.weight(1f, fill = false))
                AgeBadge(film.age)
            }
            val h = film.durationMin / 60
            val m = film.durationMin % 60
            Text("${film.genre} · $h ч ${"%02d".format(m)} мин", fontSize = 13.sp, color = Kadr.Muted)
            Spacer(Modifier.height(2.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                film.sessions.forEach { s ->
                    val past = isToday && LocalTime.parse(s.time) <= nowTime
                    val full = s.free <= 0
                    val disabled = past || full
                    Column(
                        modifier = Modifier
                            .width(66.dp)
                            .height(50.dp)
                            .background(if (disabled) Color.Transparent else Color(0xFF24242C), RoundedCornerShape(10.dp))
                            .border(1.dp, if (disabled) Color(0xFF2A2A32) else Color(0xFF24242C), RoundedCornerShape(10.dp))
                            .then(if (disabled) Modifier else Modifier.clickableNoRipple { onSession(s) }),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        val c = if (disabled) Color(0xFF5E5B68) else Kadr.Text
                        Text(s.time, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c)
                        Text(
                            when {
                                past -> if (s.posterFormat == "VIP") "прошёл · VIP" else "прошёл"
                                full -> if (s.posterFormat == "VIP") "мест нет · VIP" else "мест нет"
                                else -> "${s.price} ₽ · ${s.posterFormat}"
                            },
                            fontSize = 10.sp, color = c.copy(alpha = 0.8f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CinemaPicker(vm: AppViewModel, onClose: () -> Unit) {
    val cities = vm.cinemas.map { it.city }.distinct()
    var city by remember { mutableStateOf(vm.cinema?.city ?: cities.firstOrNull()) }
    Column(modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Кинотеатры «Кадр»", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Kadr.Text)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(cities) { c -> Chip(c, c == city) { city = c } }
        }
        Column {
            vm.cinemas.filter { it.city == city }.forEachIndexed { i, c ->
                if (i > 0) HorizontalDivider(color = Kadr.Line)
                Row(
                    modifier = Modifier.fillMaxWidth().clickableNoRipple { vm.selectCinema(c.id); onClose() }.padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(c.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Kadr.Text)
                        Text(c.address, fontSize = 12.sp, color = Kadr.Muted)
                        Text("${c.hallsCount} ${hallsWord(c.hallsCount)} · ${c.seatsCount} мест", fontSize = 12.sp, color = Kadr.Muted)
                    }
                    if (c.id == vm.cinema?.id) {
                        Box(Modifier.size(28.dp).background(Kadr.Accent, CircleShape), contentAlignment = Alignment.Center) {
                            Icon(painterResource(R.drawable.ic_check), contentDescription = "Выбран", tint = Color.White, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
        }
        Text("Выбор запомнится. Сменить кинотеатр можно в любой момент на главной.", fontSize = 12.sp, color = Kadr.Muted)
    }
}

private fun hallsWord(n: Int): String = when {
    n % 10 == 1 && n % 100 != 11 -> "зал"
    n % 10 in 2..4 && n % 100 !in 12..14 -> "зала"
    else -> "залов"
}
