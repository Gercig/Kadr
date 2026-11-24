package ru.kadr.app.data.local

import android.content.res.AssetManager
import androidx.room.withTransaction
import java.time.LocalDate
import ru.kadr.app.data.*

internal fun HallEntity.aisles(): List<Int> = when (model) {
    "imax" -> listOf((seatsPerRow + 1) / 2)
    "vip2" -> listOf(2, 4).filter { it < seatsPerRow }
    else -> emptyList()
}

internal fun HallEntity.layout(): List<HallSeatEntity> {
    val side = maxOf(0, seatsPerRow * 3 / 8 - 2)
    val first = maxOf(1, rows * 3 / 8 - 1)
    val last = maxOf(first, rows * 3 / 4)
    return (1..rows).flatMap { row -> (1..seatsPerRow).map { seat ->
        HallSeatEntity(id, row, seat, model != "vip2" && row in first..last && seat in (side + 1)..(seatsPerRow - side))
    } }
}

internal fun HallEntity.display() = Hall(id, name, rows, seatsPerRow, model, aisles())
internal fun CinemaEntity.short() = CinemaShort(id, name, city, address)
internal fun FilmEntity.display() = Film(id, title, age, genre, durationMin, color, posterVersion)
internal fun SessionEntity.hasComfort(hall: HallEntity) =
    hall.model != "vip2" && (hall.model == "imax" || format == "2D" || format.startsWith("IMAX", ignoreCase = true))

class CatalogRepository(private val db: KadrDatabase, private val assets: AssetManager) {
    private val catalog = db.catalog()
    private val accounts = db.accounts()
    private val orders = db.orders()

    suspend fun initialize() = db.withTransaction {
        if (catalog.cinemas().isEmpty()) seed()
        fillSchedule()
    }

    suspend fun cinemas(): List<Cinema> {
        val halls = catalog.halls().groupBy { it.cinemaId }
        return catalog.cinemas().filter { it.status == "active" }.map { cinema ->
            val rooms = halls[cinema.id].orEmpty()
            Cinema(cinema.id, cinema.name, cinema.city, cinema.address, rooms.size, rooms.sumOf { it.rows * it.seatsPerRow })
        }
    }

    suspend fun schedule(cinemaId: Int, day: String): Schedule {
        db.withTransaction { fillSchedule() }
        val date = LocalDate.parse(day)
        val halls = catalog.halls().filter { it.cinemaId == cinemaId }.associateBy { it.id }
        val sessions = catalog.sessions(day, date.plusDays(1).toString()).filter { it.hallId in halls }.groupBy { it.filmId }
        val now = stamp()
        return Schedule(day, catalog.films().filter { it.active }.mapNotNull { film ->
            val times = sessions[film.id] ?: return@mapNotNull null
            FilmWithSessions(film.id, film.title, film.age, film.genre, film.durationMin, film.color, film.description,
                times.map { s ->
                    val h = halls.getValue(s.hallId)
                    SessionShort(s.id, s.startsAt.takeLast(5), s.startsAt, s.format, s.price, h.name,
                        h.rows * h.seatsPerRow - orders.occupied(s.id, now).size)
                }, film.posterVersion)
        })
    }

    suspend fun session(id: Int): SessionInfo = sessionData(id).let { (s, h, f, c) ->
        val comfort = if (s.hasComfort(h)) catalog.seats(h.id).filter { it.comfort } else emptyList()
        SessionInfo(s.id, s.startsAt, s.format, s.price, f.display(), c.short(), h.display(),
            orders.occupied(id, stamp()).map { listOf(it.row, it.seat) },
            comfort.map { listOf(it.row, it.seat) }, if (comfort.isEmpty()) 0 else 100)
    }

    suspend fun poster(filmId: Int): ByteArray? = catalog.poster(filmId)

    internal data class SessionData(val session: SessionEntity, val hall: HallEntity, val film: FilmEntity, val cinema: CinemaEntity)

    internal suspend fun sessionData(id: Int): SessionData {
        val s = catalog.session(id) ?: error("Сеанс не найден")
        val h = catalog.hall(s.hallId) ?: error("Зал не найден")
        return SessionData(s, h, catalog.film(s.filmId) ?: error("Фильм не найден"),
            catalog.cinema(h.cinemaId) ?: error("Кинотеатр не найден"))
    }

    private suspend fun seed() {
        val cinemas = listOf(
            CinemaEntity(1, "Кадр на Баумана", "Казань", "ул. Баумана, 44"),
            CinemaEntity(2, "Кадр Победа", "Казань", "пр. Победы, 141"),
            CinemaEntity(3, "Кадр Заречье", "Казань", "ул. Восстания, 100"),
            CinemaEntity(4, "Кадр Таганка", "Москва", "ул. Марксистская, 3"),
            CinemaEntity(5, "Кадр Сокол", "Москва", "Ленинградский пр-т, 76"),
            CinemaEntity(6, "Кадр Центр", "Набережные Челны", "пр. Мира, 49"),
        )
        val counts = listOf(3, 4, 2, 5, 3, 2)
        for ((index, cinema) in cinemas.withIndex()) {
            catalog.insertCinema(cinema)
            repeat(counts[index]) { slot ->
                val hall = when (slot) {
                    0 -> HallEntity(cinemaId = cinema.id, name = "Зал 1 IMAX", rows = 8, seatsPerRow = 43, model = "imax")
                    1 -> HallEntity(cinemaId = cinema.id, name = "Зал 2 VIP", rows = 7, seatsPerRow = 6, model = "vip2")
                    else -> HallEntity(cinemaId = cinema.id, name = "Зал ${slot + 1}", rows = 10, seatsPerRow = 16, model = "vip")
                }
                catalog.insertSeats(hall.copy(id = catalog.insertHall(hall).toInt()).layout())
            }
        }
        val films = listOf(
            FilmEntity(1, "Рождение империи", "12+", "Историческая драма", 149, "#3A2E4F",
                "Пётр I вступает в войну со Швецией за выход к Балтийскому морю.", posterVersion = 1),
            FilmEntity(2, "Битва моторов", "12+", "Драма, приключения", 152, "#23414A",
                "Создатели «Руссо-Балта» проверяют машину на ралли в Монако.", posterVersion = 1),
            FilmEntity(3, "Дедмобиль", "12+", "Семейная комедия", 92, "#4A2F2B",
                "Водитель Валера объединяется с умным такси, чтобы заработать на лечение пса.", posterVersion = 1),
            FilmEntity(4, "Девятая планета", "16+", "Научная фантастика", 111, "#2F3F2A",
                "Автомеханик помнит события на далёкой планете, о которых не знают его сослуживцы.", posterVersion = 1),
        )
        val files = listOf("empire", "motor", "ded", "planet")
        for ((index, film) in films.withIndex()) {
            catalog.insertFilm(film)
            val bytes = assets.open("posters/${files[index]}.jpg").use { it.readBytes() }
            catalog.savePoster(PosterEntity(film.id, bytes))
        }
    }

    private data class Slot(val hall: Int, val film: Int, val time: String, val format: String, val price: Int)

    private suspend fun fillSchedule() {
        val state = accounts.state() ?: AppStateEntity()
        val today = LocalDate.now()
        val until = today.plusDays(6)
        val last = state.generatedThrough.takeIf { it.isNotEmpty() }?.let(LocalDate::parse)
        var day = maxOf(today, last?.plusDays(1) ?: today)
        if (day > until) return
        val halls = catalog.halls().groupBy { it.cinemaId }
        val films = catalog.films().filter { it.active }.map { it.id }.toSet()
        while (day <= until) {
            val slots = buildList {
                if (day.dayOfWeek.value >= 6) {
                    add(Slot(0, 1, "12:00", "IMAX", 500))
                    add(Slot(0, 1, "14:45", "IMAX", 550))
                }
                add(Slot(0, 1, "17:30", "IMAX", 550))
                add(Slot(0, 1, "20:25", "IMAX", 650))
                add(Slot(1, 3, "10:00", "2D", 1500))
                add(Slot(1, 4, "12:00", "2D", 1500))
                add(Slot(1, 2, "14:15", "2D", 1500))
                add(Slot(1, 1, "18:30", "2D", 1500))
                add(Slot(2, 3, "10:00", "2D", 450))
                add(Slot(2, 4, "11:45", "2D", 450))
                add(Slot(2, 2, "13:50", "2D", 450))
                add(Slot(2, 3, "16:35", "2D", 550))
                add(Slot(2, 2, "18:20", "2D", 550))
                add(Slot(2, 4, "21:05", "2D", 600))
            }
            val existing = catalog.sessions(day.toString(), day.plusDays(1).toString())
                .groupBy { it.hallId }
            val sessions = halls.values.flatMap { rooms -> slots.mapNotNull { slot ->
                val room = rooms.getOrNull(slot.hall) ?: return@mapNotNull null
                // Сеансы, настроенные сотрудником заранее, имеют приоритет над шаблоном дня.
                if (slot.film !in films || room.id in existing) return@mapNotNull null
                SessionEntity(hallId = room.id, filmId = slot.film, startsAt = "$day ${slot.time}", format = slot.format, price = slot.price)
            } }
            catalog.insertSessions(sessions)
            day = day.plusDays(1)
        }
        accounts.saveState(state.copy(generatedThrough = until.toString()))
    }
}
