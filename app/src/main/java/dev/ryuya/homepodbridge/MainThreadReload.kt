package dev.ryuya.homepodbridge

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Invoke teardown on the owner thread, or decline before teardown starts. */
internal object MainThreadReload {
    fun run(main: Handler, retire: () -> Boolean): Boolean {
        if (Looper.myLooper() === main.looper) return retire()
        val state = AtomicInteger(WAITING)
        val complete = CountDownLatch(1)
        var result = false
        var failure: Throwable? = null
        if (!main.post {
                if (!state.compareAndSet(WAITING, RUNNING)) return@post
                try {
                    result = retire()
                } catch (error: Throwable) {
                    failure = error
                } finally {
                    complete.countDown()
                }
            }) return false
        var interrupted = false
        try {
            val completed = try {
                complete.await(750, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                interrupted = true
                false
            }
            if (!completed) {
                if (state.compareAndSet(WAITING, CANCELLED)) return false
                // Once teardown starts, reporting cancellation would leave the old code disabled.
                while (complete.count != 0L) {
                    try {
                        complete.await()
                    } catch (_: InterruptedException) {
                        interrupted = true
                    }
                }
            }
            failure?.let { throw it }
            return result
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    private const val WAITING = 0
    private const val RUNNING = 1
    private const val CANCELLED = 2
}
