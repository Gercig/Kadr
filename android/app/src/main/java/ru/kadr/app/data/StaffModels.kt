package ru.kadr.app.data

// Данные рабочего кабинета сотрудников.

data class StaffHall(
    val id: Int,
    val cinemaId: Int,
    val name: String,
    val rows: Int,
    val seatsPerRow: Int,
    val modelUrl: String?,
)

data class StaffCinema(
    val id: Int,
    val name: String,
    val city: String,
    val address: String,
    val status: String,
    val halls: List<StaffHall>?,
)

data class StaffUser(
    val id: Int,
    val lastName: String,
    val firstName: String,
    val email: String,
    val phone: String,
    val role: String,
    val roleName: String,
    val cinemaId: Int?,
    val cinemaName: String?,
    val bonus: Int,
    val blocked: Boolean,
    val failedLogins: Int,
)

data class StaffFilm(
    val id: Int,
    val title: String,
    val age: String,
    val genre: String,
    val durationMin: Int,
    val description: String?,
    val color: String,
    val active: Boolean,
    val hasPoster: Boolean,
    val posterV: Int,
)

data class StaffSession(
    val id: Int,
    val hallId: Int,
    val filmId: Int,
    val startsAt: String,
    val format: String,
    val price: Int,
    val hallName: String,
    val title: String,
    val durationMin: Int,
    val age: String,
    val sold: Int,
    val capacity: Int,
    val endsAt: String,
)

data class ReportTotal(
    val revenue: Int,
    val cashReserved: Int,
    val tickets: Int,
    val orders: Int,
    val refunds: Int,
    val refundSum: Int,
    val bonusUsed: Int,
    val sessions: Int,
    val capacity: Int,
    val occupancy: Double,
)

data class ReportFilm(val film: String, val tickets: Int, val revenue: Int)
data class ReportDay(val day: String, val tickets: Int, val revenue: Int)
data class SalesReport(val from: String, val to: String, val total: ReportTotal, val films: List<ReportFilm>, val days: List<ReportDay>)

data class Payment(
    val id: Int,
    val createdAt: String,
    val paidAt: String?,
    val user: String,
    val email: String,
    val phone: String,
    val film: String,
    val cinema: String,
    val hall: String,
    val startsAt: String,
    val tickets: Int,
    val sumTotal: Int,
    val bonusUsed: Int,
    val total: Int,
    val payMethod: String?,
    val status: String,
    val statusName: String,
    val refundReason: String?,
    val refundedAt: String?,
)

data class CheckResult(
    val ok: Boolean,
    val verdict: String,
    val code: String?,
    val film: String?,
    val age: String?,
    val cinema: String?,
    val hall: String?,
    val startsAt: String?,
    val row: Int?,
    val seat: Int?,
    val typeName: String?,
    val viewer: String?,
    val usedAt: String?,
    val admitted: Boolean?,
)

// Параметры операций

data class CinemaBody(val name: String, val city: String, val address: String, val status: String = "active")
data class HallBody(val name: String, val rows: Int, val seatsPerRow: Int, val modelUrl: String)
data class StaffBody(
    val lastName: String,
    val firstName: String,
    val login: String,
    val phone: String,
    val password: String,
    val role: String,
    val cinemaId: Int,
)
data class UserPatch(val blocked: Boolean? = null, val role: String? = null, val cinemaId: Int? = null, val password: String? = null)
data class FilmBody(
    val title: String,
    val age: String,
    val genre: String,
    val durationMin: Int,
    val description: String,
    val color: String,
    val active: Boolean,
    val poster: String?,
)
data class SessionBody(val hallId: Int, val filmId: Int, val startsAt: String, val format: String, val price: Int)
data class RefundBody(val reason: String)
data class CheckBody(val code: String, val admit: Boolean)
