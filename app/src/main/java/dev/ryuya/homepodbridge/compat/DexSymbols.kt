package dev.ryuya.homepodbridge.compat

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import java.io.File
import java.lang.reflect.Field
import java.lang.reflect.Member
import java.lang.reflect.Method
import java.util.WeakHashMap

/** Reflection uses cached, APK-scoped proof. No DexKit bridge or Binder call in a hook callback. */
object DexSymbols {
    private data class Group(
        val paths: List<String>,
        val bindings: Map<String, String?>,
        val ambiguous: Boolean = false,
    ) {
        val classes =
            bindings.entries
                .filter { it.key.startsWith("C:") && it.value != null }
                .associate { it.value!! to it.key.removePrefix("C:") }
        val invocations =
            bindings.entries
                .filter { it.key.startsWith("M:") }
                .groupBy { entry ->
                    val key = entry.key
                    val params = key.substringAfter('(').removeSuffix(")")
                    key.substringBefore('(') +
                        "#" +
                        if (params.isEmpty()) 0 else params.count { it == ',' } + 1
                }
                .mapValues { (_, entries) -> entries.map { it.value }.distinct() }
        val members =
            buildMap<String, String> {
                for ((key, value) in bindings) {
                    if (value == null || (!key.startsWith("M:") && !key.startsWith("F:"))) continue
                    val owner = key.substring(2).substringBefore('#')
                    val actualOwner = bindings["C:$owner"] ?: owner
                    val legacy = key.substringAfter('#').substringBefore('(')
                    val suffix = if (key.startsWith("M:")) "(" + key.substringAfter('(') else ""
                    val actualKey = key.take(2) + actualOwner + "#" + value + suffix
                    val old = get(actualKey)
                    put(actualKey, if (old != null && old != legacy) "!ambiguous" else legacy)
                }
            }
    }

    private data class Snapshot(val epoch: Long, val groups: List<Group>)

    @Volatile private var snapshot = Snapshot(0, emptyList())
    @Volatile private var configured = false
    @Volatile private var prepared = false
    @Volatile private var reviewedHost = false
    private val waiting = mutableListOf<() -> Unit>()

    /** Unknown host versions wait for proof without blocking the application's main thread. */
    fun whenPrepared(context: Context, action: () -> Unit) {
        configure(context)
        if (context.packageName !in DexKitConfig.HOSTS) {
            action()
            return
        }
        synchronized(waiting) {
            if (!prepared && !reviewedHost) {
                waiting += action
                return
            }
        }
        action()
    }

    fun canInstallNow(context: Context): Boolean {
        configure(context)
        return prepared || reviewedHost
    }

    /**
     * Used to lift an exact application-version gate only if its complete recorded contract holds.
     */
    fun provenTarget(loader: ClassLoader?): Boolean {
        if (!prepared) return false
        val data = group(loader)?.bindings ?: return false
        return data.isNotEmpty() && data.values.all { it != null }
    }

    private val owners = WeakHashMap<ClassLoader, Pair<Long, Group?>>()

    fun configure(context: Context) {
        if (
            configured ||
                (context.packageName != DexKitConfig.MODULE_ID &&
                    context.packageName !in DexKitConfig.HOSTS)
        )
            return
        configured = true
        DexRuntimeCache.configure(context)
        // Known reviewed versions can retain their original guarded startup while preparing.
        // An unknown version is blocked until its semantic result is available.
        val initial = mutableListOf<Group>()
        for ((pkg, versions) in DexKitConfig.KNOWN_VERSIONS) {
            try {
                val info = context.packageManager.getApplicationInfo(pkg, 0)
                val version = context.packageManager.getPackageInfo(pkg, 0).longVersionCode
                if (pkg == context.packageName && version in versions) reviewedHost = true
                val bindings =
                    if (version in versions) emptyMap()
                    else DexKitConfig.CLASSES_BY_TARGET[pkg].orEmpty().associate { "C:$it" to null }
                initial += Group(listOf(info.sourceDir) + info.splitSourceDirs.orEmpty(), bindings)
            } catch (_: Exception) {
                /* Optional package not installed. */
            }
        }
        snapshot = Snapshot(snapshot.epoch + 1, initial)
        val uri = Uri.parse("content://${DexKitConfig.MODULE_ID}.dexkit/ready")
        fun read(): Boolean =
            try {
                val result =
                    context.contentResolver.call(uri, "bindings", context.packageName, null)
                if (result?.getBoolean("ready") != true) false
                else {
                    val bytes = result.getByteArray("snapshot") ?: return false
                    val raw = GenerationStore.decode(bytes).bindings
                    val packages = raw.keys.map { it.substringBefore('|') }.distinct()
                    val groups = packages.map { pkg ->
                        val values =
                            raw.filterKeys { it.startsWith("$pkg|") }
                                .mapKeys { it.key.substringAfter('|') }
                        val paths =
                            (0 until (values["paths.count"]?.toInt() ?: 0)).map {
                                checkNotNull(values["paths.$it"])
                            }
                        Group(
                            paths,
                            values.filterKeys {
                                it.startsWith("C:") || it.startsWith("M:") || it.startsWith("F:")
                            },
                        )
                    }
                    snapshot = Snapshot(snapshot.epoch + 1, groups)
                    val actions =
                        synchronized(waiting) {
                            prepared = true
                            waiting.toList().also { waiting.clear() }
                        }
                    val main = Handler(Looper.getMainLooper())
                    actions.forEach { main.post(it) }
                    true
                }
            } catch (_: Exception) {
                false
            }
        if (!read()) {
            val observer =
                object : ContentObserver(Handler(Looper.getMainLooper())) {
                    override fun onChange(selfChange: Boolean) {
                        if (read()) context.contentResolver.unregisterContentObserver(this)
                    }
                }
            context.contentResolver.registerContentObserver(uri, false, observer)
            if (read()) context.contentResolver.unregisterContentObserver(observer)
        }
    }

    private fun group(loader: ClassLoader?): Group? {
        if (loader == null) return null
        val current = snapshot
        synchronized(owners) {
            owners[loader]
                ?.takeIf { it.first == current.epoch }
                ?.let {
                    check(it.second?.ambiguous != true) { "Ambiguous APK classloader" }
                    return it.second
                }
        }
        val text = loader.toString()
        val matches = current.groups.filter { it.paths.any { path -> text.contains(path) } }
        // Never use a similarly named class from another plugin or an ambiguous loader.
        val found =
            if (matches.size > 1) Group(emptyList(), emptyMap(), true) else matches.singleOrNull()
        synchronized(owners) { owners[loader] = current.epoch to found }
        check(found?.ambiguous != true) { "Ambiguous APK classloader" }
        return found
    }

    fun findClass(name: String?, initialize: Boolean, loader: ClassLoader?): Class<*> {
        if (name == null) throw NullPointerException("name")
        if (name.startsWith("[")) {
            var dimensions = 0
            while (name.getOrNull(dimensions) == '[') dimensions++
            if (name.getOrNull(dimensions) == 'L' && name.endsWith(';')) {
                val component =
                    findClass(name.substring(dimensions + 1, name.length - 1), false, loader)
                return java.lang.reflect.Array.newInstance(component, *IntArray(dimensions))
                    .javaClass
            }
        }
        val existing =
            try {
                Class.forName(name, false, loader)
            } catch (_: ClassNotFoundException) {
                null
            }
        val requested = group(loader)
        val source =
            if (requested?.bindings?.containsKey("C:$name") == true) requested
            else existing?.let { group(it.classLoader) } ?: requested
        if (source != null) {
            val key = "C:$name"
            if (source.bindings.containsKey(key)) {
                val actual =
                    source.bindings[key]
                        ?: throw ClassNotFoundException("Unproven updated class: $name")
                return Class.forName(actual, initialize, loader)
            }
        }
        if (existing != null) return if (initialize) Class.forName(name, true, loader) else existing
        val candidates =
            snapshot.groups
                .mapNotNull { candidate ->
                    val actual = candidate.bindings["C:$name"] ?: return@mapNotNull null
                    val type =
                        try {
                            Class.forName(actual, false, loader)
                        } catch (_: ClassNotFoundException) {
                            return@mapNotNull null
                        }
                    if (group(type.classLoader) !== candidate) null else type
                }
                .distinct()
        val type =
            candidates.singleOrNull()
                ?: throw ClassNotFoundException("Missing or ambiguous updated class: $name")
        return if (initialize) Class.forName(type.name, true, loader) else type
    }

    fun findClass(name: String?): Class<*> =
        findClass(name, true, DexSymbols::class.java.classLoader)

    fun findClass(name: String?, loader: ClassLoader?): Class<*> = findClass(name, false, loader)

    fun logicalName(type: Class<*>): String {
        if (!type.isArray) return group(type.classLoader)?.classes?.get(type.name) ?: type.name
        var component = type
        var dimensions = 0
        while (component.isArray) {
            dimensions++
            component = component.componentType!!
        }
        return if (component.isPrimitive) type.name
        else "[".repeat(dimensions) + "L" + logicalName(component) + ";"
    }

    private fun symbolType(type: Class<*>): String =
        if (type.isArray) symbolType(type.componentType!!) + "[]" else logicalName(type)

    private fun methodKey(owner: Class<*>, name: String, params: Array<out Class<*>?>): String =
        "M:${logicalName(owner)}#$name(${params.joinToString(",") { symbolType(it ?: throw NullPointerException("parameter type")) }})"

    private fun methodName(owner: Class<*>, name: String, params: Array<out Class<*>?>): String {
        val data = group(owner.classLoader)?.bindings ?: return name
        val key = methodKey(owner, name, params)
        return if (data.containsKey(key))
            data[key] ?: throw NoSuchMethodException("Unproven updated method: $key")
        else name
    }

    fun declaredMethod(owner: Class<*>, name: String?, vararg params: Class<*>?): Method =
        if (name == null) throw NullPointerException("name")
        else if (params.any { it == null }) owner.getDeclaredMethod(name, *params)
        else owner.getDeclaredMethod(methodName(owner, name, params), *params)

    fun publicMethod(owner: Class<*>, name: String?, vararg params: Class<*>?): Method {
        if (name == null) throw NullPointerException("name")
        if (params.any { it == null }) return owner.getMethod(name, *params)
        val visited = mutableSetOf<Class<*>>()
        val pending = java.util.ArrayDeque<Class<*>>()
        pending.add(owner)
        while (pending.isNotEmpty()) {
            val type = pending.removeFirst()
            if (!visited.add(type)) continue
            val actual = methodName(type, name, params)
            if (actual != name) return owner.getMethod(actual, *params)
            type.superclass?.let(pending::add)
            type.interfaces.forEach(pending::add)
        }
        return owner.getMethod(name, *params)
    }

    private fun fieldName(owner: Class<*>, name: String): String {
        val data = group(owner.classLoader)?.bindings ?: return name
        val key = "F:${logicalName(owner)}#$name"
        return if (data.containsKey(key))
            data[key] ?: throw NoSuchFieldException("Unproven updated field: $key")
        else name
    }

    fun declaredField(owner: Class<*>, name: String?): Field =
        if (name == null) throw NullPointerException("name")
        else owner.getDeclaredField(fieldName(owner, name))

    fun publicField(owner: Class<*>, name: String?): Field {
        if (name == null) throw NullPointerException("name")
        val visited = mutableSetOf<Class<*>>()
        fun translated(type: Class<*>): String? {
            if (!visited.add(type)) return null
            val actual = fieldName(type, name)
            if (actual != name) return actual
            for (contract in type.interfaces) translated(contract)?.let {
                return it
            }
            return type.superclass?.let(::translated)
        }
        translated(owner)?.let {
            return owner.getField(it)
        }
        return owner.getField(name)
    }

    /** Retain the caller's original overload/boxing resolver after translating a proved name. */
    fun invocationName(owner: Class<*>, name: String, parameterCount: Int): String {
        var current: Class<*>? = owner
        while (current != null) {
            val group = group(current.classLoader)
            val names = group?.invocations?.get("M:${logicalName(current)}#$name#$parameterCount")
            if (names != null) {
                check(names.size == 1 && names.single() != null) {
                    "Unproven or ambiguous invocation"
                }
                return names.single()!!
            }
            current = current.superclass
        }
        return name
    }

    fun memberName(member: Member): String {
        val data = group(member.declaringClass.classLoader) ?: return member.name
        val prefix = if (member is Method) "M:" else "F:"
        val suffix =
            if (member is Method) "(${member.parameterTypes.joinToString(",") { symbolType(it) }})"
            else ""
        val legacy = data.members[prefix + member.declaringClass.name + "#" + member.name + suffix]
        check(legacy != "!ambiguous") { "Ambiguous updated member" }
        if (legacy != null) return legacy
        val key = prefix + logicalName(member.declaringClass) + "#" + member.name + suffix
        check(!data.bindings.containsKey(key) || data.bindings[key] != null) {
            "Unproven updated member"
        }
        return member.name
    }
}

fun Class<*>.compatibleDeclaredMethod(name: String?, vararg params: Class<*>?): Method =
    DexSymbols.declaredMethod(this, name, *params)

fun Class<*>.compatibleMethod(name: String?, vararg params: Class<*>?): Method =
    DexSymbols.publicMethod(this, name, *params)

fun Class<*>.compatibleDeclaredField(name: String?): Field = DexSymbols.declaredField(this, name)

fun Class<*>.compatibleField(name: String?): Field = DexSymbols.publicField(this, name)

fun ClassLoader.compatibleClass(name: String?): Class<*> = DexSymbols.findClass(name, false, this)

fun Class<*>.compatibleName(): String = DexSymbols.logicalName(this)

fun File.compatibleName(): String = name

fun java.lang.Thread.compatibleName(): String = name

fun java.lang.Package.compatibleName(): String = name

fun Member.compatibleName(): String = DexSymbols.memberName(this)
