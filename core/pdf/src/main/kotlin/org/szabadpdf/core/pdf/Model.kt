package org.szabadpdf.core.pdf

/** Page size in PDF points (1/72 inch), after the page's own rotation is applied. */
data class PageSize(val width: Float, val height: Float)

/** A rectangle in PDF points, measured from the page's top-left corner. */
data class PdfRect(val left: Float, val top: Float, val right: Float, val bottom: Float)

/** Where a tapped link leads. */
sealed interface LinkTarget {
    data class Page(val index: Int) : LinkTarget
    data class External(val uri: String) : LinkTarget
}

/** One table-of-contents entry, flattened. [pageIndex] is -1 when the entry has no page in this document. */
data class OutlineEntry(val title: String, val depth: Int, val pageIndex: Int)
