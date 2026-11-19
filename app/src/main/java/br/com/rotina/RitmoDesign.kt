package br.com.rotina

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color

/** Shared values keep the visual rhythm consistent across list, drawer, editor and dialogs. */
internal object RitmoTokens {
    val spaceXs: Dp = 4.dp
    val spaceSm: Dp = 8.dp
    val spaceMd: Dp = 12.dp
    val spaceLg: Dp = 16.dp
    val spaceXl: Dp = 24.dp
    val cornerCard: Dp = 18.dp
    val cornerControl: Dp = 14.dp
    val cornerPill: Dp = 24.dp
    val iconSize: Dp = 20.dp
    val minTouchTarget: Dp = 48.dp
}

/** Palette order is stable because list colors are persisted by index in the Room database. */
internal object RitmoPalettes {
    val listArgb = listOf(
        0xFF287267.toInt(), 0xFFD47752.toInt(), 0xFF6B68A8.toInt(),
        0xFFC65461.toInt(), 0xFF4B78A2.toInt(), 0xFF9B7A35.toInt(),
        0xFF9AB8A2.toInt(), 0xFFB7A7D6.toInt(), 0xFFE9B39B.toInt(),
        0xFFE0A7B2.toInt(), 0xFF9FC4DF.toInt(), 0xFFE9D28D.toInt()
    )
    val lists = listArgb.map { Color(it) }
    val tasks = listOf(0xFFDCEBDD.toInt(), 0xFFE9E0F4.toInt(), 0xFFF7E1D5.toInt(), 0xFFF2DDE2.toInt(), 0xFFDCEBF5.toInt(), 0xFFF5EBCB.toInt())
    val app = listOf(Color(0xFF286B5E), Color(0xFF725A96), Color(0xFFAD674E), Color(0xFF4B7095), Color(0xFF9B6A78), Color(0xFF788044))
}

internal val RitmoTypography = Typography().copy(
    headlineSmall = TextStyle(fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 23.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium)
)

@Composable
internal fun RitmoContentColor(argb: Int) = androidx.compose.ui.graphics.Color(contentArgb(argb))
