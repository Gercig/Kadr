package ru.kadr.app.ui.staff

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import ru.kadr.app.data.ApiClient
import ru.kadr.app.data.Payment
import ru.kadr.app.data.RefundBody
import ru.kadr.app.data.SalesReport
import ru.kadr.app.data.StaffCinema
import ru.kadr.app.data.userMessage
import ru.kadr.app.ui.components.ErrorText
import ru.kadr.app.ui.components.KadrField
import ru.kadr.app.ui.components.Loading
import ru.kadr.app.ui.components.SecondaryButton
import ru.kadr.app.ui.theme.Kadr
import java.time.LocalDate

private data class Period(val title: String, val start: LocalDate, val end: LocalDate)

private fun periods(): List<Period> {
    val t = LocalDate.now()
    return listOf(
        Period("Сегодня", t, t),
        Period("Вчера", t.minusDays(1), t.minusDays(1)),
        Period("7 дней", t.minusDays(6), t),
        Period("30 дней", t.minusDays(29), t),
        Period("Неделя вперёд", t, t.plusDays(6)),
    )
}

/** Для админа сети — выбор кинотеатра («Вся сеть» = 0); для админа кинотеатра — только свой. */
@Composable
private fun CinemaPicker(cinemas: List<StaffCinema>, selected: Int, onSelect: (Int) -> Unit) {
    if (cinemas.isEmpty()) return
    Choice(null, listOf(0) + cinemas.map { it.id }, selected, { id -> if (id == 0) "Вся сеть" else cinemas.first { it.id == id }.name }, onSelect)
}

// ---------------------------------------------------------------- отчёты и статистика продаж

@Composable
fun ReportTab(cinemas: List<StaffCinema>) {
    val ps = remember { periods() }
    var period by remember { mutableStateOf(ps[2]) }
    var cinema by remember { mutableStateOf(0) }
    var report by remember { mutableStateOf<SalesReport?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(period, cinema) {
        report = null
        try {
            report = ApiClient.api.report(period.start.toString(), period.end.toString(), cinema); error = null
        } catch (e: Exception) { error = e.userMessage() }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { CinemaPicker(cinemas, cinema) { cinema = it } }
        item { Choice(null, ps, period, { it.title }) { period = it } }
        item { ErrorText(error) }
        val r = report
        if (r == null) { item { Loading(Modifier.fillMaxWidth().padding(30.dp)) }; return@LazyColumn }
        item {
            Card {
                Note("По дате сеанса: ${shortStamp(r.from + " 00:00").substringBefore(",")} — ${shortStamp(r.to + " 00:00").substringBefore(",")}")
                Text(rub(r.total.revenue), fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Kadr.Text)
                Note("выручка по оплатам картой")
                KV("Продано билетов", "${r.total.tickets}")
                KV("Заказов", "${r.total.orders}")
                KV("Брони с оплатой на кассе", rub(r.total.cashReserved))
                KV("Оплачено бонусами", rub(r.total.bonusUsed))
                KV("Сеансов", "${r.total.sessions}")
                KV("Заполняемость залов", "${r.total.occupancy} %")
                KV("Возвраты", "${r.total.refunds} · ${rub(r.total.refundSum)}")
            }
        }
        if (r.days.isNotEmpty()) item {
            Card {
                Title("Выручка по дням")
                val max = (r.days.maxOf { it.revenue }).coerceAtLeast(1)
                Canvas(Modifier.fillMaxWidth().height(120.dp)) {
                    val n = r.days.size
                    val w = size.width / n
                    r.days.forEachIndexed { i, d ->
                        val h = size.height * d.revenue / max
                        drawRoundRect(Kadr.Accent, Offset(i * w + w * 0.15f, size.height - h), Size(w * 0.7f, h), CornerRadius(6f, 6f))
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Note(r.days.first().day.substring(5).replace('-', '.'))
                    Note(r.days.last().day.substring(5).replace('-', '.'))
                }
            }
        }
        if (r.films.isNotEmpty()) item { Title("По фильмам") }
        items(r.films) { f ->
            Card {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text(f.film, fontSize = 14.sp, color = Kadr.Text); Note("билетов: ${f.tickets}") }
                    Text(rub(f.revenue), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Kadr.Text)
                }
            }
        }
    }
}

// ---------------------------------------------------------------- журнал оплат и возвраты

private val STATUSES = listOf("" to "Все", "paid" to "Оплачены", "reserved" to "На кассе", "refunded" to "Возвраты", "cancelled" to "Отменены")

@Composable
fun PaymentsTab(cinemas: List<StaffCinema>) {
    val ps = remember { periods() }
    var period by remember { mutableStateOf(ps[3]) }
    var status by remember { mutableStateOf("") }
    var cinema by remember { mutableStateOf(0) }
    var q by remember { mutableStateOf("") }
    var list by remember { mutableStateOf<List<Payment>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableStateOf(0) }
    var refunding by remember { mutableStateOf<Payment?>(null) }
    LaunchedEffect(period, status, cinema, q, reload) {
        delay(250)
        list = null
        try {
            list = ApiClient.api.payments(cinema, period.start.toString(), period.end.toString(), status, q); error = null
        } catch (e: Exception) { error = e.userMessage(); list = emptyList() }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { CinemaPicker(cinemas, cinema) { cinema = it } }
        item { Choice(null, ps, period, { it.title }) { period = it } }
        item { Choice(null, STATUSES.map { it.first }, status, { s -> STATUSES.first { it.first == s }.second }) { status = it } }
        item { KadrField(q, { q = it }, "Зритель: фамилия, почта или телефон") }
        item { ErrorText(error) }
        if (list == null) item { Loading(Modifier.fillMaxWidth().padding(30.dp)) }
        list?.let { l -> item { Note("Операций: ${l.size} · на сумму ${rub(l.filter { it.status == "paid" }.sumOf { it.total })} (оплачено картой)") } }
        items(list.orEmpty()) { p ->
            Card {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Title("Заказ №${p.id} · ${rub(p.total)}")
                    Badge(p.statusName, when (p.status) { "paid" -> Kadr.Mint; "reserved" -> Kadr.Gold; "refunded" -> Kadr.AccentSoft; else -> Kadr.Muted })
                }
                Note("${shortStamp(p.createdAt)} · ${if (p.payMethod == "card") "картой онлайн" else "на кассе"}${if (p.bonusUsed > 0) " · бонусами ${p.bonusUsed}" else ""}")
                Note("${p.user} · ${p.email} · ${p.phone}")
                Note("${p.film} · ${shortStamp(p.startsAt)} · ${p.cinema}, ${p.hall} · билетов: ${p.tickets}")
                if (p.refundReason != null) Note("Возврат ${shortStamp(p.refundedAt)}: ${p.refundReason}", Kadr.AccentSoft)
                if (p.status == "paid" || p.status == "reserved") {
                    SecondaryButton("Оформить возврат", onClick = { refunding = p }, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
    refunding?.let { p -> RefundDialog(p, onDone = { refunding = null; reload++ }, onDismiss = { refunding = null }) }
}

@Composable
private fun RefundDialog(p: Payment, onDone: () -> Unit, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var reason by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    FormDialog("Возврат по заказу №${p.id}", error, busy, confirm = "Подтвердить возврат", onDismiss = onDismiss, onConfirm = {
        scope.launch {
            busy = true
            error = try { ApiClient.api.refund(p.id, RefundBody(reason)); null } catch (e: Exception) { e.userMessage() }
            busy = false
            if (error == null) onDone()
        }
    }) {
        KV("Зритель", p.user)
        KV("Сеанс", "${p.film}, ${shortStamp(p.startsAt)}")
        KV("Сумма к возврату", rub(p.total))
        if (p.bonusUsed > 0) Note("Списанные бонусы (${p.bonusUsed}) вернутся зрителю, начисленные за заказ — спишутся.")
        KadrField(reason, { reason = it }, "Причина возврата")
        Note("Места освободятся и снова появятся в продаже.")
    }
}
