package ru.kadr.app.data

data class User(
    val id: Int,
    val lastName: String,
    val firstName: String,
    val email: String,
    val phone: String,
    val role: String,
    val cinemaId: Int?,
    val bonus: Int,
    val mustChangePassword: Boolean,
)

data class Cinema(
    val id: Int,
    val name: String,
    val city: String,
    val address: String,
    val hallsCount: Int,
    val seatsCount: Int,
)

data class Schedule(val date: String, val films: List<FilmWithSessions>)

data class FilmWithSessions(
    val id: Int,
    val title: String,
    val age: String,
    val genre: String,
    val durationMin: Int,
    val color: String,
    val description: String?,
    val sessions: List<SessionShort>,
    val posterV: Int = 0,
)

data class SessionShort(
    val id: Int,
    val time: String,
    val startsAt: String,
    val format: String,
    val price: Int,
    val hallName: String,
    val free: Int,
) {
    val posterFormat: String
        get() = if (hallName.split(' ', '-', '·', '–', '—').any { it.equals("VIP", ignoreCase = true) }) "VIP" else format
}

data class Film(
    val id: Int,
    val title: String,
    val age: String,
    val genre: String,
    val durationMin: Int,
    val color: String,
    val posterV: Int = 0,
)

data class CinemaShort(val id: Int, val name: String, val city: String, val address: String)

data class Hall(
    val id: Int,
    val name: String,
    val rows: Int,
    val seatsPerRow: Int,
    val modelUrl: String?,
    val aisles: List<Int>? = null,   // после какого места в ряду проход
)

data class SessionInfo(
    val id: Int,
    val startsAt: String,
    val format: String,
    val price: Int,
    val film: Film,
    val cinema: CinemaShort,
    val hall: Hall,
    val taken: List<List<Int>>?,
    val premiumSeats: List<List<Int>>? = null,
    val premiumSurcharge: Int = 0,
)

data class Ticket(
    val id: Int,
    val row: Int,
    val seat: Int,
    val type: String,
    val typeName: String,
    val price: Int,
    val code: String,
    val used: Boolean,
    val seatSurcharge: Int = 0,
)

data class Order(
    val id: Int,
    val status: String,
    val createdAt: String,
    val expiresAt: String,
    val secondsLeft: Int,
    val payMethod: String?,
    val sumTotal: Int,
    val bonusUsed: Int,
    val total: Int,
    val bonusEarned: Int,
    val bonusBalance: Int,
    val session: SessionInfo,
    val tickets: List<Ticket>,
)

data class BonusOp(val amount: Int, val reason: String, val createdAt: String)
data class BonusInfo(val balance: Int, val history: List<BonusOp>)

// Параметры операций

data class RegisterBody(
    val lastName: String,
    val firstName: String,
    val email: String,
    val phone: String,
    val password: String,
)

data class LoginBody(val email: String, val password: String)
data class SeatBody(val row: Int, val seat: Int)
data class OrderBody(val sessionId: Int, val seats: List<SeatBody>)
data class TicketTypeBody(val id: Int, val type: String)
data class PayBody(val tickets: List<TicketTypeBody>, val useBonus: Boolean, val payMethod: String)
