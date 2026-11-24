package ru.kadr.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.kadr.app.R
import ru.kadr.app.ui.theme.Kadr

/** Знак «Кадр»: кадр плёнки с перфорацией и кнопкой «плей». */
@Composable
fun LogoMark(size: Dp, accent: Color = Kadr.Accent, play: Color = Kadr.Text) {
    Canvas(modifier = Modifier.size(size)) {
        val s = this.size.width / 120f
        drawRoundRect(
            color = accent,
            topLeft = Offset(10 * s, 10 * s),
            size = Size(100 * s, 100 * s),
            cornerRadius = CornerRadius(20 * s),
            style = Stroke(width = 8 * s),
        )
        for (x in listOf(21f, 89f)) {
            for (y in listOf(24f, 53f, 82f)) {
                drawRoundRect(accent, Offset(x * s, y * s), Size(10 * s, 14 * s), CornerRadius(3 * s))
            }
        }
        val p = Path().apply {
            moveTo(49 * s, 42 * s); lineTo(49 * s, 78 * s); lineTo(78 * s, 60 * s); close()
        }
        drawPath(p, play)
    }
}

@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, loading: Boolean = false) {
    Button(
        onClick = onClick,
        enabled = enabled && !loading,
        modifier = modifier.height(52.dp),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Kadr.Accent, contentColor = Color.White),
    ) {
        if (loading) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
        } else {
            Text(text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        }
    }
}

@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(52.dp),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, Kadr.Outline),
    ) {
        Text(text, color = Kadr.Text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
    }
}

/** Пилюля-переключатель (фильтры, типы билетов, города). */
@Composable
fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(17.dp)
    Box(
        modifier = Modifier
            .height(34.dp)
            .background(if (selected) Kadr.Text else Color.Transparent, shape)
            .border(1.dp, if (selected) Kadr.Text else Kadr.Outline, shape)
            .clickableNoRipple(onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = if (selected) Kadr.Bg else Color(0xFFD6D3DC), fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun AgeBadge(age: String) {
    Box(
        modifier = Modifier
            .border(1.dp, Color(0xFF55525E), RoundedCornerShape(6.dp))
            .padding(horizontal = 5.dp, vertical = 1.dp),
    ) {
        Text(age, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Kadr.Text)
    }
}

@Composable
fun CircleIconButton(iconRes: Int, description: String, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(44.dp).background(Kadr.Surface2, CircleShape),
    ) {
        Icon(painterResource(iconRes), contentDescription = description, tint = Kadr.Text, modifier = Modifier.size(20.dp))
    }
}

@Composable
fun ScreenHeader(title: String, subtitle: String? = null, onBack: (() -> Unit)? = null) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (onBack != null) CircleIconButton(R.drawable.ic_back, "Назад", onBack)
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Kadr.Text)
            if (subtitle != null) Text(subtitle, fontSize = 13.sp, color = Kadr.Muted)
        }
    }
}

@Composable
fun KadrField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    password: Boolean = false,
    keyboard: KeyboardType = KeyboardType.Text,
    error: String? = null,
    placeholder: String? = null,
) {
    val placeholderSlot: (@Composable () -> Unit)? = if (placeholder != null) {
        @Composable { Text(placeholder) }
    } else {
        null
    }
    val errorSlot: (@Composable () -> Unit)? = if (error != null) {
        @Composable { Text(error, color = Kadr.AccentSoft) }
    } else {
        null
    }
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        placeholder = placeholderSlot,
        singleLine = true,
        isError = error != null,
        supportingText = errorSlot,
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        shape = RoundedCornerShape(12.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Kadr.Accent,
            unfocusedBorderColor = Color(0xFF2E2E36),
            focusedLabelColor = Kadr.AccentSoft,
            cursorColor = Kadr.Accent,
        ),
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
fun ErrorText(text: String?) {
    if (text != null) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF2A1A19), RoundedCornerShape(12.dp))
                .padding(12.dp),
        ) {
            Text(text, color = Color(0xFFFFC2BD), fontSize = 13.sp)
        }
    }
}

@Composable
fun Loading(modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = Kadr.Accent)
    }
}

/** Клик без «ряби» — для собственных элементов (места в зале, даты). */
fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier =
    this.clickable(interactionSource = null, indication = null, onClick = onClick)
