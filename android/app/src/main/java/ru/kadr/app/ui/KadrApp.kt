package ru.kadr.app.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import ru.kadr.app.AppViewModel
import ru.kadr.app.R
import ru.kadr.app.ui.screens.CheckoutScreen
import ru.kadr.app.ui.screens.HomeScreen
import ru.kadr.app.ui.screens.LoginScreen
import ru.kadr.app.ui.screens.ProfileScreen
import ru.kadr.app.ui.screens.RegisterScreen
import ru.kadr.app.ui.screens.SeatScreen
import ru.kadr.app.ui.screens.SplashScreen
import ru.kadr.app.ui.screens.TicketScreen
import ru.kadr.app.ui.screens.TicketsScreen
import ru.kadr.app.ui.staff.StaffScreen
import ru.kadr.app.ui.theme.Kadr

object Routes {
    const val SPLASH = "splash"
    const val HOME = "home"
    const val TICKETS = "tickets"
    const val PROFILE = "profile"
    const val LOGIN = "login?session={session}"
    const val REGISTER = "register?session={session}"
    const val SEATS = "seats/{session}"
    const val CHECKOUT = "checkout/{order}"
    const val TICKET = "ticket/{order}"
    const val STAFF = "staff"

    fun login(session: Int = 0) = "login?session=$session"
    fun register(session: Int = 0) = "register?session=$session"
    fun seats(session: Int) = "seats/$session"
    fun checkout(order: Int) = "checkout/$order"
    fun ticket(order: Int) = "ticket/$order"
}

private data class Tab(val route: String, val label: String, val icon: Int)

@Composable
fun KadrApp() {
    val vm: AppViewModel = viewModel()
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val tabs = listOf(
        Tab(Routes.HOME, "Афиша", R.drawable.ic_film),
        Tab(Routes.TICKETS, "Билеты", R.drawable.ic_ticket),
        Tab(Routes.PROFILE, if (vm.user == null) "Войти" else "Профиль", R.drawable.ic_user),
    )
    val showTabs = tabs.any { it.route == route }

    Scaffold(
        containerColor = Kadr.Bg,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (showTabs) {
                NavigationBar(containerColor = Kadr.Surface) {
                    tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = route == tab.route,
                            onClick = {
                                if (route != tab.route) {
                                    nav.navigate(tab.route) {
                                        popUpTo(Routes.HOME) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                }
                            },
                            icon = { Icon(painterResource(tab.icon), contentDescription = null, modifier = Modifier.size(24.dp)) },
                            label = { Text(tab.label) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Kadr.Accent,
                                selectedTextColor = Kadr.Accent,
                                indicatorColor = Kadr.Surface2,
                                unselectedIconColor = Kadr.Muted,
                                unselectedTextColor = Kadr.Muted,
                            ),
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(nav, startDestination = Routes.SPLASH, modifier = Modifier.padding(padding)) {
            composable(Routes.SPLASH) {
                SplashScreen(ready = vm.startupDone) {
                    nav.navigate(Routes.HOME) { popUpTo(Routes.SPLASH) { inclusive = true } }
                }
            }
            composable(Routes.HOME) {
                HomeScreen(vm, onSession = { id -> openSession(nav, vm, id) }, onLogin = { id -> nav.navigate(Routes.login(id)) }, onRegister = { id -> nav.navigate(Routes.register(id)) })
            }
            composable(Routes.TICKETS) {
                TicketsScreen(vm, onOpen = { nav.navigate(Routes.ticket(it)) }, onLogin = { nav.navigate(Routes.login()) })
            }
            composable(Routes.PROFILE) {
                ProfileScreen(vm, onLogin = { nav.navigate(Routes.login()) }, onRegister = { nav.navigate(Routes.register()) }, onStaff = { nav.navigate(Routes.STAFF) })
            }
            composable(Routes.LOGIN, arguments = listOf(navArgument("session") { type = NavType.IntType; defaultValue = 0 })) {
                val session = it.arguments?.getInt("session") ?: 0
                LoginScreen(
                    vm,
                    onBack = { nav.popBackStack() },
                    onRegister = { nav.navigate(Routes.register(session)) { popUpTo(Routes.LOGIN) { inclusive = true } } },
                    onDone = {
                        // сотрудники после входа сразу попадают в рабочий кабинет
                        if (vm.user?.role != null && vm.user?.role != "viewer") {
                            nav.navigate(Routes.STAFF) { popUpTo(Routes.HOME) }
                        } else afterAuth(nav, session)
                    },
                )
            }
            composable(Routes.REGISTER, arguments = listOf(navArgument("session") { type = NavType.IntType; defaultValue = 0 })) {
                val session = it.arguments?.getInt("session") ?: 0
                RegisterScreen(vm, onBack = { nav.popBackStack() }, onDone = { afterAuth(nav, session) })
            }
            composable(Routes.SEATS, arguments = listOf(navArgument("session") { type = NavType.IntType })) {
                val session = it.arguments?.getInt("session") ?: 0
                SeatScreen(vm, session, onBack = { nav.popBackStack() }, onOrder = { order -> nav.navigate(Routes.checkout(order)) })
            }
            composable(Routes.CHECKOUT, arguments = listOf(navArgument("order") { type = NavType.IntType })) {
                val order = it.arguments?.getInt("order") ?: 0
                CheckoutScreen(vm, order, onBack = { nav.popBackStack() }, onPaid = {
                    nav.navigate(Routes.ticket(order)) { popUpTo(Routes.HOME) }
                })
            }
            composable(Routes.STAFF) {
                StaffScreen(vm, onBack = { nav.popBackStack() })
            }
            composable(Routes.TICKET, arguments = listOf(navArgument("order") { type = NavType.IntType })) {
                val order = it.arguments?.getInt("order") ?: 0
                TicketScreen(order, onHome = {
                    nav.navigate(Routes.HOME) { popUpTo(Routes.HOME) { inclusive = true } }
                })
            }
        }
    }
}

private fun openSession(nav: NavHostController, vm: AppViewModel, sessionId: Int) {
    if (vm.user != null) nav.navigate(Routes.seats(sessionId))
}

/** После входа: если человек шёл покупать билет — сразу к выбору мест, иначе назад. */
private fun afterAuth(nav: NavHostController, session: Int) {
    if (session > 0) {
        nav.navigate(Routes.seats(session)) { popUpTo(Routes.HOME) }
    } else {
        nav.popBackStack()
    }
}
