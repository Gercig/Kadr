package ru.kadr.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [CinemaEntity::class, HallEntity::class, HallSeatEntity::class,
    FilmEntity::class, PosterEntity::class, SessionEntity::class, UserEntity::class,
    OrderEntity::class, TicketEntity::class, BonusEntity::class, AppStateEntity::class],
    version = 2, exportSchema = true)
abstract class KadrDatabase : RoomDatabase() {
    abstract fun catalog(): CatalogDao
    abstract fun accounts(): AccountsDao
    abstract fun orders(): OrdersDao
}

