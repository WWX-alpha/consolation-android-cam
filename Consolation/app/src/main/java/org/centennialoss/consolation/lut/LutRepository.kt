package org.centennialoss.consolation.lut

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.provider.OpenableColumns
import org.centennialoss.consolation.R
import java.io.IOException

/** What the preview should colour-grade with; encoded into the `lut_selected` preference. */
sealed interface LutSelection {
    data object None : LutSelection
    data class Builtin(val fileName: String) : LutSelection
    data class Imported(val uri: String) : LutSelection

    fun encode(): String = when (this) {
        None -> PREF_NONE
        is Builtin -> "asset:$fileName"
        is Imported -> "uri:$uri"
    }

    companion object {
        const val PREF_NONE = "none"
        private const val ASSET_PREFIX = "asset:"
        private const val URI_PREFIX = "uri:"

        fun decode(raw: String?): LutSelection = when {
            raw.isNullOrBlank() || raw == PREF_NONE -> None
            raw.startsWith(ASSET_PREFIX) -> Builtin(raw.removePrefix(ASSET_PREFIX))
            raw.startsWith(URI_PREFIX) -> Imported(raw.removePrefix(URI_PREFIX))
            else -> None
        }
    }
}

/** A user-imported .cube, persisted as `displayName|uri` in the `lut_imported` preference. */
data class ImportedLut(val displayName: String, val uri: String)

/**
 * Built-in assets under `assets/luts` plus user-imported .cube files, with a one-entry parse cache.
 * [load] touches the asset/content stream, so call it off the main thread.
 */
class LutRepository(
    context: Context,
    private val prefs: SharedPreferences,
) {
    data class Entry(val selection: LutSelection, val title: String)

    private val appContext = context.applicationContext
    private val cacheLock = Any()
    private var cachedSelection: LutSelection? = null
    private var cachedLut: CubeLut? = null

    /** `none` followed by built-ins sorted by file name, then imported entries. */
    fun entries(): List<Entry> {
        val builtins = builtinFileNames().map { Entry(LutSelection.Builtin(it), builtinTitle(it)) }
        val imported = readImported(prefs).map { Entry(LutSelection.Imported(it.uri), it.displayName) }
        return listOf(Entry(LutSelection.None, appContext.getString(R.string.lut_none))) + builtins + imported
    }

    /** Records a SAF document; the caller must have taken the persistable read permission first. */
    fun importCube(uri: Uri): ImportedLut {
        val entry = ImportedLut(displayNameOf(uri), uri.toString())
        val current = readImported(prefs).filterNot { it.uri == entry.uri }
        writeImported(prefs, current + entry)
        invalidate()
        return entry
    }

    @Throws(IOException::class)
    fun load(selection: LutSelection): CubeLut {
        synchronized(cacheLock) {
            val cached = cachedLut
            if (cached != null && selection == cachedSelection) return cached
        }
        val lut = when (selection) {
            LutSelection.None -> throw IllegalArgumentException("no LUT selected")
            is LutSelection.Builtin -> appContext.assets.open("$LUT_ASSET_DIR/${selection.fileName}").use {
                CubeLut.parse(it)
            }
            is LutSelection.Imported -> {
                val stream = appContext.contentResolver.openInputStream(Uri.parse(selection.uri))
                    ?: throw IOException("cannot open ${selection.uri}")
                stream.use { CubeLut.parse(it) }
            }
        }
        synchronized(cacheLock) {
            cachedSelection = selection
            cachedLut = lut
        }
        return lut
    }

    fun invalidate() {
        synchronized(cacheLock) {
            cachedSelection = null
            cachedLut = null
        }
    }

    private fun builtinFileNames(): List<String> =
        runCatching {
            appContext.assets.list(LUT_ASSET_DIR)
                ?.filter { it.endsWith(CUBE_SUFFIX, ignoreCase = true) }
                ?.sorted()
                .orEmpty()
        }.getOrDefault(emptyList())

    private fun displayNameOf(uri: Uri): String {
        val fromProvider = runCatching {
            appContext.contentResolver
                .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        }.getOrNull()
        return fromProvider?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment?.takeIf { it.isNotBlank() }
            ?: uri.toString()
    }

    /** Drops the `_33_FF_Ver.x.y` suffix and splits CamelCase so asset names read as labels. */
    private fun builtinTitle(fileName: String): String {
        val base = if (fileName.endsWith(CUBE_SUFFIX, ignoreCase = true)) {
            fileName.substring(0, fileName.length - CUBE_SUFFIX.length)
        } else {
            fileName
        }
        return base
            .substringBefore("_Ver.")
            .replace(Regex("_\\d{1,3}(_FF)?$"), "")
            .replace('_', ' ')
            .replace(Regex("([a-z])([A-Z])"), "$1 $2")
            .trim()
    }

    companion object {
        const val LUT_ASSET_DIR = "luts"
        const val CUBE_SUFFIX = ".cube"
        const val KEY_LUT_IMPORTED = "lut_imported"

        fun readImported(prefs: SharedPreferences): List<ImportedLut> {
            val raw = prefs.getString(KEY_LUT_IMPORTED, null) ?: return emptyList()
            return raw.lineSequence().mapNotNull { line ->
                val separator = line.lastIndexOf('|')
                if (separator <= 0 || separator == line.length - 1) return@mapNotNull null
                ImportedLut(line.substring(0, separator), line.substring(separator + 1))
            }.toList()
        }

        fun writeImported(prefs: SharedPreferences, entries: List<ImportedLut>) {
            val encoded = entries.joinToString("\n") { "${it.displayName}|${it.uri}" }
            prefs.edit().putString(KEY_LUT_IMPORTED, encoded).apply()
        }
    }
}
