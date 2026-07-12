package io.legado.app.service.mimo

import java.security.MessageDigest

data class MiMoSegment(
    val chapterIndex: Int,
    val paragraphIndex: Int,
    val text: String,
    val cacheKey: String,
    val mediaId: String,
    val bookIdentity: String = "",
    val leadingOffset: Int = 0
) {
    companion object {
        fun create(
            chapterIndex: Int,
            paragraphIndex: Int,
            text: String,
            config: MiMoTtsConfig,
            bookIdentity: String = "",
            leadingOffset: Int = 0
        ): MiMoSegment {
            val signature = listOf(
                MiMoTtsContract.MODEL,
                config.voice,
                config.style,
                text
            ).joinToString("\u0000")
            val key = MessageDigest.getInstance("SHA-256")
                .digest(signature.toByteArray())
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            return MiMoSegment(
                chapterIndex = chapterIndex,
                paragraphIndex = paragraphIndex,
                text = text,
                cacheKey = key,
                mediaId = "${chapterIndex}_${paragraphIndex}_$key",
                bookIdentity = bookIdentity,
                leadingOffset = leadingOffset
            )
        }
    }
}

object MiMoPrefetchPlan {
    fun currentChapterIndices(startIndex: Int, paragraphCount: Int): List<Int> {
        if (startIndex !in 0 until paragraphCount) return emptyList()
        return (startIndex until paragraphCount).toList()
    }

    fun nextChapterIndices(paragraphCount: Int): List<Int> =
        (0 until minOf(10, paragraphCount.coerceAtLeast(0))).toList()
}
