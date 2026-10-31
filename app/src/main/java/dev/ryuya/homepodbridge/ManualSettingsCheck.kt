package dev.ryuya.homepodbridge

import android.content.Context
import dev.ryuya.homepodbridge.halprobe.PhoneTiming
import java.util.HashMap
import kotlin.jvm.JvmStatic

/* Fixed DUMP-protected persistence check; no capture, receiver or network is opened. */
object ManualSettingsCheck {
    @JvmStatic
    fun run(context: Context): String? {
        val before = StreamSettings.load(context)
        val manual = context.getSharedPreferences("manualStream", Context.MODE_PRIVATE)
        val snapshot = HashMap<String?, Any?>(manual.getAll())
        var checked: Boolean = false
        try {
            val expected =
                before!!
                    .asManual()!!
                    .withPhoneTiming(PhoneTiming(11, 447))!!
                    .withBuffers(37, 7, 123)!!
                    .withReceiverTiming(ReceiverTiming(true, 25, 180, 123))
            expected!!.save(context)
            check(expected == StreamSettings.load(context)) { "Manual round trip" }
            for (profile in intArrayOf(1, 2, 0)) {
                StreamSettings.load(context)!!.preset(profile)!!.save(context)
                check(expected == StreamSettings.savedManual(context)) { "Manual snapshot changed" }
            }
            checked = true
        } finally {
            before!!.save(context)
            val editor = manual.edit().clear()
            snapshot.forEach { (key: String?, value: Any?) ->
                if (value is Boolean) editor.putBoolean(key, value)
                else if (value is Int) editor.putInt(key, value)
                else throw IllegalStateException("Unexpected setting type")
            }
            check(!(!editor.commit() || before != StreamSettings.load(context))) {
                "Settings restoration unconfirmed"
            }
        }
        return if (checked)
            "MANUAL_SETTINGS_PASS restored=true presetSnapshots=true noPlayback=true"
        else "MANUAL_SETTINGS_FAIL"
    }
}
