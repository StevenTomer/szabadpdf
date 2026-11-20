package org.szabadpdf

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray

/** The documents open in tabs, in order, and which one is showing. Kept across restarts. */
class OpenTabs(context: Context) {

    private val prefs = context.getSharedPreferences("open_tabs", Context.MODE_PRIVATE)

    private val _tabs = MutableStateFlow(load())
    val tabs: StateFlow<List<Uri>> = _tabs.asStateFlow()

    private val _active = MutableStateFlow(prefs.getString(KEY_ACTIVE, null)?.let(Uri::parse)?.takeIf { it in _tabs.value })
    val active: StateFlow<Uri?> = _active.asStateFlow()

    /**
     * Shows [uri], adding a tab for it if there isn't one. Returns the tabs that had to be
     * dropped to stay within [MAX_TABS] (the oldest ones).
     */
    fun open(uri: Uri): List<Uri> {
        var list = _tabs.value
        if (uri !in list) list = list + uri
        val dropped = list.dropLast(MAX_TABS).filter { it != uri }
        save(list - dropped.toSet(), uri)
        return dropped
    }

    fun select(uri: Uri) {
        if (uri in _tabs.value) save(_tabs.value, uri)
    }

    /** Closes [uri]'s tab; the neighbouring tab (if any) becomes active. */
    fun close(uri: Uri) {
        val list = _tabs.value
        val index = list.indexOf(uri)
        if (index < 0) return
        val rest = list - uri
        val next = if (_active.value == uri) rest.getOrNull(index) ?: rest.lastOrNull() else _active.value
        save(rest, next)
    }

    /** Moves the tab at position [from] to position [to]. */
    fun move(from: Int, to: Int) {
        val list = _tabs.value.toMutableList()
        if (from !in list.indices || to !in list.indices || from == to) return
        list.add(to, list.removeAt(from))
        save(list, _active.value)
    }

    /** Puts [new] in [old]'s place (e.g. after "save as"). */
    fun replace(old: Uri, new: Uri) {
        val list = _tabs.value.filter { it != new }.map { if (it == old) new else it }
        save(if (new in list) list else list + new, new)
    }

    private fun save(list: List<Uri>, active: Uri?) {
        _tabs.value = list
        _active.value = active
        prefs.edit()
            .putString(KEY_TABS, JSONArray(list.map(Uri::toString)).toString())
            .putString(KEY_ACTIVE, active?.toString())
            .apply()
    }

    private fun load(): List<Uri> = runCatching {
        val json = JSONArray(prefs.getString(KEY_TABS, "[]"))
        List(json.length()) { Uri.parse(json.getString(it)) }
    }.getOrDefault(emptyList())

    private companion object {
        const val KEY_TABS = "tabs"
        const val KEY_ACTIVE = "active"
        const val MAX_TABS = 10
    }
}
