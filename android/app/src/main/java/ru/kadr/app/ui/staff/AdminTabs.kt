package ru.kadr.app.ui.staff

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import ru.kadr.app.data.ApiClient
import ru.kadr.app.data.CinemaBody
import ru.kadr.app.data.HallBody
import ru.kadr.app.data.StaffBody
import ru.kadr.app.data.StaffCinema
import ru.kadr.app.data.StaffHall
import ru.kadr.app.data.StaffUser
import ru.kadr.app.data.UserPatch
import ru.kadr.app.data.userMessage
import ru.kadr.app.ui.components.ErrorText
import ru.kadr.app.ui.components.KadrField
import ru.kadr.app.ui.components.Loading
import ru.kadr.app.ui.components.PrimaryButton
import ru.kadr.app.ui.components.SecondaryButton
import ru.kadr.app.ui.components.clickableNoRipple
import ru.kadr.app.ui.theme.Kadr

val HALL_MODELS = listOf("vip" to "Стандартный зал", "vip2" to "Зал 2 VIP (7 × 6)", "imax" to "Зал IMAX (8 × 43)")

// ---------------------------------------------------------------- кинотеатры и их залы (админ приложения)

@Composable
fun CinemasTab(cinemas: List<StaffCinema>, refresh: () -> Unit) {
    var editCinema by remember { mutableStateOf<StaffCinema?>(null) }
    var newCinema by remember { mutableStateOf(false) }
    var editHall by remember { mutableStateOf<Pair<Int, StaffHall?>?>(null) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { PrimaryButton("Добавить кинотеатр", onClick = { newCinema = true }, modifier = Modifier.fillMaxWidth()) }
        items(cinemas) { c ->
            Card {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Title(c.name)
                        Note("${c.city}, ${c.address}")
                    }
                    Badge(if (c.status == "active") "работает" else "закрыт", if (c.status == "active") Kadr.Mint else Kadr.Muted)
                }
                c.halls.orEmpty().forEach { h ->
                    Row(
                        Modifier.fillMaxWidth().clickableNoRipple { editHall = c.id to h }.padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(h.name, fontSize = 13.sp, color = Kadr.Text)
                        Note("${h.rows} × ${h.seatsPerRow} = ${h.rows * h.seatsPerRow} мест")
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SecondaryButton("Изменить", onClick = { editCinema = c }, modifier = Modifier.weight(1f))
                    SecondaryButton("+ Зал", onClick = { editHall = c.id to null }, modifier = Modifier.weight(1f))
                }
            }
        }
    }
    if (newCinema || editCinema != null) CinemaDialog(editCinema, onDone = { newCinema = false; editCinema = null; refresh() }, onDismiss = { newCinema = false; editCinema = null })
    editHall?.let { (cid, h) -> HallDialog(cid, h, onDone = { editHall = null; refresh() }, onDismiss = { editHall = null }) }
}

@Composable
private fun CinemaDialog(c: StaffCinema?, onDone: () -> Unit, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(c?.name ?: "Кадр ") }
    var city by remember { mutableStateOf(c?.city ?: "") }
    var address by remember { mutableStateOf(c?.address ?: "") }
    var active by remember { mutableStateOf(c?.status != "closed") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    FormDialog(if (c == null) "Новый кинотеатр" else "Кинотеатр", error, busy, onDismiss = onDismiss, onConfirm = {
        scope.launch {
            busy = true
            error = try {
                val body = CinemaBody(name, city, address, if (active) "active" else "closed")
                if (c == null) ApiClient.api.addCinema(body) else ApiClient.api.editCinema(c.id, body)
                null
            } catch (e: Exception) { e.userMessage() }
            busy = false
            if (error == null) onDone()
        }
    }) {
        KadrField(name, { name = it }, "Название")
        KadrField(city, { city = it }, "Город")
        KadrField(address, { address = it }, "Адрес")
        Choice("Состояние", listOf(true, false), active, { if (it) "Работает" else "Закрыт" }) { active = it }
    }
}

@Composable
fun HallDialog(cinemaId: Int, h: StaffHall?, onDone: () -> Unit, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(h?.name ?: "Зал ") }
    var rows by remember { mutableStateOf(h?.rows?.toString() ?: "10") }
    var seats by remember { mutableStateOf(h?.seatsPerRow?.toString() ?: "16") }
    var model by remember { mutableStateOf(h?.modelUrl ?: "vip") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    FormDialog(if (h == null) "Новый зал" else "Зал", error, busy, onDismiss = onDismiss, onConfirm = {
        scope.launch {
            busy = true
            error = try {
                val body = HallBody(name, rows.toIntOrNull() ?: 0, seats.toIntOrNull() ?: 0, model)
                if (h == null) ApiClient.api.addHall(cinemaId, body) else ApiClient.api.editHall(h.id, body)
                null
            } catch (e: Exception) { e.userMessage() }
            busy = false
            if (error == null) onDone()
        }
    }) {
        KadrField(name, { name = it }, "Название зала")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            KadrField(rows, { rows = it.filter(Char::isDigit) }, "Рядов", Modifier.weight(1f), keyboard = KeyboardType.Number)
            KadrField(seats, { seats = it.filter(Char::isDigit) }, "Мест в ряду", Modifier.weight(1f), keyboard = KeyboardType.Number)
        }
        Choice("3D-модель для вида из кресла", HALL_MODELS.map { it.first }, model, { k -> HALL_MODELS.first { it.first == k }.second }) {
            model = it
            when (it) {
                "imax" -> { rows = "8"; seats = "43" }
                "vip2" -> { rows = "7"; seats = "6" }
            }
        }
        Note("Схему мест нельзя менять, если на будущие сеансы уже проданы билеты.")
    }
}

// ---------------------------------------------------------------- сотрудники (админ приложения выдаёт логин и пароль)

@Composable
fun StaffTab(cinemas: List<StaffCinema>) {
    val scope = rememberCoroutineScope()
    var list by remember { mutableStateOf<List<StaffUser>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableStateOf(0) }
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<StaffUser?>(null) }
    LaunchedEffect(reload) {
        list = try {
            (ApiClient.api.staffUsers("", "cinema_admin") + ApiClient.api.staffUsers("", "controller")).sortedBy { it.cinemaName ?: "" }
        } catch (e: Exception) { error = e.userMessage(); emptyList() }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { PrimaryButton("Добавить сотрудника", onClick = { adding = true }, modifier = Modifier.fillMaxWidth()) }
        item { ErrorText(error) }
        if (list == null) item { Loading(Modifier.fillMaxWidth().padding(30.dp)) }
        items(list.orEmpty()) { u ->
            Card(Modifier.clickableNoRipple { editing = u }) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Title("${u.lastName} ${u.firstName}")
                    if (u.blocked) Badge("заблокирован", Kadr.AccentSoft)
                }
                Note("${u.roleName} · ${u.cinemaName ?: "—"}")
                Note("Логин: ${u.email} · ${u.phone}")
            }
        }
    }
    if (adding) NewStaffDialog(cinemas, onDone = { adding = false; reload++ }, onDismiss = { adding = false })
    editing?.let { u -> UserDialog(u, cinemas, onDone = { editing = null; reload++ }, onDismiss = { editing = null }) }
}

@Composable
private fun NewStaffDialog(cinemas: List<StaffCinema>, onDone: () -> Unit, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var last by remember { mutableStateOf("") }
    var first by remember { mutableStateOf("") }
    var login by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var role by remember { mutableStateOf("controller") }
    var cinema by remember { mutableStateOf(cinemas.firstOrNull()) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    FormDialog("Новый сотрудник", error, busy, confirm = "Создать", onDismiss = onDismiss, onConfirm = {
        scope.launch {
            busy = true
            error = try {
                ApiClient.api.addStaff(StaffBody(last, first, login, phone, password, role, cinema?.id ?: 0)); null
            } catch (e: Exception) { e.userMessage() }
            busy = false
            if (error == null) onDone()
        }
    }) {
        KadrField(last, { last = it }, "Фамилия")
        KadrField(first, { first = it }, "Имя")
        KadrField(login, { login = it.trim() }, "Логин или почта для входа")
        KadrField(phone, { phone = it }, "Телефон", keyboard = KeyboardType.Phone, placeholder = "+7 900 000-00-00")
        KadrField(password, { password = it }, "Пароль (не короче 6 символов)")
        Choice("Должность", listOf("controller", "cinema_admin"), role, { if (it == "controller") "Контролёр" else "Администратор кинотеатра" }) { role = it }
        Choice("Кинотеатр", cinemas, cinema, { it.name }) { cinema = it }
        Note("Логин и пароль передайте сотруднику лично.")
    }
}

/** Карточка пользователя: блокировка/разблокировка, новый пароль сотруднику. */
@Composable
private fun UserDialog(u: StaffUser, cinemas: List<StaffCinema>, onDone: () -> Unit, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var password by remember { mutableStateOf("") }
    var cinema by remember { mutableStateOf(cinemas.firstOrNull { it.id == u.cinemaId }) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    fun send(p: UserPatch) {
        scope.launch {
            busy = true
            error = try { ApiClient.api.editUser(u.id, p); null } catch (e: Exception) { e.userMessage() }
            busy = false
            if (error == null) onDone()
        }
    }
    val staff = u.role == "cinema_admin" || u.role == "controller"
    FormDialog("${u.lastName} ${u.firstName}", error, busy, confirm = "Сохранить", onDismiss = onDismiss, onConfirm = {
        send(UserPatch(password = password.ifBlank { null }, cinemaId = if (staff && cinema?.id != u.cinemaId) cinema?.id else null))
    }) {
        Note("${u.roleName} · ${u.email} · ${u.phone}")
        if (u.role == "viewer") Note("Бонусов: ${u.bonus}")
        if (u.failedLogins > 0) Note("Неудачных попыток входа: ${u.failedLogins}", Kadr.AccentSoft)
        if (staff) {
            Choice("Кинотеатр", cinemas, cinema, { it.name }) { cinema = it }
            KadrField(password, { password = it }, "Новый пароль (если нужно сменить)")
        }
        if (u.role != "app_admin") {
            SecondaryButton(
                if (u.blocked) "Разблокировать" else "Заблокировать",
                onClick = { send(UserPatch(blocked = !u.blocked)) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

// ---------------------------------------------------------------- поиск пользователей (без учёта регистра, по подстроке)

@Composable
fun UsersTab() {
    var q by remember { mutableStateOf("") }
    var list by remember { mutableStateOf<List<StaffUser>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<StaffUser?>(null) }
    var reload by remember { mutableStateOf(0) }
    LaunchedEffect(q, reload) {
        delay(300)   // ищем, когда человек перестал печатать
        list = try { ApiClient.api.staffUsers(q) } catch (e: Exception) { error = e.userMessage(); emptyList() }
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        KadrField(q, { q = it }, "Поиск: фамилия, имя, почта или телефон", Modifier.padding(top = 12.dp))
        ErrorText(error)
        LazyColumn(contentPadding = PaddingValues(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (list == null) item { Loading(Modifier.fillMaxWidth().padding(30.dp)) }
            item { if (list != null) Note("Найдено: ${list!!.size}") }
            items(list.orEmpty()) { u ->
                Card(Modifier.clickableNoRipple { editing = u }) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Title("${u.lastName} ${u.firstName}")
                        if (u.blocked) Badge("заблокирован", Kadr.AccentSoft) else Badge(u.roleName, Kadr.Muted)
                    }
                    Note("${u.email} · ${u.phone}")
                }
            }
        }
    }
    editing?.let { u -> UserDialog(u, emptyList(), onDone = { editing = null; reload++ }, onDismiss = { editing = null }) }
}
