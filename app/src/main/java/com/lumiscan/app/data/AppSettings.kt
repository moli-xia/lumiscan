package com.lumiscan.app.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import java.io.File
import java.util.UUID

enum class ExportQuality(val label: String, val maxSide: Int, val jpeg: Int) {
    STANDARD("标准", 1600, 80),
    HIGH("高清", 2400, 90),
    ORIGINAL("超清原图", 4000, 95),
}

/** Observable app preferences backed by SharedPreferences. */
class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var defaultFilter by mutableStateOf(
        runCatching { FilterType.valueOf(prefs.getString("filter", null)!!) }.getOrDefault(FilterType.ENHANCE)
    )
        private set
    var autoCrop by mutableStateOf(prefs.getBoolean("autoCrop", true))
        private set
    var exportQuality by mutableStateOf(
        runCatching { ExportQuality.valueOf(prefs.getString("quality", null)!!) }.getOrDefault(ExportQuality.HIGH)
    )
        private set

    /**
     * The user agreed to the current privacy policy and terms (asked before anything goes online,
     * and again after a material change: [PRIVACY_VERSION]).
     */
    var privacyAccepted by mutableStateOf(prefs.getInt("privacyVersion", 0) >= PRIVACY_VERSION)
        private set

    /** Agreed to an earlier version, so the notice says what changed. */
    val privacyUpdated: Boolean
        get() = prefs.getBoolean("privacyAccepted", false) || prefs.getInt("privacyVersion", 0) > 0

    /** Hash of the last announcement shown, so each one pops up only once. */
    var seenAnnouncement: Int
        get() = prefs.getInt("seenAnnouncement", 0)
        set(v) = prefs.edit { putInt("seenAnnouncement", v) }

    /** Newest optional update the user chose to skip. */
    var skippedVersion: Int
        get() = prefs.getInt("skippedVersion", 0)
        set(v) = prefs.edit { putInt("skippedVersion", v) }

    fun acceptPrivacy() {
        privacyAccepted = true; prefs.edit { putInt("privacyVersion", PRIVACY_VERSION) }
    }

    fun updateDefaultFilter(f: FilterType) {
        defaultFilter = f; prefs.edit { putString("filter", f.name) }
    }

    fun updateAutoCrop(v: Boolean) {
        autoCrop = v; prefs.edit { putBoolean("autoCrop", v) }
    }

    fun updateExportQuality(q: ExportQuality) {
        exportQuality = q; prefs.edit { putString("quality", q.name) }
    }

    companion object {
        /** 2: recognition and the image tools send images to the AI service. */
        const val PRIVACY_VERSION = 2
    }
}

/** Saved hand-drawn signatures (transparent PNGs). */
class SignatureStore(private val dir: File) {
    var files by mutableStateOf(list())
        private set

    private fun list(): List<File> =
        dir.apply { mkdirs() }.listFiles { f -> f.extension == "png" }?.sortedByDescending { it.lastModified() }.orEmpty()

    fun newFile(): File = File(dir.apply { mkdirs() }, "sig_${UUID.randomUUID().toString().take(8)}.png")

    fun refresh() {
        files = list()
    }

    fun delete(f: File) {
        f.delete(); refresh()
    }
}
