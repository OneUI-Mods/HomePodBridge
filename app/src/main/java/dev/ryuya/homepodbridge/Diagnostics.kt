package dev.ryuya.homepodbridge

import android.content.Context
import android.os.SystemClock
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import kotlin.jvm.JvmStatic
import kotlin.jvm.Synchronized

object Diagnostics {
    @JvmStatic
    @Synchronized
    fun route(context: Context, event: String?) {
        try {
            val file = File(context.getFilesDir(), "route-events.txt")
            val mode: Int = if (file.length() < 16384) Context.MODE_APPEND else Context.MODE_PRIVATE
            context.openFileOutput("route-events.txt", mode).use { output ->
                output.write(
                    (SystemClock.elapsedRealtime().toString() + " " + event + "\n").toByteArray(
                        StandardCharsets.UTF_8
                    )
                )
            }
        } catch (ignored: IOException) {}
    }
}
