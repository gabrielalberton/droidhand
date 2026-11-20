package work.alberton.droidhand.runtime

import android.content.Context
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient

/** Terminal sessions (each a login shell inside Alpine). They outlive the activity. */
object Sessions {
    val list = mutableListOf<TerminalSession>()
    var current = 0

    fun create(ctx: Context, client: TerminalSessionClient): TerminalSession {
        val linux = Linux(ctx)
        val args = linux.prootArgs(listOf("/bin/sh", "-l"))
        val s = TerminalSession(
            linux.prootPath, ctx.filesDir.path, args.toTypedArray(), linux.prootEnv(), 5000, client,
        )
        list += s
        current = list.lastIndex
        RuntimeService.start(ctx)
        return s
    }

    fun active(): TerminalSession? = list.getOrNull(current)

    fun remove(s: TerminalSession) {
        val i = list.indexOf(s)
        if (i >= 0) list.removeAt(i)
        current = current.coerceAtMost(list.lastIndex).coerceAtLeast(0)
    }

    fun finishAll() {
        list.toList().forEach { it.finishIfRunning() }
        list.clear(); current = 0
    }
}
