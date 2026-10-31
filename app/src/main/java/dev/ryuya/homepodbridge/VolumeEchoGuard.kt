package dev.ryuya.homepodbridge

/* A single stale panel write immediately following an explicit remote icon command. */
class VolumeEchoGuard {
    private var previous: Int = -1
    private var expires: Long = 0

    fun explicitChange(before: Int, after: Int, now: Long) {
        previous = if (before == after) -1 else VolumePolicy.apply(before)
        expires = now + 150
    }

    fun ignore(value: Int, now: Long): Boolean {
        if (previous < 0) return false
        val stale: Boolean = now <= expires && value == previous
        previous = -1 // A different/new write or expiry also consumes the guard.
        return stale
    }

    fun clear() {
        previous = -1
    }
}
