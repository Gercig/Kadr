package ru.kadr.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import ru.kadr.app.BuildConfig
import ru.kadr.app.ui.components.LogoMark
import ru.kadr.app.ui.theme.Kadr

/** Заставка при входе (логотип «Кадр»). Ждёт, пока приложение проверит вход и загрузит кинотеатры. */
@Composable
fun SplashScreen(ready: Boolean, onFinished: () -> Unit) {
    LaunchedEffect(ready) {
        if (ready) {
            delay(700)
            onFinished()
        }
    }
    // Логотип по центру, подписи внизу. При горизонтальном экране места мало — всё мельче и одной колонкой,
    // чтобы подписи не наезжали на логотип.
    BoxWithConstraints(Modifier.fillMaxSize().background(Kadr.Bg).systemBarsPadding()) {
        val small = maxHeight < 520.dp
        Column(
            Modifier.fillMaxSize().padding(vertical = if (small) 16.dp else 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.weight(1f))
            LogoMark(size = if (small) 64.dp else 120.dp)
            Spacer(Modifier.height(if (small) 14.dp else 28.dp))
            Text("КАДР", fontSize = if (small) 34.sp else 48.sp, fontWeight = FontWeight.Black, letterSpacing = 6.sp, color = Kadr.Text)
            Spacer(Modifier.height(if (small) 4.dp else 10.dp))
            Text("КИНОТЕАТР", fontSize = if (small) 12.sp else 14.sp, letterSpacing = 5.sp, color = Kadr.Muted)
            Spacer(Modifier.weight(1f))
            Text("Сеть кинотеатров", color = Kadr.Muted, fontSize = 12.sp)
            Spacer(Modifier.height(4.dp))
            Text("Версия ${BuildConfig.VERSION_NAME}", color = Kadr.Muted, fontSize = 12.sp)
        }
    }
}
