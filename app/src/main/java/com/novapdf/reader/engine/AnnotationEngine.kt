package com.novapdf.reader.engine

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.novapdf.reader.model.Annotation
import com.novapdf.reader.model.NormalizedRect
import com.novapdf.reader.model.StrokePoint
import com.novapdf.reader.model.ToolType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

class AnnotationEngine {

    // Map: PageIndex -> List of Annotations
    private val annotationsByPage = HashMap<Int, MutableList<Annotation>>()

    // Undo & Redo History Stacks
    private val undoStack = ArrayDeque<Command>()
    private val redoStack = ArrayDeque<Command>()

    private val _canUndo = MutableStateFlow(false)
    val canUndo: StateFlow<Boolean> = _canUndo.asStateFlow()

    private val _canRedo = MutableStateFlow(false)
    val canRedo: StateFlow<Boolean> = _canRedo.asStateFlow()

    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version.asStateFlow()

    private sealed interface Command {
        data class Add(val annotation: Annotation) : Command
        data class Remove(val annotation: Annotation) : Command
        data class BatchClear(val previous: Map<Int, List<Annotation>>) : Command
    }

    fun getAnnotationsForPage(pageIndex: Int): List<Annotation> {
        return annotationsByPage[pageIndex]?.toList() ?: emptyList()
    }

    fun addAnnotation(annotation: Annotation) {
        val list = annotationsByPage.getOrPut(annotation.pageIndex) { ArrayList() }
        list.add(annotation)
        undoStack.addLast(Command.Add(annotation))
        redoStack.clear()
        updateHistoryState()
    }

    fun removeAnnotation(annotation: Annotation) {
        val list = annotationsByPage[annotation.pageIndex] ?: return
        if (list.remove(annotation)) {
            undoStack.addLast(Command.Remove(annotation))
            redoStack.clear()
            updateHistoryState()
        }
    }

    fun eraseAt(pageIndex: Int, normalizedX: Float, normalizedY: Float, tolerance: Float = 0.03f): Boolean {
        val list = annotationsByPage[pageIndex] ?: return false
        val iterator = list.iterator()
        while (iterator.hasNext()) {
            val annot = iterator.next()
            if (isPointNearAnnotation(annot, normalizedX, normalizedY, tolerance)) {
                iterator.remove()
                undoStack.addLast(Command.Remove(annot))
                redoStack.clear()
                updateHistoryState()
                return true
            }
        }
        return false
    }

    private fun isPointNearAnnotation(annot: Annotation, x: Float, y: Float, tolerance: Float): Boolean {
        return when (annot) {
            is Annotation.Ink -> {
                annot.points.any { p ->
                    val dx = p.x - x
                    val dy = p.y - y
                    (dx * dx + dy * dy) <= (tolerance * tolerance)
                }
            }
            is Annotation.Highlight -> {
                annot.rects.any { it.contains(x, y) } ||
                annot.freehandPoints.any { p ->
                    val dx = p.x - x
                    val dy = p.y - y
                    (dx * dx + dy * dy) <= (tolerance * tolerance)
                }
            }
            is Annotation.Shape -> {
                val minX = minOf(annot.startPoint.x, annot.endPoint.x) - tolerance
                val maxX = maxOf(annot.startPoint.x, annot.endPoint.x) + tolerance
                val minY = minOf(annot.startPoint.y, annot.endPoint.y) - tolerance
                val maxY = maxOf(annot.startPoint.y, annot.endPoint.y) + tolerance
                x in minX..maxX && y in minY..maxY
            }
            is Annotation.FreeText -> annot.bounds.contains(x, y)
            is Annotation.Signature -> annot.bounds.contains(x, y)
            is Annotation.TextMarkup -> annot.rects.any { it.contains(x, y) }
            is Annotation.StickyNote -> {
                val dx = annot.position.x - x
                val dy = annot.position.y - y
                (dx * dx + dy * dy) <= (tolerance * tolerance)
            }
        }
    }

    fun undo() {
        if (undoStack.isEmpty()) return
        val command = undoStack.removeLast()
        when (command) {
            is Command.Add -> {
                annotationsByPage[command.annotation.pageIndex]?.remove(command.annotation)
                redoStack.addLast(command)
            }
            is Command.Remove -> {
                annotationsByPage.getOrPut(command.annotation.pageIndex) { ArrayList() }.add(command.annotation)
                redoStack.addLast(command)
            }
            is Command.BatchClear -> {
                annotationsByPage.clear()
                command.previous.forEach { (page, list) ->
                    annotationsByPage[page] = list.toMutableList()
                }
                redoStack.addLast(command)
            }
        }
        updateHistoryState()
    }

    fun redo() {
        if (redoStack.isEmpty()) return
        val command = redoStack.removeLast()
        when (command) {
            is Command.Add -> {
                annotationsByPage.getOrPut(command.annotation.pageIndex) { ArrayList() }.add(command.annotation)
                undoStack.addLast(command)
            }
            is Command.Remove -> {
                annotationsByPage[command.annotation.pageIndex]?.remove(command.annotation)
                undoStack.addLast(command)
            }
            is Command.BatchClear -> {
                annotationsByPage.clear()
                undoStack.addLast(command)
            }
        }
        updateHistoryState()
    }

    fun clearAll() {
        if (annotationsByPage.isEmpty()) return
        val snapshot = annotationsByPage.mapValues { it.value.toList() }
        annotationsByPage.clear()
        undoStack.addLast(Command.BatchClear(snapshot))
        redoStack.clear()
        updateHistoryState()
    }

    private fun updateHistoryState() {
        _canUndo.value = undoStack.isNotEmpty()
        _canRedo.value = redoStack.isNotEmpty()
        _version.value += 1
    }
}

