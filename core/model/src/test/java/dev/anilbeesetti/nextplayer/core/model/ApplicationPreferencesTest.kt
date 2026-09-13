package dev.anilbeesetti.nextplayer.core.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ApplicationPreferencesTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun appearanceDefaultsAreHighContrastDark() {
        val preferences = ApplicationPreferences()

        assertEquals(ThemeConfig.ON, preferences.themeConfig)
        assertTrue(preferences.useHighContrastDarkTheme)
    }

    @Test
    fun missingKeysUseAppearanceDefaults() {
        val decoded = json.decodeFromString<ApplicationPreferences>("{}")

        assertEquals(ThemeConfig.ON, decoded.themeConfig)
        assertTrue(decoded.useHighContrastDarkTheme)
    }
}
