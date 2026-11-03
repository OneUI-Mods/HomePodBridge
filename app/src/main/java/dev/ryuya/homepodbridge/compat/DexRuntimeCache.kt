package dev.ryuya.homepodbridge.compat

import android.content.Context
import android.os.Build
import java.io.File
import org.luckypray.dexkit.DexKitBridge

/** Persistent cache for feature-specific call graphs; no bridge on a hit, including failures. */
object DexRuntimeCache {
    @Volatile private var directory: File? = null

    fun configure(context: Context) {
        directory = File(context.filesDir, "dexkit-feature-results/${DexKitConfig.MODULE_ID}")
    }

    fun query(
        apk: String,
        rule: String,
        block: (DexKitBridge) -> Map<String, String?>,
    ): Map<String, String?> {
        val base = checkNotNull(directory) { "Discovery cache not configured" }
        val file = File(apk)
        val generation =
            listOf(
                    DexKitConfig.SCHEMA,
                    DexKitConfig.MODULE_VERSION,
                    DexKitConfig.PROFILE_HASH,
                    Build.VERSION.SDK_INT,
                    Build.FINGERPRINT,
                    file.path,
                    file.length(),
                    file.lastModified(),
                    rule,
                )
                .joinToString("|")
        val result =
            GenerationStore(base).readOrExplore(generation) {
                System.loadLibrary("dexkit")
                DexKitBridge.create(apk).use { bridge ->
                    bridge.setThreadNum(2)
                    block(bridge)
                }
            }
        check(result.state == "ready") { "Discovery unavailable for this version: ${result.state}" }
        return result.bindings
    }

    fun list(values: Map<String, String?>, key: String): List<String> =
        (0 until (values["$key.count"]?.toInt() ?: 0)).map { checkNotNull(values["$key.$it"]) }

    fun putList(values: MutableMap<String, String?>, key: String, list: List<String>) {
        values["$key.count"] = list.size.toString()
        list.forEachIndexed { index, value -> values["$key.$index"] = value }
    }
}
