package com.maysk.camera.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maysk.camera.ui.theme.Accent
import com.maysk.camera.ui.theme.AccentDark
import com.maysk.camera.ui.theme.AccentSoft
import com.maysk.camera.ui.theme.Maysk

val AccentGradient = Brush.horizontalGradient(listOf(Accent, Color(0xFFFF4F7E), AccentDark))

@Composable
fun PrimaryButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(RoundedCornerShape(26.dp))
            .background(if (enabled) AccentGradient else Brush.horizontalGradient(listOf(Color.Gray, Color.DarkGray)))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
    }
}

@Composable
fun OutlineButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .height(48.dp)
            .clip(RoundedCornerShape(24.dp))
            .border(1.dp, Maysk.colors.border, RoundedCornerShape(24.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center
    ) { Text(text, color = Maysk.colors.text, fontWeight = FontWeight.Medium) }
}

@Composable
fun ScreenHeader(
    title: String,
    onBack: (() -> Unit)? = null,
    onClose: (() -> Unit)? = null,
    accentIcon: ImageVector? = null,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Row(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (onBack != null) IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад", tint = Maysk.colors.text)
        } else Spacer(Modifier.width(16.dp))
        if (accentIcon != null) {
            Icon(accentIcon, null, tint = Accent, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(title, color = Maysk.colors.text, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        trailing()
        if (onClose != null) IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "Закрыть", tint = Maysk.colors.text) }
    }
}

/** Сегментированный переключатель (Фото / Видео и т.п.). */
@Composable
fun Segmented(options: List<String>, selected: Int, modifier: Modifier = Modifier, onSelect: (Int) -> Unit) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(Maysk.colors.surface)
            .padding(4.dp)
    ) {
        options.forEachIndexed { i, s ->
            Box(
                Modifier
                    .weight(1f)
                    .height(38.dp)
                    .clip(RoundedCornerShape(19.dp))
                    .background(if (i == selected) Accent else Color.Transparent)
                    .clickable { onSelect(i) },
                contentAlignment = Alignment.Center
            ) {
                Text(s, color = if (i == selected) Color.White else Maysk.colors.muted, fontWeight = FontWeight.Medium, fontSize = 14.sp)
            }
        }
    }
}

/** Ряд «пилюль» с выбором значения (ISO 50/100/200...). */
@Composable
fun ChoiceRow(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEachIndexed { i, s ->
            val sel = i == selected
            Box(
                Modifier
                    .weight(1f)
                    .height(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (sel) Accent else Maysk.colors.surface)
                    .border(1.dp, if (sel) Accent else Maysk.colors.border, RoundedCornerShape(10.dp))
                    .clickable { onSelect(i) },
                contentAlignment = Alignment.Center
            ) {
                Text(s, color = if (sel) Color.White else Maysk.colors.text, fontSize = 12.sp, maxLines = 1, textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
fun Pill(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) Accent else Maysk.colors.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp)
    ) { Text(text, color = if (selected) Color.White else Maysk.colors.muted, fontSize = 13.sp) }
}

@Composable
fun SectionLabel(text: String) {
    Text(text, color = Maysk.colors.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 14.dp, bottom = 8.dp))
}

/** Плитка режима (сетка «Режимы съёмки»). */
@Composable
fun ModeTile(icon: ImageVector, title: String, subtitle: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(
        modifier
            .heightIn(min = 96.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) Brush.verticalGradient(listOf(Accent, AccentDark)) else Brush.verticalGradient(listOf(Maysk.colors.surface, Maysk.colors.surface)))
            .border(BorderStroke(1.dp, if (selected) Accent else Maysk.colors.border), RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(icon, null, tint = if (selected) Color.White else Maysk.colors.text, modifier = Modifier.size(28.dp))
        Spacer(Modifier.height(6.dp))
        Text(title, color = if (selected) Color.White else Maysk.colors.text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        Text(subtitle, color = if (selected) Color.White.copy(alpha = 0.8f) else Maysk.colors.muted, fontSize = 11.sp, textAlign = TextAlign.Center)
    }
}

@Composable
fun SettingRow(icon: ImageVector, title: String, value: String? = null, subtitle: String? = null, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconBadge(icon)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = Maysk.colors.text, fontSize = 15.sp)
            if (subtitle != null) Text(subtitle, color = Maysk.colors.muted, fontSize = 12.sp)
        }
        if (value != null) Text(value, color = Maysk.colors.muted, fontSize = 13.sp)
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Maysk.colors.muted)
    }
}

@Composable
fun ToggleRow(icon: ImageVector, title: String, checked: Boolean, subtitle: String? = null, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable { onChange(!checked) }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconBadge(icon)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = Maysk.colors.text, fontSize = 15.sp)
            if (subtitle != null) Text(subtitle, color = Maysk.colors.muted, fontSize = 12.sp)
        }
        Switch(
            checked = checked, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = Accent, checkedThumbColor = Color.White, uncheckedTrackColor = Maysk.colors.surface2)
        )
    }
}

@Composable
fun IconBadge(icon: ImageVector, tint: Color = Maysk.colors.text) {
    Box(
        Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Maysk.colors.surface2),
        contentAlignment = Alignment.Center
    ) { Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp)) }
}

@Composable
fun CheckLine(text: String, checked: Boolean = true, onToggle: (() -> Unit)? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .let { if (onToggle != null) it.clickable { onToggle() } else it }
            .padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(22.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(if (checked) Accent else Color.Transparent)
                .border(1.5.dp, if (checked) Accent else Maysk.colors.muted, RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.Center
        ) { if (checked) Icon(Icons.Filled.Check, null, tint = Color.White, modifier = Modifier.size(16.dp)) }
        Spacer(Modifier.width(12.dp))
        Text(text, color = Maysk.colors.text, fontSize = 14.sp)
    }
}

@Composable
fun LabeledSlider(label: String, value: Float, display: String = "${(value * 100).toInt()}", range: ClosedFloatingPointRange<Float> = 0f..1f, onChange: (Float) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text(label, color = Maysk.colors.text, fontSize = 14.sp, modifier = Modifier.weight(1f))
            Text(display, color = Maysk.colors.muted, fontSize = 13.sp)
        }
        Slider(
            value = value, onValueChange = onChange, valueRange = range,
            colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Accent, inactiveTrackColor = Maysk.colors.surface2)
        )
    }
}

@Composable
fun AccentDot(modifier: Modifier = Modifier) {
    Box(modifier.size(6.dp).clip(CircleShape).background(Accent))
}

@Composable
fun Card(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Maysk.colors.surface)
            .padding(14.dp)
    ) { content() }
}

@Composable
fun AiBadge(text: String = "AI") {
    Box(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(AccentSoft)
            .border(1.dp, Accent, RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) { Text(text, color = Accent, fontWeight = FontWeight.Bold, fontSize = 12.sp) }
}
