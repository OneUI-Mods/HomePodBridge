package dev.ryuya.homepodbridge.compat

internal object DexKitConfig {
    const val SCHEMA = 2
    const val MODULE_ID = "dev.ryuya.homepodbridge"
    val MODULE_VERSION
        get() = dev.ryuya.homepodbridge.BuildConfig.VERSION_CODE

    const val PROFILE_HASH = "cae4ad6001675f5ebc246b5e9433d2448cbc713b9b89b123eb3338b2c8028639"
    val FEATURE_PREFIXES = setOf<String>()
    val TARGETS = setOf("com.android.systemui")
    val HOSTS = setOf("com.android.systemui")
    val CLASSES_BY_TARGET =
        mapOf(
            "com.android.systemui" to
                listOf(
                    "com.android.settingslib.volume.MediaSessions",
                    "com.android.settingslib.volume.MediaSessions\$SessionId",
                    "com.android.settingslib.volume.MediaSessions\$SessionId\$Media",
                    "com.android.settingslib.volume.MediaSessions\$VolumeInfo",
                    "com.android.systemui.volume.VolumeDialogControllerImpl",
                    "com.android.systemui.volume.VolumeDialogControllerImpl\$MediaSessionsCallbacks",
                    "com.android.systemui.volume.reducer.VolumePanelReducer",
                    "com.samsung.systemui.splugins.volume.VolumePanelAction",
                    "com.samsung.systemui.splugins.volume.VolumePanelAction\$ActionType",
                    "com.samsung.systemui.splugins.volume.VolumePanelState",
                )
        )
    val KNOWN_VERSIONS = mapOf("com.android.systemui" to setOf(36L, 37L))
    val PROFILE_PACKAGES =
        mapOf(36 to setOf("com.android.systemui"), 37 to setOf("com.android.systemui"))
}
