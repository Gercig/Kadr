package ru.kadr.app.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import ru.kadr.app.AppViewModel
import ru.kadr.app.R
import ru.kadr.app.data.ApiClient
import ru.kadr.app.data.Order
import ru.kadr.app.data.Ticket
import ru.kadr.app.data.userMessage
import ru.kadr.app.ui.components.ErrorText
import ru.kadr.app.ui.components.Loading
import ru.kadr.app.ui.components.PrimaryButton
import ru.kadr.app.ui.components.ScreenHeader
import ru.kadr.app.ui.components.clickableNoRipple
import ru.kadr.app.ui.theme.Kadr
import ru.kadr.app.ui.theme.parseColor

private val Paper = Color(0xFFF5F3EE)
private val Ink = Color(0xFF0E0E12)

fun qrBitmap(text: String, size: Int = 512): Bitmap {
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
    val pixels = IntArray(size * size)
    for (y in 0 until size) for (x in 0 until size) {
        pixels[y * size + x] = if (matrix.get(x, y)) 0xFF0E0E12.toInt() else 0xFFF5F3EE.toInt()
    }
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    bmp.setPixels(pixels, 0, size, 0, 0, size, size)
    return bmp
}

/** Экран после оплаты: электронный билет с QR-кодом для контролёра. */
@Composable
fun TicketScreen(orderId: Int, onHome: () -> Unit) {
    var order by remember { mutableStateOf<Order?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var current by remember { mutableStateOf(0) }
    LaunchedEffect(orderId) {
        try {
            order = ApiClient.api.order(orderId)
        } catch (e: Exception) {
            error = e.userMessage()
        }
    }
    val o = order
    Column(Modifier.fillMaxSize().background(Kadr.Bg).statusBarsPadding().navigationBarsPadding()) {
        if (o == null) {
            if (error != null) Column(Modifier.padding(20.dp)) { ErrorText(error) } else Loading(Modifier.fillMaxSize())
            return@Column
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(Modifier.height(16.dp))
            Box(Modifier.size(56.dp).background(Color(0xFF1E3A31), CircleShape), contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.ic_check), contentDescription = null, tint = Kadr.Mint, modifier = Modifier.size(28.dp))
            }
            Text(if (o.status == "paid") "Оплата прошла" else "Места забронированы", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Kadr.Text)
            Text(
                if (o.status == "paid") "Билеты сохранены в разделе «Билеты». Начислено ${o.bonusEarned} баллов."
                else "Оплатите билеты в кассе не позднее чем за 30 минут до начала сеанса.",
                color = Kadr.Muted, fontSize = 14.sp,
            )
            if (o.tickets.size > 1) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(o.tickets.size) { i ->
                        val t = o.tickets[i]
                        val on = i == current
                        Box(
                            Modifier.background(if (on) Kadr.Text else Kadr.Surface2, RoundedCornerShape(12.dp))
                                .clickableNoRipple { current = i }.padding(horizontal = 12.dp, vertical = 8.dp),
                        ) {
                            Text("Ряд ${t.row} · ${t.seat}", color = if (on) Kadr.Bg else Kadr.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
            TicketCard(o, o.tickets[current.coerceIn(0, o.tickets.lastIndex)])
        }
        PrimaryButton("На главную", onClick = onHome, modifier = Modifier.fillMaxWidth().padding(20.dp))
    }
}

@Composable
private fun TicketCard(o: Order, t: Ticket) {
    val qr = remember(t.code) { qrBitmap(t.code).asImageBitmap() }
    Column(Modifier.fillMaxWidth().background(Paper, RoundedCornerShape(22.dp))) {
        Row(
            Modifier.fillMaxWidth().background(Kadr.Accent, RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)).padding(horizontal = 18.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("КАДР", color = Color.White, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
            Text("Электронный билет", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(o.session.film.title, color = Ink, fontSize = 19.sp, fontWeight = FontWeight.Bold)
            Text("${o.session.cinema.name}, ${o.session.cinema.address}", color = Color(0xFF5C5A63), fontSize = 12.sp)
            Row(Modifier.fillMaxWidth()) {
                Field("Дата", humanDate(o.session.startsAt), Modifier.weight(1f))
                Field("Начало", o.session.startsAt.takeLast(5), Modifier.weight(1f))
                Field("Зал", o.session.hall.name.removePrefix("Зал "), Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth()) {
                Field("Ряд", "${t.row}", Modifier.weight(1f))
                Field("Место", "${t.seat}", Modifier.weight(1f))
                Field(t.typeName, "${t.price} ₽", Modifier.weight(1f))
            }
        }
        Box(Modifier.fillMaxWidth().height(2.dp).padding(horizontal = 18.dp).background(Color(0xFFC9C5CF)))
        Column(Modifier.fillMaxWidth().padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Image(bitmap = qr, contentDescription = "QR-код билета", modifier = Modifier.size(180.dp), filterQuality = FilterQuality.None)
            Text("Покажите код контролёру на входе · ${t.code}", color = Color(0xFF5C5A63), fontSize = 12.sp)
            if (o.status == "reserved") Text("Не оплачен — оплата в кассе", color = Color(0xFFB3261E), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun Field(label: String, value: String, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, color = Color(0xFF5C5A63), fontSize = 11.sp)
        Text(value, color = Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** Вкладка «Билеты»: все оплаченные и забронированные заказы. */
@Composable
fun TicketsScreen(vm: AppViewModel, onOpen: (Int) -> Unit, onLogin: () -> Unit) {
    var orders by remember { mutableStateOf<List<Order>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(vm.user) {
        if (vm.user == null) return@LaunchedEffect
        try {
            orders = ApiClient.api.tickets()
        } catch (e: Exception) {
            error = e.userMessage()
        }
    }
    val list = orders
    Column(Modifier.fillMaxSize().background(Kadr.Bg).statusBarsPadding()) {
        ScreenHeader("Мои билеты")
        when {
            vm.user == null -> Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("Войдите, чтобы видеть свои билеты", color = Kadr.Muted)
                PrimaryButton("Войти", onClick = onLogin, modifier = Modifier.fillMaxWidth())
            }
            error != null -> Column(Modifier.padding(20.dp)) { ErrorText(error) }
            list == null -> Loading(Modifier.fillMaxSize())
            list.isEmpty() -> Text("Билетов пока нет. Выберите фильм на вкладке «Афиша».", color = Kadr.Muted, modifier = Modifier.padding(20.dp))
            else -> LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(list, key = { it.id }) { o ->
                    Row(
                        Modifier.fillMaxWidth().background(Kadr.Surface, RoundedCornerShape(16.dp)).clickableNoRipple { onOpen(o.id) }.padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.width(44.dp).height(62.dp).background(parseColor(o.session.film.color), RoundedCornerShape(8.dp)))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(o.session.film.title, fontWeight = FontWeight.SemiBold, color = Kadr.Text)
                            Text("${humanDate(o.session.startsAt)} · ${o.session.startsAt.takeLast(5)} · ${o.session.cinema.name}", fontSize = 12.sp, color = Kadr.Muted)
                            Text(
                                "${o.session.hall.name} · " + o.tickets.joinToString { "р${it.row} м${it.seat}" },
                                fontSize = 12.sp, color = Kadr.Muted,
                            )
                        }
                        Text(
                            if (o.status == "paid") "Оплачен" else "В кассе",
                            fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                            color = if (o.status == "paid") Kadr.Mint else Kadr.Gold,
                        )
                    }
                }
            }
        }
    }
}
