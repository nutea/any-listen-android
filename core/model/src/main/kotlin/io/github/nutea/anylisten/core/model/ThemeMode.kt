package io.github.nutea.anylisten.core.model

enum class ThemeMode {
    LIGHT, DARK, SYSTEM;
    fun isDark(systemDark: Boolean): Boolean = when (this) { LIGHT -> false; DARK -> true; SYSTEM -> systemDark }
    companion object { fun decode(value: String?): ThemeMode = entries.firstOrNull { it.name == value } ?: SYSTEM }
}
