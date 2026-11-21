package work.alberton.droidhand.shell

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Process
import android.util.Log

/** App-side end of the shell helper link. */
object ShellBridge {
    /** Given to the helper so it can notice when the app dies. */
    val appToken: IBinder = Binder()

    @Volatile
    var helper: IShellHelper? = null
        private set

    /** Called when the helper goes away (debugging turned off, process killed). */
    @Volatile
    var onDetached: (() -> Unit)? = null

    val attached: Boolean get() = helper?.asBinder()?.isBinderAlive == true

    fun attach(binder: IBinder) {
        val h = IShellHelper.Stub.asInterface(binder)
        binder.linkToDeath({
            Log.i("DroidHand", "shell helper died")
            if (helper?.asBinder() === binder) helper = null
            onDetached?.invoke()
        }, 0)
        helper?.let { old -> runCatching { old.exit() } }
        helper = h
        Log.i("DroidHand", "shell helper attached (pid ${runCatching { h.pid() }.getOrNull()})")
    }
}

/** Receives the helper's binder. Only the shell (or root) user may call it. */
class ShellBridgeProvider : ContentProvider() {
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val uid = Binder.getCallingUid()
        if (uid != Process.SHELL_UID && uid != Process.ROOT_UID) throw SecurityException("shell only")
        if (method != "attach") return null
        val binder = extras?.getBinder("helper") ?: throw IllegalArgumentException("missing helper binder")
        ShellBridge.attach(binder)
        return Bundle().apply { putBinder("app", ShellBridge.appToken) }
    }

    override fun onCreate() = true
    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0
}
