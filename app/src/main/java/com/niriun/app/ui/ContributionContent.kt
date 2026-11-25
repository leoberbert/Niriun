package com.niriun.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.niriun.app.R

// Same recipient and payment payload used by MeuSaldo. No payment is initiated by the app.
private const val PIX_URL = "https://nubank.com.br/cobrar/1gyv7/6a121178-6e30-4623-b502-63039a46fd87"
private const val PIX_COPY_PASTE = "00020126430014BR.GOV.BCB.PIX0121leo4berbert@gmail.com5204000053039865802BR5922Leonardo Berbert Gomes6009SAO PAULO62140510ItE754b1iT63048EF4"

@Composable
internal fun ContributionContent() {
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Int?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(R.string.settings_support_heading), style = MaterialTheme.typography.titleLarge)
        Text(stringResource(R.string.settings_support_text))
        Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.large) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.settings_support_pix_title), style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary)
                Text("Leonardo Berbert Gomes", style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.settings_support_pix_amount), style = MaterialTheme.typography.bodySmall)
            }
        }
        Button(onClick = {
            error = null
            runCatching {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Pix Niriun", PIX_COPY_PASTE))
            }.onSuccess { copied = true }
                .onFailure { error = R.string.settings_support_copy_error }
        }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(if (copied) R.string.settings_support_copied else R.string.settings_support_copy))
        }
        Text(stringResource(if (copied) R.string.settings_support_after_copy else R.string.settings_support_before_copy),
            style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(onClick = {
            error = null
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, PIX_URL.toUri())) }
                .onFailure { error = R.string.settings_support_open_error }
        }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.settings_support_open_page)) }
        error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
        Text(stringResource(R.string.settings_support_thanks), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}