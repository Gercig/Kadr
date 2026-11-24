package ru.kadr.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import ru.kadr.app.data.ApiClient
import ru.kadr.app.data.Cinema
import ru.kadr.app.data.LoginBody
import ru.kadr.app.data.RegisterBody
import ru.kadr.app.data.User
import ru.kadr.app.data.userMessage

/**
 * Общее состояние приложения: кто вошёл и какой кинотеатр выбран.
 * Экраны читают его, а сетевые запросы идут через ApiClient.
 */
class AppViewModel : ViewModel() {
    var user by mutableStateOf<User?>(null)
        private set
    var cinemas by mutableStateOf<List<Cinema>>(emptyList())
        private set
    var cinemaId by mutableStateOf(ApiClient.prefs.cinemaId)
        private set
    var startupDone by mutableStateOf(false)
        private set

    val cinema: Cinema? get() = cinemas.firstOrNull { it.id == cinemaId } ?: cinemas.firstOrNull()

    init {
        viewModelScope.launch {
            if (ApiClient.prefs.token != null) {
                user = try {
                    ApiClient.api.me()
                } catch (e: Exception) {
                    null
                }
            }
            loadCinemas()
            startupDone = true
        }
    }

    suspend fun loadCinemas(): String? = try {
        cinemas = ApiClient.api.cinemas()
        null
    } catch (e: Exception) {
        e.userMessage()
    }

    fun selectCinema(id: Int) {
        cinemaId = id
        ApiClient.prefs.cinemaId = id
    }

    /** Возвращает текст ошибки или null, если всё хорошо. */
    suspend fun login(email: String, password: String): String? = try {
        val r = ApiClient.api.login(LoginBody(email.trim(), password))
        ApiClient.prefs.token = r.token
        user = r.user
        null
    } catch (e: Exception) {
        e.userMessage()
    }

    suspend fun register(body: RegisterBody): String? = try {
        val r = ApiClient.api.register(body)
        ApiClient.prefs.token = r.token
        user = r.user
        null
    } catch (e: Exception) {
        e.userMessage()
    }

    suspend fun refreshUser() {
        try {
            user = ApiClient.api.me()
        } catch (e: Exception) {
            // оставляем прежние данные
        }
    }

    fun logout() {
        viewModelScope.launch {
            try {
                ApiClient.api.logout()
            } catch (e: Exception) {
                // сервер недоступен — всё равно выходим локально
            }
            ApiClient.prefs.token = null
            user = null
        }
    }
}
