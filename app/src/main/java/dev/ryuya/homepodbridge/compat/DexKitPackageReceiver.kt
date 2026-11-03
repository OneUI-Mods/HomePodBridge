package dev.ryuya.homepodbridge.compat

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri

/** Prepares a newly installed/updated target; it does not poll or restart that application. */
class DexKitPackageReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(Intent.ACTION_PACKAGE_ADDED, Intent.ACTION_PACKAGE_REPLACED))
            return
        if (intent.data?.schemeSpecificPart !in DexKitConfig.TARGETS) return
        context.contentResolver.call(
            Uri.parse("content://${context.packageName}.dexkit/ready"),
            "status",
            null,
            null,
        )
    }
}
