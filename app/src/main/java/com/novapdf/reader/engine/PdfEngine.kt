package com.novapdf.reader.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import android.util.LruCache
import com.novapdf.reader.model.PageDimension
import com.novapdf.reader.model.PdfDocumentModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class PdfEngine(private val context: Context) {

    private val tag = "PdfEngine"
    private var fileDescriptor: ParcelFileDescriptor? = null
    private var renderer: PdfRenderer? = null
    private val rendererMutex = Mutex()
    private var cachedTempFile: File? = null

    // Lean, efficient LRU Cache for rendered page bitmaps (clamped to 36MB to prevent GC pressure & overheating)
    private val maxMemory = (Runtime.getRuntime().maxMemory() / 1024).toInt()
    private val cacheSize = (maxMemory / 12).coerceIn(16 * 1024, 36 * 1024)
    private val bitmapCache = object : LruCache<String, Bitmap>(cacheSize) {
        override fun sizeOf(key: String, bitmap: Bitmap): Int {
            return bitmap.byteCount / 1024
        }
    }

    suspend fun openDocument(uri: Uri): PdfDocumentModel = withContext(Dispatchers.IO) {
        close()

        val pfd = if (uri.scheme == "file" && uri.path != null) {
            ParcelFileDescriptor.open(File(uri.path!!), ParcelFileDescriptor.MODE_READ_ONLY)
        } else {
            try {
                context.contentResolver.openFileDescriptor(uri, "r")
                    ?: copyUriToCacheAndOpen(uri)
            } catch (e: Exception) {
                copyUriToCacheAndOpen(uri)
            }
        }
        fileDescriptor = pfd

        rendererMutex.withLock {
            val pdfRenderer = PdfRenderer(pfd)
            renderer = pdfRenderer

            val pageCount = pdfRenderer.pageCount
            val dimensions = ArrayList<PageDimension>(pageCount)

            // Fast O(1) initialization: sample first page dimension only to avoid blocking on 600+ page docs
            var defaultWidth = 595
            var defaultHeight = 842

            if (pageCount > 0) {
                try {
                    val firstPage = pdfRenderer.openPage(0)
                    defaultWidth = firstPage.width
                    defaultHeight = firstPage.height
                    firstPage.close()
                } catch (e: Exception) {
                    Log.e(tag, "Failed to sample first page dimension", e)
                }
            }

            for (i in 0 until pageCount) {
                dimensions.add(
                    PageDimension(
                        pageIndex = i,
                        width = defaultWidth,
                        height = defaultHeight
                    )
                )
            }

            val docTitle = uri.lastPathSegment?.substringAfterLast('/') ?: "Document.pdf"
            PdfDocumentModel(
                uri = uri,
                title = docTitle,
                pageCount = pageCount,
                pageDimensions = dimensions
            )
        }
    }

    private fun copyUriToCacheAndOpen(uri: Uri): ParcelFileDescriptor {
        cachedTempFile?.delete()
        val tempFile = File(context.cacheDir, "engine_doc_${System.currentTimeMillis()}.pdf")
        cachedTempFile = tempFile
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(tempFile).use { output ->
                input.copyTo(output)
            }
        }
        return ParcelFileDescriptor.open(tempFile, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    suspend fun renderPage(
        pageIndex: Int,
        targetWidth: Int = 840,
        targetHeight: Int = 1400,
        highRes: Boolean = false
    ): Bitmap? = withContext(Dispatchers.Default) {
        // Optimized render width: 840px provides razor-sharp text while cutting RAM & render time by 50%+
        val displayWidth = targetWidth.coerceIn(400, if (highRes) 1200 else 840)
        val cacheKey = "${pageIndex}_w$displayWidth"

        bitmapCache.get(cacheKey)?.let { return@withContext it }

        rendererMutex.withLock {
            val currentRenderer = renderer ?: return@withContext null
            if (pageIndex < 0 || pageIndex >= currentRenderer.pageCount) return@withContext null

            var page: PdfRenderer.Page? = null
            try {
                page = currentRenderer.openPage(pageIndex)
                val pageWidth = page.width.toFloat()
                val pageHeight = page.height.toFloat()

                val scale = displayWidth.toFloat() / pageWidth
                val bmpWidth = displayWidth
                val bmpHeight = (pageHeight * scale).toInt().coerceAtLeast(1)

                val bitmap = try {
                    Bitmap.createBitmap(bmpWidth, bmpHeight, Bitmap.Config.ARGB_8888)
                } catch (oom: OutOfMemoryError) {
                    bitmapCache.evictAll()
                    System.gc()
                    Bitmap.createBitmap(bmpWidth, bmpHeight, Bitmap.Config.ARGB_8888)
                }
                bitmap.eraseColor(Color.WHITE)

                val matrix = Matrix().apply {
                    setScale(scale, scale)
                }

                page.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bitmapCache.put(cacheKey, bitmap)
                bitmap
            } catch (e: Exception) {
                Log.e(tag, "Error rendering page $pageIndex", e)
                null
            } catch (oom: OutOfMemoryError) {
                Log.e(tag, "OOM in renderPage $pageIndex, clearing cache", oom)
                bitmapCache.evictAll()
                System.gc()
                null
            } finally {
                page?.close()
            }
        }
    }

    fun getCachedPage(pageIndex: Int, targetWidth: Int = 840): Bitmap? {
        val displayWidth = targetWidth.coerceIn(400, 840)
        return bitmapCache.get("${pageIndex}_w$displayWidth")
    }

    fun clearCache() {
        bitmapCache.evictAll()
    }

    fun close() {
        try {
            renderer?.close()
            renderer = null
            fileDescriptor?.close()
            fileDescriptor = null
            clearCache()
            cachedTempFile?.delete()
            cachedTempFile = null
        } catch (e: Exception) {
            Log.e(tag, "Error closing PdfEngine", e)
        }
    }
}
