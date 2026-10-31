package dev.ryuya.homepodbridge.halprobe

import java.util.ArrayList
import java.util.HashMap
import java.util.HashSet
import kotlin.jvm.JvmRecord
import kotlin.jvm.Synchronized

/* Prototype cleanup policy. A backend is permanently bound to one module Binder instance. */
class OwnedHalResources(
    backend: OwnedHalResources.Backend,
    baseline: OwnedHalResources.Snapshot,
    tag: OwnedHalResources.Tag,
) {
    @JvmRecord
    data class Port(val id: Int, val type: Int, val address: String?) {

        override fun toString(): String = "Port[id=$id, type=$type, address=$address]"
    }

    @JvmRecord
    data class Config(val id: Int, val portId: Int, val handle: Int) {

        override fun toString(): String = "Config[id=$id, portId=$portId, handle=$handle]"
    }

    @JvmRecord
    data class Patch(val id: Int, val sources: MutableList<Int?>?, val sinks: MutableList<Int?>?) {

        override fun toString(): String = "Patch[id=$id, sources=$sources, sinks=$sinks]"
    }

    @JvmRecord
    data class Snapshot(
        val epoch: String?,
        val ports: MutableList<OwnedHalResources.Port?>?,
        val configs: MutableList<OwnedHalResources.Config?>?,
        val patches: MutableList<OwnedHalResources.Patch?>?,
    ) {

        override fun toString(): String =
            "Snapshot[epoch=$epoch, ports=$ports, configs=$configs, patches=$patches]"
    }

    @JvmRecord
    data class Tag(
        val address: String?,
        val outputMix: Int,
        val inputMix: Int,
        val outputHandle: Int,
        val inputHandle: Int,
    ) {

        override fun toString(): String =
            "Tag[address=$address, outputMix=$outputMix, inputMix=$inputMix, outputHandle=$outputHandle, inputHandle=$inputHandle]"
    }

    @JvmRecord
    data class Result(
        val complete: Boolean,
        val oldInstanceGone: Boolean,
        val errors: MutableList<String?>?,
    ) {

        override fun toString(): String =
            "Result[complete=$complete, oldInstanceGone=$oldInstanceGone, errors=$errors]"
    }

    interface Backend {
        @Throws(Exception::class) fun snapshot(): OwnedHalResources.Snapshot?

        fun streamsClosed(): Boolean

        @Throws(Exception::class) fun removePatch(id: Int)

        @Throws(Exception::class) fun removeConfig(id: Int)

        @Throws(Exception::class) fun disconnectPort(id: Int)
    }

    private val backend: OwnedHalResources.Backend
    private val baseline: OwnedHalResources.Snapshot
    private val tag: OwnedHalResources.Tag
    private val originalPorts: MutableSet<Int?>
    private val originalConfigs: MutableSet<Int?>
    private val originalPatches: MutableSet<Int?>

    init {
        require(
            !(tag.address == null ||
                !tag.address.startsWith("HomePodBridgeDirect:") ||
                tag.address.length < 40 ||
                tag.outputHandle === tag.inputHandle ||
                tag.outputHandle === 0 ||
                tag.inputHandle === 0)
        )
        this.backend = backend
        this.baseline = baseline
        this.tag = tag
        originalPorts = OwnedHalResources.ids(baseline.ports!!, { port -> port!!.id })
        originalConfigs = OwnedHalResources.ids(baseline.configs!!, { config -> config!!.id })
        originalPatches = OwnedHalResources.ids(baseline.patches!!, { patch -> patch!!.id })
        require(
            !(baseline.ports!!.stream().anyMatch({ p -> tag.address.equals(p!!.address) }) ||
                baseline.configs!!
                    .stream()
                    .anyMatch({ c ->
                        c!!.handle === tag.inputHandle || c!!.handle === tag.outputHandle
                    }))
        ) {
            "Marker already exists"
        }
    }

    fun interface Id<T> {
        fun get(value: T?): Int
    }

    private fun ports(state: OwnedHalResources.Snapshot): MutableSet<Int?> {
        OwnedHalResources.ids(state.ports!!, { port -> port!!.id })
        val own = HashSet<Int?>()
        for (p in state.ports!!) if (
            !originalPorts.contains(p!!.id) &&
                tag.address.equals(p!!.address) &&
                (p!!.type === 11 || p!!.type === 143)
        )
            own.add(p!!.id)
        return own
    }

    private fun configs(state: OwnedHalResources.Snapshot): MutableSet<Int?> {
        OwnedHalResources.ids(state.configs!!, { config -> config!!.id })
        val ownPorts = ports(state)
        val own = HashSet<Int?>()
        val matches = HashMap<Int?, Int?>()
        for (c in state.configs!!) if (!originalConfigs.contains(c!!.id) && mix(c!!))
            matches.merge(c.handle, 1) { a: Int, b: Int -> Integer.sum(a, b) }
        for (c in state.configs!!) if (
            !originalConfigs.contains(c!!.id) &&
                (ownPorts.contains(c!!.portId) || (mix(c!!) && matches.get(c.handle) == 1))
        )
            own.add(c!!.id)
        return own
    }

    private fun mix(c: OwnedHalResources.Config): Boolean {
        return (c.portId === tag.outputMix && c.handle === tag.outputHandle) ||
            (c.portId === tag.inputMix && c.handle === tag.inputHandle)
    }

    private fun patch(p: OwnedHalResources.Patch, ownConfigs: MutableSet<Int?>): Boolean {
        return !originalPatches.contains(p.id) &&
            !p.sources!!.isEmpty() &&
            !p.sinks!!.isEmpty() &&
            ownConfigs.containsAll(p.sources) &&
            ownConfigs.containsAll(p.sinks)
    }

    private fun gone(state: OwnedHalResources.Snapshot): Boolean {
        return !baseline.epoch.equals(state.epoch)
    }

    @Synchronized
    fun cleanup(): OwnedHalResources.Result? {
        val errors = ArrayList<String?>()
        try {
            var state = backend.snapshot()
            if (gone(state!!)) return OwnedHalResources.Result(true, true, errors)
            if (!backend.streamsClosed())
                return OwnedHalResources.Result(
                    false,
                    false,
                    java.util.List.of("Owned streams must close first"),
                )
            OwnedHalResources.ids(state.patches!!, { patch -> patch!!.id })
            val own = configs(state!!)
            for (candidate in state.patches!!) if (patch(candidate!!, own)) {
                val current = backend.snapshot()
                if (gone(current!!)) return OwnedHalResources.Result(true, true, errors)
                val currentConfigs = configs(current!!)
                if (
                    current.patches!!
                        .stream()
                        .anyMatch({ p -> p!!.equals(candidate) && patch(p!!, currentConfigs) })
                )
                    try {
                        backend.removePatch(candidate.id)
                    } catch (failure: Exception) {
                        errors.add("Patch reply unavailable")
                    }
            }
            state = backend.snapshot()
            if (gone(state!!)) return OwnedHalResources.Result(true, true, errors)
            for (candidate in state.configs!!) if (configs(state!!).contains(candidate!!.id)) {
                val current = backend.snapshot()
                if (gone(current!!)) return OwnedHalResources.Result(true, true, errors)
                if (
                    configs(current!!).contains(candidate!!.id) &&
                        current.configs!!.contains(candidate) &&
                        current.patches!!
                            .stream()
                            .noneMatch({ p -> OwnedHalResources.references(p!!, candidate!!.id) })
                )
                    try {
                        backend.removeConfig(candidate!!.id)
                    } catch (failure: Exception) {
                        errors.add("Config reply unavailable")
                    }
            }
            state = backend.snapshot()
            if (gone(state!!)) return OwnedHalResources.Result(true, true, errors)
            for (candidate in state.ports!!) if (ports(state!!).contains(candidate!!.id)) {
                val current = backend.snapshot()
                if (gone(current!!)) return OwnedHalResources.Result(true, true, errors)
                if (
                    ports(current!!).contains(candidate!!.id) &&
                        current.ports!!.contains(candidate) &&
                        current.configs!!.stream().noneMatch({ c -> c!!.portId === candidate!!.id })
                )
                    try {
                        backend.disconnectPort(candidate!!.id)
                    } catch (failure: Exception) {
                        errors.add("Port reply unavailable")
                    }
            }
            state = backend.snapshot()
            if (gone(state!!)) return OwnedHalResources.Result(true, true, errors)
            val ambiguous =
                state.configs!!
                    .stream()
                    .anyMatch({ c -> !originalConfigs.contains(c!!.id) && mix(c!!) })
            return OwnedHalResources.Result(
                ports(state!!).isEmpty() && configs(state!!).isEmpty() && !ambiguous,
                false,
                errors,
            )
        } catch (failure: Exception) {
            errors.add("Cleanup state unavailable: " + failure.javaClass.getSimpleName())
            return OwnedHalResources.Result(false, false, errors)
        }
    }

    companion object {
        @kotlin.jvm.JvmStatic
        private fun <T> ids(
            values: MutableList<T?>,
            id: OwnedHalResources.Id<T?>,
        ): MutableSet<Int?> {
            val result = HashSet<Int?>()
            for (value in values) require(!(id.get(value) <= 0 || !result.add(id.get(value)))) {
                "Invalid resource IDs"
            }
            return result
        }

        @kotlin.jvm.JvmStatic
        private fun references(p: OwnedHalResources.Patch, config: Int): Boolean {
            return p.sources!!.contains(config) || p.sinks!!.contains(config)
        }
    }
}
