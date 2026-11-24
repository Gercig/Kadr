package ru.kadr.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import ru.kadr.app.AppViewModel
import ru.kadr.app.R
import ru.kadr.app.data.ApiClient
import ru.kadr.app.data.Order
import ru.kadr.app.data.PayBody
import ru.kadr.app.data.Ticket
import ru.kadr.app.data.TicketTypeBody
import ru.kadr.app.data.userMessage
import ru.kadr.app.ui.components.AgeBadge
import ru.kadr.app.ui.components.Chip
import ru.kadr.app.ui.components.ErrorText
import ru.kadr.app.ui.components.Loading
import ru.kadr.app.ui.components.PrimaryButton
import ru.kadr.app.ui.components.ScreenHeader
import ru.kadr.app.ui.components.clickableNoRipple
import ru.kadr.app.ui.theme.Kadr
import ru.kadr.app.ui.theme.parseColor

// Типы билетов и скидки — как на сервере (services.TICKET_TYPES)
private val TYPES = listOf("adult" to "Взрослый", "reduced" to "Льготный", "child" to "Детский")
private val DISCOUNT = mapOf("adult" to 0, "reduced" to 100, "child" to 150)

private fun checkoutPrice(ticket: Ticket, type: String): Int =
    maxOf(200, ticket.price - ticket.seatSurcharge - (DISCOUNT[type] ?: 0)) + ticket.seatSurcharge

@Composable
fun CheckoutScreen(vm: AppViewModel, orderId: Int, onBack: () -> Unit, onPaid: () -> Unit) {
    val scope = rememberCoroutineScope()
    var order by remember { mutableStateOf<Order?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var secondsLeft by remember { mutableIntStateOf(0) }
    var useBonus by remember { mutableStateOf(true) }
    var payMethod by remember { mutableStateOf("card") }
    val types = remember { mutableStateMapOf<Int, String>() }

    LaunchedEffect(orderId) {
        try {
            val o = ApiClient.api.order(orderId)
            order = o
            secondsLeft = o.secondsLeft
            o.tickets.forEach { types[it.id] = it.type }
        } catch (e: Exception) {
            error = e.userMessage()
        }
    }
    LaunchedEffect(order) {
        while (order != null && secondsLeft > 0) {
            delay(1000)
            secondsLeft -= 1
        }
    }

    val o = order
    Column(Modifier.fillMaxSize().background(Kadr.Bg).statusBarsPadding()) {
        ScreenHeader("Оформление заказа", onBack = {
            // уходя назад, освобождаем места
            scope.launch { try { ApiClient.api.cancel(orderId) } catch (ignored: Exception) { } }
            onBack()
        })
        if (o == null) {
            if (error != null) Column(Modifier.padding(20.dp)) { ErrorText(error) } else Loading(Modifier.fillMaxSize())
            return@Column
        }
        val sum = o.tickets.sumOf { checkoutPrice(it, types[it.id] ?: it.type) }
        val balance = vm.user?.bonus ?: o.bonusBalance
        val bonus = if (useBonus && payMethod == "card") minOf((sum * 0.3).toInt(), balance) else 0
        val total = sum - bonus
        val expired = secondsLeft <= 0

        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(
                Modifier.fillMaxWidth().background(if (expired) Color(0xFF3A1614) else Color(0xFF2A1A19), RoundedCornerShape(12.dp)).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(painterResource(R.drawable.ic_clock), contentDescription = null, tint = Kadr.AccentSoft, modifier = Modifier.size(16.dp))
                Text(
                    if (expired) "Время брони истекло, выберите места заново"
                    else "Места забронированы ещё на %02d:%02d".format(secondsLeft / 60, secondsLeft % 60),
                    color = Color(0xFFFFC2BD), fontSize = 13.sp,
                )
            }

            Row(Modifier.fillMaxWidth().background(Kadr.Surface, RoundedCornerShape(18.dp)).padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.width(56.dp).height(80.dp).background(parseColor(o.session.film.color), RoundedCornerShape(10.dp)))
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(o.session.film.title, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = Kadr.Text)
                        AgeBadge(o.session.film.age)
                    }
                    Text("${humanDate(o.session.startsAt)} · ${o.session.startsAt.takeLast(5)}", fontSize = 13.sp, color = Kadr.Muted)
                    Text("${o.session.cinema.name} · ${o.session.hall.name} · ${o.session.format}", fontSize = 13.sp, color = Kadr.Muted)
                }
            }

            Column(Modifier.fillMaxWidth().background(Kadr.Surface, RoundedCornerShape(18.dp)).padding(horizontal = 14.dp, vertical = 6.dp)) {
                o.tickets.forEachIndexed { i, t ->
                    if (i > 0) HorizontalDivider(color = Kadr.Line)
                    val type = types[t.id] ?: t.type
                    Column(Modifier.padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Ряд ${t.row} · место ${t.seat}", fontWeight = FontWeight.SemiBold, color = Kadr.Text)
                            Text("${checkoutPrice(t, type)} ₽", fontWeight = FontWeight.SemiBold, color = Kadr.Text)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            TYPES.forEach { (key, label) -> Chip(label, key == type) { types[t.id] = key } }
                        }
                    }
                }
            }

            Row(
                Modifier.fillMaxWidth().background(Kadr.Surface, RoundedCornerShape(18.dp)).padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(Modifier.size(40.dp).background(Color(0xFF2A2A33), RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                    Icon(painterResource(R.drawable.ic_star), contentDescription = null, tint = Kadr.Gold, modifier = Modifier.size(20.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text("Списать бонусы", fontWeight = FontWeight.SemiBold, color = Kadr.Text)
                    Text(
                        if (payMethod == "card") "На карте $balance баллов, можно до 30% суммы" else "Бонусы действуют только при оплате картой",
                        fontSize = 12.sp, color = Kadr.Muted,
                    )
                }
                Switch(
                    checked = useBonus && payMethod == "card",
                    onCheckedChange = { useBonus = it },
                    enabled = payMethod == "card" && balance > 0,
                    colors = SwitchDefaults.colors(checkedTrackColor = Kadr.Accent, checkedThumbColor = Color.White),
                )
            }

            Text("Способ оплаты", fontSize = 13.sp, color = Kadr.Muted, modifier = Modifier.padding(start = 4.dp))
            PayOption("Банковская карта", "МИР, Visa, Mastercard · тестовая оплата", payMethod == "card") { payMethod = "card" }
            PayOption("Оплатить в кассе", "Бронь сохранится, оплата на месте", payMethod == "cash") { payMethod = "cash" }
            ErrorText(error)
        }

        Column(
            Modifier.fillMaxWidth().background(Kadr.Surface).navigationBarsPadding().padding(horizontal = 20.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("$sum ₽" + if (bonus > 0) " − $bonus бонусами" else "", fontSize = 13.sp, color = Kadr.Muted)
                if (payMethod == "card") Text("+${(total * 0.05).toInt()} баллов", fontSize = 13.sp, color = Kadr.Gold)
            }
            PrimaryButton(
                if (payMethod == "card") "Оплатить $total ₽" else "Забронировать",
                enabled = !expired,
                loading = busy,
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    scope.launch {
                        busy = true
                        error = null
                        try {
                            ApiClient.api.pay(orderId, PayBody(o.tickets.map { TicketTypeBody(it.id, types[it.id] ?: "adult") }, useBonus, payMethod))
                            vm.refreshUser()
                            onPaid()
                        } catch (e: Exception) {
                            error = e.userMessage()
                        }
                        busy = false
                    }
                },
            )
        }
    }
}

@Composable
private fun PayOption(title: String, sub: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Kadr.Surface, RoundedCornerShape(14.dp))
            .border(1.dp, if (selected) Kadr.Accent else Kadr.Line, RoundedCornerShape(14.dp))
            .clickableNoRipple(onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.size(18.dp).border(if (selected) 5.dp else 2.dp, if (selected) Kadr.Accent else Color(0xFF55525E), CircleShape),
        )
        Column {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = Kadr.Text)
            Text(sub, fontSize = 12.sp, color = Kadr.Muted)
        }
    }
}
