package com.novapdf.reader.ui.tts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TtsSettingsDialog(
    speechRate: Float,
    pitch: Float,
    autoAdvancePage: Boolean,
    currentSleepTimerSeconds: Long?,
    onSpeechRateChanged: (Float) -> Unit,
    onPitchChanged: (Float) -> Unit,
    onAutoAdvancePageToggled: (Boolean) -> Unit,
    onSleepTimerSet: (Int) -> Unit,
    onDismissRequest: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = rememberModalBottomSheetState()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 12.dp)
        ) {
            Text(
                text = "Read Out Loud Settings",
                style = MaterialTheme.typography.titleMedium
            )

            Spacer(modifier = Modifier.height(18.dp))

            // Speech Rate Slider
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Speech Rate (Speed)",
                    style = MaterialTheme.typography.labelMedium
                )
                Text(
                    text = "${String.format("%.2f", speechRate).trimEnd('0').trimEnd('.')}x",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Slider(
                value = speechRate,
                onValueChange = onSpeechRateChanged,
                valueRange = 0.5f..3.0f,
                steps = 24, // 0.1x steps
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(14.dp))

            // Voice Pitch Slider
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Voice Pitch",
                    style = MaterialTheme.typography.labelMedium
                )
                Text(
                    text = "${String.format("%.2f", pitch)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Slider(
                value = pitch,
                onValueChange = onPitchChanged,
                valueRange = 0.5f..2.0f,
                steps = 15,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(14.dp))

            // Auto-advance to next page switch
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Auto-Turn Page",
                        style = MaterialTheme.typography.labelMedium
                    )
                    Text(
                        text = "Automatically continue reading on next page",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = autoAdvancePage,
                    onCheckedChange = onAutoAdvancePageToggled
                )
            }

            Spacer(modifier = Modifier.height(18.dp))

            // Sleep Timer Options (ReadEra style)
            Text(
                text = "Sleep Timer",
                style = MaterialTheme.typography.labelMedium
            )

            Spacer(modifier = Modifier.height(8.dp))

            val timerOptions = listOf(0, 15, 30, 45, 60)
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                items(timerOptions) { minutes ->
                    val isSelected = if (minutes == 0) {
                        currentSleepTimerSeconds == null
                    } else {
                        currentSleepTimerSeconds != null && (currentSleepTimerSeconds / 60L).toInt() in (minutes - 2)..minutes
                    }

                    FilterChip(
                        selected = isSelected,
                        onClick = { onSleepTimerSet(minutes) },
                        label = {
                            Text(if (minutes == 0) "Off" else "$minutes min")
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(28.dp))
        }
    }
}
