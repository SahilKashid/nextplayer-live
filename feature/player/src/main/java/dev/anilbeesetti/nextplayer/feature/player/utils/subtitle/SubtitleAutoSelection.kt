package dev.anilbeesetti.nextplayer.feature.player.utils.subtitle

/**
 * Pure helpers for choosing which subtitle track to enable on prepare / open.
 *
 * Hard rule: if any text/sidecar track exists, never leave selection on Disable.
 * Prefer [preferredLanguage] when a track language matches; otherwise pick the
 * first available text track. Disable is only correct when there are no tracks.
 *
 * Saved per-video index:
 * - `null` or `< 0` with tracks present → auto-select (stale / unset Disable)
 * - `>= 0` → honor that track when still in range
 */
object SubtitleAutoSelection {

    /**
     * ISO 639-1 ↔ 639-2/T pairs used for preferred-language matching.
     * Settings store ISO3 via LocalesHelper (`eng`); sidecar tags are often ISO2 (`en`).
     */
    private val LANGUAGE_ALIASES = mapOf(
        "en" to "eng",
        "eng" to "en",
        "es" to "spa",
        "spa" to "es",
        "fr" to "fre",
        "fre" to "fr",
        "fra" to "fr",
        "de" to "ger",
        "ger" to "de",
        "deu" to "de",
        "it" to "ita",
        "ita" to "it",
        "pt" to "por",
        "por" to "pt",
        "ru" to "rus",
        "rus" to "ru",
        "ja" to "jpn",
        "jpn" to "ja",
        "zh" to "chi",
        "chi" to "zh",
        "zho" to "zh",
        "ko" to "kor",
        "kor" to "ko",
        "hi" to "hin",
        "hin" to "hi",
        "ar" to "ara",
        "ara" to "ar",
    )

    /**
     * @return track index to enable, or `null` when there are no tracks (Disable is OK).
     * Never returns `-1` when [trackCount] > 0.
     */
    fun resolveTrackIndex(
        savedIndex: Int?,
        trackCount: Int,
        trackLanguages: List<String?>,
        preferredLanguage: String,
    ): Int? {
        if (trackCount <= 0) return null

        if (savedIndex != null && savedIndex >= 0 && savedIndex < trackCount) {
            return savedIndex
        }

        // null, out-of-range, or Disable (-1): always pick a real track when any exist.
        return autoSelectIndex(trackLanguages, preferredLanguage)
    }

    fun autoSelectIndex(
        trackLanguages: List<String?>,
        preferredLanguage: String,
    ): Int {
        if (trackLanguages.isEmpty()) return 0
        val preferred = preferredLanguage.trim().lowercase()
        if (preferred.isNotEmpty()) {
            val match = trackLanguages.indexOfFirst { languagesMatch(preferred, it) }
            if (match >= 0) return match
        }
        // Prefer first track that has any language tag (often a sidecar), else index 0.
        val firstTagged = trackLanguages.indexOfFirst { !it.isNullOrBlank() && it != "und" }
        return if (firstTagged >= 0) firstTagged else 0
    }

    fun languagesMatch(preferred: String, trackLanguage: String?): Boolean {
        if (trackLanguage.isNullOrBlank() || trackLanguage == "und") return false
        val a = preferred.trim().lowercase().substringBefore('-')
        val b = trackLanguage.trim().lowercase().substringBefore('-')
        if (a.isEmpty() || b.isEmpty()) return false
        if (a == b) return true
        if (LANGUAGE_ALIASES[a] == b) return true
        if (LANGUAGE_ALIASES[b] == a) return true
        return a.startsWith(b) || b.startsWith(a)
    }
}
