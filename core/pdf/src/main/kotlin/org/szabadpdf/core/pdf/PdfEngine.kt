package org.szabadpdf.core.pdf

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * MuPDF's Java bindings are not safe to call from several threads at once, so every call
 * goes through this single thread. Work from different documents simply queues up.
 */
internal object PdfEngine {
    val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "mupdf").apply { isDaemon = true }
    }
    val dispatcher: CoroutineDispatcher = executor.asCoroutineDispatcher()
}
