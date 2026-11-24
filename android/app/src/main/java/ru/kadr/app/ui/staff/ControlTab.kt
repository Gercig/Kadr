package ru.kadr.app.ui.staff

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.launch
import ru.kadr.app.data.ApiClient
import ru.kadr.app.data.CheckBody
import ru.kadr.app.data.CheckResult
import ru.kadr.app.data.userMessage
import androidx.activity.compose.rememberLauncherForActivityResult
import ru.kadr.app.ui.components.ErrorText
import ru.kadr.app.ui.components.KadrField
import ru.kadr.app.ui.components.PrimaryButton
import ru.kadr.app.ui.components.SecondaryButton
import ru.kadr.app.ui.theme.Kadr

/**
 * Контроль на входе: камера считывает QR-код билета, сервер отвечает, действителен ли он.
 * «Пропустить» гасит билет — повторно по нему не пройти. Код можно ввести и вручную.
 */
@Composable
fun ControlTab() {
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<CheckResult?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun verify(c: String, admit: Boolean) {
        if (c.isBlank()) return
        scope.launch {
            busy = true
            try {
                result = ApiClient.api.checkTicket(CheckBody(c.trim(), admit)); error = null
            } catch (e: Exception) { error = e.userMessage() }
            busy = false
        }
    }
    val scan = rememberLauncherForActivityResult(ScanContract()) { r ->
        val text = r.contents
        if (text != null) { code = text; verify(text, false) }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PrimaryButton("Сканировать QR-код билета", loading = busy, modifier = Modifier.fillMaxWidth(), onClick = {
            scan.launch(
                ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setPrompt("Наведите камеру на QR-код билета")
                    .setBeepEnabled(true).setOrientationLocked(false),
            )
        })
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            KadrField(code, { code = it.uppercase() }, "Код билета (KADR-…)", Modifier.weight(1f))
        }
        SecondaryButton("Проверить код", onClick = { verify(code, false) }, modifier = Modifier.fillMaxWidth())
        ErrorText(error)
        result?.let { r ->
            val good = r.ok
            Column(
                Modifier.fillMaxWidth()
                    .background(if (good) Color(0xFF14322A) else Color(0xFF3A1A18), RoundedCornerShape(18.dp))
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(if (good) (if (r.admitted == true) "✓ ПРОХОД РАЗРЕШЁН" else "✓ БИЛЕТ ДЕЙСТВИТЕЛЕН") else "✕ НЕЛЬЗЯ ПРОПУСКАТЬ",
                    fontSize = 20.sp, fontWeight = FontWeight.Bold, color = if (good) Kadr.Mint else Kadr.AccentSoft)
                Text(r.verdict, fontSize = 14.sp, color = Kadr.Text)
                if (r.film != null) {
                    KV("Фильм", "${r.film} · ${r.age}")
                    KV("Сеанс", shortStamp(r.startsAt))
                    KV("Зал", "${r.cinema}, ${r.hall}")
                    KV("Место", "ряд ${r.row}, место ${r.seat}")
                    KV("Билет", r.typeName ?: "")
                    KV("Зритель", r.viewer ?: "")
                    if (r.typeName == "Детский" || r.typeName == "Льготный") Note("Проверьте документ для льготного/детского билета", Kadr.Gold)
                }
                if (good && r.admitted != true) {
                    PrimaryButton("Пропустить", modifier = Modifier.fillMaxWidth(), loading = busy, onClick = { verify(r.code ?: code, true) })
                }
            }
        }
    }
}
