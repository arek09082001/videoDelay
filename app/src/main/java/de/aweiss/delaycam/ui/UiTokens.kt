package de.aweiss.delaycam.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Gemeinsame Gestaltungswerte (Plan §5): hoher Kontrast fürs Hallenlicht, große Ziele für die
 * Bedienung ohne Hinsehen. Touch-Ziele ≥ 64 dp, Schrift ≥ 16 sp — hier zentral, damit es in
 * allen Leisten wirklich eingehalten wird.
 */
object UiTokens {
    val Accent = Color(0xFF00E5A0)          // LIVE / aktiver Zustand
    val AccentDim = Color(0xFF00795A)
    val Warn = Color(0xFFFFB300)
    val Danger = Color(0xFFFF5252)
    val Panel = Color(0xE6101214)           // halbtransparente Leisten über dem Video
    val PanelSolid = Color(0xFF15181B)
    val OnPanel = Color(0xFFF2F4F6)
    val OnPanelDim = Color(0xFF9AA3AC)
    val Loop = Color(0xFF4FC3F7)

    val TouchTarget = 64.dp
    val ButtonGap = 8.dp
    val PanelPadding = 12.dp
    val TextSize = 16.sp
    val TextSizeSmall = 14.sp
    val BadgeTextSize = 20.sp
}

/** „1×“ / „½×“ / „¼×“ / „⅒×“ — die vier Stufen aus dem Plan, alles andere numerisch. */
fun speedLabel(permille: Int): String = when (permille) {
    1000 -> "1×"
    500 -> "½×"
    250 -> "¼×"
    100 -> "⅒×"
    else -> "${permille / 1000},${(permille % 1000) / 100}×"
}
