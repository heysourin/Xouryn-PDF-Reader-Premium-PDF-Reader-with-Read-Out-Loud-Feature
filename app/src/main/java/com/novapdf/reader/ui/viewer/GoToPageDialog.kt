package com.novapdf.reader.ui.viewer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FindInPage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

@Composable
fun GoToPageDialog(
    currentPage: Int,
    pageCount: Int,
    onPageSelected: (Int) -> Unit,
    onDismissRequest: () -> Unit
) {
    var textValue by remember {
        val initialStr = (currentPage + 1).toString()
        mutableStateOf(TextFieldValue(initialStr, selection = TextRange(0, initialStr.length)))
    }
    var sliderValue by remember { mutableFloatStateOf((currentPage + 1).toFloat().coerceIn(1f, pageCount.toFloat().coerceAtLeast(1f))) }
    val focusRequester = remember { FocusRequester() }

    fun submitPage() {
        val parsed = textValue.text.toIntOrNull() ?: sliderValue.roundToInt()
        val clamped = parsed.coerceIn(1, pageCount.coerceAtLeast(1))
        onPageSelected(clamped - 1)
        onDismissRequest()
    }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        shape = RoundedCornerShape(20.dp),
        icon = {
            Icon(
                imageVector = Icons.Default.FindInPage,
                contentDescription = "Go to Page",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp)
            )
        },
        title = {
            Text(
                text = "Go to Page",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Enter a page number between 1 and $pageCount",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(16.dp))

                OutlinedTextField(
                    value = textValue,
                    onValueChange = { newValue ->
                        textValue = newValue
                        val num = newValue.text.toIntOrNull()
                        if (num != null && num in 1..pageCount) {
                            sliderValue = num.toFloat()
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth(0.7f)
                        .focusRequester(focusRequester),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Go
                    ),
                    keyboardActions = KeyboardActions(
                        onGo = { submitPage() }
                    ),
                    shape = RoundedCornerShape(12.dp)
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Interactive Slider
                Slider(
                    value = sliderValue,
                    onValueChange = { newFloat ->
                        sliderValue = newFloat
                        val pageInt = newFloat.roundToInt().coerceIn(1, pageCount.coerceAtLeast(1))
                        textValue = TextFieldValue(pageInt.toString())
                    },
                    valueRange = 1f..pageCount.toFloat().coerceAtLeast(1f),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Quick Jump Chips
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    OutlinedButton(
                        onClick = {
                            sliderValue = 1f
                            textValue = TextFieldValue("1")
                        },
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Page 1", fontSize = 11.sp)
                    }

                    if (pageCount > 2) {
                        OutlinedButton(
                            onClick = {
                                val mid = pageCount / 2
                                sliderValue = mid.toFloat()
                                textValue = TextFieldValue(mid.toString())
                            },
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("Mid (${pageCount / 2})", fontSize = 11.sp)
                        }
                    }

                    OutlinedButton(
                        onClick = {
                            sliderValue = pageCount.toFloat()
                            textValue = TextFieldValue(pageCount.toString())
                        },
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Page $pageCount", fontSize = 11.sp)
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { submitPage() },
                shape = RoundedCornerShape(10.dp)
            ) {
                Text("Go")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text("Cancel")
            }
        }
    )
}
