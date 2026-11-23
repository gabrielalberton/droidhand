package work.alberton.droidhand.ui

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import work.alberton.droidhand.R
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * An invite hands a new phone everything it needs to join the relay: where to download the
 * app, the relay address and its own token. The admin phone makes it (Relay → Add another
 * phone) as text and as a QR code; on the new phone, Connection → Paste invite / Scan QR
 * code reads it back.
 *
 * The QR code is the release page with the connection in the fragment
 * (`…/releases/latest#relay=ws://…&token=…&name=…`): the phone's own camera opens the
 * download page, DroidHand reads the connection. A fragment is never sent to the server.
 */
object Invite {
    const val DOWNLOAD_URL = "https://github.com/gabrielalberton/droidhand/releases/latest"

    data class Data(val relay: String, val token: String, val name: String? = null)

    private val RELAY = Regex("""wss?://[^\s"'<>&#]+""")
    private val TOKEN = Regex("""(?<![0-9a-fA-F])[0-9a-fA-F]{32,128}(?![0-9a-fA-F])""")

    fun text(ctx: Context, name: String, relay: String, token: String): String =
        ctx.getString(R.string.invite_text, name, DOWNLOAD_URL, relay, token)

    /** What the QR code holds: the release page plus everything needed to connect. */
    fun link(name: String, relay: String, token: String): String =
        "$DOWNLOAD_URL#relay=$relay&token=$token&name=${URLEncoder.encode(name, "UTF-8")}"

    /** Relay address and token found in an invite text or link, or null when either is missing. */
    fun parse(text: String): Data? {
        val frag = text.substringAfter('#', "")
        if (frag.contains("relay=")) {
            val q = frag.trim().split('&').mapNotNull { p ->
                p.split('=', limit = 2).takeIf { it.size == 2 }?.let { (k, v) -> k to URLDecoder.decode(v, "UTF-8") }
            }.toMap()
            val relay = q["relay"]?.takeIf { RELAY.matches(it) }
            val token = q["token"]?.takeIf { TOKEN.matches(it) }
            if (relay != null && token != null) return Data(relay, token, q["name"])
        }
        val relay = RELAY.find(text)?.value?.trimEnd('.', ',', ';', ')') ?: return null
        val token = TOKEN.find(text)?.value ?: return null
        return Data(relay, token)
    }

    fun fromClipboard(ctx: Context): Data? =
        ctx.getSystemService(ClipboardManager::class.java)?.primaryClip
            ?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString()?.let(::parse)

    fun share(ctx: Context, text: String) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        ctx.startActivity(Intent.createChooser(send, ctx.getString(R.string.invite_share)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** QR code of [content], [size] px square, black on white with a quiet zone. */
    fun qr(content: String, size: Int): Bitmap {
        val m = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size,
            mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 2))
        val px = IntArray(m.width * m.height) { i -> if (m.get(i % m.width, i / m.width)) Color.BLACK else Color.WHITE }
        return Bitmap.createBitmap(px, m.width, m.height, Bitmap.Config.ARGB_8888)
    }

    /**
     * Read a QR code with Google's code scanner (no camera permission; needs Play services).
     * [onResult] gets the invite, or null with an error message resource.
     */
    fun scan(ctx: Context, onResult: (Data?, Int?) -> Unit) {
        val opts = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
        GmsBarcodeScanning.getClient(ctx, opts).startScan()
            .addOnSuccessListener { code ->
                val inv = code.rawValue?.let(::parse)
                onResult(inv, if (inv == null) R.string.invite_scan_not_invite else null)
            }
            .addOnCanceledListener { onResult(null, null) }
            .addOnFailureListener { onResult(null, R.string.invite_scan_failed) }
    }
}
