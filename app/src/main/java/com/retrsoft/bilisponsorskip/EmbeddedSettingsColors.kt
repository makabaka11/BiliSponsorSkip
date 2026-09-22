package com.retrsoft.bilisponsorskip

internal data class EmbeddedSettingsColors(
    val surface: Int,
    val popupSurface: Int,
    val primaryText: Int,
    val secondaryText: Int,
    val accent: Int,
    val switchThumbUnchecked: Int,
    val switchTrackChecked: Int,
    val switchTrackUnchecked: Int,
)

internal fun embeddedSettingsColors(dark: Boolean): EmbeddedSettingsColors = if (dark) {
    EmbeddedSettingsColors(
        surface = 0xFF242426.toInt(),
        popupSurface = 0xFF2D2D30.toInt(),
        primaryText = 0xFFF2F2F7.toInt(),
        secondaryText = 0xFFC7C7CC.toInt(),
        accent = 0xFFFF8DB6.toInt(),
        switchThumbUnchecked = 0xFFD1D1D6.toInt(),
        switchTrackChecked = 0xFF7A3954.toInt(),
        switchTrackUnchecked = 0xFF5A5A5E.toInt(),
    )
} else {
    EmbeddedSettingsColors(
        surface = 0xFFFFFFFF.toInt(),
        popupSurface = 0xFFFAFAFC.toInt(),
        primaryText = 0xFF202023.toInt(),
        secondaryText = 0xFF58585D.toInt(),
        accent = 0xFFB3265E.toInt(),
        switchThumbUnchecked = 0xFF707075.toInt(),
        switchTrackChecked = 0xFFF0A9C3.toInt(),
        switchTrackUnchecked = 0xFFC8C8CC.toInt(),
    )
}

internal fun isDarkColor(color: Int): Boolean {
    val red = color ushr 16 and 0xFF
    val green = color ushr 8 and 0xFF
    val blue = color and 0xFF
    return 0.2126 * red + 0.7152 * green + 0.0722 * blue < 150.0
}

internal fun contrastRatio(foreground: Int, background: Int): Double {
    fun luminance(color: Int): Double {
        fun channel(value: Int): Double {
            val normalized = value / 255.0
            return if (normalized <= 0.04045) normalized / 12.92 else Math.pow((normalized + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channel(color ushr 16 and 0xFF) +
            0.7152 * channel(color ushr 8 and 0xFF) +
            0.0722 * channel(color and 0xFF)
    }

    val first = luminance(foreground)
    val second = luminance(background)
    val lighter = maxOf(first, second)
    val darker = minOf(first, second)
    return (lighter + 0.05) / (darker + 0.05)
}
