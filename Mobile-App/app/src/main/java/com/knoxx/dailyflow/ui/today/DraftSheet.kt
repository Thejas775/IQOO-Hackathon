package com.knoxx.dailyflow.ui.today

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.knoxx.dailyflow.ui.PrimaryChip
import com.knoxx.dailyflow.ui.SecondaryChip
import com.knoxx.dailyflow.ui.SunkenPanel
import com.knoxx.dailyflow.ui.theme.CardWhite
import com.knoxx.dailyflow.ui.theme.Eyebrow
import com.knoxx.dailyflow.ui.theme.Ink
import com.knoxx.dailyflow.ui.theme.InkSecondary

/**
 * Shows a drafted follow-up for the user to copy or share.
 *
 * It is never sent. The user always performs the send themselves, in whatever
 * app they choose — an app that both reads your messages and writes them is a
 * different and far more dangerous proposition, and the gap between "drafted"
 * and "sent" is the entire safety margin.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DraftSheet(state: DraftState, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = CardWhite
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("SUGGESTED FOLLOW-UP", style = Eyebrow, color = InkSecondary)

            Text(
                state.memory.summary.orEmpty(),
                style = MaterialTheme.typography.titleSmall,
                color = InkSecondary
            )

            SunkenPanel(Modifier.fillMaxWidth()) {
                Text(
                    if (state.generating) "Writing it on this device…" else state.text,
                    style = MaterialTheme.typography.bodyLarge,
                    color = Ink,
                    modifier = Modifier.padding(16.dp)
                )
            }

            if (!state.generating) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    // onClick is named because `modifier` is the last
                    // parameter, so a trailing lambda would bind to that
                    // instead — and the failure is a type error rather than a
                    // silently dead button, but only because Modifier and
                    // () -> Unit happen to be incompatible.
                    PrimaryChip("Copy", onClick = {
                        clipboard.setText(AnnotatedString(state.text))
                        onDismiss()
                    })
                    SecondaryChip("Share", onClick = {
                        // A plain share sheet rather than targeting the source
                        // app: there is no reliable way to open a specific
                        // conversation in WhatsApp or Slack, and silently
                        // opening the wrong thread would be worse than letting
                        // the user pick.
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, state.text)
                        }
                        context.startActivity(Intent.createChooser(send, null))
                        onDismiss()
                    })
                    SecondaryChip("Close", onDismiss)
                }
            }
        }
    }
}
