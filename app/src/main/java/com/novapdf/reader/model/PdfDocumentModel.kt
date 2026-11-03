package com.novapdf.reader.model

import android.net.Uri

/**
 * Represents an open PDF document.
 */
data class PdfDocumentModel(
    val uri: Uri,
    val title: String,
    val pageCount: Int,
    val pageDimensions: List<PageDimension> = emptyList(),
    val outlines: List<OutlineNode> = emptyList(),
    val bookmarks: Set<Int> = emptySet(),
    val isEncrypted: Boolean = false
)

data class PageDimension(
    val pageIndex: Int,
    val width: Int,
    val height: Int,
    val aspectRatio: Float = if (height > 0) width.toFloat() / height.toFloat() else 1f
)

data class OutlineNode(
    val title: String,
    val pageIndex: Int,
    val children: List<OutlineNode> = emptyList()
)

