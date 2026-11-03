package dev.ryuya.homepodbridge.compat

import android.content.*
import android.database.Cursor
import android.net.Uri
import android.os.*
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import org.json.JSONObject

/** Fixed, module-owned target list. External callers cannot supply APK paths or queries. */
class DexKitProvider : ContentProvider() {
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "DexKitFirstStartup")
    }
    private val results = ConcurrentHashMap<String, Pair<String, GenerationStore.Result>>()
    private val pending = ConcurrentHashMap.newKeySet<String>()
    private val discoveryStarts = java.util.concurrent.atomic.AtomicInteger()

    override fun onCreate(): Boolean {
        refresh()
        return true
    }

    private fun catalog(): JSONObject =
        DexKitProvider::class
            .java
            .classLoader!!
            .getResourceAsStream("oneui-dexkit-profiles.json")!!
            .bufferedReader()
            .use { JSONObject(it.readText()) }

    private fun identity(pkg: String): Pair<String, List<File>>? =
        try {
            val c = context!!
            val info = c.packageManager.getApplicationInfo(pkg, 0)
            val version = c.packageManager.getPackageInfo(pkg, 0)
            val paths = (listOf(info.sourceDir) + info.splitSourceDirs.orEmpty()).map(::File)
            val moduleVersion = c.packageManager.getPackageInfo(c.packageName, 0).longVersionCode
            val key =
                listOf(
                        DexKitConfig.SCHEMA,
                        moduleVersion,
                        DexKitConfig.PROFILE_HASH,
                        Build.VERSION.SDK_INT,
                        Build.FINGERPRINT,
                        pkg,
                        version.longVersionCode,
                        version.lastUpdateTime,
                    )
                    .joinToString("|") +
                    paths.joinToString("|") { "${it.path}:${it.length()}:${it.lastModified()}" }
            key to paths
        } catch (_: Exception) {
            null
        }

    private fun refresh() {
        for (pkg in DexKitConfig.TARGETS) {
            if (pkg !in DexKitConfig.PROFILE_PACKAGES[Build.VERSION.SDK_INT].orEmpty()) continue
            val (generation, files) = identity(pkg) ?: continue
            if (results[pkg]?.first == generation || generation in pending) continue
            val store = GenerationStore(File(context!!.filesDir, "dexkit-bindings/$pkg"))
            val cached = store.readExisting(generation)
            if (cached != null) {
                results[pkg] = generation to cached
                continue
            }
            if (!pending.add(generation)) continue
            worker.execute {
                try {
                    val result =
                        store.readOrExplore(generation) {
                            discoveryStarts.incrementAndGet()
                            val profiles = catalog().getJSONArray("profiles")
                            val output = linkedMapOf<String, String?>()
                            var matchedProfile = false
                            for (i in 0 until profiles.length()) {
                                val profile = profiles.getJSONObject(i)
                                if (
                                    profile.getString("package") != pkg ||
                                        profile.getInt("sdk") != Build.VERSION.SDK_INT
                                )
                                    continue
                                // A profile describes the complete base APK. A previously unknown
                                // split is not guessed; its classes remain on their existing
                                // guards.
                                val resolved = DexKitEngine.explore(files.first(), profile)
                                for ((key, value) in resolved) {
                                    if (!output.containsKey(key)) output[key] = value
                                    else if (output[key] != value) output[key] = null
                                }
                                matchedProfile = true
                            }
                            check(matchedProfile) { "No reviewed Android profile" }
                            output
                        }
                    results[pkg] = generation to result
                    context!!
                        .contentResolver
                        .notifyChange(
                            Uri.parse("content://${context!!.packageName}.dexkit/ready"),
                            null,
                        )
                } finally {
                    pending.remove(generation)
                }
            }
        }
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val uid = Binder.getCallingUid()
        val own = uid == context!!.applicationInfo.uid
        if (method == "status") {
            require(own || uid == 2000 || uid == 0) { "Status caller unavailable" }
            refresh()
            return Bundle().apply {
                putString("state", if (pending.isEmpty()) "idle" else "preparing")
                putInt("completed", results.size)
                putInt("discoveryStarts", discoveryStarts.get())
                for ((pkg, value) in results) {
                    putString(pkg, value.second.state)
                    putInt("$pkg.unresolved", value.second.bindings.values.count { it == null })
                }
            }
        }
        require(method == "bindings" && arg != null && (own || arg in DexKitConfig.HOSTS)) {
            "Unsupported request"
        }
        require(own || context!!.packageManager.getPackagesForUid(uid).orEmpty().contains(arg)) {
            "Host UID mismatch"
        }
        refresh()
        val merged = linkedMapOf<String, String?>()
        var ready = true
        for (pkg in DexKitConfig.TARGETS) {
            if (pkg !in DexKitConfig.PROFILE_PACKAGES[Build.VERSION.SDK_INT].orEmpty()) continue
            val expected = identity(pkg) ?: continue
            val result = results[pkg]
            if (result == null || result.first != expected.first) {
                ready = false
                continue
            }
            val data =
                if (result.second.state == "ready") result.second.bindings
                else DexKitConfig.CLASSES_BY_TARGET[pkg].orEmpty().associate { "C:$it" to null }
            DexRuntimeCache.putList(merged, "$pkg|paths", expected.second.map { it.path })
            for ((key, value) in data) merged["$pkg|$key"] = value
        }
        return Bundle().apply {
            putBoolean("ready", ready)
            if (ready) {
                val bytes = GenerationStore.encode(GenerationStore.Result("ready", merged))
                check(bytes.size <= 512 * 1024) { "Binding bundle too large" }
                putByteArray("snapshot", bytes)
            }
        }
    }

    override fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        throw UnsupportedOperationException()

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int =
        throw UnsupportedOperationException()

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<String>?,
    ): Int = throw UnsupportedOperationException()
}
