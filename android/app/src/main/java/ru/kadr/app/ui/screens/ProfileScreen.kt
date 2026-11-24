package ru.kadr.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.kadr.app.AppViewModel
import ru.kadr.app.R
import ru.kadr.app.data.ApiClient
import ru.kadr.app.data.BonusInfo
import ru.kadr.app.ui.components.LogoMark
import ru.kadr.app.ui.components.PrimaryButton
import ru.kadr.app.ui.components.ScreenHeader
import ru.kadr.app.ui.components.SecondaryButton
import ru.kadr.app.ui.components.clickableNoRipple
import ru.kadr.app.ui.theme.Kadr

@Composable
fun ProfileScreen(vm: AppViewModel, onLogin: () -> Unit, onRegister: () -> Unit, onStaff: () -> Unit = {}) {
    val user = vm.user
    var bonus by remember { mutableStateOf<BonusInfo?>(null) }
    LaunchedEffect(user?.id) {
        if (user != null) {
            vm.refreshUser()
            bonus = try { ApiClient.api.bonus() } catch (e: Exception) { null }
        }
    }

    Column(Modifier.fillMaxSize().background(Kadr.Bg).statusBarsPadding().verticalScroll(rememberScrollState())) {
        ScreenHeader("Профиль")
        if (user == null) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    LogoMark(size = 44.dp)
                    Text("Войдите, чтобы покупать билеты, хранить их в приложении и копить бонусы", color = Kadr.Muted, fontSize = 14.sp)
                }
                PrimaryButton("Войти", onClick = onLogin, modifier = Modifier.fillMaxWidth())
                SecondaryButton("Зарегистрироваться", onClick = onRegister, modifier = Modifier.fillMaxWidth())
            }
            return@Column
        }
        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(
                    Modifier.size(56.dp).background(Kadr.Surface2, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("${user.firstName.take(1)}${user.lastName.take(1)}", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Kadr.Text)
                }
                Column {
                    Text("${user.lastName} ${user.firstName}", fontWeight = FontWeight.SemiBold, fontSize = 17.sp, color = Kadr.Text)
                    Text(user.email, fontSize = 13.sp, color = Kadr.Muted)
                    Text(user.phone, fontSize = 13.sp, color = Kadr.Muted)
                }
            }

            if (user.role != "viewer") {
                PrimaryButton("Рабочий кабинет", onClick = onStaff, modifier = Modifier.fillMaxWidth())
            }
            Column(
                Modifier.fillMaxWidth().background(Kadr.Surface, RoundedCornerShape(18.dp)).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(painterResource(R.drawable.ic_star), contentDescription = null, tint = Kadr.Gold, modifier = Modifier.size(22.dp))
                    Text("Бонусная карта", fontWeight = FontWeight.SemiBold, color = Kadr.Text)
                }
                Text("${bonus?.balance ?: user.bonus} баллов", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Kadr.Text)
                Text("5% от покупки возвращается баллами. Баллами можно оплатить до 30% стоимости билета.", fontSize = 12.sp, color = Kadr.Muted)
                val history = bonus?.history.orEmpty()
                if (history.isNotEmpty()) {
                    HorizontalDivider(color = Kadr.Line)
                    history.take(10).forEach { op ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column {
                                Text(op.reason, fontSize = 13.sp, color = Kadr.Text)
                                Text(op.createdAt.take(16), fontSize = 11.sp, color = Kadr.Muted)
                            }
                            Text(
                                (if (op.amount > 0) "+" else "") + op.amount,
                                fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                                color = if (op.amount > 0) Kadr.Mint else Kadr.AccentSoft,
                            )
                        }
                    }
                }
            }

            Row(
                Modifier.fillMaxWidth().background(Kadr.Surface, RoundedCornerShape(14.dp)).clickableNoRipple { vm.logout() }.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(painterResource(R.drawable.ic_logout), contentDescription = null, tint = Kadr.AccentSoft, modifier = Modifier.size(20.dp))
                Text("Выйти из аккаунта", color = Kadr.AccentSoft, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
