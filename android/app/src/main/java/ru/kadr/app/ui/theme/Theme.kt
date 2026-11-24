package ru.kadr.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Палитра «Кадр» — та же, что в макетах
object Kadr {
    val Bg = Color(0xFF0E0E12)
    val Surface = Color(0xFF17171D)
    val Surface2 = Color(0xFF1B1B22)
    val Line = Color(0xFF24242C)
    val Outline = Color(0xFF3A3842)
    val Text = Color(0xFFF5F3EE)
    val Muted = Color(0xFFA6A2AE)
    val Accent = Color(0xFFFF5A4E)
    val AccentSoft = Color(0xFFFF8A82)
    val Mint = Color(0xFF4FD1B0)
    val Gold = Color(0xFFF2B544)
    val Seat = Color(0xFF33333D)
    val SeatTaken = Color(0xFF1A1A20)
    val Exit = Color(0xFF86FF5C)
}

private val scheme = darkColorScheme(
    primary = Kadr.Accent,
    onPrimary = Color.White,
    background = Kadr.Bg,
    onBackground = Kadr.Text,
    surface = Kadr.Surface,
    onSurface = Kadr.Text,
    surfaceVariant = Kadr.Surface2,
    onSurfaceVariant = Kadr.Muted,
    outline = Kadr.Outline,
    error = Kadr.AccentSoft,
)

@Composable
fun KadrTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, content = content)
}

fun parseColor(hex: String): Color = try {
    Color(android.graphics.Color.parseColor(hex))
} catch (e: IllegalArgumentException) {
    Color(0xFF2A2A33)
}
