package dev.ryuya.homepodbridge.halprobe

import java.io.IOException
import java.util.List
import kotlin.jvm.JvmField
import kotlin.jvm.JvmStatic
import org.junit.Test

class StreamOpenLedgerTest {
    class Disk : StreamOpenLedger.Journal {
        @JvmField
        var saved: MutableList<StreamOpenLedger.Entry?>? =
            java.util.List.of<StreamOpenLedger.Entry?>()
        @JvmField var fail: Boolean = false

        @Throws(Exception::class)
        override fun persist(entries: MutableList<StreamOpenLedger.Entry?>?) {
            if (fail) throw IOException("journal unavailable")
            saved = List.copyOf<StreamOpenLedger.Entry?>(entries)
        }
    }

    @Test
    @Throws(Exception::class)
    fun lifecycleFaultCases() {
        StreamOpenLedgerTest.main(emptyArray<String>())
    }

    companion object {
        @JvmStatic
        fun check(value: Boolean) {
            if (!value) throw AssertionError()
        }

        @Throws(Exception::class)
        @JvmStatic
        fun main(args: Array<String>) {
            val disk = StreamOpenLedgerTest.Disk()
            val lease = StreamOpenLedger(disk, java.util.List.of<StreamOpenLedger.Entry?>())
            StreamOpenLedgerTest.check(lease.allClosed() && !lease.attempted())
            lease.begin(201, false)
            StreamOpenLedgerTest.check(
                lease.attempted() && lease.replyUncertain() && !lease.allClosed()
            )
            // HAL allocated, reply lost, caller died: recovery must not reset any resources.
            val recovered = StreamOpenLedger(disk, disk.saved!!)
            StreamOpenLedgerTest.check(recovered.replyUncertain() && !recovered.allClosed())
            val fake = OwnedHalResourcesTest.Fake()
            fake.createThrough(8)
            fake.closed = recovered.allClosed()
            StreamOpenLedgerTest.check(
                !OwnedHalResources(fake, OwnedHalResourcesTest.BASE, OwnedHalResourcesTest.TAG)
                    .cleanup()!!
                    .complete
            )
            StreamOpenLedgerTest.check(fake.removed.isEmpty())
            try {
                recovered.begin(201, false)
                throw AssertionError()
            } catch (expected: IllegalStateException) {}
            try {
                recovered.closed(201)
                throw AssertionError()
            } catch (expected: IllegalStateException) {}
            lease.received(201)
            StreamOpenLedgerTest.check(!lease.replyUncertain() && !lease.allClosed())
            disk.fail = true
            try {
                lease.closed(201)
                throw AssertionError()
            } catch (expected: IOException) {}
            StreamOpenLedgerTest.check(!lease.allClosed())
            disk.fail = false
            lease.closed(201)
            StreamOpenLedgerTest.check(lease.allClosed())
            StreamOpenLedgerTest.check(StreamOpenLedger(disk, disk.saved!!).allClosed())
            val broken = StreamOpenLedgerTest.Disk()
            broken.fail = true
            val noDispatch = StreamOpenLedger(broken, java.util.List.of<StreamOpenLedger.Entry?>())
            try {
                noDispatch.begin(202, true)
                throw AssertionError()
            } catch (expected: IOException) {}
            StreamOpenLedgerTest.check(!noDispatch.allClosed())
            println(
                "{\"passed\":true,\"guards\":[\"lost open reply\",\"recovery gate\",\"no duplicate" +
                    " open\",\"journal before dispatch\",\"close journal failure\"]}"
            )
        }
    }
}
