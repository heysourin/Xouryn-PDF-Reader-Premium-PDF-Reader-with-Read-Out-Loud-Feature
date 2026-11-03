package com.novapdf.reader.ui.viewer

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Brightness4
import androidx.compose.material.icons.filled.ChromeReaderMode
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Toc
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.novapdf.reader.engine.AnnotationEngine
import com.novapdf.reader.engine.PdfEngine
import com.novapdf.reader.engine.PdfTextExtractor
import com.novapdf.reader.model.Annotation
import com.novapdf.reader.model.DocumentTab
import com.novapdf.reader.model.NormalizedRect
import com.novapdf.reader.model.PageDimension
import com.novapdf.reader.model.PageTextContent
import com.novapdf.reader.model.PdfDocumentModel
import com.novapdf.reader.model.PlaybackState
import com.novapdf.reader.model.ToolType
import com.novapdf.reader.tts.TtsController
import com.novapdf.reader.ui.annotations.AnnotationStylePicker
import com.novapdf.reader.ui.annotations.SignatureDialog
import com.novapdf.reader.ui.annotations.StylusToolbar
import com.novapdf.reader.ui.navigation.BookmarksDrawer
import com.novapdf.reader.ui.navigation.OutlineDrawer
import com.novapdf.reader.ui.navigation.ThumbnailDrawer
import com.novapdf.reader.ui.tabs.DocumentTabBar
import com.novapdf.reader.ui.theme.ReadingThemeMode
import com.novapdf.reader.ui.tts.TtsBottomBar
import com.novapdf.reader.ui.tts.TtsSettingsDialog
import com.novapdf.reader.ui.tts.VoiceSelectionSheet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class ActiveDrawer {
    NONE,
    THUMBNAILS,
    OUTLINE,
    BOOKMARKS
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfViewerScreen(
    document: PdfDocumentModel,
    tabs: List<DocumentTab>,
    activeTabId: String?,
    initialPageIndex: Int = 0,
    pdfEngine: PdfEngine,
    textExtractor: PdfTextExtractor,
    annotationEngine: AnnotationEngine,
    ttsController: TtsController,
    onTabSelected: (DocumentTab) -> Unit,
    onTabClosed: (DocumentTab) -> Unit,
    onNewTabRequested: () -> Unit,
    onOpenPdfPickerRequested: () -> Unit,
    onPageChanged: (Int) -> Unit = {},
    onFullScreenToggled: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val configuration = LocalConfiguration.current
    val isTablet = configuration.screenWidthDp >= 600
    val isLandscape = configuration.screenWidthDp > configuration.screenHeightDp

    // Reading & Viewer state
    var currentPageIndex by remember(document.uri) {
        mutableIntStateOf(initialPageIndex.coerceIn(0, (document.pageCount - 1).coerceAtLeast(0)))
    }
    var isFullScreen by remember { mutableStateOf(false) }
    var isDualPageMode by remember { mutableStateOf(isTablet && isLandscape) }
    var readingTheme by remember { mutableStateOf(ReadingThemeMode.LIGHT) }
    val bookmarks = remember { mutableStateOf(document.bookmarks.toMutableSet()) }

    // Active Drawer State
    var activeDrawer by remember { mutableStateOf(ActiveDrawer.NONE) }

    // Annotation state
    var activeTool by remember { mutableStateOf(ToolType.PAN) }
    var currentColor by remember { mutableStateOf(Color(0xFF0066FF)) }
    var currentStrokeWidth by remember { mutableFloatStateOf(4f) }
    var currentAlpha by remember { mutableFloatStateOf(1f) }

    var showStylePicker by remember { mutableStateOf(false) }
    var showSignatureDialog by remember { mutableStateOf(false) }

    // Undo / Redo states
    val canUndo by annotationEngine.canUndo.collectAsState()
    val canRedo by annotationEngine.canRedo.collectAsState()
    val annotationVersion by annotationEngine.version.collectAsState()

    // ReadEra TTS States
    val ttsPlaybackState by ttsController.playbackState.collectAsState()
    val ttsPosition by ttsController.currentPosition.collectAsState()
    val ttsSettings by ttsController.settings.collectAsState()
    val availableVoices by ttsController.availableVoices.collectAsState()

    var showTtsSettingsDialog by remember { mutableStateOf(false) }
    var showVoiceSelectionSheet by remember { mutableStateOf(false) }
    val isTtsBarVisible = ttsPlaybackState != PlaybackState.IDLE || activeTool == ToolType.TTS_READER

    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = initialPageIndex.coerceIn(0, (document.pageCount - 1).coerceAtLeast(0))
    )

    // Notify activity of system bar fullscreen changes
    LaunchedEffect(isFullScreen) {
        onFullScreenToggled(isFullScreen)
    }

    // Scroll to initial page if document changed
    LaunchedEffect(document.uri) {
        val target = initialPageIndex.coerceIn(0, (document.pageCount - 1).coerceAtLeast(0))
        if (target > 0) {
            listState.scrollToItem(target)
        }
    }

    // Configure on-demand text extraction callback for TTS
    LaunchedEffect(ttsController, document) {
        ttsController.onTextExtractionRequired = { pageIdx ->
            val dim = document.pageDimensions.getOrNull(pageIdx)
            textExtractor.extractPageText(
                document.uri,
                pageIdx,
                dim?.width ?: 800,
                dim?.height ?: 1100
            )
        }
    }

    // Connect TTS page change callback to view pager / scroll
    LaunchedEffect(ttsController) {
        ttsController.onPageChangeRequested = { newPage ->
            currentPageIndex = newPage
            onPageChanged(newPage)
            scope.launch {
                listState.animateScrollToItem(newPage)
            }
        }
    }

    // Continuous scroll tracking
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex }.collect { index ->
            currentPageIndex = index
            onPageChanged(index)
        }
    }

    Scaffold(
        topBar = {
            AnimatedVisibility(
                visible = !isFullScreen,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column {
                    TopAppBar(
                        title = {
                            Column {
                                Text(
                                    text = document.title,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                                )
                                Text(
                                    text = "Page ${currentPageIndex + 1} of ${document.pageCount}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        },
                        actions = {
                            // Open PDF Picker
                            IconButton(onClick = onOpenPdfPickerRequested) {
                                Icon(imageVector = Icons.Default.FolderOpen, contentDescription = "Open PDF")
                            }

                            // Tablet Dual Page Spread toggle
                            if (isTablet) {
                                IconButton(onClick = { isDualPageMode = !isDualPageMode }) {
                                    Icon(
                                        imageVector = if (isDualPageMode) Icons.Default.MenuBook else Icons.Default.ChromeReaderMode,
                                        contentDescription = "Toggle Dual Page Spread",
                                        tint = if (isDualPageMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            // Fullscreen Toggle
                            IconButton(onClick = { isFullScreen = !isFullScreen }) {
                                Icon(
                                    imageVector = if (isFullScreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                                    contentDescription = "Toggle Fullscreen"
                                )
                            }

                            // Bookmark Active Page toggle
                            IconButton(onClick = {
                                val currentSet = bookmarks.value.toMutableSet()
                                if (currentSet.contains(currentPageIndex)) {
                                    currentSet.remove(currentPageIndex)
                                } else {
                                    currentSet.add(currentPageIndex)
                                }
                                bookmarks.value = currentSet
                            }) {
                                Icon(
                                    imageVector = if (bookmarks.value.contains(currentPageIndex)) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                                    contentDescription = "Bookmark",
                                    tint = if (bookmarks.value.contains(currentPageIndex)) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            // Thumbnails Drawer Trigger
                            IconButton(onClick = {
                                activeDrawer = if (activeDrawer == ActiveDrawer.THUMBNAILS) ActiveDrawer.NONE else ActiveDrawer.THUMBNAILS
                            }) {
                                Icon(imageVector = Icons.Default.GridView, contentDescription = "Thumbnails")
                            }

                            // Table of Contents Trigger
                            IconButton(onClick = {
                                activeDrawer = if (activeDrawer == ActiveDrawer.OUTLINE) ActiveDrawer.NONE else ActiveDrawer.OUTLINE
                            }) {
                                Icon(imageVector = Icons.Default.Toc, contentDescription = "Outline")
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        )
                    )

                    // Multi-Document Tab Strip (Xodo style)
                    if (tabs.size > 1) {
                        DocumentTabBar(
                            tabs = tabs,
                            activeTabId = activeTabId,
                            onTabSelected = onTabSelected,
                            onTabClosed = onTabClosed,
                            onNewTabRequested = onNewTabRequested
                        )
                    }

                    // Stylus & Annotation Tool Bar (Xodo style)
                    StylusToolbar(
                        activeTool = activeTool,
                        currentColor = currentColor,
                        canUndo = canUndo,
                        canRedo = canRedo,
                        onToolSelected = { selected ->
                            activeTool = selected
                            if (selected == ToolType.TTS_READER) {
                                ttsController.play(currentPageIndex)
                            }
                        },
                        onColorPickerRequested = { showStylePicker = true },
                        onSignatureRequested = { showSignatureDialog = true },
                        onUndoClicked = { annotationEngine.undo() },
                        onRedoClicked = { annotationEngine.redo() },
                        onClearAllClicked = { annotationEngine.clearAll() }
                    )
                }
            }
        },
        bottomBar = {
            // ReadEra-style TTS Control Bar with smooth slide animation
            AnimatedVisibility(
                visible = !isFullScreen && isTtsBarVisible,
                enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
            ) {
                TtsBottomBar(
                    isVisible = isTtsBarVisible,
                    playbackState = ttsPlaybackState,
                    speechPosition = ttsPosition,
                    speechRate = ttsSettings.speechRate,
                    selectedVoiceDisplayName = ttsSettings.selectedVoiceName,
                    sleepTimerSeconds = ttsSettings.sleepTimerRemainingSeconds,
                    onPlayPauseClicked = { ttsController.togglePlayPause() },
                    onSkipPreviousClicked = { ttsController.skipPrevious() },
                    onSkipNextClicked = { ttsController.skipNext() },
                    onSpeedPillClicked = {
                        val nextSpeed = when {
                            ttsSettings.speechRate < 0.95f -> 1.0f
                            ttsSettings.speechRate < 1.2f -> 1.25f
                            ttsSettings.speechRate < 1.45f -> 1.5f
                            ttsSettings.speechRate < 1.9f -> 2.0f
                            else -> 0.75f
                        }
                        ttsController.setSpeechRate(nextSpeed)
                    },
                    onVoicePickerClicked = { showVoiceSelectionSheet = true },
                    onSettingsClicked = { showTtsSettingsDialog = true },
                    onCloseClicked = {
                        ttsController.stop()
                        if (activeTool == ToolType.TTS_READER) {
                            activeTool = ToolType.PAN
                        }
                    }
                )
            }
        }
    ) { innerPadding ->
        val contentPadding = if (isFullScreen) PaddingValues(0.dp) else innerPadding

        Box(
            modifier = modifier
                .fillMaxSize()
                .padding(contentPadding)
                .background(
                    when (readingTheme) {
                        ReadingThemeMode.LIGHT -> Color(0xFFF0F2F5)
                        ReadingThemeMode.SEPIA -> Color(0xFFF4ECD8)
                        ReadingThemeMode.DARK -> Color(0xFF1E1E1E)
                        ReadingThemeMode.NIGHT -> Color.Black
                        else -> MaterialTheme.colorScheme.background
                    }
                )
        ) {
            // Main Document Viewer
            if (isTablet && isDualPageMode) {
                // Dual Page Spread (Tablet Mode)
                val leftPage = (currentPageIndex / 2) * 2
                val rightPage = (leftPage + 1).takeIf { it < document.pageCount }

                DualPageSpreadView(
                    leftPageIndex = leftPage,
                    rightPageIndex = rightPage,
                    dimensions = document.pageDimensions,
                    pdfEngine = pdfEngine,
                    textExtractor = textExtractor,
                    documentUri = document.uri,
                    getAnnotationsForPage = { p ->
                        val dummy = annotationVersion
                        annotationEngine.getAnnotationsForPage(p)
                    },
                    activeTool = activeTool,
                    currentColor = currentColor,
                    currentStrokeWidth = currentStrokeWidth,
                    currentAlpha = currentAlpha,
                    speechPosition = ttsPosition,
                    onAnnotationAdded = { annot -> annotationEngine.addAnnotation(annot) },
                    onEraseRequested = { p, x, y -> annotationEngine.eraseAt(p, x, y) },
                    onTapToReadRequested = { p, x, y -> ttsController.tapToRead(p, x, y) },
                    onSpeakFromSentenceRequested = { p, s -> ttsController.play(p, s) },
                    onPageTapped = { isFullScreen = !isFullScreen }
                )
            } else {
                // Continuous Vertical Scroll (Handheld & Portrait Tablet Mode)
                ZoomableContainer(enabled = activeTool == ToolType.PAN) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = if (isFullScreen) 0.dp else 16.dp, vertical = if (isFullScreen) 0.dp else 12.dp)
                    ) {
                        items(document.pageCount) { pageIndex ->
                            val dim = document.pageDimensions.getOrNull(pageIndex)

                            PageCanvasView(
                                pageIndex = pageIndex,
                                dimension = dim,
                                pdfEngine = pdfEngine,
                                textExtractor = textExtractor,
                                documentUri = document.uri,
                                annotations = run {
                                    val dummy = annotationVersion
                                    annotationEngine.getAnnotationsForPage(pageIndex)
                                },
                                activeTool = activeTool,
                                currentColor = currentColor,
                                currentStrokeWidth = currentStrokeWidth,
                                currentAlpha = currentAlpha,
                                speechPosition = ttsPosition,
                                onAnnotationAdded = { annot -> annotationEngine.addAnnotation(annot) },
                                onEraseRequested = { x, y -> annotationEngine.eraseAt(pageIndex, x, y) },
                                onTapToReadRequested = { x, y -> ttsController.tapToRead(pageIndex, x, y) },
                                onSpeakFromSentenceRequested = { sIndex -> ttsController.play(pageIndex, sIndex) },
                                onPageTapped = { isFullScreen = !isFullScreen },
                                modifier = Modifier.padding(bottom = if (isFullScreen) 6.dp else 16.dp)
                            )
                        }
                    }
                }
            }

            // Navigation Drawers (Side Overlays)
            AnimatedVisibility(
                visible = activeDrawer != ActiveDrawer.NONE,
                enter = slideInHorizontally(initialOffsetX = { -it }),
                exit = slideOutHorizontally(targetOffsetX = { -it }),
                modifier = Modifier.align(Alignment.CenterStart)
            ) {
                when (activeDrawer) {
                    ActiveDrawer.THUMBNAILS -> {
                        ThumbnailDrawer(
                            pageCount = document.pageCount,
                            currentPageIndex = currentPageIndex,
                            dimensions = document.pageDimensions,
                            getThumbnailBitmap = { p -> pdfEngine.getCachedPage(p) },
                            onPageSelected = { selected ->
                                currentPageIndex = selected
                                onPageChanged(selected)
                                activeDrawer = ActiveDrawer.NONE
                                scope.launch { listState.scrollToItem(selected) }
                            },
                            onCloseRequested = { activeDrawer = ActiveDrawer.NONE }
                        )
                    }
                    ActiveDrawer.OUTLINE -> {
                        OutlineDrawer(
                            outlines = document.outlines,
                            onPageSelected = { selected ->
                                currentPageIndex = selected
                                onPageChanged(selected)
                                activeDrawer = ActiveDrawer.NONE
                                scope.launch { listState.scrollToItem(selected) }
                            },
                            onCloseRequested = { activeDrawer = ActiveDrawer.NONE }
                        )
                    }
                    ActiveDrawer.BOOKMARKS -> {
                        BookmarksDrawer(
                            bookmarks = bookmarks.value,
                            currentPageIndex = currentPageIndex,
                            onPageSelected = { selected ->
                                currentPageIndex = selected
                                onPageChanged(selected)
                                activeDrawer = ActiveDrawer.NONE
                                scope.launch { listState.scrollToItem(selected) }
                            },
                            onToggleBookmark = { p ->
                                val set = bookmarks.value.toMutableSet()
                                if (set.contains(p)) set.remove(p) else set.add(p)
                                bookmarks.value = set
                            },
                            onCloseRequested = { activeDrawer = ActiveDrawer.NONE }
                        )
                    }
                    else -> Unit
                }
            }

            // Stylus Style Picker Sheet
            if (showStylePicker) {
                AnnotationStylePicker(
                    selectedColor = currentColor,
                    strokeWidth = currentStrokeWidth,
                    alpha = currentAlpha,
                    onColorChanged = { currentColor = it },
                    onStrokeWidthChanged = { currentStrokeWidth = it },
                    onAlphaChanged = { currentAlpha = it },
                    onDismissRequest = { showStylePicker = false }
                )
            }

            // Signature Pad Dialog
            if (showSignatureDialog) {
                SignatureDialog(
                    onSignatureConfirmed = { strokes ->
                        val sigAnnot = Annotation.Signature(
                            pageIndex = currentPageIndex,
                            colorArgb = currentColor.toArgb(),
                            strokeWidth = currentStrokeWidth,
                            strokes = strokes,
                            bounds = NormalizedRect(0.2f, 0.4f, 0.8f, 0.6f)
                        )
                        annotationEngine.addAnnotation(sigAnnot)
                        showSignatureDialog = false
                    },
                    onDismissRequest = { showSignatureDialog = false }
                )
            }

            // ReadEra TTS Settings Sheet
            if (showTtsSettingsDialog) {
                TtsSettingsDialog(
                    speechRate = ttsSettings.speechRate,
                    pitch = ttsSettings.pitch,
                    autoAdvancePage = ttsSettings.autoAdvancePage,
                    currentSleepTimerSeconds = ttsSettings.sleepTimerRemainingSeconds,
                    onSpeechRateChanged = { ttsController.setSpeechRate(it) },
                    onPitchChanged = { ttsController.setPitch(it) },
                    onAutoAdvancePageToggled = { /* toggle auto advance */ },
                    onSleepTimerSet = { ttsController.setSleepTimer(it) },
                    onDismissRequest = { showTtsSettingsDialog = false }
                )
            }

            // ReadEra Voice & Accent Selection Sheet
            if (showVoiceSelectionSheet) {
                VoiceSelectionSheet(
                    voices = availableVoices,
                    selectedVoiceName = ttsSettings.selectedVoiceName,
                    onVoiceSelected = { voice ->
                        ttsController.setVoice(voice.name)
                        showVoiceSelectionSheet = false
                    },
                    onDismissRequest = { showVoiceSelectionSheet = false }
                )
            }
        }
    }
}
