package sa.gheras.edutrack.ui.common

import sa.gheras.edutrack.BuildConfig

object PhotoUtils {

    /**
     * Resolves a scoped relative student photo URL against the base server domain.
     * Whitelists strictly relative paths starting with "/api/v1/students/" to prevent
     * leaking Bearer authentication tokens to external or unverified hosts.
     * Returns null for any blank, external (http/https), or non-whitelisted path.
     */
    fun resolveUrl(storageKeyOrUrl: String?): String? {
        if (storageKeyOrUrl.isNullOrBlank()) return null
        if (!storageKeyOrUrl.startsWith("/api/v1/students/")) {
            return null
        }
        val baseDomain = BuildConfig.BASE_URL.substringBefore("/api/v1")
        return "$baseDomain$storageKeyOrUrl"
    }
}
