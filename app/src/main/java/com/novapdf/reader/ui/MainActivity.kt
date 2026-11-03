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
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.novapdf.reader.engine.AnnotationEngine
import com.novapdf.reader.engine.PdfEngine
import com.novapdf.reader.engine.PdfTextExtractor
import com.novapdf.reader.model.DocumentTab
import com.novapdf.reader.model.PdfDocumentModel
import com.novapdf.reader.tts.TtsController
import com.novapdf.reader.ui.theme.NovaPDFTheme
import com.novapdf.reader.ui.viewer.PdfViewerScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

class MainActivity : ComponentActivity() {

    private lateinit var pdfEngine: PdfEngine
    private lateinit var textExtractor: PdfTextExtractor
    private lateinit var annotationEngine: AnnotationEngine
    private lateinit var ttsController: TtsController

    private val documentTabs = mutableStateListOf<DocumentTab>()
    private val activeTabId = mutableStateOf<String?>(null)
    private val activeDocument = mutableStateOf<PdfDocumentModel?>(null)
    private val isLoading = mutableStateOf(true)

    // Storage Access Framework PDF Picker
    private val openPdfLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { openPdfUri(it) }
    }

    // Notification Permission Launcher (Android 13+)
    private val requestNotificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* Permission handled */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        pdfEngine = PdfEngine(applicationContext)
        textExtractor = PdfTextExtractor(applicationContext)
        annotationEngine = AnnotationEngine()
        ttsController = TtsController(applicationContext, lifecycleScope)

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
                    PdfViewerScreen(
                        document = doc,
                        tabs = documentTabs.toList(),
                        activeTabId = activeTabId.value,
                        pdfEngine = pdfEngine,
                        textExtractor = textExtractor,
                        annotationEngine = annotationEngine,
                        ttsController = ttsController,
                        onTabSelected = { tab ->
                            activeTabId.value = tab.id
                            openPdfUri(tab.uri, existingTabId = tab.id)
                        },
                        onTabClosed = { tab ->
                            closeTab(tab)
                        },
                        onNewTabRequested = {
                            openPdfLauncher.launch(arrayOf("application/pdf"))
                        },
                        onOpenPdfPickerRequested = {
                            openPdfLauncher.launch(arrayOf("application/pdf"))
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
            // Load or create default showcase PDF document
            lifecycleScope.launch {
                val sampleUri = getOrCreateSampleDocument()
                openPdfUri(sampleUri)
            }
        }
    }

    private fun openPdfUri(uri: Uri, existingTabId: String? = null) {
        isLoading.value = true
        lifecycleScope.launch {
            try {
                val doc = pdfEngine.openDocument(uri)
                activeDocument.value = doc

                val tabId = existingTabId ?: UUID.randomUUID().toString()
                if (existingTabId == null) {
                    val newTab = DocumentTab(
                        id = tabId,
                        title = doc.title,
                        uri = uri
                    )
                    documentTabs.add(newTab)
                }
                activeTabId.value = tabId
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                isLoading.value = false
            }
        }
    }

    private fun closeTab(tab: DocumentTab) {
        val index = documentTabs.indexOfFirst { it.id == tab.id }
        if (index != -1) {
            documentTabs.removeAt(index)
            if (activeTabId.value == tab.id) {
                if (documentTabs.isNotEmpty()) {
                    val nextTab = documentTabs.last()
                    activeTabId.value = nextTab.id
                    openPdfUri(nextTab.uri, existingTabId = nextTab.id)
                } else {
                    activeDocument.value = null
                    activeTabId.value = null
                }
            }
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
        val sampleFile = File(cacheDir, "NovaPDF_User_Guide.pdf")
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

            // Page 1: Overview & ReadEra TTS
            val page1Info = PdfDocument.PageInfo.Builder(595, 842, 1).create() // A4 points
            val page1 = doc.startPage(page1Info)
            val canvas1 = page1.canvas
            canvas1.drawText("NovaPDF Reader & Annotator", 40f, 60f, titlePaint)
            canvas1.drawText("Designed for Mobile, Tablets & Foldables", 40f, 85f, subtitlePaint)

            val p1Lines = listOf(
                "Welcome to NovaPDF! This app combines the professional markup tools of Xodo",
                "with the cutting-edge Text-to-Speech (TTS) reading experience of ReadEra.",
                "",
                "1. Read Out Loud (ReadEra-Style TTS):",
                "   • Tap the 'Read Out Loud' icon on the toolbar to begin listening.",
                "   • Tap directly on any sentence on this page to jump speech to that line instantly.",
                "   • Notice how the active sentence glows with an amber highlight as it is spoken.",
                "   • Use the bottom control bar to adjust reading speed from 0.5x up to 3.0x smoothly.",
                "   • Pick different voices and international accents (US, UK, Indian, Canadian English, etc.).",
                "   • Playback continues uninterrupted in the background and from your lock screen.",
                "",
                "2. Xodo-Grade Stylus & Annotation Suite:",
                "   • Pen & Stylus: Low-latency ink with palm rejection and Bézier curve smoothing.",
                "   • Highlighter: Snaps or draws smoothly with Multiply blending mode.",
                "   • Shapes: Draw Rectangles, Ovals, Arrows, and Lines.",
                "   • Signatures: Vector signature capture dialog to sign contracts with your finger or S-Pen.",
                "   • Eraser, Undo, Redo, and Document Tabs for desktop-grade productivity."
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
                "NovaPDF adapts dynamically to your screen size and orientation:",
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
    }
}

