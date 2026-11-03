package com.novapdf.reader.ui.annotations

import android.view.MotionEvent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.unit.dp
import com.novapdf.reader.model.StrokePoint

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun SignatureDialog(
    onSignatureConfirmed: (List<List<StrokePoint>>) -> Unit,
    onDismissRequest: () -> Unit
) {
    val strokes = remember { mutableStateListOf<List<StrokePoint>>() }
    val currentStroke = remember { mutableStateListOf<StrokePoint>() }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = {
            Text(text = "Draw Signature")
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Sign using your finger or stylus pen below:",
                    style = MaterialTheme.typography.bodySmall
                )

                Spacer(modifier = Modifier.height(12.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.White)
                        .border(1.dp, Color.Gray, RoundedCornerShape(8.dp))
                        .pointerInteropFilter { event ->
                            when (event.actionMasked) {
                                MotionEvent.ACTION_DOWN -> {
                                    currentStroke.clear()
                                    currentStroke.add(
                                        StrokePoint(
                                            x = event.x,
                                            y = event.y,
                                            pressure = event.pressure
                                        )
                                    )
                                    true
                                }
                                MotionEvent.ACTION_MOVE -> {
                                    currentStroke.add(
                                        StrokePoint(
                                            x = event.x,
                                            y = event.y,
                                            pressure = event.pressure
                                        )
                                    )
                                    true
                                }
                                MotionEvent.ACTION_UP -> {
                                    if (currentStroke.isNotEmpty()) {
                                        strokes.add(currentStroke.toList())
                                        currentStroke.clear()
                                    }
                                    true
                                }
                                else -> false
                            }
                        }
                ) {
                    Canvas(modifier = Modifier.matchParentSize()) {
                        // Draw completed strokes
                        strokes.forEach { stroke ->
                            if (stroke.size > 1) {
                                val path = Path().apply {
                                    moveTo(stroke.first().x, stroke.first().y)
                                    for (i in 1 until stroke.size) {
                                        lineTo(stroke[i].x, stroke[i].y)
                                    }
                                }
                                drawPath(
                                    path = path,
                                    color = Color(0xFF1A237E),
                                    style = Stroke(
                                        width = 4.dp.toPx(),
                                        cap = StrokeCap.Round,
                                        join = StrokeJoin.Round
                                    )
                                )
                            }
                        }

                        // Draw active stroke
                        if (currentStroke.size > 1) {
                            val path = Path().apply {
                                moveTo(currentStroke.first().x, currentStroke.first().y)
                                for (i in 1 until currentStroke.size) {
                                    lineTo(currentStroke[i].x, currentStroke[i].y)
                                }
                            }
                            drawPath(
                                path = path,
                                color = Color(0xFF1A237E),
                                style = Stroke(
                                    width = 4.dp.toPx(),
                                    cap = StrokeCap.Round,
                                    join = StrokeJoin.Round
                                )
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (strokes.isNotEmpty()) {
                        onSignatureConfirmed(strokes.toList())
                    }
                },
                enabled = strokes.isNotEmpty()
            ) {
                Text(text = "Insert")
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    onClick = {
                        strokes.clear()
                        currentStroke.clear()
                    }
                ) {
                    Text(text = "Clear")
                }

                OutlinedButton(onClick = onDismissRequest) {
                    Text(text = "Cancel")
                }
            }
        }
    )
}

