package com.knoxx.dailyflow.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.knoxx.dailyflow.data.MemoryRepository
import com.knoxx.dailyflow.ui.theme.CardWhite
import com.knoxx.dailyflow.ui.theme.Eyebrow
import com.knoxx.dailyflow.ui.theme.Ink
import com.knoxx.dailyflow.ui.theme.InkSecondary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.Calendar

/**
 * Typing a task by hand.
 *
 * A manual task skips the whole capture pipeline: there is nothing to dedup
 * against and nothing to interpret, so it is written straight in as an
 * already-understood memory and appears immediately. That is also why its
 * direction is MANUAL — the cards should say "Added by you", not imply
 * somebody asked.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddTaskSheet(
    scope: CoroutineScope,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var title by remember { mutableStateOf("") }
    var category by remember { mutableStateOf<String?>(null) }
    var due by remember { mutableStateOf(DueChoice.Today) }

    fun submit() {
        val text = title.trim()
        if (text.isEmpty()) return
        scope.launch {
            MemoryRepository.addManual(
                title = text,
                dueAt = due.toMillis(),
                category = category,
                personName = null
            )
        }
        onDismiss()
    }

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
                .navigationBarsPadding()
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("NEW TASK", style = Eyebrow, color = InkSecondary)

            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("What needs doing?") },
                singleLine = false,
                maxLines = 3,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() })
            )

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("WHEN", style = Eyebrow, color = InkSecondary)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DueChoice.entries.forEach { choice ->
                        ChoiceChip(choice.label, due == choice) { due = choice }
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("CATEGORY", style = Eyebrow, color = InkSecondary)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Work", "Personal", "Home").forEach { name ->
                        ChoiceChip(name, category == name) {
                            // Tapping the selected chip clears it — the field
                            // is optional and there must be a way back to none.
                            category = if (category == name) null else name
                        }
                    }
                }
            }

            if (title.isNotBlank()) {
                PrimaryChip("Add task", ::submit, Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun ChoiceChip(label: String, selected: Boolean, onClick: () -> Unit) {
    if (selected) PrimaryChip(label, onClick) else SecondaryChip(label, onClick)
}

private enum class DueChoice(val label: String) {
    Today("Today"),
    Tomorrow("Tomorrow"),
    NextWeek("Next week"),
    None("No date");

    /** 9 AM local, matching where extraction puts a date-only deadline, so a
     *  typed task and an extracted one sort together rather than the typed one
     *  always landing at midnight. */
    fun toMillis(): Long? {
        if (this == None) return null
        val cal = Calendar.getInstance().apply {
            add(
                Calendar.DAY_OF_YEAR,
                when (this@DueChoice) {
                    Today -> 0
                    Tomorrow -> 1
                    NextWeek -> 7
                    None -> 0
                }
            )
            set(Calendar.HOUR_OF_DAY, 9)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return cal.timeInMillis
    }
}
