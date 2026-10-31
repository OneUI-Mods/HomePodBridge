package dev.ryuya.homepodbridge.halprobe

import java.io.IOException
import java.util.ArrayList
import java.util.List
import kotlin.jvm.JvmField
import kotlin.jvm.JvmStatic
import org.junit.Test

class OwnedHalResourcesTest {
    class Fake : OwnedHalResources.Backend {
        @JvmField var epoch: String = "old"
        @JvmField var closed: Boolean = true
        @JvmField var lostReply: Boolean = false
        @JvmField
        var ports: MutableList<OwnedHalResources.Port?> =
            ArrayList(OwnedHalResourcesTest.BASE.ports)
        @JvmField
        var configs: MutableList<OwnedHalResources.Config?> =
            ArrayList(OwnedHalResourcesTest.BASE.configs)
        @JvmField
        var patches: MutableList<OwnedHalResources.Patch?> =
            ArrayList(OwnedHalResourcesTest.BASE.patches)
        @JvmField var removed: MutableList<Int?> = ArrayList<Int?>()

        override fun snapshot(): OwnedHalResources.Snapshot? {
            return OwnedHalResources.Snapshot(
                epoch,
                List.copyOf(ports),
                List.copyOf(configs),
                List.copyOf(patches),
            )
        }

        override fun streamsClosed(): Boolean {
            return closed
        }

        @Throws(Exception::class)
        fun reply() {
            if (lostReply) throw IOException("Lost reply after mutation")
        }

        @Throws(Exception::class)
        override fun removePatch(id: Int) {
            OwnedHalResourcesTest.check(id == 301 || id == 302)
            removed.add(id)
            patches.removeIf { p: OwnedHalResources.Patch? -> p!!.id === id }
            reply()
        }

        @Throws(Exception::class)
        override fun removeConfig(id: Int) {
            OwnedHalResourcesTest.check(id >= 201 && id <= 204)
            removed.add(id)
            configs.removeIf { c: OwnedHalResources.Config? -> c!!.id === id }
            reply()
        }

        @Throws(Exception::class)
        override fun disconnectPort(id: Int) {
            OwnedHalResourcesTest.check(id == 101 || id == 102)
            removed.add(id)
            ports.removeIf { p: OwnedHalResources.Port? -> p!!.id === id }
            reply()
        }

        fun createThrough(count: Int) {
            if (count >= 1)
                ports.add(OwnedHalResources.Port(101, 143, OwnedHalResourcesTest.ADDRESS))
            if (count >= 2)
                ports.add(OwnedHalResources.Port(102, 11, OwnedHalResourcesTest.ADDRESS))
            if (count >= 3) configs.add(OwnedHalResources.Config(201, 3, 90001))
            if (count >= 4) configs.add(OwnedHalResources.Config(202, 4, 90002))
            if (count >= 5) configs.add(OwnedHalResources.Config(203, 101, 0))
            if (count >= 6) configs.add(OwnedHalResources.Config(204, 102, 0))
            if (count >= 7)
                patches.add(
                    OwnedHalResources.Patch(301, java.util.List.of(201), java.util.List.of(203))
                )
            if (count >= 8)
                patches.add(
                    OwnedHalResources.Patch(302, java.util.List.of(204), java.util.List.of(202))
                )
        }
    }

    @Test
    @Throws(Exception::class)
    fun lifecycleFaultCases() {
        OwnedHalResourcesTest.main(emptyArray<String>())
    }

    companion object {
        const val ADDRESS: String = "HomePodBridgeDirect:12345678-1234-5678-1234-567812345678"
        @JvmField
        val TAG: OwnedHalResources.Tag =
            OwnedHalResources.Tag(OwnedHalResourcesTest.ADDRESS, 3, 4, 90001, 90002)
        @JvmField
        val BASE: OwnedHalResources.Snapshot =
            OwnedHalResources.Snapshot(
                "old",
                List.of(
                    OwnedHalResources.Port(1, 143, ""),
                    OwnedHalResources.Port(2, 11, ""),
                    OwnedHalResources.Port(3, 0, ""),
                    OwnedHalResources.Port(4, 0, ""),
                    OwnedHalResources.Port(5, 11, "0"),
                ),
                List.of(
                    OwnedHalResources.Config(974, 5, 0),
                    OwnedHalResources.Config(975, 4, 2190),
                ),
                List.of(OwnedHalResources.Patch(7, java.util.List.of(974), java.util.List.of(975))),
            )

        @JvmStatic
        fun check(condition: Boolean) {
            if (!condition) throw AssertionError()
        }

        @JvmStatic
        fun main(args: Array<String>) {
            for (stage in 0..8) for (lose in booleanArrayOf(false, true)) {
                val fake = OwnedHalResourcesTest.Fake()
                fake.createThrough(stage)
                fake.lostReply = lose
                val lease =
                    OwnedHalResources(fake, OwnedHalResourcesTest.BASE, OwnedHalResourcesTest.TAG)
                OwnedHalResourcesTest.check(lease.cleanup()!!.complete)
                OwnedHalResourcesTest.check(fake.snapshot() == OwnedHalResourcesTest.BASE)
                val calls = fake.removed.size
                OwnedHalResourcesTest.check(lease.cleanup()!!.complete)
                OwnedHalResourcesTest.check(fake.removed.size == calls)
            }
            val live = OwnedHalResourcesTest.Fake()
            live.createThrough(8)
            live.closed = false
            val lease =
                OwnedHalResources(live, OwnedHalResourcesTest.BASE, OwnedHalResourcesTest.TAG)
            OwnedHalResourcesTest.check(!lease.cleanup()!!.complete && live.removed.isEmpty())
            live.closed = true
            OwnedHalResourcesTest.check(lease.cleanup()!!.complete)
            val foreign = OwnedHalResourcesTest.Fake()
            foreign.createThrough(8)
            val adopted =
                OwnedHalResources.Patch(399, java.util.List.of(201), java.util.List.of(975))
            foreign.patches.add(adopted)
            val guarded =
                OwnedHalResources(foreign, OwnedHalResourcesTest.BASE, OwnedHalResourcesTest.TAG)
            OwnedHalResourcesTest.check(!guarded.cleanup()!!.complete)
            OwnedHalResourcesTest.check(foreign.patches.contains(adopted))
            OwnedHalResourcesTest.check(
                foreign.configs.contains(OwnedHalResources.Config(201, 3, 90001))
            )
            OwnedHalResourcesTest.check(
                foreign.patches.containsAll(OwnedHalResourcesTest.BASE.patches!!) &&
                    foreign.configs.containsAll(OwnedHalResourcesTest.BASE.configs!!)
            )
            foreign.patches.remove(adopted)
            OwnedHalResourcesTest.check(guarded.cleanup()!!.complete)
            OwnedHalResourcesTest.check(foreign.snapshot() == OwnedHalResourcesTest.BASE)
            val restarted = OwnedHalResourcesTest.Fake()
            restarted.createThrough(8)
            restarted.epoch = "new"
            val replaced =
                OwnedHalResources(restarted, OwnedHalResourcesTest.BASE, OwnedHalResourcesTest.TAG)
                    .cleanup()
            OwnedHalResourcesTest.check(
                replaced!!.complete && replaced!!.oldInstanceGone && restarted.removed.isEmpty()
            )
            val collision = OwnedHalResourcesTest.Fake()
            collision.createThrough(8)
            collision.configs.add(OwnedHalResources.Config(9999, 3, 90001))
            OwnedHalResourcesTest.check(
                !OwnedHalResources(collision, OwnedHalResourcesTest.BASE, OwnedHalResourcesTest.TAG)
                    .cleanup()!!
                    .complete
            )
            OwnedHalResourcesTest.check(
                !collision.removed.contains(201) &&
                    collision.configs.stream().anyMatch { c: OwnedHalResources.Config? ->
                        c!!.id === 9999
                    }
            )
            println(
                ("{\"passed\":true,\"partialAllocationAndLostReplyCases\":18,\"guards\":[\"live" +
                    " streams\",\"foreign patch\",\"module restart\",\"ambiguous" +
                    " marker\",\"idempotency\"]}")
            )
        }
    }
}
