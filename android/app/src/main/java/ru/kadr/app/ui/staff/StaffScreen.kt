package ru.kadr.app.ui.staff

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ru.kadr.app.AppViewModel
import ru.kadr.app.data.ApiClient
import ru.kadr.app.data.StaffCinema
import ru.kadr.app.ui.components.Chip
import ru.kadr.app.ui.components.ErrorText
import ru.kadr.app.ui.components.ScreenHeader
import ru.kadr.app.ui.theme.Kadr
import ru.kadr.app.data.userMessage

private val ROLE_NAMES = mapOf(
    "app_admin" to "Администратор приложения",
    "cinema_admin" to "Администратор кинотеатра",
    "controller" to "Контролёр",
)

/**
 * Рабочий кабинет сотрудника. Набор вкладок зависит от роли:
 *  • администратор приложения — кинотеатры и залы, сотрудники, пользователи, отчёты и оплаты по сети;
 *  • администратор кинотеатра — сеансы и цены, фильмы, залы, отчёты, оплаты и возвраты, контроль билетов;
 *  • контролёр — проверка билетов по QR-коду.
 */
@Composable
fun StaffScreen(vm: AppViewModel, onBack: () -> Unit) {
    val user = vm.user ?: return
    val tabs = when (user.role) {
        "app_admin" -> listOf("Кинотеатры", "Сотрудники", "Пользователи", "Отчёты", "Оплаты")
        "cinema_admin" -> listOf("Сеансы", "Фильмы", "Залы", "Отчёты", "Оплаты", "Контроль")
        else -> listOf("Контроль")
    }
    var tab by rememberSaveable { mutableStateOf(tabs.first()) }
    var cinemas by remember { mutableStateOf<List<StaffCinema>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableStateOf(0) }
    LaunchedEffect(reload) {
        try {
            cinemas = ApiClient.api.staffCinemas()
            error = null
        } catch (e: Exception) {
            error = e.userMessage()
        }
    }
    val own = cinemas.firstOrNull { it.id == user.cinemaId }

    Column(Modifier.fillMaxSize().background(Kadr.Bg).statusBarsPadding()) {
        ScreenHeader(
            "Рабочий кабинет",
            ROLE_NAMES[user.role].orEmpty() + (own?.let { " · ${it.name}" } ?: ""),
            onBack = onBack,
        )
        if (tabs.size > 1) {
            LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(tabs) { t -> Chip(t, t == tab) { tab = t } }
            }
        }
        if (error != null) Column(Modifier.padding(20.dp)) { ErrorText(error) }
        val refresh: () -> Unit = { reload++ }
        when (tab) {
            "Кинотеатры" -> CinemasTab(cinemas, refresh)
            "Сотрудники" -> StaffTab(cinemas)
            "Пользователи" -> UsersTab()
            "Сеансы" -> SessionsTab(own)
            "Фильмы" -> FilmsTab()
            "Залы" -> HallsTab(own, refresh)
            "Отчёты" -> ReportTab(if (user.role == "app_admin") cinemas else emptyList())
            "Оплаты" -> PaymentsTab(if (user.role == "app_admin") cinemas else emptyList())
            "Контроль" -> ControlTab()
        }
    }
}
