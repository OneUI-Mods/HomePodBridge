package dev.ryuya.homepodbridge

import java.io.IOException
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.jvm.JvmStatic

/* Temporary, destination- and UID-scoped Wi-Fi route when a VPN prevents normal binding. */
class LocalRoute private constructor(process: java.lang.Process) : AutoCloseable {
    private val process: java.lang.Process

    init {
        this.process = process
    }

    override fun close() {
        // Give the helper's EXIT trap time to remove its own rule even after cancellation.
        var interrupted = Thread.interrupted()
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(1500)
        try {
            try {
                process.outputStream.close()
            } catch (_: IOException) {}
            while (process.isAlive) {
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) {
                    process.destroy()
                    break
                }
                try {
                    process.waitFor(remaining, TimeUnit.NANOSECONDS)
                } catch (_: InterruptedException) {
                    interrupted = true
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    companion object {
        @JvmStatic
        @Throws(IOException::class, InterruptedException::class)
        fun acquire(host: String): LocalRoute {
            if (!LocalRoute.isLocalIpv4(host))
                throw IOException("Receiver is not on a private local network")
            val uid = android.os.Process.myUid()
            val selector: String =
                "pref 12990 to " + host + "/32 uidrange " + uid + "-" + uid + " lookup \"\$iface\""
            val command: String =
                ("iface=$(getprop wifi.interface); [ -n \"\$iface\" ] || iface=wlan0; " +
                    "case \"\$iface\" in wlan[0-9]*) ;; *) exit 2 ;; esac; " +
                    "ip -4 route get " +
                    host +
                    " oif \"\$iface\" >/dev/null 2>&1 || exit 3; " +
                    "cleanup() { ip -4 rule del " +
                    selector +
                    " 2>/dev/null; }; " +
                    "trap cleanup EXIT HUP INT TERM; " +
                    "ip -4 rule add " +
                    selector +
                    " || exit 4; " +
                    "echo HOMEPOD_ROUTE_READY; read -r lifetime")
            val helper = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
            return awaitHelper(helper)
        }

        /** Reading a partial line must not bypass the deadline or strand the scoped helper. */
        internal fun awaitHelper(helper: Process, timeoutMillis: Long = 20_000): LocalRoute {
            val route = LocalRoute(helper)
            val reader = Executors.newSingleThreadExecutor { task ->
                Thread(task, "HomePodRouteReady").apply { isDaemon = true }
            }
            val marker =
                reader.submit(
                    Callable {
                        helper.inputStream.bufferedReader().use { input ->
                            val line = StringBuilder()
                            var total = 0
                            while (true) {
                                val char = input.read()
                                if (char < 0) return@Callable false
                                if (++total > 8192)
                                    throw IOException("Route readiness output too large")
                                if (char == '\n'.code) {
                                    if (line.toString().trimEnd('\r') == "HOMEPOD_ROUTE_READY")
                                        return@Callable true
                                    line.setLength(0)
                                } else line.append(char.toChar())
                            }
                            @Suppress("UNREACHABLE_CODE") false
                        }
                    },
                )
            var ready = false
            try {
                if (!marker.get(timeoutMillis, TimeUnit.MILLISECONDS))
                    throw IOException("Route helper unavailable")
                ready = true
                return route
            } catch (error: TimeoutException) {
                throw IOException("Route helper timed out", error)
            } catch (error: ExecutionException) {
                throw IOException("Route helper unavailable", error.cause)
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                throw error
            } finally {
                if (!ready) route.close()
                marker.cancel(true)
                reader.shutdownNow()
            }
        }

        @JvmStatic
        fun isLocalIpv4(host: String?): Boolean {
            if (host == null || !host.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}".toRegex()))
                return false
            val parts = TextCompatibility.split(host, "\\.")
            val ip = IntArray(4)
            for (i in 0..3) {
                ip[i] = parts[i].toInt()
                if (ip[i] > 255) return false
            }
            return ip[0] == 10 ||
                (ip[0] == 172 && ip[1] >= 16 && ip[1] <= 31) ||
                (ip[0] == 192 && ip[1] == 168) ||
                (ip[0] == 169 && ip[1] == 254)
        }
    }
}
