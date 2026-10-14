package com.hiru.instanthotspot.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hiru.instanthotspot.R

val Sky = Color(0xFF79ABE6)
val Ink = Color(0xFF202D40)
val Muted = Color(0xFF778699)
val Pale = Color(0xFFEAF3FF)
val Line = Color(0xFFE8EFF7)

@Composable fun HiruTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF4B84C6), onPrimary = Color.White,
        primaryContainer = Pale, background = Color(0xFFF7FAFE), surface = Color.White,
        onSurface = Ink, onBackground = Ink, outlineVariant = Line), content = content)
}
@Composable fun Lucide(@DrawableRes icon: Int, description: String? = null, modifier: Modifier = Modifier.size(24.dp), tint: Color = Ink) {
    Icon(painterResource(icon), contentDescription = description, modifier = modifier, tint = tint)
}
@Composable fun CloudBackground(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFFF9FBFE), Color(0xFFEDF5FF))))) {
        Box(Modifier.align(Alignment.TopEnd).offset(100.dp, (-80).dp).size(420.dp)
            .background(Brush.radialGradient(listOf(Color(0xFFDCEBFA).copy(alpha = .5f), Color.Transparent)), CircleShape))
        Box(Modifier.align(Alignment.BottomStart).offset((-160).dp, 140.dp).size(500.dp)
            .background(Brush.radialGradient(listOf(Color(0xFFD9EBFC).copy(alpha = .5f), Color.Transparent)), CircleShape))
        content()
    }
}
@Composable fun SoftCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.shadow(12.dp, RoundedCornerShape(26.dp), ambientColor = Color(0xFFB7CEE7), spotColor = Color(0xFFB7CEE7))
        .clip(RoundedCornerShape(26.dp)).background(Color.White.copy(alpha = .88f))
        .border(1.dp, Color.White, RoundedCornerShape(26.dp)).padding(22.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp), content = content)
}
@Composable fun SkyButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, @DrawableRes icon: Int? = null) {
    Button(onClick, modifier.heightIn(min = 54.dp), enabled = enabled, shape = RoundedCornerShape(20.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent, disabledContainerColor = Color(0xFFE4EBF3)),
        contentPadding = PaddingValues(0.dp), elevation = ButtonDefaults.buttonElevation(0.dp)) {
        Row(Modifier.fillMaxWidth().background(if (enabled) Brush.horizontalGradient(listOf(Color(0xFF99C7F8), Color(0xFF79ADEB)))
            else Brush.horizontalGradient(listOf(Color(0xFFE4EBF3), Color(0xFFE4EBF3)))).padding(horizontal = 20.dp, vertical = 17.dp),
            horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Text(text, color = if (enabled) Color.White else Muted, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            if (icon != null) { Spacer(Modifier.width(10.dp)); Lucide(icon, modifier = Modifier.size(20.dp), tint = Color.White) }
        }
    }
}
@Composable fun Halo(@DrawableRes icon: Int, modifier: Modifier = Modifier, large: Boolean = false) {
    val size = if (large) 184.dp else 112.dp
    Box(modifier.size(size).background(Color(0xFFEAF3FE).copy(alpha = .6f), CircleShape), contentAlignment = Alignment.Center) {
        Box(Modifier.size(size * .79f).background(Color(0xFFDFEDFD).copy(alpha = .7f), CircleShape), contentAlignment = Alignment.Center) {
            Box(Modifier.size(size * .59f).background(Brush.linearGradient(listOf(Color(0xFFCEE4FF), Color(0xFFE8F3FF))), CircleShape), contentAlignment = Alignment.Center) {
                Lucide(icon, modifier = Modifier.size(if (large) 68.dp else 42.dp), tint = Sky)
            }
        }
    }
}
@Composable fun PageTitle(title: String, subtitle: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, fontSize = 27.sp, fontWeight = FontWeight.Bold, color = Ink)
        subtitle?.let { Text(it, color = Muted, fontSize = 14.sp, lineHeight = 22.sp) }
    }
}
@Composable fun DeviceRow(name: String, subtitle: String, @DrawableRes icon: Int = R.drawable.ic_lucide_smartphone,
    onClick: (() -> Unit)? = null, trailing: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
        .padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(50.dp).background(Pale, RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
            Lucide(icon, modifier = Modifier.size(27.dp), tint = Color(0xFF57789D))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Ink)
            Text(subtitle, fontSize = 12.sp, color = Muted)
        }
        if (trailing != null) trailing() else if (onClick != null) Lucide(R.drawable.ic_lucide_chevron_right, tint = Muted)
    }
}
@Composable fun QuietNote(text: String, modifier: Modifier = Modifier, @DrawableRes icon: Int = R.drawable.ic_lucide_lock_keyhole) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Lucide(icon, modifier = Modifier.size(18.dp), tint = Muted)
        Text(text, color = Muted, fontSize = 12.sp, lineHeight = 19.sp)
    }
}
