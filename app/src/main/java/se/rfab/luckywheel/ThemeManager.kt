package se.rfab.luckywheel

import androidx.compose.ui.graphics.Color

data class WheelTheme(
    val id: String,
    val name: String,
    val colors: List<Color>      // exactly 6 colors, one per option slot
)

object ThemeManager {

    val themes: List<WheelTheme> = listOf(
        WheelTheme(
            id = "standard",
            name = "Standard",
            colors = listOf(
                Color(0xFFFF6B6B),  // coral red
                Color(0xFF4ECDC4),  // teal
                Color(0xFF45B7D1),  // sky blue
                Color(0xFFFFA07A),  // light salmon
                Color(0xFF98D8C8),  // mint
                Color(0xFFF7DC6F)   // sunflower yellow
            )
        ),
        WheelTheme(
            id = "neon",
            name = "Neon",
            colors = listOf(
                Color(0xFFFF073A),  // neon crimson
                Color(0xFF39FF14),  // neon green
                Color(0xFF00FFFF),  // electric cyan
                Color(0xFFFF00FF),  // neon magenta
                Color(0xFFFFD700),  // neon gold
                Color(0xFF00BFFF)   // deep sky blue
            )
        ),
        WheelTheme(
            id = "grey",
            name = "Grey",
            colors = listOf(
                Color(0xFF78909C),  // blue-grey 400
                Color(0xFF546E7A),  // blue-grey 700
                Color(0xFF90A4AE),  // blue-grey 300
                Color(0xFF607D8B),  // blue-grey 500
                Color(0xFFB0BEC5),  // blue-grey 200
                Color(0xFF455A64)   // blue-grey 800
            )
        )
    )

    /** Returns the theme with [id], falling back to the first theme. */
    fun getById(id: String): WheelTheme = themes.find { it.id == id } ?: themes.first()
}
