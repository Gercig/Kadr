package ru.kadr.app.data.local

import androidx.room.withTransaction
import java.time.Duration
import java.time.LocalDateTime
import java.util.Locale
import java.util.UUID
import ru.kadr.app.data.*

internal val ticketNames = mapOf("adult" to "Взрослый", "reduced" to "Льготный", "child" to "Детский")
internal val discounts = mapOf("adult" to 0, "reduced" to 100, "child" to 150)
internal val activeOrders = setOf("paid", "reserved")

class BookingRepository(
    private val db: KadrDatabase,
    private val catalog: CatalogRepository,
    private val accounts: AccountRepository,
) {
    private val orders = db.orders()
    private val users = db.accounts()

    suspend fun createOrder(body: OrderBody): Order = db.withTransaction {
        val user = accounts.requireUser()
        val selected = body.seats.toSet()
        require(selected.isNotEmpty() && selected.size <= 10) { "Выберите от одного до десяти мест" }
        val now = LocalDateTime.now()
        val s = catalog.session(body.sessionId)
        require(LocalDateTime.parse(s.startsAt, sessionFormat) > now) { "Сеанс уже начался" }
        require(selected.all { it.row in 1..s.hall.rows && it.seat in 1..s.hall.seatsPerRow }) { "Такого места нет в зале" }

        orders.forUser(user.id).filter { it.sessionId == s.id && it.status == "pending" }.forEach {
            orders.updateOrder(it.copy(status = "cancelled"))
        }
        val taken = orders.occupied(s.id, stamp(now)).map { SeatBody(it.row, it.seat) }.toSet()
        require(selected.none { it in taken }) { "Выбранное место уже занято" }
        require(!createsGap(selected, taken, s.hall.seatsPerRow, s.hall.aisles.orEmpty())) {
            "Нельзя оставлять одно свободное место между выбранными или занятыми местами"
        }
        val comfort = s.premiumSeats.orEmpty().map { SeatBody(it[0], it[1]) }.toSet()
        val order = OrderEntity(userId = user.id, sessionId = s.id, createdAt = stamp(now), expiresAt = stamp(now.plusMinutes(10)))
        val tickets = selected.map {
            val surcharge = if (it in comfort) s.premiumSurcharge else 0
            TicketEntity(orderId = 0, row = it.row, seat = it.seat, type = "adult",
                price = maxOf(200, s.price) + surcharge,
                code = "KADR-" + UUID.randomUUID().toString().replace("-", "").take(16).uppercase(Locale.ROOT),
                seatSurcharge = surcharge)
        }
        val sum = tickets.sumOf { it.price }
        val saved = order.copy(sumTotal = sum, total = sum)
        val id = orders.insertOrder(saved).toInt()
        orders.insertTickets(tickets.map { it.copy(orderId = id) })
        display(saved.copy(id = id), user)
    }

    suspend fun order(id: Int): Order = db.withTransaction {
        val user = accounts.requireUser()
        display(owned(id, user), user)
    }

    suspend fun tickets(): List<Order> = db.withTransaction {
        val user = accounts.requireUser()
        orders.forUser(user.id).filter { it.status in activeOrders }.map { display(it, user) }
    }

    suspend fun pay(id: Int, body: PayBody): Order = db.withTransaction {
        val user = accounts.requireUser()
        val order = owned(id, user)
        require(order.status == "pending" && order.expiresAt > stamp()) { "Время брони истекло. Выберите места заново" }
        require(body.payMethod in setOf("card", "cash")) { "Выберите способ оформления" }
        val types = body.tickets.associate { it.id to it.type }
        val tickets = orders.tickets(id)
        require(types.keys.all { id -> tickets.any { it.id == id } }) { "Неизвестный билет" }
        val updated = tickets.map { ticket ->
            val type = types[ticket.id] ?: ticket.type
            val discount = discounts[type] ?: error("Неизвестный тип билета")
            val price = maxOf(200, ticket.price - ticket.seatSurcharge - discount) + ticket.seatSurcharge
            ticket.copy(type = type, price = price)
        }
        updated.forEach { orders.updateTicket(it) }
        val sum = updated.sumOf { it.price }
        val card = body.payMethod == "card"
        val used = if (card && body.useBonus) minOf(sum * 30 / 100, user.bonus) else 0
        val total = sum - used
        val earned = if (card) total * 5 / 100 else 0
        val after = changeBonus(user, used, earned)
        val saved = order.copy(status = if (card) "paid" else "reserved", payMethod = body.payMethod,
            paidAt = if (card) stamp() else null, sumTotal = sum, bonusUsed = used, bonusEarned = earned, total = total)
        orders.updateOrder(saved)
        display(saved, after)
    }

    suspend fun cancel(id: Int): Order = db.withTransaction {
        val user = accounts.requireUser()
        val order = owned(id, user)
        if (order.status !in activeOrders && order.status != "pending") return@withTransaction display(order, user)
        if (order.status in activeOrders) {
            val start = LocalDateTime.parse(catalog.sessionData(order.sessionId).session.startsAt, sessionFormat)
            require(LocalDateTime.now() <= start.minusMinutes(30)) { "Отмена доступна не позднее чем за 30 минут до начала" }
        }
        val after = if (order.status == "paid") reverseBonus(order, user) else user
        val saved = order.copy(status = "cancelled")
        orders.updateOrder(saved)
        display(saved, after)
    }

    internal suspend fun reverseBonus(order: OrderEntity, user: UserEntity): UserEntity {
        val balance = maxOf(0, user.bonus + order.bonusUsed - order.bonusEarned)
        val delta = balance - user.bonus
        val after = user.copy(bonus = balance)
        if (delta != 0) {
            users.addBonus(BonusEntity(userId = user.id, amount = delta, reason = "Отмена заказа", createdAt = stamp()))
            users.updateUser(after)
        }
        return after
    }

    private suspend fun changeBonus(user: UserEntity, used: Int, earned: Int): UserEntity {
        val now = stamp()
        if (used > 0) users.addBonus(BonusEntity(userId = user.id, amount = -used, reason = "Оплата бонусами", createdAt = now))
        if (earned > 0) users.addBonus(BonusEntity(userId = user.id, amount = earned, reason = "Оформление билета", createdAt = now))
        val after = user.copy(bonus = user.bonus - used + earned)
        if (after != user) users.updateUser(after)
        return after
    }

    private suspend fun owned(id: Int, user: UserEntity): OrderEntity =
        orders.order(id)?.takeIf { it.userId == user.id } ?: error("Заказ не найден")

    private suspend fun display(value: OrderEntity, user: UserEntity): Order {
        val left = Duration.between(LocalDateTime.now(), LocalDateTime.parse(value.expiresAt, timestampFormat)).seconds
        val status = if (value.status == "pending" && left <= 0) "expired" else value.status
        return Order(value.id, status, value.createdAt, value.expiresAt, maxOf(0, left).toInt(),
            value.payMethod, value.sumTotal, value.bonusUsed, value.total, value.bonusEarned, user.bonus,
            catalog.session(value.sessionId), orders.tickets(value.id).map {
                Ticket(it.id, it.row, it.seat, it.type, ticketNames.getValue(it.type), it.price, it.code, it.usedAt != null, it.seatSurcharge)
            })
    }

    private fun createsGap(wanted: Set<SeatBody>, taken: Set<SeatBody>, cols: Int, aisles: List<Int>): Boolean {
        val occupied = wanted + taken
        return wanted.map { it.row }.distinct().any { row ->
            (2 until cols).any { seat ->
                val left = SeatBody(row, seat - 1)
                val right = SeatBody(row, seat + 1)
                seat - 1 !in aisles && seat !in aisles && SeatBody(row, seat) !in occupied &&
                    left in occupied && right in occupied && (left in wanted || right in wanted)
            }
        }
    }
}
