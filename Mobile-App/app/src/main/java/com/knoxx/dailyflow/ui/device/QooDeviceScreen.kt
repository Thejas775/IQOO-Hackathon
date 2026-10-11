package com.knoxx.dailyflow.ui.device

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.knoxx.dailyflow.device.QooConnection

/**
 * Pair with the QOO watch.
 *
 * Deliberately thin: the connection itself lives in [QooConnection], which outlives this
 * screen. Leaving the page used to drop the socket and silently unpair the watch, which
 * made the device useful only while you were looking at its settings. Everything here
 * reads state and sends two commands.
 *
 * The six-digit code is what picks that watch out of however many are on the network,
 * and it is a real check rather than decoration - the watch refuses a connection
 * presenting the wrong one.
 */
@Composable
fun QooDeviceScreen(onBack: () -> Unit = {}) {
    val context = LocalContext.current
    val state by QooConnection.state.collectAsState()
    var code by remember { mutableStateOf(state.code) }

    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .statusBarsPadding()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onBack) { Text("Back") }
            }

            Text("QOO device", style = MaterialTheme.typography.titleLarge)
            Text(state.message, style = MaterialTheme.typography.bodyMedium)

            if (!state.isPaired) {
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(
                    value = code,
                    onValueChange = { typed -> code = typed.filter { it.isDigit() }.take(6) },
                    label = { Text("Pairing code") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    singleLine = true
                )
                Button(
                    enabled = code.length == 6 && !state.isBusy,
                    onClick = { QooConnection.connect(context, code) }
                ) { Text(if (state.isBusy) "Connecting..." else "Connect") }

                Spacer(Modifier.height(4.dp))
                Text(
                    "The phone and the watch must be on the same network. The watch does " +
                        "not need internet - only to reach this phone.",
                    style = MaterialTheme.typography.bodySmall
                )
            } else {
                Spacer(Modifier.height(4.dp))
                Text(
                    "Connected. You can leave this screen - QOO stays paired until you " +
                        "disconnect. Hold the watch face to record a note; hold the knob " +
                        "to ask a question.",
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedButton(onClick = { QooConnection.disconnect() }) { Text("Disconnect") }
            }

            if (state.log.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text("Activity", style = MaterialTheme.typography.titleSmall)
                state.log.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}
