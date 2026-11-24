package ru.kadr.app.data.local

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.room.withTransaction
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Locale
import ru.kadr.app.data.*

class StaffRepository(
    private val db: KadrDatabase,
    private val catalog: CatalogRepository,
    private val accounts: AccountRepository,
    private val booking: BookingRepository,
) {
    private val data = db.catalog()
    private val users = db.accounts()
    private val orders = db.orders()

    private suspend fun requireRole(vararg roles: String): UserEntity =
        accounts.requireUser().also { check(it.role in roles) { "Недостаточно прав" } }

    private suspend fun managers() = requireRole("app_admin", "cinema_admin")

    private fun UserEntity.canAccess(cinemaId: Int) = role == "app_admin" || this.cinemaId == cinemaId

    private fun UserEntity.checkCinema(cinemaId: Int) {
        check(canAccess(cinemaId)) { "Доступ только к своему кинотеатру" }
    }

    suspend fun staffCinemas(): List<StaffCinema> {
        val user = requireRole("app_admin", "cinema_admin", "controller")
        val halls = data.halls().groupBy { it.cinemaId }
        return data.cinemas().filter { user.canAccess(it.id) }.map {
            it.display(halls[it.id].orEmpty().map { hall -> hall.staff() })
        }
    }

    suspend fun addCinema(body: CinemaBody) = saveCinema(null, body)
    suspend fun editCinema(id: Int, body: CinemaBody) = saveCinema(id, body)

    private suspend fun saveCinema(id: Int?, body: CinemaBody): StaffCinema = db.withTransaction {
        requireRole("app_admin")
        val existing = id?.let { data.cinema(it) ?: error("Кинотеатр не найден") }
        require(body.status in setOf("active", "inactive", "maintenance", "closed")) { "Неизвестный статус" }
        val value = CinemaEntity(id ?: 0, field(body.name, "Название"), field(body.city, "Город"),
            field(body.address, "Адрес"), body.status)
        if (existing != null) data.updateCinema(value)
        val saved = if (id == null) value.copy(id = data.insertCinema(value).toInt()) else value
        saved.display(data.halls().filter { it.cinemaId == saved.id }.map { it.staff() })
    }

    suspend fun addHall(cinemaId: Int, body: HallBody) = saveHall(null, cinemaId, body)
    suspend fun editHall(id: Int, body: HallBody) = saveHall(id, null, body)

    private suspend fun saveHall(id: Int?, cinemaId: Int?, body: HallBody): StaffHall = db.withTransaction {
        val user = managers()
        val existing = id?.let { data.hall(it) ?: error("Зал не найден") }
        val cid = existing?.cinemaId ?: cinemaId ?: error("Кинотеатр не выбран")
        user.checkCinema(cid)
        require(data.cinema(cid) != null) { "Кинотеатр не найден" }
        require(body.rows in 1..50 && body.seatsPerRow in 1..100) { "Проверьте вместимость зала" }
        val model = body.modelUrl.ifBlank { "vip" }
        require(model in setOf("vip", "vip2", "imax")) { "Неизвестная модель зала" }
        if (existing != null && (body.rows < existing.rows || body.seatsPerRow < existing.seatsPerRow)) {
            val occupied = orders.all().filter { it.status in activeOrders }
                .filter { data.session(it.sessionId)?.hallId == existing.id }
                .flatMap { orders.tickets(it.id) }
            require(occupied.none { it.row > body.rows || it.seat > body.seatsPerRow }) { "Часть мест уже занята" }
        }
        val value = HallEntity(id ?: 0, cid, field(body.name, "Название"), body.rows, body.seatsPerRow, model)
        if (existing != null) data.updateHall(value)
        val saved = if (id == null) value.copy(id = data.insertHall(value).toInt()) else value
        if (existing == null || existing.layout() != saved.layout()) {
            data.clearSeats(saved.id)
            data.insertSeats(saved.layout())
        }
        saved.staff()
    }

    suspend fun staffUsers(q: String, role: String = ""): List<StaffUser> {
        requireRole("app_admin")
        val query = q.trim()
        return users.users().filter {
            (role.isBlank() || it.role == role) && "${it.fullName()} ${it.email} ${it.phone}".contains(query, ignoreCase = true)
        }.map { it.staff() }
    }

    suspend fun addStaff(body: StaffBody): StaffUser = db.withTransaction {
        requireRole("app_admin")
        require(body.role in setOf("cinema_admin", "controller") && data.cinema(body.cinemaId) != null) { "Выберите должность и кинотеатр" }
        val login = field(normalizeLogin(body.login), "Логин")
        require(users.byLogin(login) == null) { "Этот логин уже используется" }
        val user = UserEntity(lastName = field(body.lastName, "Фамилия"), firstName = field(body.firstName, "Имя"),
            email = login, phone = body.phone.trim(), verifier = verifiedHash(body.password), role = body.role, cinemaId = body.cinemaId)
        user.copy(id = users.insertUser(user).toInt()).staff()
    }

    suspend fun editUser(id: Int, body: UserPatch): StaffUser = db.withTransaction {
        val actor = requireRole("app_admin")
        val user = users.user(id) ?: error("Пользователь не найден")
        val role = body.role ?: user.role
        val cid = body.cinemaId ?: user.cinemaId
        require(role in roleNames && (role != "app_admin" || user.role == "app_admin")) { "Неизвестная роль" }
        require(id != actor.id || (body.blocked != true && role == "app_admin")) { "Нельзя заблокировать своего администратора" }
        require(role !in setOf("cinema_admin", "controller") || (cid != null && data.cinema(cid) != null)) { "Выберите кинотеатр сотрудника" }
        require(body.password == null || user.role != "viewer") { "Зритель задаёт пароль сам" }
        val patched = user.copy(role = role, cinemaId = cid, blocked = body.blocked ?: user.blocked,
            failedLogins = if (body.blocked != null || body.password != null) 0 else user.failedLogins,
            verifier = body.password?.let(::verifiedHash) ?: user.verifier)
        users.updateUser(patched)
        patched.staff()
    }

    suspend fun staffFilms(): List<StaffFilm> {
        managers()
        return data.films().map { it.staff() }
    }

    suspend fun addFilm(body: FilmBody) = saveFilm(null, body)
    suspend fun editFilm(id: Int, body: FilmBody) = saveFilm(id, body)

    private suspend fun saveFilm(id: Int?, body: FilmBody): StaffFilm = db.withTransaction {
        managers()
        val old = id?.let { data.film(it) ?: error("Фильм не найден") }
        require(body.durationMin in 1..600 && body.age in setOf("0+", "6+", "12+", "16+", "18+")) { "Проверьте возраст и длительность" }
        val image = body.poster?.let {
            val bytes = Base64.decode(it, Base64.DEFAULT)
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            require(bytes.size <= 1_000_000 && options.outWidth > 0 && options.outHeight > 0) { "Не удалось сохранить постер" }
            bytes
        }
        val version = (old?.posterVersion ?: 0) + if (image != null) 1 else 0
        val value = FilmEntity(id ?: 0, field(body.title, "Название"), body.age, field(body.genre, "Жанр"),
            body.durationMin, body.color, body.description.trim(), body.active, version)
        if (old != null) data.updateFilm(value)
        val saved = if (id == null) value.copy(id = data.insertFilm(value).toInt()) else value
        if (image != null) data.savePoster(PosterEntity(saved.id, image))
        saved.staff()
    }

    suspend fun staffSessions(day: String, cinemaId: Int = 0): List<StaffSession> {
        val user = managers()
        catalog.initialize()
        val date = LocalDate.parse(day)
        return data.sessions(day, date.plusDays(1).toString()).mapNotNull {
            val (_, hall, film) = catalog.sessionData(it.id)
            if (!user.canAccess(hall.cinemaId) || (cinemaId != 0 && hall.cinemaId != cinemaId)) return@mapNotNull null
            it.staff(hall, film)
        }
    }

    suspend fun addSession(body: SessionBody) = saveSession(null, body)
    suspend fun editSession(id: Int, body: SessionBody) = saveSession(id, body)

    private suspend fun saveSession(id: Int?, body: SessionBody): StaffSession = db.withTransaction {
        val user = managers()
        val old = id?.let { data.session(it) ?: error("Сеанс не найден") }
        val hall = data.hall(old?.hallId ?: body.hallId) ?: error("Зал не найден")
        user.checkCinema(hall.cinemaId)
        val film = data.film(old?.filmId ?: body.filmId) ?: error("Фильм не найден")
        val start = runCatching { LocalDateTime.parse(body.startsAt.trim(), sessionFormat) }.getOrElse { error("Укажите дату и время сеанса") }
        require(start >= LocalDateTime.now().minusMinutes(1) && body.price in 100..5000) { "Проверьте дату и цену сеанса" }
        if (old != null && old.startsAt != start.format(sessionFormat)) {
            require(orders.occupied(old.id, stamp()).isEmpty()) { "На сеанс уже оформлены билеты" }
        }
        val end = start.plusMinutes(film.durationMin.toLong() + 15)
        val overlaps = data.sessions(start.toLocalDate().minusDays(1).toString(), end.toLocalDate().plusDays(1).toString())
            .filter { it.hallId == hall.id && it.id != id }.any {
                val otherStart = LocalDateTime.parse(it.startsAt, sessionFormat)
                val otherLength = data.film(it.filmId)?.durationMin ?: 0
                end > otherStart && start < otherStart.plusMinutes(otherLength.toLong() + 15)
            }
        require(!overlaps) { "В это время зал занят другим сеансом" }
        val value = SessionEntity(id ?: 0, hall.id, film.id, start.format(sessionFormat), field(body.format, "Формат"), body.price)
        if (id != null) data.updateSession(value)
        val saved = if (id == null) value.copy(id = data.insertSession(value).toInt()) else value
        saved.staff(hall, film)
    }

    suspend fun deleteSession(id: Int) = db.withTransaction {
        managers().checkCinema(catalog.sessionData(id).hall.cinemaId)
        require(orders.forSession(id).isEmpty()) { "Сеанс связан с заказами" }
        data.deleteSession(id)
    }

    suspend fun payments(cinemaId: Int = 0, from: String = "", to: String = "", status: String = "", q: String = ""): List<Payment> {
        val user = managers()
        val window = if (from.isNotEmpty() && to.isNotEmpty()) period(from, to) else null
        return orders.all().filter { it.payMethod != null && (status.isEmpty() || it.status == status) }
            .filter { window == null || (it.createdAt >= window.first && it.createdAt < window.second) }
            .mapNotNull {
                val (session, hall, film, cinema) = catalog.sessionData(it.sessionId)
                if (!user.canAccess(cinema.id) || (cinemaId != 0 && cinema.id != cinemaId)) return@mapNotNull null
                val customer = users.user(it.userId) ?: return@mapNotNull null
                if (!"${customer.fullName()} ${customer.email} ${customer.phone}".contains(q.trim(), ignoreCase = true)) return@mapNotNull null
                Payment(it.id, it.createdAt, it.paidAt, customer.fullName(), customer.email, customer.phone, film.title,
                    cinema.name, hall.name, session.startsAt, orders.tickets(it.id).size, it.sumTotal, it.bonusUsed, it.total,
                    it.payMethod, it.status, statusNames[it.status] ?: it.status, it.refundReason, it.refundedAt)
            }.take(300)
    }

    suspend fun report(from: String, to: String, cinemaId: Int = 0): SalesReport = db.withTransaction {
        val user = managers()
        val (start, end) = period(from, to)
        val halls = data.halls().filter { user.canAccess(it.cinemaId) && (cinemaId == 0 || it.cinemaId == cinemaId) }.associateBy { it.id }
        val sessions = data.sessions(start, end).filter { it.hallId in halls }.associateBy { it.id }
        val relevant = orders.all().filter { it.sessionId in sessions }
        val refunds = relevant.filter { it.status == "refunded" }
        val confirmed = relevant.filter { it.status in activeOrders }
        val paid = confirmed.filter { it.status == "paid" }
        val counts = confirmed.associate { it.id to orders.tickets(it.id).size }
        val tickets = counts.values.sum()
        val capacity = sessions.values.sumOf { halls.getValue(it.hallId).let { h -> h.rows * h.seatsPerRow } }
        val totals = ReportTotal(paid.sumOf { it.total }, confirmed.filter { it.status == "reserved" }.sumOf { it.total },
            tickets, confirmed.size, refunds.size, refunds.sumOf { it.total }, paid.sumOf { it.bonusUsed },
            sessions.size, capacity, if (capacity == 0) 0.0 else tickets * 100.0 / capacity)
        val films = confirmed.groupBy { sessions.getValue(it.sessionId).filmId }.map { (filmId, group) ->
            ReportFilm(data.film(filmId)?.title.orEmpty(), group.sumOf { counts.getValue(it.id) },
                group.filter { it.status == "paid" }.sumOf { it.total })
        }.sortedByDescending { it.revenue }
        val days = confirmed.groupBy { sessions.getValue(it.sessionId).startsAt.take(10) }.map { (day, group) ->
            ReportDay(day, group.sumOf { counts.getValue(it.id) }, group.filter { it.status == "paid" }.sumOf { it.total })
        }.sortedBy { it.day }
        SalesReport(from, to, totals, films, days)
    }

    suspend fun refund(id: Int, body: RefundBody) = db.withTransaction {
        val user = managers()
        val order = orders.order(id) ?: error("Заказ не найден")
        user.checkCinema(catalog.sessionData(order.sessionId).hall.cinemaId)
        require(order.status in activeOrders && orders.tickets(id).none { it.usedAt != null }) { "Возврат для этого заказа невозможен" }
        val reason = field(body.reason, "Причина возврата")
        if (order.status == "paid") users.user(order.userId)?.let { booking.reverseBonus(order, it) }
        orders.updateOrder(order.copy(status = "refunded", refundedAt = stamp(), refundReason = reason))
    }

    suspend fun checkTicket(body: CheckBody): CheckResult = db.withTransaction {
        val user = requireRole("app_admin", "cinema_admin", "controller")
        val code = body.code.trim().uppercase(Locale.ROOT)
        val ticket = orders.byCode(code) ?: return@withTransaction CheckResult(false, "Билет не найден", code,
            null, null, null, null, null, null, null, null, null, null, null)
        val order = orders.order(ticket.orderId) ?: error("Заказ не найден")
        val (session, hall, film, cinema) = catalog.sessionData(order.sessionId)
        val start = LocalDateTime.parse(session.startsAt, sessionFormat)
        val now = LocalDateTime.now()
        val issue = when {
            !user.canAccess(cinema.id) -> "Билет в другой кинотеатр"
            order.status == "reserved" -> "Бронь не оплачена"
            order.status != "paid" -> "Билет недействителен"
            ticket.usedAt != null -> "По билету уже прошли"
            now < start.minusHours(1) -> "Вход открывается за час до сеанса"
            now > start.plusMinutes(film.durationMin.toLong()) -> "Сеанс уже закончился"
            else -> null
        }
        val admitted = issue == null && body.admit
        val usedAt = if (admitted) stamp(now) else ticket.usedAt
        if (admitted) orders.updateTicket(ticket.copy(usedAt = usedAt))
        val verdict = issue ?: if (admitted) "Проход разрешён" else "Билет действителен"
        CheckResult(issue == null, verdict, code, film.title, film.age, cinema.name, hall.name, session.startsAt,
            ticket.row, ticket.seat, ticketNames[ticket.type], users.user(order.userId)?.fullName(), usedAt, admitted)
    }

    private suspend fun SessionEntity.staff(hall: HallEntity, film: FilmEntity) = StaffSession(
        id, hallId, filmId, startsAt, format, price, hall.name, film.title, film.durationMin, film.age,
        orders.occupied(id, stamp()).size, hall.rows * hall.seatsPerRow,
        LocalDateTime.parse(startsAt, sessionFormat).plusMinutes(film.durationMin.toLong()).format(sessionFormat),
    )

    private suspend fun UserEntity.staff() = StaffUser(id, lastName, firstName, email, phone, role,
        roleNames[role] ?: role, cinemaId, cinemaId?.let { data.cinema(it)?.name }, bonus, blocked, failedLogins)
}

private val roleNames = mapOf("app_admin" to "Администратор приложения", "cinema_admin" to "Администратор кинотеатра",
    "controller" to "Контролёр", "viewer" to "Зритель")
private val statusNames = mapOf("paid" to "Оплачен", "reserved" to "Бронь", "cancelled" to "Отменён", "refunded" to "Возврат")
private fun UserEntity.fullName() = "$lastName $firstName"
private fun CinemaEntity.display(halls: List<StaffHall>) = StaffCinema(id, name, city, address, status, halls)
private fun HallEntity.staff() = StaffHall(id, cinemaId, name, rows, seatsPerRow, model)
private fun FilmEntity.staff() = StaffFilm(id, title, age, genre, durationMin, description, color, active, posterVersion > 0, posterVersion)
private fun period(from: String, to: String): Pair<String, String> {
    val first = LocalDate.parse(from)
    val last = LocalDate.parse(to)
    require(last >= first) { "Дата окончания раньше даты начала" }
    return first.toString() to last.plusDays(1).toString()
}
private fun field(value: String, label: String): String =
    value.trim().also { require(it.isNotEmpty() && it.length <= 200) { "Проверьте поле «$label»" } }
