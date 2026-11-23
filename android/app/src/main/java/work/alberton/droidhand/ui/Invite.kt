package work.alberton.droidhand.ui

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import work.alberton.droidhand.R

/**
 * An invite hands a new phone everything it needs to join the relay: where to download the
 * app, the relay address and its own token. The admin phone makes it (Relay → Add another
 * phone); on the new phone, Connection → Paste invite reads it back from the clipboard.
 */
object Invite {
    const val DOWNLOAD_URL = "https://github.com/gabrielalberton/droidhand/releases/latest"

    private val RELAY = Regex("""wss?://[^\s"'<>]+""")
    private val TOKEN = Regex("""(?<![0-9a-fA-F])[0-9a-fA-F]{32,128}(?![0-9a-fA-F])""")

    fun text(ctx: Context, name: String, relay: String, token: String): String =
        ctx.getString(R.string.invite_text, name, DOWNLOAD_URL, relay, token)

    /** Relay address and token found in [text], or null when either is missing. */
    fun parse(text: String): Pair<String, String>? {
        val relay = RELAY.find(text)?.value?.trimEnd('.', ',', ';', ')') ?: return null
        val token = TOKEN.find(text)?.value ?: return null
        return relay to token
    }

    fun fromClipboard(ctx: Context): Pair<String, String>? =
        ctx.getSystemService(ClipboardManager::class.java)?.primaryClip
            ?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString()?.let(::parse)

    fun share(ctx: Context, text: String) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        ctx.startActivity(Intent.createChooser(send, ctx.getString(R.string.invite_share)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
