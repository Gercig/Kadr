package ru.kadr.app.data.local

import androidx.room.withTransaction
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.Locale
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import ru.kadr.app.data.*

internal val timestampFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss")
internal val sessionFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm")
internal fun stamp(time: LocalDateTime = LocalDateTime.now()): String = time.format(timestampFormat)
internal fun UserEntity.display() = User(id, lastName, firstName, email, phone, role, cinemaId, bonus, false)

internal object LocalCredentials {
    private val random = SecureRandom()
    private val encoder = Base64.getEncoder()
    private val decoder = Base64.getDecoder()

    fun hash(input: String): String {
        val salt = ByteArray(16).also(random::nextBytes)
        return encoder.encodeToString(salt) + ":" + encoder.encodeToString(derive(input, salt))
    }

    fun matches(input: String, saved: String): Boolean {
        val (salt, expected) = saved.split(':', limit = 2)
        return MessageDigest.isEqual(derive(input, decoder.decode(salt)), decoder.decode(expected))
    }

    private fun derive(input: String, salt: ByteArray): ByteArray {
        val specification = PBEKeySpec(input.toCharArray(), salt, 210_000, 256)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(specification).encoded
        } finally {
            specification.clearPassword()
        }
    }

    fun validate(input: String) {
        require(input.length >= 8) { "Введите не менее восьми символов" }
    }
}

internal fun normalizeLogin(input: String): String = input.trim().lowercase(Locale.ROOT)

class AccountRepository(private val db: KadrDatabase) {
    private val dao = db.accounts()
    suspend fun state(): AppStateEntity = dao.state() ?: AppStateEntity()
    suspend fun current(): UserEntity? = state().userId?.let { dao.user(it) }?.takeUnless { it.blocked }
    internal suspend fun requireUser(): UserEntity = current() ?: error("Войдите в приложение")
    suspend fun me(): User = requireUser().display()
    suspend fun hasAdmin(): Boolean = dao.hasAdmin()

    suspend fun selectCinema(id: Int) = db.withTransaction {
        dao.saveState(state().copy(cinemaId = id))
    }

    suspend fun login(body: LoginBody): User {
        // Возвращаем ошибку после завершения транзакции, чтобы учесть неудачную попытку.
        val result = db.withTransaction {
            val user = dao.byLogin(normalizeLogin(body.email))
                ?: return@withTransaction null to "Неверная почта или пароль"
            if (user.blocked) return@withTransaction null to "Учётная запись заблокирована"
            if (!LocalCredentials.matches(body.password, user.verifier)) {
                val failed = user.failedLogins + 1
                dao.updateUser(user.copy(failedLogins = failed, blocked = failed >= 5))
                return@withTransaction null to if (failed >= 5) "Учётная запись заблокирована" else
                    "Неверная почта или пароль. Осталось попыток: ${5 - failed}"
            }
            dao.updateUser(user.copy(failedLogins = 0))
            dao.saveState(state().copy(userId = user.id))
            user.display() to null
        }
        return result.first ?: error(result.second ?: "Не удалось войти")
    }

    suspend fun register(body: RegisterBody): User = db.withTransaction {
        val name = Regex("[А-Яа-яЁё-]{1,30}")
        require(name.matches(body.firstName.trim()) && name.matches(body.lastName.trim())) { "Проверьте имя и фамилию" }
        val email = normalizeLogin(body.email)
        require(android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) { "Укажите электронную почту" }
        val digits = body.phone.filter(Char::isDigit)
        require(digits.length == 11 && digits.first() in "78") { "Укажите номер телефона" }
        val phone = "+7" + digits.drop(1)
        require(dao.users().none { it.phone == phone }) { "Этот номер уже зарегистрирован" }
        create(UserEntity(lastName = body.lastName.trim(), firstName = body.firstName.trim(), email = email,
            phone = phone, verifier = verifiedHash(body.password)))
    }

    suspend fun createAdmin(login: String, password: String): User = db.withTransaction {
        check(!dao.hasAdmin()) { "Администратор уже настроен" }
        val email = normalizeLogin(login)
        require(email.isNotEmpty()) { "Укажите логин" }
        create(UserEntity(lastName = "Администратор", firstName = "Кадр", email = email,
            phone = "", verifier = verifiedHash(password), role = "app_admin"))
    }

    private suspend fun create(value: UserEntity): User {
        require(dao.byLogin(value.email) == null) { "Этот логин уже используется" }
        val id = dao.insertUser(value).toInt()
        dao.saveState(state().copy(userId = id))
        return value.copy(id = id).display()
    }

    suspend fun logout() = db.withTransaction { dao.saveState(state().copy(userId = null)) }
    suspend fun bonus(): BonusInfo = requireUser().let { user ->
        BonusInfo(user.bonus, dao.bonus(user.id).map { BonusOp(it.amount, it.reason, it.createdAt) })
    }
}

internal fun verifiedHash(password: String): String {
    LocalCredentials.validate(password)
    return LocalCredentials.hash(password)
}
