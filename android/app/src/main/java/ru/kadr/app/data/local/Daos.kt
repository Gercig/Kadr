package ru.kadr.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert

@Dao
interface CatalogDao {
    @Query("SELECT * FROM cinemas ORDER BY id") suspend fun cinemas(): List<CinemaEntity>
    @Query("SELECT * FROM cinemas WHERE id = :id") suspend fun cinema(id: Int): CinemaEntity?
    @Insert suspend fun insertCinema(value: CinemaEntity): Long
    @Update suspend fun updateCinema(value: CinemaEntity)

    @Query("SELECT * FROM halls ORDER BY id") suspend fun halls(): List<HallEntity>
    @Query("SELECT * FROM halls WHERE id = :id") suspend fun hall(id: Int): HallEntity?
    @Insert suspend fun insertHall(value: HallEntity): Long
    @Update suspend fun updateHall(value: HallEntity)
    @Query("SELECT * FROM hall_seats WHERE hallId = :hallId ORDER BY `row`, seat")
    suspend fun seats(hallId: Int): List<HallSeatEntity>
    @Query("DELETE FROM hall_seats WHERE hallId = :hallId") suspend fun clearSeats(hallId: Int)
    @Insert suspend fun insertSeats(values: List<HallSeatEntity>)

    @Query("SELECT * FROM films ORDER BY id") suspend fun films(): List<FilmEntity>
    @Query("SELECT * FROM films WHERE id = :id") suspend fun film(id: Int): FilmEntity?
    @Insert suspend fun insertFilm(value: FilmEntity): Long
    @Update suspend fun updateFilm(value: FilmEntity)
    @Query("SELECT image FROM posters WHERE filmId = :id") suspend fun poster(id: Int): ByteArray?
    @Upsert suspend fun savePoster(value: PosterEntity)

    @Query("SELECT * FROM sessions WHERE id = :id") suspend fun session(id: Int): SessionEntity?
    @Query("SELECT * FROM sessions WHERE startsAt >= :from AND startsAt < :until ORDER BY startsAt, id")
    suspend fun sessions(from: String, until: String): List<SessionEntity>
    @Insert suspend fun insertSessions(values: List<SessionEntity>)
    @Insert suspend fun insertSession(value: SessionEntity): Long
    @Update suspend fun updateSession(value: SessionEntity)
    @Query("DELETE FROM sessions WHERE id = :id") suspend fun deleteSession(id: Int)
}

@Dao
interface AccountsDao {
    @Query("SELECT * FROM app_state WHERE id = 1") suspend fun state(): AppStateEntity?
    @Upsert suspend fun saveState(value: AppStateEntity)
    @Query("SELECT * FROM users WHERE id = :id") suspend fun user(id: Int): UserEntity?
    @Query("SELECT * FROM users WHERE email = :email") suspend fun byLogin(email: String): UserEntity?
    @Query("SELECT * FROM users ORDER BY lastName, firstName") suspend fun users(): List<UserEntity>
    @Query("SELECT EXISTS(SELECT 1 FROM users WHERE role = 'app_admin')") suspend fun hasAdmin(): Boolean
    @Insert suspend fun insertUser(value: UserEntity): Long
    @Update suspend fun updateUser(value: UserEntity)
    @Query("SELECT * FROM bonus_ops WHERE userId = :user ORDER BY id DESC") suspend fun bonus(user: Int): List<BonusEntity>
    @Insert suspend fun addBonus(value: BonusEntity)
}

@Dao
interface OrdersDao {
    @Query("SELECT * FROM orders WHERE id = :id") suspend fun order(id: Int): OrderEntity?
    @Query("SELECT * FROM orders WHERE userId = :user ORDER BY id DESC") suspend fun forUser(user: Int): List<OrderEntity>
    @Query("SELECT * FROM orders WHERE sessionId = :session") suspend fun forSession(session: Int): List<OrderEntity>
    @Query("SELECT * FROM orders ORDER BY id DESC") suspend fun all(): List<OrderEntity>
    @Insert suspend fun insertOrder(value: OrderEntity): Long
    @Update suspend fun updateOrder(value: OrderEntity)
    @Query("SELECT * FROM tickets WHERE orderId = :order ORDER BY `row`, seat")
    suspend fun tickets(order: Int): List<TicketEntity>
    @Query("SELECT * FROM tickets WHERE code = :code") suspend fun byCode(code: String): TicketEntity?
    @Query("""
        SELECT tickets.* FROM tickets JOIN orders ON orders.id = tickets.orderId
        WHERE orders.sessionId = :session AND (orders.status IN ('paid', 'reserved')
            OR (orders.status = 'pending' AND orders.expiresAt > :now))
    """) suspend fun occupied(session: Int, now: String): List<TicketEntity>
    @Insert suspend fun insertTickets(values: List<TicketEntity>)
    @Update suspend fun updateTicket(value: TicketEntity)
}
