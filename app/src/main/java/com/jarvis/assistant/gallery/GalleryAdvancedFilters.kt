package com.jarvis.assistant.gallery

/** Local-only filters applied to MediaStore metadata; no media contents are read. */
enum class GallerySizeFilter(val label: String, private val minBytes: Long, private val maxBytes: Long?) {
    ALL("All sizes", 0L, null),
    UNDER_1_MB("Under 1 MB", 0L, 1L * 1024L * 1024L),
    FROM_1_TO_10_MB("1–10 MB", 1L * 1024L * 1024L, 10L * 1024L * 1024L),
    FROM_10_TO_100_MB("10–100 MB", 10L * 1024L * 1024L, 100L * 1024L * 1024L),
    OVER_100_MB("Over 100 MB", 100L * 1024L * 1024L, null);

    fun matches(sizeBytes: Long): Boolean {
        val size = sizeBytes.coerceAtLeast(0L)
        return size >= minBytes && (maxBytes == null || size < maxBytes)
    }
}

/** Filters by the file extension, with MIME type as a fallback. */
enum class GalleryFormatFilter(val label: String, private val extensions: Set<String>) {
    ALL("All formats", emptySet()),
    JPEG("JPEG", setOf("jpg", "jpeg")),
    PNG("PNG", setOf("png")),
    WEBP("WebP", setOf("webp")),
    HEIC("HEIC / HEIF", setOf("heic", "heif")),
    GIF("GIF", setOf("gif")),
    MP4("MP4 video", setOf("mp4", "m4v")),
    WEBM("WebM video", setOf("webm")),
    THREE_GP("3GP video", setOf("3gp")),
    OTHER("Other formats", emptySet());

    fun matches(displayName: String, mimeType: String?): Boolean {
        if (this == ALL) return true
        val extension = displayName.substringAfterLast('.', "").lowercase()
        val mime = mimeType.orEmpty().lowercase()
        val knownExtensions = values().filter { it != ALL && it != OTHER }
            .flatMap { it.extensions }.toSet()
        if (this == OTHER) {
            return extension.isNotBlank() && extension !in knownExtensions &&
                !mime.startsWith("image/jpeg") && !mime.startsWith("image/png")
        }
        if (extension.isNotBlank()) return extension in extensions
        val expectedMime = when (this) {
            JPEG -> "image/jpeg"
            PNG -> "image/png"
            WEBP -> "image/webp"
            HEIC -> "image/heic"
            GIF -> "image/gif"
            MP4 -> "video/mp4"
            WEBM -> "video/webm"
            THREE_GP -> "video/3gpp"
            else -> return false
        }
        return mime == expectedMime || (this == HEIC && mime == "image/heif")
    }
}
