package ai.tushar.nanochat.ui.theme

import ai.tushar.nanochat.data.AgentColor
import androidx.compose.ui.graphics.Color

/** On-screen tone for each [AgentColor], tuned per theme. */
fun AgentColor.tone(dark: Boolean): Color = when (this) {
    AgentColor.Coral -> if (dark) Color(0xFFFF9C80) else Color(0xFFFF8A6B)
    AgentColor.Apricot -> if (dark) Color(0xFFF7B66C) else Color(0xFFF5A954)
    AgentColor.Sage -> if (dark) Color(0xFF9BC793) else Color(0xFF86B77E)
    AgentColor.Sky -> if (dark) Color(0xFF8BBAE3) else Color(0xFF6FA8DC)
    AgentColor.Rose -> if (dark) Color(0xFFF29BB4) else Color(0xFFEE85A2)
    AgentColor.Plum -> if (dark) Color(0xFFB9A0DA) else Color(0xFFA889CF)
}
