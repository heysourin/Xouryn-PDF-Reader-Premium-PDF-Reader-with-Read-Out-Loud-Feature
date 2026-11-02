package com.novapdf.reader.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
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

    private var fileDescriptor: ParcelFileDescriptor? = null
    private var renderer: PdfRenderer? = null
    private val rendererMutex = Mutex()

    // 64 MB Bitmap LRU Cache for high-performance instant page recall
    private val maxMemory = (Runtime.getRuntime().maxMemory() / 1024).toInt()
    private val cacheSize = (maxMemory / 8).coerceAtLeast(32 * 1024)
    private val bitmapCache = object : LruCache<String, Bitmap>(cacheSize) {
        override fun sizeOf(key: String, bitmap: Bitmap): Int {
            return bitmap.byteCount / 1024
        }
    }

    suspend fun openDocument(uri: Uri): PdfDocumentModel = withContext(Dispatchers.IO) {
        close()

        val pfd = if (uri.scheme == "file") {
            ParcelFileDescriptor.open(File(uri.path!!), ParcelFileDescriptor.MODE_READ_ONLY)
        } else {
            // For content URIs, open input stream and copy to cache if needed or open direct file descriptor
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

            for (i in 0 until pageCount) {
                val page = pdfRenderer.openPage(i)
                dimensions.add(
                    PageDimension(
                        pageIndex = i,
                        width = page.width,
                        height = page.height
                    )
                )
                page.close()
            }

            val docTitle = uri.lastPathSegment ?: "Document.pdf"
            PdfDocumentModel(
                uri = uri,
                title = docTitle,
                pageCount = pageCount,
                pageDimensions = dimensions
            )
        }
    }

    private fun copyUriToCacheAndOpen(uri: Uri): ParcelFileDescriptor {
        val tempFile = File(context.cacheDir, "current_doc_${System.currentTimeMillis()}.pdf")
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(tempFile).use { output ->
                input.copyTo(output)
            }
        }
        return ParcelFileDescriptor.open(tempFile, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    suspend fun renderPage(
        pageIndex: Int,
        targetWidth: Int,
        targetHeight: Int,
        highRes: Boolean = false
    ): Bitmap? = withContext(Dispatchers.Default) {
        val cacheKey = "${pageIndex}_${targetWidth}x${targetHeight}_${highRes}"
        bitmapCache.get(cacheKey)?.let { return@withContext it }

        rendererMutex.withLock {
            val currentRenderer = renderer ?: return@withContext null
            if (pageIndex < 0 || pageIndex >= currentRenderer.pageCount) return@withContext null

            var page: PdfRenderer.Page? = null
            try {
                page = currentRenderer.openPage(pageIndex)
                val scale = if (highRes) 2.0f else 1.5f
                val bmpWidth = ((targetWidth.takeIf { it > 0 } ?: page.width) * scale).toInt().coerceAtLeast(1)
                val bmpHeight = ((targetHeight.takeIf { it > 0 } ?: page.height) * scale).toInt().coerceAtLeast(1)

                val bitmap = Bitmap.createBitmap(bmpWidth, bmpHeight, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(Color.WHITE)

                val matrix = Matrix().apply {
                    val scaleX = bmpWidth.toFloat() / page.width.toFloat()
                    val scaleY = bmpHeight.toFloat() / page.height.toFloat()
                    setScale(scaleX, scaleY)
                }

                page.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bitmapCache.put(cacheKey, bitmap)
                bitmap
            } catch (e: Exception) {
                e.printStackTrace()
                null
            } finally {
                page?.close()
            }
        }
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
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
