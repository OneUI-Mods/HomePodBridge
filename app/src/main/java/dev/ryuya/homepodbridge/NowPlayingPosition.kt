package dev.ryuya.homepodbridge

internal object NowPlayingPosition {
    fun estimate(
        position: Long,
        updatedAt: Long,
        now: Long,
        speed: Float,
        playing: Boolean,
        duration: Long,
    ): Long {
        if (position < 0) return -1
        val elapsed =
            if (playing && updatedAt > 0 && now >= updatedAt && speed.isFinite() && speed > 0)
                ((now - updatedAt).coerceAtMost(86_400_000L) * speed.toDouble())
                    .toLong()
                    .coerceIn(0, 86_400_000L)
            else 0L
        val result = position.coerceAtMost(86_400_000L) + elapsed
        return if (duration > 0) result.coerceIn(0, minOf(duration, 86_400_000L))
        else result.coerceIn(0, 86_400_000L)
    }
}
