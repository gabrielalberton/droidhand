package work.alberton.droidhand.ui.home

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import work.alberton.droidhand.BuildConfig
import work.alberton.droidhand.R
import work.alberton.droidhand.ui.components.Card
import work.alberton.droidhand.update.Updates
import java.io.File

/** Shown on the home screen when GitHub has a newer release: download, check and install it. */
@Composable
fun UpdateCard() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var rel by remember { mutableStateOf<Updates.Release?>(null) }
    var progress by remember { mutableStateOf<Float?>(null) }
    var apk by remember { mutableStateOf<File?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { rel = Updates.check() }
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
