package com.novapdf.reader.model

import android.net.Uri

/**
 * Xodo-style Document Tab for multi-document navigation.
 */
data class DocumentTab(
    val id: String,
    val title: String,
    val uri: Uri,
    val currentPageIndex: Int = 0,
    val scrollOffset: Float = 0f,
    val isModified: Boolean = false,
    val lastOpenedTimestamp: Long = System.currentTimeMillis()
)

