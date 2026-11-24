package ru.kadr.app.data.local

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

internal val migration1To2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE tickets ADD COLUMN seatSurcharge INTEGER NOT NULL DEFAULT 0")
    }
}

/** Общий экземпляр базы и репозиториев на процесс приложения. */
object LocalData {
    lateinit var catalog: CatalogRepository
        private set
    lateinit var accounts: AccountRepository
        private set
    lateinit var booking: BookingRepository
        private set
    lateinit var staff: StaffRepository
        private set

    @Synchronized
    fun init(context: Context) {
        if (::catalog.isInitialized) return
        val app = context.applicationContext
        val db = Room.databaseBuilder(app, KadrDatabase::class.java, "kadr-local.db")
            .addMigrations(migration1To2)
            .build()
        catalog = CatalogRepository(db, app.assets)
        accounts = AccountRepository(db)
        booking = BookingRepository(db, catalog, accounts)
        staff = StaffRepository(db, catalog, accounts, booking)
    }
}
