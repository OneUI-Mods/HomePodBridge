package dev.ryuya.homepodbridge.compat

import android.content.Context
import org.luckypray.dexkit.DexKitBridge

/** Persistent cache for feature-specific call graphs; no bridge on a hit, including failures. */
object DexRuntimeCache {
    @Volatile private var hostContext: Context? = null

    fun configure(context: Context) {
        hostContext = context
    }

    fun query(
        apk: String,
        rule: String,
        block: (DexKitBridge) -> Map<String, String?>,
    ): Map<String, String?> {
        val context = checkNotNull(hostContext) { "Discovery cache not configured" }
        val uri = android.net.Uri.parse("content://${DexKitConfig.MODULE_ID}.dexkit/ready")
        val request =
            android.os.Bundle().apply {
                putString("apk", apk)
                putString("rule", rule)
            }
        val start =
            checkNotNull(
                context.contentResolver.call(uri, "feature_begin", context.packageName, request)
            )
        val token = start.getString("token")
        val result =
            if (token == null) {
                GenerationStore.decode(checkNotNull(start.getByteArray("snapshot")))
            } else {
                val explored =
                    try {
                        System.loadLibrary("dexkit")
                        DexKitBridge.create(apk).use { bridge ->
                            bridge.setThreadNum(2)
                            GenerationStore.Result("ready", block(bridge))
                        }
                    } catch (_: Exception) {
                        GenerationStore.Result("failed")
                    } catch (_: LinkageError) {
                        GenerationStore.Result("native-unavailable")
                    }
                val bytes = GenerationStore.encode(explored)
                check(bytes.size <= 512 * 1024) { "Feature result too large" }
                request.putString("token", token)
                request.putByteArray("snapshot", bytes)
                val saved =
                    context.contentResolver.call(
                        uri,
                        "feature_finish",
                        context.packageName,
                        request,
                    )
                check(saved?.getBoolean("saved") == true) { "Discovery claim changed" }
                explored
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
