package dev.ryuya.homepodbridge.compat

import java.io.File
import java.lang.reflect.Modifier
import java.security.MessageDigest
import org.json.JSONObject
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.result.ClassData
import org.luckypray.dexkit.result.MethodData

/** Semantic anchors plus shape and uniqueness; never select the first ambiguous result. */
object DexKitEngine {
    fun explore(apk: File, profile: JSONObject): Map<String, String?> {
        System.loadLibrary("dexkit")
        val exact =
            profile.getString("sha256") ==
                File(apk.path).inputStream().use { stream ->
                    val digest = MessageDigest.getInstance("SHA-256")
                    val bytes = ByteArray(65536)
                    while (true) {
                        val count = stream.read(bytes)
                        if (count < 0) break
                        digest.update(bytes, 0, count)
                    }
                    digest.digest().joinToString("") { "%02x".format(it) }
                }
        return DexKitBridge.create(apk.path).use { bridge ->
            bridge.setThreadNum(2)
            val output = linkedMapOf<String, String?>()
            val seeds = profile.getJSONArray("classes")
            val classes = linkedMapOf<String, ClassData?>()
            for (i in 0 until seeds.length()) {
                val seed = seeds.getJSONObject(i)
                val original = seed.getString("name")
                val anchors = seed.strings("anchors")
                val found =
                    if (exact) bridge.getClassData(original)
                    else if (anchors.size >= 2) {
                        bridge.findClass { matcher { usingStrings(anchors) } }.singleOrNull()
                    } else null
                classes[original] = found
                output["C:$original"] = found?.name
            }
            if (!exact) {
                // Descriptive, non-obfuscated classes can lack strings (especially interfaces).
                // Retain their exact name only with at least two independently matching named
                // method contracts. A name alone, or an obfuscated one, is never evidence.
                repeat(3) {
                    fun mappedType(type: String): String =
                        if (type.endsWith("[]")) mappedType(type.removeSuffix("[]")) + "[]"
                        else classes[type]?.name ?: type
                    for (i in 0 until seeds.length()) {
                        val seed = seeds.getJSONObject(i)
                        val name = seed.getString("name")
                        if (
                            classes[name] != null ||
                                !name.startsWith("com.") ||
                                !stableName(name.substringAfterLast('.').substringBefore('$'))
                        )
                            continue
                        val candidate = bridge.getClassData(name) ?: continue
                        val methods = seed.getJSONArray("methods")
                        val contracts =
                            (0 until methods.length()).count { index ->
                                val old = methods.getJSONObject(index)
                                val methodName = old.getString("name")
                                stableName(methodName) &&
                                    candidate.methods.count { actual ->
                                        actual.name == methodName &&
                                            actual.paramTypeNames ==
                                                old.strings("params").map(::mappedType) &&
                                            actual.returnTypeName ==
                                                mappedType(old.getString("returns")) &&
                                            Modifier.isStatic(actual.modifiers) ==
                                                old.getBoolean("static")
                                    } == 1
                            }
                        if (contracts >= 2) {
                            classes[name] = candidate
                            output["C:$name"] = candidate.name
                        }
                    }
                }
                // Inner state classes often have no strings. Resolve them only through an
                // already proven owner and an independently stable superclass relationship.
                repeat(3) {
                    for (i in 0 until seeds.length()) {
                        val seed = seeds.getJSONObject(i)
                        val name = seed.getString("name")
                        if (classes[name] != null) continue
                        val parentType = seed.optString("super")
                        if (!(parentType.startsWith("android.") || parentType.startsWith("java.")))
                            continue
                        val candidates = linkedMapOf<String, ClassData>()
                        for (j in 0 until seeds.length()) {
                            val ownerSeed = seeds.getJSONObject(j)
                            val owner = classes[ownerSeed.getString("name")] ?: continue
                            val fields = ownerSeed.getJSONArray("fields")
                            if (
                                (0 until fields.length()).none {
                                    fields.getJSONObject(it).getString("type") == name
                                }
                            )
                                continue
                            for (field in owner.fields) {
                                val type = bridge.getClassData(field.typeName) ?: continue
                                if (type.superClass?.name != parentType) continue
                                val required = seed.getJSONArray("fields")
                                val sdkTypes =
                                    (0 until required.length())
                                        .map { required.getJSONObject(it).getString("type") }
                                        .filter {
                                            it.startsWith("android.") || it.startsWith("java.")
                                        }
                                        .groupingBy { it }
                                        .eachCount()
                                val available =
                                    type.fields.map { it.typeName }.groupingBy { it }.eachCount()
                                if (
                                    sdkTypes.isNotEmpty() &&
                                        sdkTypes.all { (key, count) ->
                                            (available[key] ?: 0) >= count
                                        }
                                )
                                    candidates[type.name] = type
                            }
                        }
                        val found = candidates.values.singleOrNull() ?: continue
                        classes[name] = found
                        output["C:$name"] = found.name
                    }
                }
                val collisions =
                    classes.entries
                        .filter { it.value != null }
                        .groupBy { it.value!!.name }
                        .values
                        .filter { it.size > 1 }
                for (group in collisions) for (entry in group) {
                    classes[entry.key] = null
                    output["C:${entry.key}"] = null
                }
            }
            fun mapped(type: String): String =
                if (type.endsWith("[]")) mapped(type.removeSuffix("[]")) + "[]"
                else classes[type]?.name ?: type
            val allMatched = linkedMapOf<String, MethodData>()
            for (i in 0 until seeds.length()) {
                val seed = seeds.getJSONObject(i)
                val original = seed.getString("name")
                val found = classes[original]
                val methods = seed.getJSONArray("methods")
                val matched = allMatched
                for (j in 0 until methods.length()) {
                    val method = methods.getJSONObject(j)
                    val key = method.getString("key")
                    val params = method.strings("params").map(::mapped)
                    val returns = mapped(method.getString("returns"))
                    val oldName = method.getString("name")
                    val anchors = method.strings("anchors")
                    val invokes = method.strings("invokes")
                    val ops = method.getString("ops")
                    val candidates =
                        found
                            ?.methods
                            ?.filter { candidate ->
                                candidate.paramTypeNames == params &&
                                    candidate.returnTypeName == returns &&
                                    Modifier.isStatic(candidate.modifiers) ==
                                        method.getBoolean("static") &&
                                    when {
                                        exact -> candidate.name == oldName
                                        anchors.isNotEmpty() ->
                                            candidate.usingStrings.containsAll(anchors)
                                        stableName(oldName) -> candidate.name == oldName
                                        ops.isNotEmpty() && invokes.isNotEmpty() ->
                                            opcodeHash(candidate) == ops &&
                                                candidate.invokes
                                                    .map { it.descriptor }
                                                    .containsAll(invokes)
                                        else -> false
                                    }
                            }
                            .orEmpty()
                    val candidate = candidates.singleOrNull()
                    output[key] = candidate?.name
                    if (candidate != null) matched[key] = candidate
                }
            }
            for (i in 0 until seeds.length()) {
                val seed = seeds.getJSONObject(i)
                val found = classes[seed.getString("name")]
                val fields = seed.getJSONArray("fields")
                for (j in 0 until fields.length()) {
                    val field = fields.getJSONObject(j)
                    val key = field.getString("key")
                    val oldName = field.getString("name")
                    val type = mapped(field.getString("type"))
                    val readers =
                        field.strings("readers").mapNotNull { allMatched[it]?.descriptor }.toSet()
                    val writers =
                        field.strings("writers").mapNotNull { allMatched[it]?.descriptor }.toSet()
                    val candidates =
                        found
                            ?.fields
                            ?.filter { candidate ->
                                candidate.typeName == type &&
                                    Modifier.isStatic(candidate.modifiers) ==
                                        field.getBoolean("static") &&
                                    if (exact || stableName(oldName)) candidate.name == oldName
                                    else
                                        (readers.isNotEmpty() || writers.isNotEmpty()) &&
                                            candidate.readers
                                                .map { it.descriptor }
                                                .containsAll(readers) &&
                                            candidate.writers
                                                .map { it.descriptor }
                                                .containsAll(writers)
                            }
                            .orEmpty()
                    output[key] = candidates.singleOrNull()?.name
                }
            }
            output
        }
    }

    private fun stableName(name: String): Boolean =
        name.length >= 4 && name.any { it.isLowerCase() } && !name.matches(Regex("[a-zA-Z]\\d+"))

    fun opcodeHash(method: MethodData): String =
        if (method.opCodes.size < 8) ""
        else GenerationStore.digest(method.opCodes.joinToString(","))

    fun JSONObject.strings(key: String): List<String> =
        getJSONArray(key).let { array -> (0 until array.length()).map { array.getString(it) } }
}
