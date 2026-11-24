package ru.kadr.app.ui.screens

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.util.Base64
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import ru.kadr.app.ui.components.Chip
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import ru.kadr.app.AppViewModel
import ru.kadr.app.R
import ru.kadr.app.data.ApiClient
import ru.kadr.app.data.OrderBody
import ru.kadr.app.data.SeatBody
import ru.kadr.app.data.SessionInfo
import ru.kadr.app.data.userMessage
import ru.kadr.app.ui.components.ErrorText
import ru.kadr.app.ui.components.Loading
import ru.kadr.app.ui.components.PosterCache
import ru.kadr.app.ui.components.PrimaryButton
import ru.kadr.app.ui.components.ScreenHeader
import ru.kadr.app.ui.components.clickableNoRipple
import ru.kadr.app.ui.theme.Kadr

private data class SeatId(val row: Int, val seat: Int)
private val PremiumSeatColor = Color(0xFFA27A43)

@Composable
fun SeatScreen(vm: AppViewModel, sessionId: Int, onBack: () -> Unit, onOrder: (Int) -> Unit) {
    val scope = rememberCoroutineScope()
    var info by remember { mutableStateOf<SessionInfo?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val picked = remember { mutableStateListOf<SeatId>() }
    var fullScreen by remember(sessionId) { mutableStateOf(false) }
    var focus by remember { mutableStateOf(SeatId(6, 8)) }

    LaunchedEffect(sessionId) {
        try {
            val s = ApiClient.api.session(sessionId)
            info = s
            focus = SeatId((s.hall.rows + 1) / 2 + 1, (s.hall.seatsPerRow + 1) / 2)
        } catch (e: Exception) {
            error = e.userMessage()
        }
    }

    val s = info
    val filmId = s?.film?.id
    val posterVersion = s?.film?.posterV ?: 0
    var posterDataUrl by remember(filmId, posterVersion) { mutableStateOf<String?>(null) }
    LaunchedEffect(filmId, posterVersion) {
        if (filmId == null || posterVersion <= 0) return@LaunchedEffect
        val poster = PosterCache.load(filmId, posterVersion) ?: return@LaunchedEffect
        posterDataUrl = withContext(Dispatchers.Default) {
            val bytes = ByteArrayOutputStream()
            if (poster.asAndroidBitmap().compress(Bitmap.CompressFormat.JPEG, 92, bytes)) {
                "data:image/jpeg;base64," + Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP)
            } else {
                null
            }
        }
    }
    val premium = remember(s?.premiumSeats) {
        s?.premiumSeats.orEmpty().map { SeatId(it[0], it[1]) }.toSet()
    }
    val total = s?.let { session ->
        picked.sumOf { maxOf(200, session.price) + if (it in premium) session.premiumSurcharge else 0 }
    } ?: 0
    val model = when {
        s?.hall?.modelUrl == "imax" && s.hall.rows == 8 && s.hall.seatsPerRow == 43 -> "imax_compact"
        else -> s?.hall?.modelUrl ?: "vip"
    }
    Box(Modifier.fillMaxSize()) {
    Column(modifier = Modifier.fillMaxSize().background(Kadr.Bg).statusBarsPadding()) {
        ScreenHeader(
            s?.film?.title ?: "Выбор места",
            s?.let { "${humanDate(it.startsAt)} · ${it.startsAt.takeLast(5)} · ${it.hall.name} · ${it.format}" },
            onBack = onBack,
        )
        if (s == null) {
            if (error != null) Column(Modifier.padding(20.dp)) { ErrorText(error) } else Loading(Modifier.fillMaxSize())
            return@Column
        }
        val taken = remember(s) { s.taken.orEmpty().map { SeatId(it[0], it[1]) }.toSet() }
        val aisles = s.hall.aisles.orEmpty()
        val gapsForGrid = singleGaps(picked.toSet(), taken, s.hall.seatsPerRow, aisles)
        val screenJson = screenInfo(s, picked.toList(), taken, model, total)

        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            SeatView3D(
                focus, picked.isEmpty(), model, screenJson, posterDataUrl,
                modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth().height(200.dp),
                onToggle = { fullScreen = true },
            )

            // Несколько мест: переключаем, из какого кресла смотреть
            if (picked.size > 1) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 10.dp),
                ) {
                    items(picked.toList()) { p ->
                        Chip("Ряд ${p.row} · место ${p.seat}", p == focus) { focus = p }
                    }
                }
            }

            // Экран (дуга)
            Column(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Canvas(Modifier.width(300.dp).height(16.dp)) {
                    val p = Path().apply {
                        moveTo(4.dp.toPx(), size.height - 2.dp.toPx())
                        quadraticTo(size.width / 2, -size.height * 0.8f, size.width - 4.dp.toPx(), size.height - 2.dp.toPx())
                    }
                    drawPath(p, Color(0xFF8C8897), style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round))
                }
                Text("ЭКРАН", fontSize = 11.sp, letterSpacing = 4.sp, color = Kadr.Muted)
            }

            // Схема зала: лаймовые линии по бокам — проходы к эвакуационным выходам.
            // Большой зал (IMAX, 54 места в ряду) не помещается по ширине — схема прокручивается вбок.
            BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                val cols = s.hall.seatsPerRow
                val aisleW = 14.dp
                val fitCell = (maxWidth - 60.dp - aisleW * aisles.size) / cols
                val cell = if (fitCell < 15.dp) 20.dp else min(22.dp, fitCell)
                val spacing = cell * 0.18f
                val seatWidth = cell * 0.82f
                val gapWidth = aisleW - spacing
                val density = LocalDensity.current
                val aisleCenters = with(density) {
                    val label = 14.dp.roundToPx()
                    val seat = seatWidth.roundToPx()
                    val gap = spacing.roundToPx()
                    val aisle = gapWidth.roundToPx()
                    aisles.mapIndexed { index, afterSeat ->
                        label + afterSeat * (seat + gap) + gap + index * (aisle + gap) + aisle / 2f
                    }
                }
                val hScroll = rememberScrollState()
                val centerAisle = aisleCenters.singleOrNull()
                val leftInset = with(density) { 2.dp.roundToPx() + 6.dp.roundToPx() }
                LaunchedEffect(hScroll.maxValue, hScroll.viewportSize, centerAisle) {
                    if (hScroll.maxValue in 1 until Int.MAX_VALUE && hScroll.viewportSize > 0) {
                        val position = if (model == "imax_compact" && centerAisle != null) {
                            (leftInset + centerAisle - hScroll.viewportSize / 2f).toInt()
                        } else {
                            hScroll.maxValue / 2
                        }
                        hScroll.scrollTo(position.coerceIn(0, hScroll.maxValue))
                    }
                }
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(hScroll),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Box(Modifier.width(2.dp).height((cell + 4.dp) * s.hall.rows).background(Kadr.Exit))
                    Column(
                        Modifier.padding(horizontal = 6.dp).drawBehind {
                            if (model == "vip2" || model == "imax_compact") {
                                aisleCenters.forEach { x ->
                                    drawLine(Kadr.Exit, Offset(x, 0f), Offset(x, size.height), strokeWidth = 2.dp.toPx())
                                }
                            }
                        },
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        for (r in 1..s.hall.rows) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(spacing)) {
                                Text("$r", fontSize = 10.sp, color = Kadr.Muted, modifier = Modifier.width(14.dp), textAlign = TextAlign.End)
                                for (c in 1..cols) {
                                    val id = SeatId(r, c)
                                    val isTaken = id in taken
                                    val on = id in picked
                                    val isPremium = id in premium
                                    val color = when {
                                        on -> Kadr.Accent
                                        isTaken -> Kadr.SeatTaken
                                        id in gapsForGrid -> Color(0xFF6B2E2A)
                                        isPremium -> PremiumSeatColor
                                        else -> Kadr.Seat
                                    }
                                    val seatLabel = "Ряд $r, место $c" + when {
                                        isTaken -> ", занято"
                                        isPremium -> ", место категории «Комфорт», ${maxOf(200, s.price) + s.premiumSurcharge} ₽"
                                        else -> ""
                                    }
                                    Box(
                                        Modifier
                                            .size(seatWidth, cell * 0.86f)
                                            .background(color, RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp, bottomStart = 3.dp, bottomEnd = 3.dp))
                                            .then(if (isTaken) Modifier.border(1.dp, Color(0xFF2E2E36), RoundedCornerShape(4.dp)) else Modifier)
                                            .semantics { contentDescription = seatLabel }
                                            .then(
                                                if (isTaken) Modifier else Modifier.clickableNoRipple {
                                                    if (on) picked.remove(id) else if (picked.size < 10) picked.add(id)
                                                    focus = if (on) picked.lastOrNull() ?: focus else id
                                                },
                                            ),
                                    )
                                    if (c in aisles) Spacer(Modifier.width(gapWidth))
                                }
                                Text("$r", fontSize = 10.sp, color = Kadr.Muted, modifier = Modifier.width(14.dp))
                            }
                        }
                    }
                    Box(Modifier.width(2.dp).height((cell + 4.dp) * s.hall.rows).background(Kadr.Exit))
                }
            }

            Row(
                Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
            ) {
                Legend(Kadr.Seat, "Свободно")
                Legend(Kadr.Accent, "Ваш выбор")
                Legend(Kadr.SeatTaken, "Занято")
                Legend(Kadr.Exit, "К выходу", thin = true)
            }
            if (premium.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
                ) {
                    Legend(Kadr.Seat, "Обычные · ${maxOf(200, s.price)} ₽")
                    Legend(PremiumSeatColor, "Комфорт · ${maxOf(200, s.price) + s.premiumSurcharge} ₽")
                }
            }
            if (gapsForGrid.isNotEmpty()) {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
                    ErrorText(
                        "Нельзя оставлять одно свободное место между местами: " +
                            gapsForGrid.joinToString { "ряд ${it.row}, место ${it.seat}" } + ". Выберите места рядом.",
                    )
                }
            }
            if (error != null) Column(Modifier.padding(horizontal = 20.dp)) { ErrorText(error) }
        }

        // Итог и переход к оформлению
        val gaps = gapsForGrid
        Row(
            modifier = Modifier.fillMaxWidth().background(Kadr.Surface).navigationBarsPadding().padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    if (picked.isEmpty()) "Выберите места на схеме"
                    else "${picked.size} ${ticketsWord(picked.size)} · " + picked.joinToString { "р${it.row} м${it.seat}" },
                    fontSize = 13.sp, color = Kadr.Muted, maxLines = 1,
                )
                Text("$total ₽", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Kadr.Text)
            }
            PrimaryButton(
                "Продолжить",
                enabled = picked.isNotEmpty() && gaps.isEmpty(),
                loading = busy,
                onClick = {
                    scope.launch {
                        busy = true
                        error = null
                        try {
                            val order = ApiClient.api.createOrder(OrderBody(sessionId, picked.map { SeatBody(it.row, it.seat) }))
                            onOrder(order.id)
                        } catch (e: Exception) {
                            error = e.userMessage()
                            // места могли занять — обновим схему
                            try { info = ApiClient.api.session(sessionId) } catch (ignored: Exception) { }
                            picked.clear()
                        }
                        busy = false
                    }
                },
            )
        }
    }
    }
    if (fullScreen && s != null) {
        val taken = remember(s) { s.taken.orEmpty().map { SeatId(it[0], it[1]) }.toSet() }
        Dialog(
            onDismissRequest = { fullScreen = false },
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
        ) {
            Box(Modifier.fillMaxSize().background(Kadr.Bg)) {
                SeatView3D(
                    focus, picked.isEmpty(), model, screenInfo(s, picked.toList(), taken, model, total), posterDataUrl,
                    modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(),
                    fullScreen = true,
                    onToggle = { fullScreen = false },
                )
            }
        }
    }
}

@Composable
private fun Legend(color: Color, label: String, thin: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(if (thin) 3.dp else 12.dp, if (thin) 14.dp else 12.dp).background(color, RoundedCornerShape(3.dp)))
        Text(label, fontSize = 12.sp, color = Kadr.Muted)
    }
}

/** Блок «Вид с места»: WebView с 3D-моделью зала (assets/hall3d.html). */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun SeatView3D(
    focus: SeatId,
    overview: Boolean,
    model: String,
    screenJson: String,
    posterDataUrl: String?,
    modifier: Modifier,
    fullScreen: Boolean = false,
    onToggle: () -> Unit,
) {
    var webView by remember { mutableStateOf<WebView?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var noGl by remember { mutableStateOf(false) }

    // Пока место не выбрано — камера в центре зала, после выбора — вид из кресла
    LaunchedEffect(focus, overview, loaded) {
        if (!loaded) return@LaunchedEffect
        webView?.evaluateJavascript(if (overview) "setOverview()" else "setSeat(${focus.row}, ${focus.seat})", null)
    }
    // Экран в зале показывает фильм, сеанс и выбранные места — обновляем при каждом выборе
    LaunchedEffect(screenJson, loaded) {
        if (loaded) webView?.evaluateJavascript("setScreen($screenJson)", null)
    }
    LaunchedEffect(posterDataUrl, loaded) {
        if (loaded) {
            val poster = posterDataUrl?.let { JSONObject.quote(it) } ?: "null"
            webView?.evaluateJavascript("KadrScreen.setPoster($poster)", null)
        }
    }

    Box(
        modifier.background(Color(0xFF0A0A0D), RoundedCornerShape(18.dp))
            .border(1.dp, Kadr.Line, RoundedCornerShape(18.dp)),
    ) {
        if (noGl) {
            Text("3D-вид не поддерживается на этом устройстве", color = Kadr.Muted, modifier = Modifier.align(Alignment.Center))
        } else {
            AndroidView(
                modifier = Modifier.fillMaxSize().padding(1.dp),
                onRelease = { it.destroy() },
                factory = { ctx ->
                    WebView(ctx).apply {
                        // Явный размер «на весь блок»: без него WebView подгоняет высоту под содержимое и холст получает высоту 0
                        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                        settings.javaScriptEnabled = true
                        // Свайп по 3D-виду поворачивает взгляд, а не прокручивает экран
                        setOnTouchListener { v, _ ->
                            v.parent?.requestDisallowInterceptTouchEvent(true)
                            false
                        }
                        setBackgroundColor(android.graphics.Color.parseColor("#0A0A0D"))
                        addJavascriptInterface(object {
                            @JavascriptInterface
                            fun noWebGL() {
                                post { noGl = true }
                            }
                        }, "Android")
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView?, url: String?) {
                                loaded = true
                            }
                        }
                        loadUrl("file:///android_asset/hall3d.html#$model")
                        webView = this
                    }
                },
            )
        }
        Row(
            Modifier.align(Alignment.TopStart).padding(10.dp).background(Color(0xCC0A0A0D), RoundedCornerShape(10.dp)).padding(horizontal = 9.dp, vertical = 5.dp),
        ) {
            Text(if (overview) "Вид на зал" else "Вид с места · ряд ${focus.row}, место ${focus.seat}", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Kadr.Text)
        }
        IconButton(
            onClick = onToggle,
            modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).size(40.dp)
                .background(Color(0xCC0A0A0D), CircleShape),
        ) {
            Icon(
                painterResource(if (fullScreen) R.drawable.ic_close_3d else R.drawable.ic_fullscreen_3d),
                contentDescription = if (fullScreen) "Закрыть полноэкранный 3D-вид" else "Открыть 3D на весь экран",
                tint = Kadr.Text,
                modifier = Modifier.size(22.dp),
            )
        }
        Text(
            "Проведите, чтобы осмотреться",
            fontSize = 11.sp,
            color = Color(0xFFD6D3DC),
            modifier = Modifier.align(Alignment.BottomStart).padding(start = 10.dp, end = 56.dp, bottom = 8.dp)
                .background(Color(0xCC0A0A0D), RoundedCornerShape(10.dp)).padding(horizontal = 8.dp, vertical = 5.dp),
        )
        IconButton(
            onClick = { webView?.evaluateJavascript("resetLook()", null) },
            modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp).size(36.dp).background(Color(0xCC0A0A0D), CircleShape),
        ) {
            Icon(painterResource(R.drawable.ic_reset), contentDescription = "Повернуть взгляд к экрану", tint = Kadr.Text, modifier = Modifier.size(18.dp))
        }
    }
}

/** Правило «нельзя через одно место»: одиночные свободные места между выбранными/занятыми (как на сервере).
 *  Места по разные стороны прохода соседями не считаются. */
private fun singleGaps(picked: Set<SeatId>, taken: Set<SeatId>, cols: Int, aisles: List<Int> = emptyList()): Set<SeatId> {
    val occupied = picked + taken
    val gaps = mutableSetOf<SeatId>()
    for (r in picked.map { it.row }.toSet()) {
        for (c in 2 until cols) {
            val here = SeatId(r, c)
            if (here in occupied || (c - 1) in aisles || c in aisles) continue
            val left = SeatId(r, c - 1)
            val right = SeatId(r, c + 1)
            if (left in occupied && right in occupied && (left in picked || right in picked)) gaps.add(here)
        }
    }
    return gaps
}

/** Данные для экрана в 3D-зале: постер, фильм, сеанс, выбранные места и мини-схема. */
private fun screenInfo(s: SessionInfo, picked: List<SeatId>, taken: Set<SeatId>, model: String, total: Int): String {
    val day = s.startsAt.take(10)
    val today = LocalDate.now()
    val prefix = when (day) {
        today.toString() -> "Сегодня, "
        today.plusDays(1).toString() -> "Завтра, "
        else -> ""
    }
    val m = s.film.durationMin
    val o = JSONObject()
        .put("cinema", s.cinema.name)
        .put("hall", s.hall.name)
        .put("title", s.film.title)
        .put("age", s.film.age)
        .put("genre", s.film.genre)
        .put("duration", if (m >= 60) "${m / 60} ч %02d мин".format(m % 60) else "$m мин")
        .put("format", s.format)
        .put("color", s.film.color)
        .put("date", prefix + humanDate(s.startsAt))
        .put("time", s.startsAt.takeLast(5))
        .put("rows", s.hall.rows)
        .put("cols", s.hall.seatsPerRow)
        .put("aisles", JSONArray(s.hall.aisles.orEmpty()))
        .put("model", model)
        .put("total", total)
        .put("premium", JSONArray(s.premiumSeats.orEmpty().map { JSONArray(it) }))
        .put("seats", JSONArray(picked.map { JSONArray(listOf(it.row, it.seat)) }))
        .put("taken", JSONArray(taken.map { JSONArray(listOf(it.row, it.seat)) }))
    return o.toString()
}

fun ticketsWord(n: Int): String {
    val m10 = n % 10
    val m100 = n % 100
    return when {
        m10 == 1 && m100 != 11 -> "билет"
        m10 in 2..4 && m100 !in 12..14 -> "билета"
        else -> "билетов"
    }
}

private val MONTHS = listOf("января", "февраля", "марта", "апреля", "мая", "июня", "июля", "августа", "сентября", "октября", "ноября", "декабря")

/** "2026-11-19 17:10" → "19 ноября" */
fun humanDate(startsAt: String): String {
    val d = startsAt.take(10).split("-")
    if (d.size < 3) return startsAt
    val month = d[1].toIntOrNull() ?: return startsAt
    return "${d[2].trimStart('0')} ${MONTHS.getOrElse(month - 1) { "" }}"
}
