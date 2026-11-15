package dev.ryuya.homepodbridge.startup

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** One-shot unlock delivery, including unlock during listener registration. No polling. */
internal class UnlockStartup(
    private val isUnlocked: () -> Boolean,
    private val subscribe: (() -> Unit) -> (() -> Unit),
) {
    fun run(ready: () -> Unit) {
        if (isUnlocked()) {
            ready()
            return
        }
        val completed = AtomicBoolean()
        val disposed = AtomicBoolean()
        val unsubscribe = AtomicReference<(() -> Unit)?>(null)
        fun dispose() {
            val action = unsubscribe.get() ?: return
            if (disposed.compareAndSet(false, true)) action()
        }
        fun check() {
            if (isUnlocked() && completed.compareAndSet(false, true)) {
                dispose()
                ready()
            }
        }
        unsubscribe.set(subscribe(::check))
        // The signal may arrive synchronously, or unlock may occur without a delivered broadcast
        // between the initial check and subscription. Cover both without installing twice.
        if (completed.get()) dispose() else check()
    }
}
