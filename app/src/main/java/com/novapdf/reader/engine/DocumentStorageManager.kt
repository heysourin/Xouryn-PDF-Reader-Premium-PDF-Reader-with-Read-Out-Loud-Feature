package com.novapdf.reader.engine

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.novapdf.reader.model.DocumentTab
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

data class SavedDocumentTab(
    val id: String,
    val title: String,
    val uriString: String,
    val localFilePath: String? = null,
    val currentPageIndex: Int = 0,
    val lastOpenedTimestamp: Long = System.currentTimeMillis()
)

class DocumentStorageManager(private val context: Context) {

    private val tag = "DocumentStorageManager"
    private val prefs: SharedPreferences = context.getSharedPreferences("novapdf_document_storage", Context.MODE_PRIVATE)
    private val gson = Gson()

    private val storageDir: File by lazy {
        File(context.filesDir, "saved_documents").apply {
            if (!exists()) mkdirs()
        }
    }

    /**
     * Efficiently makes document persistent without redundant copying.
     * Always executes file I/O safely on background dispatcher.
     */
    suspend fun makeDocumentPersistent(tabId: String, sourceUri: Uri): Uri = withContext(Dispatchers.IO) {
        try {
            // 1. If it's already a local file that exists, reuse it immediately (0ms)
            if (sourceUri.scheme == "file") {
                val existing = File(sourceUri.path ?: "")
                if (existing.exists() && existing.length() > 0L) {
                    return@withContext sourceUri
                }
            }

            // 2. If it was already persisted for this tab, reuse it immediately (0ms)
            val destFile = File(storageDir, "doc_${tabId}.pdf")
            if (destFile.exists() && destFile.length() > 0L) {
                return@withContext Uri.fromFile(destFile)
            }

            // 3. Otherwise copy stream to persistent storage
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output, bufferSize = 64 * 1024)
                }
            }
            if (destFile.exists() && destFile.length() > 0L) {
                Uri.fromFile(destFile)
            } else {
                sourceUri
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to persist document locally for $sourceUri", e)
            sourceUri
        }
    }

    /**
     * Saves the current open tabs and active tab ID.
     */
    fun saveTabs(tabs: List<DocumentTab>, activeTabId: String?) {
        try {
            val savedList = tabs.map { tab ->
                val localPath = if (tab.uri.scheme == "file") tab.uri.path else null
                SavedDocumentTab(
                    id = tab.id,
                    title = tab.title,
                    uriString = tab.uri.toString(),
                    localFilePath = localPath,
                    currentPageIndex = getPagePosition(tab.id, tab.currentPageIndex),
                    lastOpenedTimestamp = tab.lastOpenedTimestamp
                )
            }
            val json = gson.toJson(savedList)
            prefs.edit()
                .putString("saved_tabs", json)
                .putString("active_tab_id", activeTabId)
                .apply()
        } catch (e: Exception) {
            Log.e(tag, "Error saving tabs", e)
        }
    }

    /**
     * Loads previously saved document tabs.
     */
    fun loadSavedTabs(): Pair<List<DocumentTab>, String?> {
        val json = prefs.getString("saved_tabs", null) ?: return Pair(emptyList(), null)
        val activeId = prefs.getString("active_tab_id", null)

        return try {
            val type = object : TypeToken<List<SavedDocumentTab>>() {}.type
            val list: List<SavedDocumentTab> = gson.fromJson(json, type) ?: emptyList()

            val tabs = list.mapNotNull { saved ->
                val uri = if (saved.localFilePath != null && File(saved.localFilePath).exists()) {
                    Uri.fromFile(File(saved.localFilePath))
                } else {
                    val localFallback = File(storageDir, "doc_${saved.id}.pdf")
                    if (localFallback.exists() && localFallback.length() > 0L) {
                        Uri.fromFile(localFallback)
                    } else {
                        try {
                            Uri.parse(saved.uriString)
                        } catch (e: Exception) {
                            null
                        }
                    }
                }

                if (uri != null) {
                    DocumentTab(
                        id = saved.id,
                        title = saved.title,
                        uri = uri,
                        currentPageIndex = saved.currentPageIndex,
                        lastOpenedTimestamp = saved.lastOpenedTimestamp
                    )
                } else {
                    null
                }
            }

            Pair(tabs, activeId)
        } catch (e: Exception) {
            Log.e(tag, "Error loading saved tabs", e)
            Pair(emptyList(), null)
        }
    }

    /**
     * Persists the current reading page position for a tab.
     */
    fun savePagePosition(tabId: String, pageIndex: Int) {
        prefs.edit().putInt("page_pos_${tabId}", pageIndex).apply()
    }

    fun getPagePosition(tabId: String, fallback: Int = 0): Int {
        return prefs.getInt("page_pos_${tabId}", fallback)
    }

    /**
     * Cleans up local cached document file when user closes a tab.
     */
    fun deleteDocumentForTab(tabId: String) {
        try {
            val file = File(storageDir, "doc_${tabId}.pdf")
            if (file.exists()) {
                file.delete()
            }
            prefs.edit().remove("page_pos_${tabId}").apply()
        } catch (e: Exception) {
            Log.e(tag, "Error deleting document for tab $tabId", e)
        }
    }
}
