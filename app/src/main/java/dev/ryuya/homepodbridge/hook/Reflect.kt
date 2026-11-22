package dev.ryuya.homepodbridge.hook

import java.lang.reflect.Constructor
import java.lang.reflect.Executable
import java.lang.reflect.Field
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap

/** Cached reflection with the same overload ranking and exception boundary as the Java adapter. */
object Reflect {
    private data class FieldKey(val owner: Class<*>, val name: String)

    private data class MethodKey(
        val owner: Class<*>,
        val name: String,
        val args: List<Class<*>>,
        val statik: Boolean,
    )

    private val fields = ConcurrentHashMap<FieldKey, Field>()
    private val methods = ConcurrentHashMap<MethodKey, Executable>()

    fun findClass(name: String, loader: ClassLoader?): Class<*> =
        Class.forName(name, false, loader)

    fun findClassIfExists(name: String, loader: ClassLoader?): Class<*>? =
        try {
            findClass(name, loader)
        } catch (_: ClassNotFoundException) {
            null
        }

    @JvmStatic
    fun findAndHookMethod(
        owner: Class<*>,
        name: String,
        vararg parameters: Any?,
    ): HookCallback.Unhook {
        val callback =
            parameters.lastOrNull() as? HookCallback
                ?: throw IllegalArgumentException("Hook callback required")
        val types =
            Array(parameters.size - 1) { index ->
                val parameter = parameters[index]
                if (parameter is Class<*>) parameter
                else findClass(parameter as String, owner.classLoader)
            }
        var type: Class<*>? = owner
        while (type != null) {
            try {
                val method = type.getDeclaredMethod(name, *types)
                method.isAccessible = true
                return Hooks.hook(method, callback)
            } catch (_: NoSuchMethodException) {}
            type = type.superclass
        }
        throw IllegalStateException(
            "Method not found: ${owner.name}.$name${types.contentToString()}"
        )
    }

    private fun field(target: Any, name: String): Field {
        val owner = if (target is Class<*>) target else target.javaClass
        return fields.computeIfAbsent(FieldKey(owner, name)) {
            var type: Class<*>? = owner
            while (type != null) {
                try {
                    return@computeIfAbsent type.getDeclaredField(name).also {
                        it.isAccessible = true
                    }
                } catch (_: NoSuchFieldException) {}
                type = type.superclass
            }
            throw IllegalStateException("Field not found: ${owner.name}.$name")
        }
    }

    fun getStaticIntField(type: Class<*>, name: String): Int = field(type, name).getInt(null)

    @JvmStatic
    fun callMethod(receiver: Any?, name: String, vararg args: Any?): Any? {
        val target = requireNotNull(receiver)
        return invoke(best(target.javaClass, name, args, false), target, args)
    }

    @JvmStatic
    fun callStaticMethod(owner: Class<*>, name: String, vararg args: Any?): Any? =
        invoke(best(owner, name, args, true), null, args)

    @JvmStatic
    fun newInstance(owner: Class<*>, vararg args: Any?): Any =
        requireNotNull(invoke(best(owner, "<init>", args, false), null, args))

    private fun invoke(executable: Executable, receiver: Any?, args: Array<out Any?>): Any? =
        try {
            if (executable is Method) executable.invoke(receiver, *args)
            else (executable as Constructor<*>).newInstance(*args)
        } catch (error: InvocationTargetException) {
            throw IllegalStateException("Invocation failed: $executable", error.cause)
        } catch (error: ReflectiveOperationException) {
            throw IllegalStateException(error)
        }

    private fun best(
        owner: Class<*>,
        name: String,
        args: Array<out Any?>,
        statik: Boolean,
    ): Executable {
        val signature = args.map { it?.javaClass ?: Void::class.java }
        return methods.computeIfAbsent(
            MethodKey(owner, name, java.util.List.copyOf(signature), statik)
        ) {
            var selected: Executable? = null
            var lowest = Int.MAX_VALUE
            fun consider(candidate: Executable) {
                val score = score(candidate.parameterTypes, args)
                if (score < lowest) {
                    selected = candidate
                    lowest = score
                }
            }
            if (name == "<init>") owner.declaredConstructors.forEach(::consider)
            else {
                var type: Class<*>? = owner
                while (type != null) {
                    for (method in type.declaredMethods) {
                        if (
                            method.name == name &&
                                Modifier.isStatic(method.modifiers) == statik
                        )
                            consider(method)
                    }
                    type = type.superclass
                }
            }
            if (selected == null && name != "<init>") {
                for (method in owner.methods) {
                    if (
                        method.name == name &&
                            Modifier.isStatic(method.modifiers) == statik
                    )
                        consider(method)
                }
            }
            val result =
                selected
                    ?: throw NoSuchMethodError("No matching method: ${owner.name}.$name$signature")
            result.isAccessible = true
            result
        }
    }

    private fun boxed(type: Class<*>): Class<*> =
        when (type) {
            Boolean::class.javaPrimitiveType -> Boolean::class.javaObjectType
            Byte::class.javaPrimitiveType -> Byte::class.javaObjectType
            Short::class.javaPrimitiveType -> Short::class.javaObjectType
            Char::class.javaPrimitiveType -> Char::class.javaObjectType
            Int::class.javaPrimitiveType -> Int::class.javaObjectType
            Long::class.javaPrimitiveType -> Long::class.javaObjectType
            Float::class.javaPrimitiveType -> Float::class.javaObjectType
            Double::class.javaPrimitiveType -> Double::class.javaObjectType
            else -> if (type.isPrimitive) Void::class.java else type
        }

    private fun score(parameters: Array<Class<*>>, args: Array<out Any?>): Int {
        if (parameters.size != args.size) return Int.MAX_VALUE
        var score = 0
        for (index in args.indices) {
            val expected = parameters[index]
            val actual = args[index]
            if (actual == null) {
                if (expected.isPrimitive) return Int.MAX_VALUE
                score += 8
                continue
            }
            if (boxed(expected) == actual.javaClass) continue
            if (expected.isAssignableFrom(actual.javaClass)) {
                score += if (expected == Any::class.java) 6 else 2
                continue
            }
            val from = number(actual.javaClass)
            val to = number(boxed(expected))
            if (expected.isPrimitive && from >= 0 && to >= from) {
                score += to - from + 1
                continue
            }
            return Int.MAX_VALUE
        }
        return score
    }

    private fun number(type: Class<*>): Int =
        when (type) {
            Byte::class.javaObjectType -> 0
            Short::class.javaObjectType -> 1
            Int::class.javaObjectType -> 2
            Long::class.javaObjectType -> 3
            Float::class.javaObjectType -> 4
            Double::class.javaObjectType -> 5
            else -> -1
        }

}
