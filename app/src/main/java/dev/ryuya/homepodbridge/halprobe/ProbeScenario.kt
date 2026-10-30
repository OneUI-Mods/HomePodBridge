package dev.ryuya.homepodbridge.halprobe

import kotlin.jvm.JvmField
import kotlin.jvm.JvmOverloads
import kotlin.jvm.JvmStatic

/* Closed set of silent virtual-path diagnostics; never accepts caller audio or shell text. */
enum class ProbeScenario
@JvmOverloads
constructor(command: String, operation: String, workerMode: String, zeroFrames: Int = 0) {
    CONTROL("probe-hal-streams", "streams", "none"),
    EXIT_FDS("probe-hal-exit-fds", "streams-exit-fds", "after-fds"),
    EXIT_MAP("probe-hal-exit-map", "streams-exit-map", "after-map"),
    EXIT_STATUS("probe-hal-exit-status", "streams-exit-status", "after-status"),
    HANG("probe-hal-worker-hang", "streams-worker-hang", "hang"),
    ZERO_21MS("probe-hal-zero-21ms", "streams-zero-21ms", "zero-21ms", 928),
    ZERO_2MS("probe-hal-zero-2ms", "streams-zero-2ms", "zero-2ms", 88),
    ZERO_1MS("probe-hal-zero-1ms", "streams-zero-1ms", "zero-1ms", 44),
    ZERO_HALF_MS("probe-hal-zero-half-ms", "streams-zero-half-ms", "zero-half-ms", 22),
    ZERO_INPUT_EXIT("probe-hal-zero-input-exit", "streams-zero-input-exit", "zero-input-exit", 928),
    ZERO_OUTPUT_BLOCK(
        "probe-hal-zero-output-block",
        "streams-zero-output-block",
        "zero-output-block",
        928,
    ),
    POLICY_INPUT("probe-policy-capture", "policy-capture", "policy-capture"),
    POLICY_FAST("probe-policy-capture-fast", "policy-capture-fast", "policy-capture-fast"),
    POLICY_MINIMUM(
        "probe-policy-capture-minimum",
        "policy-capture-minimum",
        "policy-capture-minimum",
    ),
    POLICY_SMALLER(
        "probe-policy-capture-smaller",
        "policy-capture-smaller",
        "policy-capture-smaller",
    ),
    POLICY_POLL("probe-policy-capture-poll", "policy-capture-poll", "policy-capture-poll"),
    POLICY_HALF("probe-policy-capture-half", "policy-capture-half", "policy-capture-half"),
    POLICY_FORWARD("probe-policy-forward", "policy-forward", "policy-forward"),
    LIVE("internal-live", "live", "live"),
    LIVE_ZERO("internal-live-zero", "live-zero", "live-zero");

    fun live(): Boolean {
        return this == ProbeScenario.LIVE || this == ProbeScenario.LIVE_ZERO
    }

    fun policyInput(): Boolean {
        return this == ProbeScenario.POLICY_INPUT ||
            this == ProbeScenario.POLICY_FAST ||
            this == ProbeScenario.POLICY_MINIMUM ||
            this == ProbeScenario.POLICY_POLL ||
            this == ProbeScenario.POLICY_HALF ||
            this == ProbeScenario.POLICY_SMALLER ||
            this == ProbeScenario.POLICY_FORWARD ||
            live()
    }

    @JvmField val command: String
    @JvmField val operation: String
    @JvmField val workerMode: String
    @JvmField val zeroFrames: Int

    init {
        this.command = command
        this.operation = operation
        this.workerMode = workerMode
        this.zeroFrames = zeroFrames
    }

    fun acceptsFailure(
        exitCode: Int,
        timedOut: Boolean,
        forced: Boolean,
        diagnostics: String?,
    ): Boolean {
        if (
            this == ProbeScenario.CONTROL ||
                !("\n" + diagnostics).contains("\nFAULT_CHECKPOINT=" + workerMode + "\n")
        )
            return false
        if (this == ProbeScenario.HANG) return timedOut && forced && exitCode == 137
        if (this == ProbeScenario.ZERO_INPUT_EXIT) return !timedOut && !forced && exitCode == 75
        if (zeroFrames > 0) return false
        return !timedOut &&
            !forced &&
            exitCode == (if (this == ProbeScenario.EXIT_STATUS) 74 else 73)
    }

    companion object {
        @JvmStatic
        fun forCommand(value: String?): ProbeScenario? {
            for (scenario in ProbeScenario.entries) if (
                scenario != ProbeScenario.POLICY_FORWARD &&
                    !scenario.live() &&
                    scenario.command == value
            )
                return scenario
            return null
        }

        @JvmStatic
        fun forOperation(value: String?): ProbeScenario? {
            for (scenario in ProbeScenario.entries) if (scenario.operation == value) return scenario
            return null
        }

        @JvmStatic
        fun forWorker(value: String?): ProbeScenario? {
            for (scenario in ProbeScenario.entries) if (scenario.workerMode == value)
                return scenario
            return null
        }
    }
}
