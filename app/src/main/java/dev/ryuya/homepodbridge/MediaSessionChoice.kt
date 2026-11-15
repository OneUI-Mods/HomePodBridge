package dev.ryuya.homepodbridge

/** Chooses only an eligible source; the Bridge volume session must never become a song. */
internal data class MediaSessionChoice(
    val packageName: String,
    val state: Int,
    val voice: Boolean = false,
) {
    companion object {
        fun choose(
            sessions: List<MediaSessionChoice>,
            sourcePackage: String?,
            whole: Boolean,
        ): Int? =
            sessions.indices
                .filter { i ->
                    val s = sessions[i]
                    s.packageName != RootProtocol.PACKAGE &&
                        !s.voice &&
                        (whole || s.packageName == sourcePackage) &&
                        (s.state in setOf(3, 6, 8) || (!whole && s.state == 2))
                }
                .maxWithOrNull(
                    compareBy<Int>(
                        {
                            when (sessions[it].state) {
                                3 -> 3
                                6,
                                8 -> 2
                                else -> 1
                            }
                        },
                        { if (sessions[it].packageName == sourcePackage) 1 else 0 },
                        { -it },
                    )
                )
    }
}
