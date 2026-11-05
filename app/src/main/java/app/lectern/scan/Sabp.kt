package app.lectern.scan

import java.nio.ByteBuffer

/**
 * Reads Smart AudioBook Player's `position.sabp.dat` (a Java-serialized BookData object)
 * so books carry over with their place, speed and finished state.
 *
 * The primitive fields are serialized in the class descriptor's order:
 * I mBookQueuePosition, I mBoostVolume, I mCurrentPlaybackTimeCache, I mFilePosition,
 * J mLastPlaybackTime, F mPlaybackSpeed, J mProcessedMp3Length, Z mShowId3Titles,
 * I mSkipSilence, I mTotalPlaybackTimeCache — then objects, incl. the BookState enum name.
 */
object Sabp {
    data class Position(
        val fileIndex: Int,
        /** Seconds into the file at [fileIndex]. */
        val filePosition: Double,
        val lastPlayed: Long,
        val speed: Float,
        val finished: Boolean,
    )

    fun parse(data: ByteArray): Position? = runCatching {
        val marker = indexOf(data, "xp".toByteArray()) ?: return null
        // Make sure this really is Smart AudioBook Player's BookData with the expected field layout.
        if (indexOf(data, "mBookQueuePosition".toByteArray()) == null || indexOf(data, "mFilePosition".toByteArray()) == null) return null
        val b = ByteBuffer.wrap(data, marker + 2, data.size - marker - 2)
        val queue = b.int
        b.int // boost
        b.int // current playback time cache
        val filePos = b.int
        val last = b.long
        val speed = b.float
        val finished = indexOf(data, "Finished".toByteArray()) != null
        Position(queue, filePos.toDouble(), last, speed.takeIf { it in 0.25f..4f } ?: 1f, finished)
    }.getOrNull()

    private fun indexOf(hay: ByteArray, needle: ByteArray): Int? {
        outer@ for (i in 0..hay.size - needle.size) {
            for (j in needle.indices) if (hay[i + j] != needle[j]) continue@outer
            return i
        }
        return null
    }
}
