package com.retrsoft.bilisponsorskip

internal object CategoryColors {
    private val defaults = mapOf(
        "sponsor" to (0x00D400 to 0x007800),
        "selfpromo" to (0xFFFF00 to 0xBFBF35),
        "interaction" to (0xCC00FF to 0x6C0087),
        "intro" to (0x00FFFF to 0x008080),
        "outro" to (0x0202ED to 0x000070),
        "preview" to (0x008FD6 to 0x005799),
        "music_offtopic" to (0xFF9900 to 0xA6634A),
        "filler" to (0x7300FF to 0x2E0066),
        "padding" to (0x222222 to 0x111111),
    )

    fun normalize(value: String?): String? {
        val hex = value?.trim()?.removePrefix("#") ?: return null
        if (!hex.matches(Regex("[0-9a-fA-F]{6}"))) return null
        return "#${hex.uppercase(java.util.Locale.ROOT)}"
    }

    fun hex(color: Int): String = "#%06X".format(java.util.Locale.ROOT, color and 0xFFFFFF)

    fun foreground(color: Int): Int = if (
        contrastRatio(0xFFFFFFFF.toInt(), color) >= contrastRatio(0xFF000000.toInt(), color)
    ) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()

    fun resolve(category: String, custom: String? = null, preview: Boolean = false): Int {
        val rgb = normalize(custom)?.removePrefix("#")?.toInt(16)
        if (rgb == null) {
            val pair = defaults[category] ?: (0x999999 to 0x555555)
            return 0xFF000000.toInt() or if (preview) pair.second else pair.first
        }
        if (!preview) return 0xFF000000.toInt() or rgb
        fun channel(shift: Int) = (((rgb ushr shift) and 255) * 0.62f).toInt()
        return 0xFF000000.toInt() or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }
}

internal fun SettingsSnapshot.categoryColor(category: String, preview: Boolean = false): Int =
    CategoryColors.resolve(category, categoryColors[category], preview)
