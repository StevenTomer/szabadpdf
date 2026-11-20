package org.szabadpdf

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

data class RecentFile(
    val uri: Uri,
    val name: String,
    val lastPage: Int,
    val openedAt: Long,
)

/** Recently opened documents and the page each was left on. Stored in SharedPreferences. */
class RecentFiles(private val context: Context) {

    private val prefs = context.getSharedPreferences("recent_files", Context.MODE_PRIVATE)
    private val _items = MutableStateFlow(load())
    val items: StateFlow<List<RecentFile>> = _items.asStateFlow()

    fun get(uri: Uri): RecentFile? = _items.value.firstOrNull { it.uri == uri }

    /** Moves [uri] to the top of the list (adding it if new) and returns its entry. */
    fun recordOpened(uri: Uri): RecentFile {
        val existing = get(uri)
        val entry = RecentFile(
            uri = uri,
            name = displayName(uri) ?: existing?.name ?: uri.lastPathSegment ?: "Document",
            lastPage = existing?.lastPage ?: 0,
            openedAt = System.currentTimeMillis(),
        )
        update { listOf(entry) + it.filterNot { f -> f.uri == uri } }
        return entry
    }

    fun updatePage(uri: Uri, page: Int) = update { list ->
        list.map { if (it.uri == uri) it.copy(lastPage = page) else it }
    }

    fun remove(uri: Uri) {
        update { list -> list.filterNot { it.uri == uri } }
        runCatching {
            context.contentResolver.releasePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun update(change: (List<RecentFile>) -> List<RecentFile>) {
        val list = change(_items.value).take(MAX_ITEMS)
        _items.value = list
        val json = JSONArray()
        list.forEach {
            json.put(
                JSONObject()
                    .put("uri", it.uri.toString())
                    .put("name", it.name)
                    .put("page", it.lastPage)
                    .put("opened", it.openedAt),
            )
        }
        prefs.edit().putString(KEY, json.toString()).apply()
    }

    private fun load(): List<RecentFile> = runCatching {
        val json = JSONArray(prefs.getString(KEY, "[]"))
        List(json.length()) { i ->
            val o = json.getJSONObject(i)
            RecentFile(Uri.parse(o.getString("uri")), o.getString("name"), o.optInt("page"), o.optLong("opened"))
        }
    }.getOrDefault(emptyList())

    private fun displayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    private companion object {
        const val KEY = "items"
        const val MAX_ITEMS = 50
    }
}
