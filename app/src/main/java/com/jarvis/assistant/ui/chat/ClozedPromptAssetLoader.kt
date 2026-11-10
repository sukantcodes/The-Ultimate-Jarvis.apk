package com.jarvis.assistant.ui.chat

/** A behaviour-defining prompt asset must never silently degrade to an empty string. */
internal class ClozedAssetMissingException(
    val assetPath: String,
    cause: Throwable? = null
) : IllegalStateException("Required coding-agent asset is missing or empty: $assetPath", cause)

/** Small injectable seam so missing/empty asset behaviour can be unit-tested without Android classes. */
internal class ClozedPromptAssetLoader(private val read: (String) -> String) {

    fun required(path: String): String {
        val value = try {
            read(path)
        } catch (cause: Exception) {
            throw ClozedAssetMissingException(path, cause)
        }
        if (value.isBlank()) throw ClozedAssetMissingException(path)
        return value
    }

    /** Optional model addenda may be absent, but the caller must surface the returned null as a warning. */
    fun optional(path: String): String? = try {
        read(path).takeIf { it.isNotBlank() }
    } catch (_: Exception) {
        null
    }
}
