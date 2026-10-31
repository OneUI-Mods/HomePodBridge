package dev.ryuya.homepodbridge

import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.Consumer
import kotlin.concurrent.Volatile
import kotlin.jvm.JvmOverloads
import kotlin.jvm.JvmRecord

/* Per-connection lease; the backend is bound to one authenticated capture registration. */
class OwnedAudioPath
@JvmOverloads
constructor(
    backend: OwnedAudioPath.Backend,
    executor: Executor,
    listener: Consumer<OwnedAudioPath.Update?>,
    targetFrames: Int = 1856,
) : AutoCloseable {
    enum class State {
        CHECKING,
        ACTIVE,
        STANDARD,
        RECOVERY_REQUIRED,
    }

    @JvmRecord
    data class Update
    @JvmOverloads
    constructor(
        val state: OwnedAudioPath.State?,
        val report: String?,
        val targetFrames: Int = 1856,
    ) {

        override fun toString(): String =
            "Update[state=$state, report=$report, targetFrames=$targetFrames]"
    }

    fun interface Backend {
        @Throws(Exception::class) fun operate(operation: Int): String?
    }

    private val backend: OwnedAudioPath.Backend
    private val executor: Executor
    private val listener: Consumer<OwnedAudioPath.Update?>
    private val targetFrames: Int
    private val closed: AtomicBoolean = AtomicBoolean()
    private val started: AtomicBoolean = AtomicBoolean()
    private var mutationPossible: Boolean = false
    private var restored: Boolean = false
    private var restorationAttempted: Boolean = false
    @Volatile private var restorationReport: String = "No restoration attempted"

    fun restorationReport(): String {
        return restorationReport
    }

    init {
        require(!(targetFrames != 1856 && targetFrames != 928)) { "Unsupported playback reserve" }
        this.backend = backend
        this.executor = executor
        this.listener = listener
        this.targetFrames = targetFrames
    }

    fun start() {
        if (!started.compareAndSet(false, true)) return
        executor.execute(
            Runnable {
                if (closed.get()) return@Runnable
                emit(OwnedAudioPath.State.CHECKING, "Checking the owned device path")
                var report: String? = null
                try {
                    report = backend.operate(if (targetFrames == 928) 4 else 2)
                    mutationPossible =
                        !report!!.contains("writeAttempts=0") &&
                            (report!!.contains("Read-only owned route verified") ||
                                report!!.contains("Owned reserve changed") ||
                                report!!.contains("rollback failed"))
                } catch (failure: Exception) {
                    report = "Device path command failed: " + failure.javaClass.getSimpleName()
                    mutationPossible =
                        true // A lost reply must not be treated as proof of no mutation.
                }
                if (report!!.contains("Owned reserve changed: 2587 -> " + targetFrames)) {
                    if (!closed.get()) emit(OwnedAudioPath.State.ACTIVE, report)
                    // close() has already queued restoration if the client stopped during this
                    // command.
                } else {
                    val restoration = restore()
                    emit(
                        if (restored || !mutationPossible) OwnedAudioPath.State.STANDARD
                        else OwnedAudioPath.State.RECOVERY_REQUIRED,
                        report + "\n" + restoration,
                    )
                }
            }
        )
    }

    private fun restore(): String? {
        if (!mutationPossible || restored) return "No restoration needed"
        if (restorationAttempted) return restorationReport
        restorationAttempted = true
        try {
            val report = backend.operate(if (targetFrames == 928) 5 else 3)
            restored =
                report!!.contains("Owned reserve changed: " + targetFrames + " -> 2587") ||
                    report!!.contains("Original reserve already present") ||
                    report!!.contains("No matching live owned route") ||
                    report!!.contains("owned registration absent")
            restorationReport = report
            return report
        } catch (failure: Exception) {
            restorationReport =
                "Owned restoration unavailable: " + failure.javaClass.getSimpleName()
            return restorationReport
        }
    }

    private fun emit(state: OwnedAudioPath.State?, report: String?) {
        if (!closed.get()) listener.accept(OwnedAudioPath.Update(state, report, targetFrames))
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) executor.execute(Runnable { this.restore() })
    }
}
