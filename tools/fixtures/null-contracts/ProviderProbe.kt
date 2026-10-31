package dev.ryuya.nullprobe

import android.app.Activity
import android.app.Instrumentation
import android.net.Uri
import android.os.Bundle

/** Fake approved host, installed ONLY on our two disposable AOSP emulators. */
class ProviderProbe : Instrumentation() {
    private var before = false

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        before = arguments?.getString("phase") == "before"
        start()
    }

    override fun onStart() {
        val camera = targetContext.packageName == "com.sec.android.app.camera"
        val uri =
            if (camera) "content://dev.ryuya.galaxycamera.config"
            else "content://dev.ryuya.oneuihometweaks.config"
        val method = if (camera) "zoom_curves_publish" else "grid_result"
        try {
            targetContext.contentResolver.call(Uri.parse(uri), method, null, null)
            error("Invalid payload accepted")
        } catch (e: Exception) {
            val valid =
                if (before) e is NullPointerException
                else if (camera) e is IllegalArgumentException else e is SecurityException
            val result = Bundle()
            result.putString(
                "result",
                "${if (valid) "PASS" else "FAIL"} $method ${e.javaClass.simpleName}",
            )
            finish(if (valid) Activity.RESULT_OK else Activity.RESULT_CANCELED, result)
        }
    }
}
