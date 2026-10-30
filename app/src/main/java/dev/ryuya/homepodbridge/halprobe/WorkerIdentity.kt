package dev.ryuya.homepodbridge.halprobe

import java.util.UUID
import kotlin.jvm.JvmRecord
import kotlin.jvm.JvmStatic

/* Immutable identity authenticated before any FD handoff, rechecked immediately before SIGKILL. */
@JvmRecord
data class WorkerIdentity(
    val pid: Int,
    val parentPid: Int,
    val startTicks: Long,
    val commandSuffix: String?,
) {
    fun matches(stat: String, status: String, command: String): Boolean {
        try {
            return WorkerIdentity.start(stat, pid, parentPid) == startTicks &&
                command.endsWith(commandSuffix!!) &&
                status.matches("(?s).*\\nUid:\\s+0\\s+0\\s+0\\s+0\\s*\\n.*".toRegex())
        } catch (invalid: RuntimeException) {
            return false
        }
    }

    companion object {
        @JvmStatic
        fun authenticate(
            pid: Int,
            uid: Int,
            parent: Int,
            stat: String,
            status: String,
            command: String,
            nonce: String,
            scenario: ProbeScenario?,
        ): WorkerIdentity {
            if (
                pid <= 0 ||
                    parent <= 0 ||
                    uid != 0 ||
                    scenario == null ||
                    (UUID.fromString(nonce).toString() != nonce)
            )
                throw SecurityException("Invalid worker identity")
            val suffix: String =
                ("dev.ryuya.homepodbridge.halprobe.HalQueueMapping\u0000hpb-map-" +
                    nonce +
                    "\u0000" +
                    nonce +
                    "\u0000" +
                    parent +
                    "\u0000" +
                    scenario.workerMode +
                    "\u0000")
            val identity =
                WorkerIdentity(pid, parent, WorkerIdentity.start(stat, pid, parent), suffix)
            if (!identity.matches(stat, status, command))
                throw SecurityException("Unexpected worker process")
            return identity
        }

        @JvmStatic
        private fun start(stat: String, pid: Int, parent: Int): Long {
            val space = stat.indexOf(' ')
            val close = stat.lastIndexOf(')')
            if (space < 1 || close < space || (stat.substring(0, space) != pid.toString()))
                throw SecurityException("Invalid process stat")
            val fields =
                stat
                    .substring(close + 2)
                    .split("\\s+".toRegex())
                    .dropLastWhile { it.isEmpty() }
                    .toTypedArray()
            if (
                fields.size < 20 ||
                    (fields[1] != parent.toString()) ||
                    !fields[19].matches("[1-9][0-9]*".toRegex())
            )
                throw SecurityException("Invalid worker epoch")
            return fields[19].toLong()
        }
    }

    override fun toString(): String =
        "WorkerIdentity[pid=$pid, parentPid=$parentPid, startTicks=$startTicks, commandSuffix=$commandSuffix]"
}
