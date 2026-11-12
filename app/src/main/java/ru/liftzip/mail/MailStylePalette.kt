package ru.liftzip.mail

import androidx.compose.ui.graphics.Color

/** Appearance owns colors only; density, typography and reader geometry stay shared. */
internal data class MailStylePalette(
    val accent: Color,
    val container: Color,
    val onContainer: Color,
    val secondaryContainer: Color,
    val onSecondaryContainer: Color,
    val surface: Color,
    val card: Color=Color(0xFFFFFEFC),
    val header: Color=surface,
)

internal val MailStyle.palette: MailStylePalette get()=when(this) {
    MailStyle.COZY->MailStylePalette(Color(0xFF784BCE),Color(0xFFEFE6FF),Color(0xFF50327C),Color(0xFFF0EAF7),Color(0xFF344661),Color(0xFFFFF8EF))
    MailStyle.BUSINESS->MailStylePalette(Color(0xFF135DBD),Color(0xFFDBE8FF),Color(0xFF163F81),Color(0xFFDFEAFE),Color(0xFF344661),Color(0xFFF0F5FF),header=Color(0xFFD7E7FC))
    MailStyle.FOREST->MailStylePalette(Color(0xFF28641F),Color(0xFFE0EED7),Color(0xFF173B13),Color(0xFFE9F1E2),Color(0xFF30492C),Color(0xFFF1F6ED),Color(0xFFFEFFFC))
    MailStyle.FROST->MailStylePalette(Color(0xFF0865CF),Color(0xFFDCEEFF),Color(0xFF123F70),Color(0xFFE5F1FC),Color(0xFF304962),Color(0xFFF0F7FF),Color(0xFFFCFDFF))
    MailStyle.EMERALD->MailStylePalette(Color(0xFF427A0B),Color(0xFFE7F2D4),Color(0xFF284705),Color(0xFFEDF3E2),Color(0xFF3A4C27),Color(0xFFF7F9EE),Color(0xFFFFFEFB))
    MailStyle.MEADOW->MailStylePalette(Color(0xFF477923),Color(0xFFE6F1D6),Color(0xFF294612),Color(0xFFEDF3E3),Color(0xFF3C4C30),Color(0xFFF8FAF2),Color(0xFFFFFEFC))
    MailStyle.STANDARD->MailStylePalette(Color(0xFF4C5D47),Color(0xFFE4EBD9),Color(0xFF293525),Color(0xFFE3E8DC),Color(0xFF293525),Color(0xFFF8F9F6))
}
