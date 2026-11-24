package ru.kadr.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import ru.kadr.app.AppViewModel
import ru.kadr.app.data.RegisterBody
import ru.kadr.app.ui.components.ErrorText
import ru.kadr.app.ui.components.KadrField
import ru.kadr.app.ui.components.LogoMark
import ru.kadr.app.ui.components.PrimaryButton
import ru.kadr.app.ui.components.ScreenHeader
import ru.kadr.app.ui.components.clickableNoRipple
import ru.kadr.app.ui.theme.Kadr

@Composable
fun LoginScreen(vm: AppViewModel, onBack: () -> Unit, onRegister: () -> Unit, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize().background(Kadr.Bg).statusBarsPadding().imePadding().verticalScroll(rememberScrollState()),
    ) {
        ScreenHeader("Вход", onBack = onBack)
        Column(modifier = Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                LogoMark(size = 40.dp)
                Text("Войдите, чтобы покупать билеты и копить бонусы", color = Kadr.Muted, fontSize = 14.sp)
            }
            KadrField(email, { email = it }, "Электронная почта или логин", keyboard = KeyboardType.Email)
            KadrField(password, { password = it }, "Пароль", password = true, keyboard = KeyboardType.Password)
            ErrorText(error)
            PrimaryButton(
                "Войти",
                loading = busy,
                enabled = email.isNotBlank() && password.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    scope.launch {
                        busy = true
                        error = vm.login(email, password)
                        busy = false
                        if (error == null) onDone()
                    }
                },
            )
            Text(
                "Нет аккаунта? Зарегистрироваться",
                color = Kadr.AccentSoft,
                fontSize = 14.sp,
                modifier = Modifier.clickableNoRipple(onRegister).padding(vertical = 8.dp),
            )
            if (vm.adminSetupAvailable) {
                Text(
                    "Создать локального администратора с введёнными данными",
                    color = Kadr.AccentSoft,
                    fontSize = 14.sp,
                    modifier = Modifier.clickableNoRipple {
                        if (!busy) scope.launch {
                            busy = true
                            error = vm.createAdmin(email, password)
                            busy = false
                            if (error == null) onDone()
                        }
                    }.padding(vertical = 8.dp),
                )
            } else {
                Text("Сотрудники входят с данными, выданными администратором", color = Kadr.Muted, fontSize = 12.sp)
            }
        }
    }
}

/** Маска телефона: +7 ___-___-__-__ */
private fun formatPhone(input: String): String {
    var digits = input.filter { it.isDigit() }
    if (digits.startsWith("7") || digits.startsWith("8")) digits = digits.drop(1)
    digits = digits.take(10)
    val sb = StringBuilder("+7 ")
    digits.forEachIndexed { i, ch ->
        if (i == 3 || i == 6 || i == 8) sb.append('-')
        sb.append(ch)
    }
    return sb.toString()
}

private val NAME = Regex("^[А-Яа-яЁё-]{0,30}$")

private fun passwordProblem(p: String): String? = when {
    p.length < 8 -> "Не менее 8 символов"
    !p.any { it in 'a'..'z' } || !p.any { it in 'A'..'Z' } -> "Нужны латинские буквы в верхнем и нижнем регистре"
    !p.any { it.isDigit() } -> "Нужна хотя бы одна цифра"
    !p.any { it in "~+$&#@%!?*_-.=^" } -> "Нужен спецсимвол (~+$&#@% и др.)"
    else -> null
}

@Composable
fun RegisterScreen(vm: AppViewModel, onBack: () -> Unit, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var lastName by remember { mutableStateOf("") }
    var firstName by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("+7 ") }
    var password by remember { mutableStateOf("") }
    var password2 by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    val nameError = "Ограничение до 30 символов. Ввод допустим только буквами русского алфавита"
    val lastNameErr = if (!NAME.matches(lastName)) nameError else null
    val firstNameErr = if (!NAME.matches(firstName)) nameError else null
    val emailErr = when {
        email.length > 50 -> "Недопустимое количество символов. Попробуйте снова"
        email.isNotEmpty() && !Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$").matches(email) -> "Неверный формат электронной почты"
        else -> null
    }
    val passErr = if (password.isNotEmpty()) passwordProblem(password) else null
    val pass2Err = if (password2.isNotEmpty() && password2 != password) "Пароли не совпадают" else null
    val phoneDigits = phone.count { it.isDigit() }
    val ready = lastName.isNotBlank() && firstName.isNotBlank() && email.isNotBlank() && phoneDigits == 11 &&
        password.isNotEmpty() && password == password2 &&
        listOf(lastNameErr, firstNameErr, emailErr, passErr, pass2Err).all { it == null }

    Column(
        modifier = Modifier.fillMaxSize().background(Kadr.Bg).statusBarsPadding().imePadding().verticalScroll(rememberScrollState()),
    ) {
        ScreenHeader("Регистрация", onBack = onBack)
        Column(modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            KadrField(lastName, { lastName = it.take(31) }, "Фамилия", error = lastNameErr)
            KadrField(firstName, { firstName = it.take(31) }, "Имя", error = firstNameErr)
            KadrField(email, { email = it.trim() }, "Электронная почта", keyboard = KeyboardType.Email, error = emailErr)
            KadrField(phone, { phone = formatPhone(it) }, "Номер телефона", keyboard = KeyboardType.Phone, placeholder = "+7 ___-___-__-__")
            KadrField(password, { password = it }, "Пароль", password = true, keyboard = KeyboardType.Password, error = passErr)
            KadrField(password2, { password2 = it }, "Повторите пароль", password = true, keyboard = KeyboardType.Password, error = pass2Err)
            Text(
                "Пароль: не менее 8 символов, латинские буквы в верхнем и нижнем регистре, цифра и спецсимвол.",
                color = Kadr.Muted, fontSize = 12.sp,
            )
            ErrorText(error)
            PrimaryButton(
                "Зарегистрироваться",
                enabled = ready,
                loading = busy,
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    scope.launch {
                        busy = true
                        error = vm.register(RegisterBody(lastName.trim(), firstName.trim(), email.trim(), phone, password))
                        busy = false
                        if (error == null) onDone()
                    }
                },
            )
        }
    }
}
