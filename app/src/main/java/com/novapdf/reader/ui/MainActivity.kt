package com.novapdf.reader.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.novapdf.reader.engine.AnnotationEngine
import com.novapdf.reader.engine.DocumentStorageManager
import com.novapdf.reader.engine.PdfEngine
import com.novapdf.reader.engine.PdfTextExtractor
import com.novapdf.reader.model.DocumentTab
import com.novapdf.reader.model.PdfDocumentModel
import com.novapdf.reader.tts.TtsController
import com.novapdf.reader.ui.theme.NovaPDFTheme
import com.novapdf.reader.ui.viewer.PdfViewerScreen
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

class MainActivity : ComponentActivity() {

    private val tag = "NovaPDF_Main"

    private lateinit var pdfEngine: PdfEngine
    private lateinit var textExtractor: PdfTextExtractor
    private lateinit var annotationEngine: AnnotationEngine
    private lateinit var ttsController: TtsController
    private lateinit var documentStorageManager: DocumentStorageManager

    private val documentTabs = mutableStateListOf<DocumentTab>()
    private val activeTabId = mutableStateOf<String?>(null)
    private val activeDocument = mutableStateOf<PdfDocumentModel?>(null)
    private val isLoading = mutableStateOf(true)

    // Storage Access Framework PDF Picker
    private val openPdfLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            try {
                val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                contentResolver.takePersistableUriPermission(it, takeFlags)
            } catch (e: Exception) {
                Log.w(tag, "Could not take persistable permission for $it", e)
            }
            openPdfUri(it)
        }
    }

    // Notification Permission Launcher (Android 13+)
    private val requestNotificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* Permission handled */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        try {
            PDFBoxResourceLoader.init(applicationContext)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        pdfEngine = PdfEngine(applicationContext)
        textExtractor = PdfTextExtractor(applicationContext)
        annotationEngine = AnnotationEngine()
        ttsController = TtsController(applicationContext, lifecycleScope)
        documentStorageManager = DocumentStorageManager(applicationContext)

        checkNotificationPermissions()

        setContent {
            NovaPDFTheme {
                val doc = activeDocument.value
                val loading = isLoading.value

                if (loading || doc == null) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                } else {
                    val currentTabId = activeTabId.value
                    val initialPage = if (currentTabId != null) {
                        documentStorageManager.getPagePosition(currentTabId, 0)
                    } else {
                        0
                    }

                    PdfViewerScreen(
                        document = doc,
                        tabs = documentTabs.toList(),
                        activeTabId = currentTabId,
                        initialPageIndex = initialPage,
                        pdfEngine = pdfEngine,
                        textExtractor = textExtractor,
                        annotationEngine = annotationEngine,
                        ttsController = ttsController,
                        onTabSelected = { tab ->
                            activeTabId.value = tab.id
                            openPdfUri(tab.uri, existingTabId = tab.id, initialPage = tab.currentPageIndex)
                        },
                        onTabClosed = { tab ->
                            closeTab(tab)
                        },
                        onNewTabRequested = {
                            openPdfLauncher.launch(arrayOf("application/pdf"))
                        },
                        onOpenPdfPickerRequested = {
                            openPdfLauncher.launch(arrayOf("application/pdf"))
                        },
                        onPageChanged = { pageIndex ->
                            activeTabId.value?.let { currentId ->
                                documentStorageManager.savePagePosition(currentId, pageIndex)
                                val idx = documentTabs.indexOfFirst { it.id == currentId }
                                if (idx != -1) {
                                    documentTabs[idx] = documentTabs[idx].copy(currentPageIndex = pageIndex)
                                }
                            }
                        },
                        onFullScreenToggled = { isFullScreen ->
                            setSystemBarsVisible(!isFullScreen)
                        }
                    )
                }
            }
        }

        handleIncomingIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        val uri = intent?.data
        if (uri != null) {
            openPdfUri(uri)
        } else {
            // Restore previously saved tabs from memory storage
            val (savedTabs, lastActiveId) = documentStorageManager.loadSavedTabs()
            if (savedTabs.isNotEmpty()) {
                documentTabs.clear()
                documentTabs.addAll(savedTabs)
                val targetTab = savedTabs.find { it.id == lastActiveId } ?: savedTabs.first()
                activeTabId.value = targetTab.id
                openPdfUri(targetTab.uri, existingTabId = targetTab.id, initialPage = targetTab.currentPageIndex)
            } else {
                // First-time user: generate and open the sample showcase guide
                lifecycleScope.launch {
                    val sampleUri = getOrCreateSampleDocument()
                    openPdfUri(sampleUri)
                }
            }
        }
    }

    private fun openPdfUri(uri: Uri, existingTabId: String? = null, initialPage: Int = 0) {
        isLoading.value = true
        lifecycleScope.launch {
            try {
                val tabId = existingTabId ?: UUID.randomUUID().toString()

                // Persist the document locally so it NEVER goes away when reopening the app
                val persistentUri = documentStorageManager.makeDocumentPersistent(tabId, uri)

                textExtractor.close()
                val doc = pdfEngine.openDocument(persistentUri)
                activeDocument.value = doc

                if (existingTabId == null) {
                    val newTab = DocumentTab(
                        id = tabId,
                        title = doc.title,
                        uri = persistentUri,
                        currentPageIndex = initialPage
                    )
                    documentTabs.add(newTab)
                } else {
                    val idx = documentTabs.indexOfFirst { it.id == tabId }
                    if (idx != -1) {
                        documentTabs[idx] = documentTabs[idx].copy(
                            uri = persistentUri,
                            currentPageIndex = initialPage
                        )
                    }
                }
                activeTabId.value = tabId

                // Persist tabs state to storage
                documentStorageManager.saveTabs(documentTabs.toList(), tabId)
            } catch (e: Exception) {
                Log.e(tag, "Failed to open PDF $uri", e)
            } finally {
                isLoading.value = false
            }
        }
    }

    private fun closeTab(tab: DocumentTab) {
        val index = documentTabs.indexOfFirst { it.id == tab.id }
        if (index != -1) {
            documentTabs.removeAt(index)
            documentStorageManager.deleteDocumentForTab(tab.id)

            if (activeTabId.value == tab.id) {
                if (documentTabs.isNotEmpty()) {
                    val nextTab = documentTabs.last()
                    activeTabId.value = nextTab.id
                    openPdfUri(nextTab.uri, existingTabId = nextTab.id, initialPage = nextTab.currentPageIndex)
                } else {
                    activeDocument.value = null
                    activeTabId.value = null
                }
            }
            documentStorageManager.saveTabs(documentTabs.toList(), activeTabId.value)
        }
    }

    fun setSystemBarsVisible(visible: Boolean) {
        val decorView = window?.decorView ?: return
        val insetsController = WindowCompat.getInsetsController(window, decorView)
        insetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (visible) {
            insetsController.show(WindowInsetsCompat.Type.systemBars())
        } else {
            insetsController.hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun checkNotificationPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    /**
     * Generates a sample multi-page PDF document with rich text so users can immediately test
     * viewing, annotating, and the ReadEra TTS speech engine without having to manually transfer files.
     */
    private suspend fun getOrCreateSampleDocument(): Uri = withContext(Dispatchers.IO) {
        val sampleFile = File(cacheDir, "xouryn_user_guide.pdf")
        if (!sampleFile.exists()) {
            val doc = PdfDocument()
            val paint = Paint().apply {
                color = Color.BLACK
                textSize = 14f
                isAntiAlias = true
            }
            val titlePaint = Paint().apply {
                color = Color.rgb(25, 118, 210) // BluePrimary
                textSize = 22f
                isFakeBoldText = true
                isAntiAlias = true
            }
            val subtitlePaint = Paint().apply {
                color = Color.DKGRAY
                textSize = 12f
                isAntiAlias = true
            }

            // Page 1: Welcome & Overview
            val page1Info = PdfDocument.PageInfo.Builder(595, 842, 1).create()
            val page1 = doc.startPage(page1Info)
            val canvas1 = page1.canvas
            canvas1.drawText("xouryn pdf reader", 40f, 60f, titlePaint)
            canvas1.drawText("High-Performance Android PDF Workspace", 40f, 85f, subtitlePaint)

            val p1Lines = listOf(
                "Welcome to xouryn pdf reader! This app brings professional desktop-class PDF tools",
                "to Android smartphones, tablets, foldables, and Chromebooks.",
                "",
                "Key Capabilities:",
                "• Read Out Loud (TTS Engine): Tap any sentence to listen in human-like voices.",
                "  Customize accent, pitch, and speed. Includes background playback and sleep timer.",
                "• Vector Annotations: Draw with pen, text highlighter, rectangles, ovals, arrows,",
                "  underlines, strikeouts, sticky notes, and digital signatures with full Undo/Redo.",
                "• Smooth Fullscreen: Tap anywhere on the page to go completely fullscreen,",
                "  hiding all status and toolbars. Tap again to bring them back.",
                "• Smart Persistent Memory: All opened tabs and your exact reading positions",
                "  are automatically remembered and restored when you reopen the app.",
                "• Infinite Long Document Support: Optimized for 600+ page books with zero lag",
                "  and rock-solid memory management."
            )
            var y = 130f
            p1Lines.forEach { line ->
                canvas1.drawText(line, 40f, y, paint)
                y += 24f
            }
            doc.finishPage(page1)

            // Page 2: Tablet Optimization & Architecture
            val page2Info = PdfDocument.PageInfo.Builder(595, 842, 2).create()
            val page2 = doc.startPage(page2Info)
            val canvas2 = page2.canvas
            canvas2.drawText("Tablet & Large Screen Optimization", 40f, 60f, titlePaint)
            canvas2.drawText("Dual-Page Spread & Multi-Window Multitasking", 40f, 85f, subtitlePaint)

            val p2Lines = listOf(
                "Tablets and foldables offer magnificent canvas area for document editing.",
                "xouryn pdf reader adapts dynamically to your screen size and orientation:",
                "",
                "• Dual-Page Book Spread: On tablets in landscape mode, pages appear side-by-side",
                "  just like reading a printed physical book or open binder.",
                "• Document Tabs: Switch between multiple research papers or contracts instantly",
                "  using the top document tab bar, matching desktop PDF workflows.",
                "• Stylus S-Pen Awareness: When drawing with an active stylus, finger touches are",
                "  reserved for panning and zooming, completely eliminating accidental palm marks.",
                "• Night & Sepia Themes: Switch reading modes for late night study sessions.",
                "",
                "Reverse-Engineered with REA (Reverse Engineer Anything) principles for",
                "maximum efficiency, clean Kotlin coroutines, and modular AndroidX architecture."
            )
            y = 130f
            p2Lines.forEach { line ->
                canvas2.drawText(line, 40f, y, paint)
                y += 24f
            }
            doc.finishPage(page2)

            FileOutputStream(sampleFile).use { out ->
                doc.writeTo(out)
            }
            doc.close()
        }
        Uri.fromFile(sampleFile)
    }

    override fun onDestroy() {
        super.onDestroy()
        ttsController.destroy()
        pdfEngine.close()
        textExtractor.close()
    }
}
