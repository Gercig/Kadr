package ru.kadr.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import ru.kadr.app.data.Cinema
import ru.kadr.app.data.LoginBody
import ru.kadr.app.data.RegisterBody
import ru.kadr.app.data.User
import ru.kadr.app.data.local.LocalData
import ru.kadr.app.data.userMessage

class AppViewModel : ViewModel() {
    var user by mutableStateOf<User?>(null)
        private set
    var cinemas by mutableStateOf<List<Cinema>>(emptyList())
        private set
    var cinemaId by mutableStateOf(1)
        private set
    var startupDone by mutableStateOf(false)
        private set
    var adminSetupAvailable by mutableStateOf(false)
        private set

    val cinema: Cinema? get() = cinemas.firstOrNull { it.id == cinemaId } ?: cinemas.firstOrNull()

    init {
        viewModelScope.launch {
            loadCinemas()
            try {
                cinemaId = LocalData.accounts.state().cinemaId
                user = LocalData.accounts.current()?.let { LocalData.accounts.me() }
                adminSetupAvailable = !LocalData.accounts.hasAdmin()
            } finally {
                startupDone = true
            }
        }
    }

    suspend fun loadCinemas(): String? = try {
        LocalData.catalog.initialize()
        cinemas = LocalData.catalog.cinemas()
        null
    } catch (e: Exception) {
        e.userMessage()
    }

    fun selectCinema(id: Int) {
        cinemaId = id
        viewModelScope.launch { LocalData.accounts.selectCinema(id) }
    }

    suspend fun login(email: String, password: String): String? = auth {
        LocalData.accounts.login(LoginBody(email, password))
    }

    suspend fun register(body: RegisterBody): String? = auth {
        LocalData.accounts.register(body)
    }

    suspend fun createAdmin(login: String, password: String): String? = auth {
        LocalData.accounts.createAdmin(login, password).also { adminSetupAvailable = false }
    }

    private suspend fun auth(operation: suspend () -> User): String? = try {
        user = operation()
        null
    } catch (e: Exception) {
        e.userMessage()
    }

    suspend fun refreshUser() {
        try {
            user = LocalData.accounts.me()
        } catch (e: Exception) {
            e.userMessage()
        }
    }

    fun logout() {
        viewModelScope.launch {
            LocalData.accounts.logout()
            user = null
        }
    }
}
