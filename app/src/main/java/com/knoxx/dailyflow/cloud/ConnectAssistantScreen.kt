package com.knoxx.dailyflow.cloud

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.knoxx.dailyflow.BuildConfig
import com.knoxx.dailyflow.ui.FlowCard
import com.knoxx.dailyflow.ui.PrimaryChip
import com.knoxx.dailyflow.ui.SecondaryChip
import com.knoxx.dailyflow.ui.SectionHeader
import com.knoxx.dailyflow.ui.SunkenPanel
import com.knoxx.dailyflow.ui.flowClickable
import com.knoxx.dailyflow.ui.theme.Danger
import com.knoxx.dailyflow.ui.theme.Eyebrow
import com.knoxx.dailyflow.ui.theme.Ink
import com.knoxx.dailyflow.ui.theme.InkFaint
import com.knoxx.dailyflow.ui.theme.InkSecondary
import com.knoxx.dailyflow.ui.theme.Paper
import com.knoxx.dailyflow.ui.theme.PaperSunken
import com.knoxx.dailyflow.ui.theme.Violet
import java.security.SecureRandom
import kotlinx.coroutines.launch

/**
 * Hands an assistant the address and password for this phone's memory.
 *
 * Rebuilt on DailyFlow's components rather than ported verbatim: Wick's
 * version is written against its own design system — WickButton, WickBorder,
 * EyebrowStyle — which this app deliberately replaced. Every behaviour, every
 * endpoint call and every line of user-facing copy is carried over unchanged;
 * only the parts that draw are different.
 */
@Composable
fun ConnectAssistantScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var effectivePassword by remember { mutableStateOf(McpPasswordStore.current(context)) }
    var hasConsented by remember { mutableStateOf(CloudSyncConsent.hasConsented(context)) }
    var showConsentDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }

    LazyColumn(
        modifier = modifier.fillMaxSize().background(Paper),
        contentPadding = PaddingValues(bottom = 40.dp)
    ) {
        item {
            Row(
                Modifier.fillMaxWidth().statusBarsPadding()
                    .padding(start = 20.dp, end = 20.dp, top = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    Modifier.size(36.dp).clip(MaterialTheme.shapes.medium)
                        .background(PaperSunken).flowClickable(onBack),
                    contentAlignment = Alignment.Center
                ) {
                    Text("<", style = MaterialTheme.typography.titleLarge, color = Ink)
                }
                Text(
                    "Connect an assistant",
                    style = MaterialTheme.typography.headlineSmall,
                    color = Ink
                )
            }
        }

        item {
            Text(
                "An assistant can look things up in your memory instead of you " +
                    "repeating them. It reaches a cloud copy, so this only works with " +
                    "sync switched on.",
                style = MaterialTheme.typography.bodyMedium,
                color = InkSecondary,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp)
            )
        }

        item {
            SyncStateCard(
                consented = hasConsented,
                onEnable = { showConsentDialog = true },
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)
            )
        }

        item { SectionHeader("Credentials", Modifier.padding(horizontal = 20.dp)) }
        item {
            CredentialCard(
                label = "SERVER URL",
                value = BuildConfig.MCP_SERVER_URL,
                masked = false,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
            )
        }
        item {
            PasswordCard(
                password = effectivePassword,
                busy = busy,
                onRotate = {
                    busy = true
                    note = null
                    scope.launch {
                        val fresh = generateStrongPassword()
                        val current = McpPasswordStore.current(context)
                        AccountActionsClient.rotatePassword(current, fresh)
                            .onSuccess { revoked ->
                                McpPasswordStore.set(context, fresh)
                                effectivePassword = fresh
                                note = "Password rotated. $revoked session(s) signed out."
                            }
                            .onFailure { note = it.message ?: "Couldn't rotate the password" }
                        busy = false
                    }
                },
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
            )
        }

        item {
            SectionHeader("Setup steps", Modifier.padding(start = 20.dp, end = 20.dp, top = 22.dp))
        }
        item {
            SunkenPanel(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    StepRow(1, "In Claude, open Settings → Connectors → Add custom connector")
                    StepRow(2, "Paste the server URL above")
                    StepRow(3, "When prompted to authorize, enter the owner password above")
                    StepRow(4, "Ask Claude something about your day — it can look this up itself")
                }
            }
        }

        item {
            SectionHeader("Account", Modifier.padding(start = 20.dp, end = 20.dp, top = 22.dp))
        }
        item {
            SecondaryChip(
                "Sign out every connected assistant",
                onClick = {
                    busy = true
                    note = null
                    scope.launch {
                        val current = McpPasswordStore.current(context)
                        AccountActionsClient.revokeAccess(current)
                            .onSuccess { note = "Revoked $it session(s)." }
                            .onFailure { note = it.message ?: "Couldn't revoke access" }
                        busy = false
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)
            )
        }
        item {
            Box(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp)
                    .clip(MaterialTheme.shapes.medium)
                    .flowClickable { showDeleteDialog = true }
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "Delete the cloud copy",
                    style = MaterialTheme.typography.labelLarge,
                    color = Danger
                )
            }
        }
        item {
            Text(
                "Deleting the cloud copy leaves everything on this phone untouched.",
                style = MaterialTheme.typography.bodySmall,
                color = InkFaint,
                modifier = Modifier.padding(horizontal = 20.dp)
            )
        }

        note?.let {
            item {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = InkSecondary,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp)
                )
            }
        }
    }

    if (showConsentDialog) {
        AlertDialog(
            onDismissRequest = { showConsentDialog = false },
            title = { Text("Sync to a secure cloud store?") },
            text = {
                Text(
                    "Summaries are encrypted before they are stored, and only an " +
                        "assistant holding the password above can read them back. " +
                        "Raw screen text is never sent."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    CloudSyncConsent.setConsented(context, true)
                    hasConsented = true
                    showConsentDialog = false
                    CloudSyncScheduler.runNow(context)
                }) { Text("Enable cloud sync") }
            },
            dismissButton = {
                TextButton(onClick = { showConsentDialog = false }) { Text("Not now") }
            }
        )
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete the cloud copy?") },
            text = {
                Text(
                    "Every synced memory is removed from the cloud and sync is switched " +
                        "off. What is on this phone stays exactly as it is."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    busy = true
                    scope.launch {
                        val current = McpPasswordStore.current(context)
                        AccountActionsClient.deleteCloudData(current)
                            .onSuccess {
                                CloudSyncConsent.setConsented(context, false)
                                hasConsented = false
                                AuthRepository.signOut()
                                note = "Deleted $it memories from the cloud."
                            }
                            .onFailure { note = it.message ?: "Couldn't delete the cloud copy" }
                        busy = false
                    }
                }) { Text("Delete", color = Danger) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun SyncStateCard(
    consented: Boolean,
    onEnable: () -> Unit,
    modifier: Modifier = Modifier
) {
    FlowCard(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(Modifier.padding(end = 8.dp)) {
                Text(
                    if (consented) "Cloud sync is on" else "Cloud sync is off",
                    style = MaterialTheme.typography.titleSmall,
                    color = Ink
                )
                Text(
                    if (consented) "Summaries are synced, encrypted, for an assistant to read"
                    else "An assistant cannot reach anything until this is on",
                    style = MaterialTheme.typography.bodySmall,
                    color = InkSecondary,
                    modifier = Modifier.padding(top = 3.dp)
                )
            }
        }
        if (!consented) {
            PrimaryChip(
                label = "Turn on cloud sync",
                onClick = onEnable,
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
            )
        }
    }
}

/**
 * A value the user has to get into another app.
 *
 * Copy is the only affordance offered. Retyping a 32-character password by
 * hand is where this flow would actually fail, and a wrong character produces
 * an authorization error that looks like a broken connector.
 */
@Composable
private fun CredentialCard(
    label: String,
    value: String,
    masked: Boolean,
    modifier: Modifier = Modifier
) {
    val clipboard = LocalClipboardManager.current
    var revealed by remember { mutableStateOf(!masked) }

    SunkenPanel(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(label, style = Eyebrow, color = InkSecondary)
            Text(
                if (revealed) value.ifBlank { "not configured" } else "•".repeat(24),
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = Ink
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryChip(
                    "Copy",
                    onClick = { clipboard.setText(AnnotatedString(value)) },
                    modifier = Modifier.padding(top = 2.dp)
                )
                if (masked) {
                    SecondaryChip(
                        if (revealed) "Hide" else "Show",
                        onClick = { revealed = !revealed },
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun PasswordCard(
    password: String,
    busy: Boolean,
    onRotate: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier) {
        CredentialCard(label = "OWNER PASSWORD", value = password, masked = true)
        SecondaryChip(
            if (busy) "Working…" else "Generate a new password",
            onClick = { if (!busy) onRotate() },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
        )
        Text(
            "Rotating signs out every assistant currently connected.",
            style = MaterialTheme.typography.bodySmall,
            color = InkFaint,
            modifier = Modifier.padding(top = 6.dp)
        )
    }
}

@Composable
private fun StepRow(number: Int, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(
            Modifier.size(22.dp).clip(MaterialTheme.shapes.small).background(Violet),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "$number",
                style = MaterialTheme.typography.labelSmall,
                color = Paper
            )
        }
        Text(text, style = MaterialTheme.typography.bodySmall, color = InkSecondary)
    }
}

/** Carried over from Wick unchanged, SecureRandom included: this password is
 *  the only thing standing between an assistant and the memory store. */
private fun generateStrongPassword(): String {
    val chars = ('a'..'z') + ('A'..'Z') + ('0'..'9')
    val random = SecureRandom()
    return (1..32).map { chars[random.nextInt(chars.size)] }.joinToString("")
}
