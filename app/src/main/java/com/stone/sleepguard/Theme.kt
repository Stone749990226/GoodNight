package com.stone.sleepguard

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*

internal val Ink = Color(0xFF0D171F)
internal val Panel = Color(0xFF16242E)
internal val Cream = Color(0xFFE8EFE9)
internal val Muted = Color(0xFF9FB2B9)
internal val Mint = Color(0xFFA8DCCB)
internal val Amber = Color(0xFFE7C38D)
@Composable fun NightTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(primary = Mint, onPrimary = Ink,
        primaryContainer = Color(0xFF2B4B46), onPrimaryContainer = Cream,
        secondary = Amber, onSecondary = Ink,
        secondaryContainer = Color(0xFF2B4B46), onSecondaryContainer = Cream,
        tertiary = Amber, onTertiary = Ink,
        tertiaryContainer = Color(0xFF3B3225), onTertiaryContainer = Amber,
        background = Ink, onBackground = Cream, surface = Panel, onSurface = Cream,
        surfaceVariant = Color(0xFF1C2D38), onSurfaceVariant = Muted, outline = Color(0xFF3B505C),
        error = Color(0xFFF2ACA5), onError = Ink)) {
        Surface(Modifier.fillMaxSize(), color = Ink, contentColor = Cream, content = content)
    }
}
@Composable internal fun SectionLabel(title: String, caption: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(title, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = Cream, modifier = Modifier.weight(1f))
        Text(caption, fontSize = 10.sp, letterSpacing = 1.5.sp, color = Muted)
    }
}
@Composable internal fun SoftCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(Panel)
        .border(1.dp, Color(0xFF263943), RoundedCornerShape(26.dp)).padding(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp), content = content)
}
@Composable internal fun Metric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, fontSize = 11.sp, color = Muted)
        BoxWithConstraints {
            val scale = androidx.compose.ui.platform.LocalDensity.current.fontScale
            val size = (maxWidth.value / (if (value.length > 5) 3.8f else 2.9f) / scale).coerceIn(12f, 21f)
            Text(value, fontSize = size.sp, color = Cream, fontWeight = FontWeight.Medium)
        }
    }
}
@Composable internal fun NavMark(index: Int, selected: Boolean) {
    Canvas(Modifier.size(22.dp)) {
        val c = if (selected) Mint else Muted; val w = size.width; val h = size.height
        when (index) {
            0 -> { drawCircle(c, w * .38f, androidx.compose.ui.geometry.Offset(w * .48f, h * .5f)); drawCircle(Ink, w * .32f, androidx.compose.ui.geometry.Offset(w * .67f, h * .3f)) }
            1 -> { for (i in 0..2) drawLine(c, androidx.compose.ui.geometry.Offset(w * .15f, h * (.2f + .3f * i)), androidx.compose.ui.geometry.Offset(w * .85f, h * (.2f + .3f * i)), 2.dp.toPx(), StrokeCap.Round) }
            else -> { drawCircle(c, w * .36f, style = Stroke(2.dp.toPx())); drawCircle(c, w * .1f) }
        }
    }
}
