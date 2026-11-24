package ru.kadr.app.ui.staff

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.kadr.app.ui.components.Chip
import ru.kadr.app.ui.components.ErrorText
import ru.kadr.app.ui.theme.Kadr
import java.time.LocalDate

// Общие детали интерфейса рабочего кабинета

@Composable
fun Card(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth().background(Kadr.Surface, RoundedCornerShape(16.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        content = content,
    )
}

@Composable
fun Title(text: String) = Text(text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = Kadr.Text)

@Composable
fun Note(text: String, color: Color = Kadr.Muted) = Text(text, fontSize = 12.sp, color = color)

@Composable
fun Badge(text: String, color: Color) {
    Text(
        text, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = color,
        modifier = Modifier.border(1.dp, color.copy(alpha = 0.6f), RoundedCornerShape(8.dp)).padding(horizontal = 7.dp, vertical = 2.dp),
    )
}

/** Выбор одного варианта из списка — строкой «чипсов». */
@Composable
fun <T> Choice(label: String?, options: List<T>, selected: T?, title: (T) -> String, onSelect: (T) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (label != null) Note(label)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(end = 8.dp)) {
            items(options) { o -> Chip(title(o), o == selected) { onSelect(o) } }
        }
    }
}

/** Окно-форма: поля, ошибка, кнопки «Сохранить» и «Отмена». */
@Composable
fun FormDialog(
    title: String,
    error: String?,
    busy: Boolean,
    confirm: String = "Сохранить",
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        containerColor = Kadr.Surface,
        title = { Text(title, color = Kadr.Text, fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                content()
                ErrorText(error)
            }
        },
        confirmButton = { TextButton(onClick = onConfirm, enabled = !busy) { Text(if (busy) "Сохраняю…" else confirm, color = Kadr.Accent) } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Отмена", color = Kadr.Muted) } },
    )
}

@Composable
fun KV(k: String, v: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(k, fontSize = 13.sp, color = Kadr.Muted)
        Text(v, fontSize = 13.sp, color = Kadr.Text, fontWeight = FontWeight.SemiBold)
    }
}

private val MONTHS = listOf("янв", "фев", "мар", "апр", "мая", "июн", "июл", "авг", "сен", "окт", "ноя", "дек")
private val WEEK = listOf("пн", "вт", "ср", "чт", "пт", "сб", "вс")

fun dayLabel(d: LocalDate): String {
    val today = LocalDate.now()
    return when (d) {
        today -> "Сегодня"
        today.plusDays(1) -> "Завтра"
        else -> "${WEEK[d.dayOfWeek.value - 1]}, ${d.dayOfMonth} ${MONTHS[d.monthValue - 1]}"
    }
}

/** "2026-11-20 19:45[:00]" → "20 ноя, 19:45" */
fun shortStamp(s: String?): String {
    if (s == null || s.length < 16) return s ?: ""
    val m = s.substring(5, 7).toIntOrNull() ?: return s
    return "${s.substring(8, 10).trimStart('0')} ${MONTHS[m - 1]}, ${s.substring(11, 16)}"
}

fun rub(n: Int): String = "%,d ₽".format(n).replace(',', ' ')
