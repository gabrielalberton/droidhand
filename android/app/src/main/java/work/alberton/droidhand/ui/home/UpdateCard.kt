package work.alberton.droidhand.ui.home

import android.content.Context
import android.content.Intent
import android.widget.Toast
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import work.alberton.droidhand.BuildConfig
import work.alberton.droidhand.R
import work.alberton.droidhand.ui.components.Card
import work.alberton.droidhand.update.Updates
import java.io.File

/**
 * What the home screen knows about updates. Lives above the screens so it survives navigation,
 * starts from the last release found (the card shows at once) and is refreshed on every resume
 * or with the refresh button.
 */
class UpdateModel(private val ctx: Context) {
    var release by mutableStateOf(Updates.cached(ctx))
        private set
    var checking by mutableStateOf(false)
        private set
    private var last = 0L

    /** [manual] ignores the one-minute throttle and tells the user the outcome with a toast. */
    suspend fun check(manual: Boolean) {
        if (checking) return
        if (!manual && System.currentTimeMillis() - last < 60_000) return
        checking = true
        val res = Updates.checkNow()
        checking = false
        when (res) {
            is Updates.Check.Available -> { release = res.release; Updates.remember(ctx, res.release); last = System.currentTimeMillis() }
            Updates.Check.UpToDate -> { release = null; Updates.remember(ctx, null); last = System.currentTimeMillis() }
            Updates.Check.Failed -> {}
        }
        if (manual) Toast.makeText(ctx, when (res) {
            is Updates.Check.Available -> ctx.getString(R.string.update_found, res.release.version)
            Updates.Check.UpToDate -> ctx.getString(R.string.update_none, BuildConfig.VERSION_NAME)
            Updates.Check.Failed -> ctx.getString(R.string.update_check_failed)
        }, Toast.LENGTH_SHORT).show()
    }
}

/** Small refresh button for the header: looks for a new version now. */
@Composable
fun UpdateButton(model: UpdateModel) {
    val scope = rememberCoroutineScope()
    IconButton(onClick = { scope.launch { model.check(manual = true) } }, enabled = !model.checking) {
        if (model.checking) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        else Icon(painterResource(R.drawable.ic_refresh), stringResource(R.string.update_check), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Shown on the home screen when GitHub has a newer release: download, check and install it. */
@Composable
fun UpdateCard(model: UpdateModel) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val rel = model.release
    var progress by remember { mutableStateOf<Float?>(null) }
    var apk by remember { mutableStateOf<File?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val r = rel ?: return
    val cs = MaterialTheme.colorScheme

    fun installOrAsk(f: File) {
        if (Updates.canInstall(ctx)) Updates.install(ctx, f) else Updates.openInstallPermission(ctx)
    }

    Spacer(Modifier.height(16.dp))
    Card(color = cs.primaryContainer) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.update_title, r.version), style = MaterialTheme.typography.titleMedium, color = cs.onPrimaryContainer)
            Text(stringResource(R.string.update_body, BuildConfig.VERSION_NAME), style = MaterialTheme.typography.bodyMedium, color = cs.onPrimaryContainer)
            progress?.let { p ->
                LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
            }
            error?.let { Text(stringResource(R.string.update_failed, it), style = MaterialTheme.typography.bodyMedium, color = cs.error) }
            val f = apk
            if (f != null && !Updates.canInstall(ctx)) {
                Text(stringResource(R.string.update_allow_note), style = MaterialTheme.typography.bodySmall, color = cs.onPrimaryContainer)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = progress == null || f != null,
                    onClick = {
                        if (f != null) { installOrAsk(f); return@Button }
                        error = null; progress = 0f
                        scope.launch {
                            runCatching { Updates.download(ctx, r) { p -> progress = p } }
                                .onSuccess { apk = it; progress = null; installOrAsk(it) }
                                .onFailure { error = it.message ?: it.javaClass.simpleName; progress = null }
                        }
                    },
                ) { Text(stringResource(if (f != null) R.string.update_install else if (progress != null) R.string.update_downloading else R.string.update_download)) }
                TextButton(onClick = {
                    ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(r.pageUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }) { Text(stringResource(R.string.update_whats_new)) }
            }
        }
    }
}
