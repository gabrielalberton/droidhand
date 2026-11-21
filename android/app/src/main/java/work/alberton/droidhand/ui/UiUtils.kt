package work.alberton.droidhand.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.compose.runtime.getValue
import work.alberton.droidhand.R

/** Small Android helpers used by several screens. */

fun copy(ctx: Context, text: String) {
    ctx.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("DroidHand", text))
    // Android 13+ shows its own clipboard confirmation.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) Toast.makeText(ctx, R.string.copied, Toast.LENGTH_SHORT).show()
}

fun openAppInfo(ctx: Context) =
    ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")))
