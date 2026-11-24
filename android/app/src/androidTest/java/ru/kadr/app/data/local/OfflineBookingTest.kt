package ru.kadr.app.data.local

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import ru.kadr.app.data.LoginBody
import ru.kadr.app.data.OrderBody
import ru.kadr.app.data.PayBody
import ru.kadr.app.data.RegisterBody
import ru.kadr.app.data.SeatBody

@RunWith(AndroidJUnit4::class)
class OfflineBookingTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext

    @Test
    fun openingAndPurchasingPersistAfterDatabaseReopen() = runBlocking {
        val name = "booking-test-${UUID.randomUUID()}.db"
        var db = open(app, name)
        try {
            val catalog = CatalogRepository(db, app.assets)
            val accounts = AccountRepository(db)
            val booking = BookingRepository(db, catalog, accounts)
            catalog.initialize()
            assertEquals(6, catalog.cinemas().size)
            val schedule = catalog.schedule(1, LocalDate.now().plusDays(1).toString())
            assertEquals(4, schedule.films.size)
            val film = schedule.films.first()
            assertTrue(catalog.poster(film.id)!!.isNotEmpty())
            val show = catalog.session(film.sessions.first { it.format == "IMAX" }.id)
            assertEquals(75, show.premiumSeats?.size)
            val email = "viewer@example.test"
            val password = "Aa1!" + UUID.randomUUID()
            val user = accounts.register(RegisterBody("Тестов", "Зритель", email, "+70000000001", password))
            val order = booking.createOrder(OrderBody(show.id, listOf(SeatBody(2, 14), SeatBody(2, 15))))
            assertEquals(listOf(show.price, show.price + 100), order.tickets.map { it.price })
            val paid = booking.pay(order.id, PayBody(emptyList(), useBonus = false, payMethod = "card"))
            assertEquals("paid", paid.status)
            val codes = paid.tickets.map { it.code }
            db.close()

            db = open(app, name)
            val restoredAccounts = AccountRepository(db)
            val restoredCatalog = CatalogRepository(db, app.assets)
            restoredCatalog.initialize()
            assertEquals(user.id, restoredAccounts.me().id)
            assertEquals(user.id, restoredAccounts.login(LoginBody(email, password)).id)
            val restored = BookingRepository(db, restoredCatalog, restoredAccounts).tickets().single()
            assertEquals(codes, restored.tickets.map { it.code })
            assertTrue(restored.session.taken.orEmpty().contains(listOf(2, 15)))
            assertEquals(paid.total, restored.total)
        } finally {
            db.close()
            app.deleteDatabase(name)
        }
    }

    @Test
    fun migrationKeepsExistingTicket() = runBlocking {
        val name = "migration-test-${UUID.randomUUID()}.db"
        val file = app.getDatabasePath(name)
        file.parentFile?.mkdirs()
        try {
            val raw = instrumentation.context.assets.open("ru.kadr.app.data.local.KadrDatabase/1.json")
                .bufferedReader().use { it.readText() }
            val schema = JSONObject(raw).getJSONObject("database")
            SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
                val tables = schema.getJSONArray("entities")
                for (i in 0 until tables.length()) {
                    val table = tables.getJSONObject(i)
                    val nameOfTable = table.getString("tableName")
                    db.execSQL(table.getString("createSql").replace("\${TABLE_NAME}", nameOfTable))
                    val indices = table.optJSONArray("indices")
                    if (indices != null) for (j in 0 until indices.length()) {
                        db.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", nameOfTable))
                    }
                }
                val setup = schema.getJSONArray("setupQueries")
                for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
                db.setForeignKeyConstraintsEnabled(true)
                db.execSQL("INSERT INTO cinemas VALUES (1, 'Кадр', 'Город', 'Адрес', 'active')")
                db.execSQL("INSERT INTO halls VALUES (1, 1, 'Зал', 10, 16, 'vip')")
                db.execSQL("INSERT INTO films VALUES (1, 'Фильм', '12+', 'Драма', 100, '#123456', NULL, 1, 0)")
                db.execSQL("INSERT INTO sessions VALUES (1, 1, 1, '2027-01-01 15:00', '2D', 500)")
                db.execSQL("INSERT INTO users VALUES (1, 'Тестов', 'Зритель', 'viewer@example.test', '', '', 'viewer', NULL, 0, 0, 0)")
                db.execSQL("INSERT INTO orders (id,userId,sessionId,createdAt,expiresAt,status,sumTotal,bonusUsed,bonusEarned,total) VALUES (1,1,1,'2027-01-01 12:00:00','2027-01-01 12:10:00','paid',500,0,0,500)")
                db.execSQL("INSERT INTO tickets (id, orderId, `row`, seat, type, price, code) VALUES (1, 1, 3, 4, 'adult', 500, 'TEST-MIGRATION')")
                db.version = 1
            }
            val db = open(app, name)
            try {
                val ticket = db.orders().byCode("TEST-MIGRATION")
                assertNotNull(ticket)
                assertEquals(500, ticket!!.price)
                assertEquals(0, ticket.seatSurcharge)
            } finally {
                db.close()
            }
        } finally {
            app.deleteDatabase(name)
        }
    }

    private fun open(context: Context, name: String) = Room.databaseBuilder(context, KadrDatabase::class.java, name)
        .addMigrations(migration1To2).build()
}
