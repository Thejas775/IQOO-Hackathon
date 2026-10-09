package com.knoxx.dailyflow.cloud

import android.util.Patterns
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.knoxx.dailyflow.ui.PrimaryChip
import com.knoxx.dailyflow.ui.SecondaryChip
import com.knoxx.dailyflow.ui.flowClickable
import com.knoxx.dailyflow.ui.theme.Danger
import com.knoxx.dailyflow.ui.theme.Eyebrow
import com.knoxx.dailyflow.ui.theme.Ink
import com.knoxx.dailyflow.ui.theme.InkSecondary
import com.knoxx.dailyflow.ui.theme.Paper
import com.knoxx.dailyflow.ui.theme.PaperSunken
import io.github.jan.supabase.exceptions.RestException
import kotlinx.coroutines.launch

/**
 * The account that owns the cloud copy.
 *
 * Rebuilt on DailyFlow's components rather than ported verbatim — Wick's
 * version draws with WickButton and WickBorder, which do not exist here. The
 * flow, the validation and the messages are carried over as they were.
 *
 * This is the only account in the app, and it exists solely so a synced
 * memory belongs to someone. Everything on the phone works signed out.
 */
@Composable
fun SignInScreen(
    onBack: () -> Unit,
    onSignedIn: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isSignUp by remember { mutableStateOf(false) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var infoMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    val emailValid = Patterns.EMAIL_ADDRESS.matcher(email).matches()
    val canSubmit = emailValid && password.length >= 6 && !isLoading

    fun submit() {
        errorMessage = null
        infoMessage = null
        isLoading = true
        scope.launch {
            try {
                if (isSignUp) {
                    AuthRepository.signUp(email, password)
                    infoMessage = "Check your email to confirm your account, then sign in."
                    isSignUp = false
                } else {
                    AuthRepository.signIn(email, password)
                    onSignedIn()
                }
            } catch (e: RestException) {
                errorMessage = e.message ?: "Something went wrong. Try again."
            } catch (e: Exception) {
                errorMessage = "Couldn't reach the server. Check your connection."
            } finally {
                isLoading = false
            }
        }
    }

    Column(
        modifier = modifier.fillMaxSize().background(Paper).imePadding()
    ) {
        Box(
            Modifier.statusBarsPadding().padding(start = 20.dp, top = 10.dp)
        ) {
            Box(
                Modifier.size(36.dp).clip(MaterialTheme.shapes.medium)
                    .background(PaperSunken).flowClickable(onBack),
                contentAlignment = Alignment.Center
            ) {
                Text("<", style = MaterialTheme.typography.titleLarge, color = Ink)
            }
        }

        Column(
            Modifier.fillMaxWidth().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                if (isSignUp) "CREATE AN ACCOUNT" else "SIGN IN",
                style = Eyebrow,
                color = InkSecondary
            )
            Text(
                if (isSignUp) "An account for the cloud copy" else "Sign in to sync",
                style = MaterialTheme.typography.headlineSmall,
                color = Ink
            )
            Text(
                "Only used to own the synced copy. Capture, understanding and " +
                    "everything on this phone work without it.",
                style = MaterialTheme.typography.bodyMedium,
                color = InkSecondary
            )

            OutlinedTextField(
                value = email,
                onValueChange = { email = it },
                label = { Text("Email") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Email,
                    imeAction = ImeAction.Next
                ),
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Password") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done
                ),
                supportingText = {
                    // Stated up front rather than only after a rejected
                    // attempt: the rule is the server's, and finding it out by
                    // failing is a worse way to learn it.
                    Text("At least 6 characters", style = MaterialTheme.typography.bodySmall)
                },
                modifier = Modifier.fillMaxWidth()
            )

            errorMessage?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = Danger)
            }
            infoMessage?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = InkSecondary)
            }

            PrimaryChip(
                label = when {
                    isLoading -> "Working…"
                    isSignUp -> "Create account"
                    else -> "Sign in"
                },
                onClick = { if (canSubmit) submit() },
                modifier = Modifier.fillMaxWidth()
            )

            SecondaryChip(
                if (isSignUp) "I already have an account" else "Create an account instead",
                onClick = {
                    isSignUp = !isSignUp
                    errorMessage = null
                    infoMessage = null
                },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
