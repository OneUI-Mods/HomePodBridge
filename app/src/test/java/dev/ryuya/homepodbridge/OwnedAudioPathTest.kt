package dev.ryuya.homepodbridge

import java.io.IOException
import java.util.ArrayDeque
import java.util.ArrayList
import java.util.concurrent.Executor
import java.util.function.Consumer
import kotlin.jvm.JvmField
import org.junit.Assert.*
import org.junit.Test

class OwnedAudioPathTest {
    @Test
    fun smallerPlaybackReserveUsesItsMatchingRestoreAndRejectsStarvingExperiment() {
        val queue = OwnedAudioPathTest.Queue()
        val calls = ArrayList<Int?>()
        val states = ArrayList<OwnedAudioPath.Update?>()
        val lease =
            OwnedAudioPath(
                OwnedAudioPath.Backend { op: Int ->
                    calls.add(op)
                    if (op == 4) "Owned reserve changed: 2587 -> 928"
                    else "Owned reserve changed: 928 -> 2587"
                },
                queue,
                Consumer { p -> states.add(p) },
                928,
            )
        lease.start()
        queue.drain()
        assertEquals(OwnedAudioPath.State.ACTIVE, states.get(states.size - 1)!!.state)
        assertEquals(928, states.get(states.size - 1)!!.targetFrames)
        lease.close()
        queue.drain()
        assertEquals(java.util.List.of(4, 5), calls)
        assertThrows(
            IllegalArgumentException::class.java,
            {
                OwnedAudioPath(
                    OwnedAudioPath.Backend { op: Int -> "" },
                    queue,
                    Consumer { p -> states.add(p) },
                    464,
                )
            },
        )
    }

    @Test
    fun aRefusedWriteDoesNotRestoreAnIndependentExistingTarget() {
        val queue = OwnedAudioPathTest.Queue()
        val calls = ArrayList<Int?>()
        val lease =
            OwnedAudioPath(
                OwnedAudioPath.Backend { op: Int ->
                    calls.add(op)
                    ("Read-only owned route verified\n" +
                        "Owned HAL operation refused: stale owned target; writeAttempts=0")
                },
                queue,
                Consumer { value: OwnedAudioPath.Update? -> },
            )
        lease.start()
        queue.drain()
        lease.close()
        queue.drain()
        assertEquals(java.util.List.of(2), calls)
    }

    private class Queue : Executor {
        @JvmField val pending: ArrayDeque<Runnable?> = ArrayDeque<Runnable?>()

        override fun execute(task: Runnable?) {
            pending.add(task)
        }

        fun drain() {
            while (!pending.isEmpty()) pending.remove()!!.run()
        }
    }

    @Test
    fun activeLeaseRestoresOnceOnCloseAndDoesNotPublishAfterClose() {
        val queue = OwnedAudioPathTest.Queue()
        val calls = ArrayList<Int?>()
        val states = ArrayList<OwnedAudioPath.Update?>()
        val lease =
            OwnedAudioPath(
                OwnedAudioPath.Backend { op: Int ->
                    calls.add(op)
                    if (op == 2) OwnedAudioPathTest.APPLIED else OwnedAudioPathTest.RESTORED
                },
                queue,
                Consumer { p -> states.add(p) },
            )
        lease.start()
        lease.start()
        queue.drain()
        assertEquals(OwnedAudioPath.State.ACTIVE, states.get(states.size - 1)!!.state)
        val size = states.size
        lease.close()
        lease.close()
        queue.drain()
        assertEquals(java.util.List.of(2, 3), calls)
        assertEquals(size, states.size)
    }

    @Test
    fun closedBeforeDispatchNeverTouchesHardware() {
        val queue = OwnedAudioPathTest.Queue()
        val calls = ArrayList<Int?>()
        val lease =
            OwnedAudioPath(
                OwnedAudioPath.Backend { op: Int ->
                    calls.add(op)
                    OwnedAudioPathTest.APPLIED
                },
                queue,
                Consumer { u: OwnedAudioPath.Update? -> fail() },
            )
        lease.start()
        lease.close()
        queue.drain()
        assertTrue(calls.isEmpty())
    }

    @Test
    fun clientDeathDuringApplyRestoresWithoutPublishingActive() {
        val queue = OwnedAudioPathTest.Queue()
        val calls = ArrayList<Int?>()
        val states = ArrayList<OwnedAudioPath.Update?>()
        val holder = arrayOfNulls<OwnedAudioPath>(1)
        holder[0] =
            OwnedAudioPath(
                OwnedAudioPath.Backend { op: Int ->
                    calls.add(op)
                    if (op == 2) {
                        holder[0]!!.close()
                        return@Backend OwnedAudioPathTest.APPLIED
                    }
                    OwnedAudioPathTest.RESTORED
                },
                queue,
                Consumer { p -> states.add(p) },
            )
        holder[0]!!.start()
        queue.drain()
        assertEquals(java.util.List.of(2, 3), calls)
        assertFalse(
            states.stream().anyMatch { u: OwnedAudioPath.Update? ->
                u!!.state === OwnedAudioPath.State.ACTIVE
            }
        )
    }

    @Test
    fun unsupportedDeviceKeepsExistingCaptureWithoutRestorationAttempts() {
        val queue = OwnedAudioPathTest.Queue()
        val calls = ArrayList<Int?>()
        val states = ArrayList<OwnedAudioPath.Update?>()
        val lease =
            OwnedAudioPath(
                OwnedAudioPath.Backend { op: Int ->
                    calls.add(op)
                    "Read-only route probe unavailable: Unsupported HAL build"
                },
                queue,
                Consumer { p -> states.add(p) },
            )
        lease.start()
        queue.drain()
        lease.close()
        queue.drain()
        assertEquals(java.util.List.of(2), calls)
        assertEquals(OwnedAudioPath.State.STANDARD, states.get(states.size - 1)!!.state)
    }

    @Test
    fun failedMutationStageMustRestoreBeforeFallback() {
        val queue = OwnedAudioPathTest.Queue()
        val calls = ArrayList<Int?>()
        val states = ArrayList<OwnedAudioPath.Update?>()
        val lease =
            OwnedAudioPath(
                OwnedAudioPath.Backend { op: Int ->
                    calls.add(op)
                    if (op == 2) "Read-only owned route verified\noperation refused"
                    else "Original reserve already present"
                },
                queue,
                Consumer { p -> states.add(p) },
            )
        lease.start()
        queue.drain()
        assertEquals(java.util.List.of(2, 3), calls)
        assertEquals(OwnedAudioPath.State.STANDARD, states.get(states.size - 1)!!.state)
    }

    @Test
    fun lostReplyAndUnconfirmedRollbackRequireOwnedCaptureRecovery() {
        val queue = OwnedAudioPathTest.Queue()
        val states = ArrayList<OwnedAudioPath.Update?>()
        val lease =
            OwnedAudioPath(
                OwnedAudioPath.Backend { op: Int ->
                    throw IOException("Lost reply")
                },
                queue,
                Consumer { p -> states.add(p) },
            )
        lease.start()
        queue.drain()
        assertEquals(OwnedAudioPath.State.RECOVERY_REQUIRED, states.get(states.size - 1)!!.state)
    }

    companion object {
        private const val APPLIED: String =
            "Read-only owned route verified\nOwned reserve changed: 2587 -> 1856"
        private const val RESTORED: String = "Owned reserve changed: 1856 -> 2587"
    }
}
