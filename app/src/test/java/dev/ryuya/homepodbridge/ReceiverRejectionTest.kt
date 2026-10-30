package dev.ryuya.homepodbridge

import org.junit.Assert.*
import org.junit.Test

open class ReceiverRejectionTest {
    @Test
    open fun userNoticeDistinguishesRejectionCompatibilityAndLastAttempt() {
        assertEquals(
            R.string.receiver_request_rejected,
            ReceiverRejection(400, true).messageResource(false),
        )
        assertEquals(
            R.string.receiver_request_compatibility,
            ReceiverRejection(400, false).messageResource(true),
        )
        assertEquals(
            R.string.receiver_request_last_retry,
            ReceiverRejection(400, false).messageResource(false),
        )
        assertEquals(0, ReceiverRejection.NONE.messageResource(false))
    }

    @Test
    open fun receiverReplyDistinguishesRetryAndTerminalRefusal() {
        assertEquals(
            ReceiverRejection(400, false),
            ReceiverRejection.fromNative(intArrayOf(400, 0)),
        )
        assertEquals(
            ReceiverRejection(415, true),
            ReceiverRejection.fromNative(intArrayOf(415, 1)),
        )
        assertEquals(
            ReceiverRejection(403, true),
            ReceiverRejection.fromNative(intArrayOf(403, 1)),
        )
    }

    @Test
    open fun missingMalformedAndSuccessfulRepliesCannotBecomeRejection() {
        for (value in
            arrayOf<IntArray?>(
                null,
                intArrayOf(),
                intArrayOf(400),
                intArrayOf(200, 0),
                intArrayOf(0, 1),
                intArrayOf(399, 1),
                intArrayOf(600, 1),
                intArrayOf(400, -1),
                intArrayOf(400, 2),
                intArrayOf(400, 0, 1),
            )) assertEquals(ReceiverRejection.NONE, ReceiverRejection.fromNative(value))
        assertThrows(IllegalArgumentException::class.java, { ReceiverRejection(0, true) })
    }
}
