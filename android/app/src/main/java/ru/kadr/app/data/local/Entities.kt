package ru.kadr.app.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "cinemas")
data class CinemaEntity(@PrimaryKey(autoGenerate = true) val id: Int = 0, val name: String,
    val city: String, val address: String, val status: String = "active")

@Entity(tableName = "halls", foreignKeys = [ForeignKey(entity = CinemaEntity::class,
    parentColumns = ["id"], childColumns = ["cinemaId"])], indices = [Index("cinemaId")])
data class HallEntity(@PrimaryKey(autoGenerate = true) val id: Int = 0, val cinemaId: Int,
    val name: String, val rows: Int, val seatsPerRow: Int, val model: String)

@Entity(tableName = "hall_seats", primaryKeys = ["hallId", "row", "seat"],
    foreignKeys = [ForeignKey(entity = HallEntity::class, parentColumns = ["id"],
        childColumns = ["hallId"], onDelete = ForeignKey.CASCADE)])
data class HallSeatEntity(val hallId: Int, val row: Int, val seat: Int, val comfort: Boolean)

@Entity(tableName = "films")
data class FilmEntity(@PrimaryKey(autoGenerate = true) val id: Int = 0, val title: String,
    val age: String, val genre: String, val durationMin: Int, val color: String,
    val description: String?, val active: Boolean = true, val posterVersion: Int = 0)

@Entity(tableName = "posters", foreignKeys = [ForeignKey(entity = FilmEntity::class,
    parentColumns = ["id"], childColumns = ["filmId"], onDelete = ForeignKey.CASCADE)])
data class PosterEntity(@PrimaryKey val filmId: Int, val image: ByteArray)

@Entity(tableName = "sessions", foreignKeys = [
    ForeignKey(entity = HallEntity::class, parentColumns = ["id"], childColumns = ["hallId"]),
    ForeignKey(entity = FilmEntity::class, parentColumns = ["id"], childColumns = ["filmId"])],
    indices = [Index("hallId", "startsAt"), Index("filmId")])
data class SessionEntity(@PrimaryKey(autoGenerate = true) val id: Int = 0, val hallId: Int,
    val filmId: Int, val startsAt: String, val format: String, val price: Int)

@Entity(tableName = "users", indices = [Index(value = ["email"], unique = true)])
data class UserEntity(@PrimaryKey(autoGenerate = true) val id: Int = 0, val lastName: String,
    val firstName: String, val email: String, val phone: String, val verifier: String,
    val role: String = "viewer", val cinemaId: Int? = null, val bonus: Int = 0,
    val blocked: Boolean = false, val failedLogins: Int = 0)

@Entity(tableName = "orders", foreignKeys = [
    ForeignKey(entity = UserEntity::class, parentColumns = ["id"], childColumns = ["userId"]),
    ForeignKey(entity = SessionEntity::class, parentColumns = ["id"], childColumns = ["sessionId"])],
    indices = [Index("userId"), Index("sessionId")])
data class OrderEntity(@PrimaryKey(autoGenerate = true) val id: Int = 0, val userId: Int,
    val sessionId: Int, val createdAt: String, val expiresAt: String, val status: String = "pending",
    val payMethod: String? = null, val paidAt: String? = null, val sumTotal: Int = 0,
    val bonusUsed: Int = 0, val bonusEarned: Int = 0, val total: Int = 0,
    val refundReason: String? = null, val refundedAt: String? = null)

@Entity(tableName = "tickets", foreignKeys = [ForeignKey(entity = OrderEntity::class,
    parentColumns = ["id"], childColumns = ["orderId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("orderId"), Index(value = ["code"], unique = true)])
data class TicketEntity(@PrimaryKey(autoGenerate = true) val id: Int = 0, val orderId: Int,
    val row: Int, val seat: Int, val type: String, val price: Int, val code: String, val usedAt: String? = null,
    @ColumnInfo(defaultValue = "0") val seatSurcharge: Int = 0)

@Entity(tableName = "bonus_ops", foreignKeys = [ForeignKey(entity = UserEntity::class,
    parentColumns = ["id"], childColumns = ["userId"])], indices = [Index("userId")])
data class BonusEntity(@PrimaryKey(autoGenerate = true) val id: Int = 0, val userId: Int,
    val amount: Int, val reason: String, val createdAt: String)

@Entity(tableName = "app_state")
data class AppStateEntity(@PrimaryKey val id: Int = 1, val userId: Int? = null,
    val cinemaId: Int = 1, val generatedThrough: String = "")

