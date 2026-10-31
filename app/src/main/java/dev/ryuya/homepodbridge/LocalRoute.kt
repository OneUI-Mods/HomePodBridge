package dev.ryuya.homepodbridge

import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import kotlin.jvm.JvmStatic

/* Temporary, destination- and UID-scoped Wi-Fi route when a VPN prevents normal binding. */
class LocalRoute private constructor(process: java.lang.Process) : AutoCloseable {
    private val process: java.lang.Process

    init {
        this.process = process
    }

    override fun close() {
        try {
            process.getOutputStream().close()
        } catch (ignored: IOException) {}
        try {
            if (!process.waitFor(1500, TimeUnit.MILLISECONDS)) process.destroy()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            process.destroy()
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
            val route = LocalRoute(helper)
            val response = BufferedReader(InputStreamReader(helper.getInputStream()))
            val deadline: Long = System.nanoTime() + 20000000000L
            while (System.nanoTime() < deadline) {
                if (response.ready()) {
                    val line = response.readLine()
                    if ("HOMEPOD_ROUTE_READY" == line) return route
                }
                if (!helper.isAlive()) break
                Thread.sleep(50)
            }
            route.close()
            throw IOException("Root permission is required to route local audio around this VPN")
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
