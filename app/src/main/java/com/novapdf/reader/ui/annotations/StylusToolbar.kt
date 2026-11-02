package com.novapdf.reader.ui.annotations

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.BorderColor
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.CropSquare
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FormatColorFill
import androidx.compose.material.icons.filled.FormatStrikethrough
import androidx.compose.material.icons.filled.FormatUnderlined
import androidx.compose.material.icons.filled.Gesture
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Redo
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.TrendingFlat
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.novapdf.reader.model.ToolType

@Composable
fun StylusToolbar(
    activeTool: ToolType,
    currentColor: Color,
    canUndo: Boolean,
    canRedo: Boolean,
    onToolSelected: (ToolType) -> Unit,
    onColorPickerRequested: () -> Unit,
    onSignatureRequested: () -> Unit,
    onUndoClicked: () -> Unit,
    onRedoClicked: () -> Unit,
    onClearAllClicked: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 4.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Pan (Scroll / Zoom)
            ToolButton(
                icon = Icons.Default.PanTool,
                tooltip = "Pan / Scroll",
                isSelected = activeTool == ToolType.PAN,
                onClick = { onToolSelected(ToolType.PAN) }
            )

            // Stylus Pen
            ToolButton(
                icon = Icons.Default.Edit,
                tooltip = "Pen / Stylus",
                isSelected = activeTool == ToolType.STYLUS_PEN,
                onClick = { onToolSelected(ToolType.STYLUS_PEN) }
            )

            // Highlighter
            ToolButton(
                icon = Icons.Default.AutoFixHigh,
                tooltip = "Highlighter",
                isSelected = activeTool == ToolType.HIGHLIGHTER,
                onClick = { onToolSelected(ToolType.HIGHLIGHTER) }
            )

            // Text Underline
            ToolButton(
                icon = Icons.Default.FormatUnderlined,
                tooltip = "Underline",
                isSelected = activeTool == ToolType.UNDERLINE,
                onClick = { onToolSelected(ToolType.UNDERLINE) }
            )

            // Text Strikeout
            ToolButton(
                icon = Icons.Default.FormatStrikethrough,
                tooltip = "Strikeout",
                isSelected = activeTool == ToolType.STRIKEOUT,
                onClick = { onToolSelected(ToolType.STRIKEOUT) }
            )

            // Shapes: Rectangle
            ToolButton(
                icon = Icons.Default.CropSquare,
                tooltip = "Rectangle",
                isSelected = activeTool == ToolType.RECTANGLE,
                onClick = { onToolSelected(ToolType.RECTANGLE) }
            )

            // Shapes: Oval
            ToolButton(
                icon = Icons.Default.RadioButtonUnchecked,
                tooltip = "Oval",
                isSelected = activeTool == ToolType.OVAL,
                onClick = { onToolSelected(ToolType.OVAL) }
            )

            // Shapes: Arrow
            ToolButton(
                icon = Icons.Default.TrendingFlat,
                tooltip = "Arrow",
                isSelected = activeTool == ToolType.ARROW,
                onClick = { onToolSelected(ToolType.ARROW) }
            )

            // Text Box
            ToolButton(
                icon = Icons.Default.TextFields,
                tooltip = "Text Box",
                isSelected = activeTool == ToolType.FREE_TEXT,
                onClick = { onToolSelected(ToolType.FREE_TEXT) }
            )

            // Signature Stamp
            ToolButton(
                icon = Icons.Default.Gesture,
                tooltip = "Signature",
                isSelected = activeTool == ToolType.SIGNATURE,
                onClick = onSignatureRequested
            )

            // Eraser
            ToolButton(
                icon = Icons.Default.Delete,
                tooltip = "Eraser",
                isSelected = activeTool == ToolType.ERASER,
                onClick = { onToolSelected(ToolType.ERASER) }
            )

            // ReadEra-Style Read Out Loud Tool
            ToolButton(
                icon = Icons.Default.RecordVoiceOver,
                tooltip = "Read Out Loud (Tap to Speak)",
                isSelected = activeTool == ToolType.TTS_READER,
                selectedTint = MaterialTheme.colorScheme.tertiary,
                onClick = { onToolSelected(ToolType.TTS_READER) }
            )

            Spacer(modifier = Modifier.width(6.dp))
            VerticalDivider(modifier = Modifier.height(24.dp))
            Spacer(modifier = Modifier.width(6.dp))

            // Active Color Swatch Picker
            IconButton(
                onClick = onColorPickerRequested,
                modifier = Modifier.size(36.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(currentColor)
                )
            }

            // Undo
            IconButton(
                onClick = onUndoClicked,
                enabled = canUndo,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Undo,
                    contentDescription = "Undo",
                    modifier = Modifier.size(20.dp),
                    tint = if (canUndo) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                )
            }

            // Redo
            IconButton(
                onClick = onRedoClicked,
                enabled = canRedo,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Redo,
                    contentDescription = "Redo",
                    modifier = Modifier.size(20.dp),
                    tint = if (canRedo) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                )
            }

            // Clear All Annotations
            IconButton(
                onClick = onClearAllClicked,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Clear,
                    contentDescription = "Clear all markups",
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun ToolButton(
    icon: ImageVector,
    tooltip: String,
    isSelected: Boolean,
    selectedTint: Color = MaterialTheme.colorScheme.primary,
    onClick: () -> Unit
) {
    val bg = if (isSelected) selectedTint.copy(alpha = 0.15f) else Color.Transparent
    val tint = if (isSelected) selectedTint else MaterialTheme.colorScheme.onSurfaceVariant

    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(38.dp)
            .padding(2.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = tooltip,
            modifier = Modifier.size(22.dp),
            tint = tint
        )
    }
}
