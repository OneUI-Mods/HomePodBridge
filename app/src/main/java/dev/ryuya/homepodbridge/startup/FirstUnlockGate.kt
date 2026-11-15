package dev.ryuya.homepodbridge.startup

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.UserManager
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/** Defer CE-dependent setup until first unlock; normal later locks do not stop it. */
internal class FirstUnlockGate {
    private val waiting = AtomicBoolean()

    fun run(context: Context, ready: () -> Unit) {
        val host = context.applicationContext ?: context
        fun unlocked() = host.getSystemService(UserManager::class.java)?.isUserUnlocked == true
        if (unlocked()) {
            ready()
            return
        }
        // Repeated lifecycle callbacks while locked must not add duplicate receivers.
        if (!waiting.compareAndSet(false, true)) return
        UnlockStartup(::unlocked) { check ->
                val receiver =
                    object : BroadcastReceiver() {
                        override fun onReceive(context: Context, intent: Intent) {
                            if (intent.action == Intent.ACTION_USER_UNLOCKED) check()
                        }
                    }
                host.registerReceiver(
                    receiver,
                    IntentFilter(Intent.ACTION_USER_UNLOCKED),
                    Context.RECEIVER_NOT_EXPORTED,
                )
                val removeListener: () -> Unit = { host.unregisterReceiver(receiver) }
                removeListener
            }
            .run {
                // A broadcast callback is outside the framework's protective hook boundary.
                // A failed initialization must leave the host running with its native behavior.
                try {
                    ready()
                } catch (error: Throwable) {
                    Log.e("FirstUnlockGate", "Deferred initialization stopped", error)
                }
            }
    }
}
