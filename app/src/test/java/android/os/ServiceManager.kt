package android.os

/** JVM fixture: this environment deliberately has no optional vendor HAL service. */
object ServiceManager {
    @JvmStatic fun getService(name: String): IBinder? = null
}
