package com.lumiscan.app.ml

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.withTimeoutOrNull

data class Lang(val code: String, val label: String)

/** On-device translation (ML Kit). Each language model (~30MB) downloads once, then works offline. */
object TranslatorEngine {

    val languages = listOf(
        Lang(TranslateLanguage.CHINESE, "中文"),
        Lang(TranslateLanguage.ENGLISH, "英语"),
        Lang(TranslateLanguage.JAPANESE, "日语"),
        Lang(TranslateLanguage.KOREAN, "韩语"),
        Lang(TranslateLanguage.FRENCH, "法语"),
        Lang(TranslateLanguage.GERMAN, "德语"),
        Lang(TranslateLanguage.SPANISH, "西班牙语"),
        Lang(TranslateLanguage.RUSSIAN, "俄语"),
        Lang(TranslateLanguage.ITALIAN, "意大利语"),
        Lang(TranslateLanguage.PORTUGUESE, "葡萄牙语"),
        Lang(TranslateLanguage.ARABIC, "阿拉伯语"),
        Lang(TranslateLanguage.THAI, "泰语"),
        Lang(TranslateLanguage.VIETNAMESE, "越南语"),
    )

    /** Languages the bundled OCR can read (Chinese + Latin scripts). */
    val sourceLanguages = languages.filter {
        it.code in setOf(
            TranslateLanguage.CHINESE, TranslateLanguage.ENGLISH, TranslateLanguage.FRENCH, TranslateLanguage.GERMAN,
            TranslateLanguage.SPANISH, TranslateLanguage.ITALIAN, TranslateLanguage.PORTUGUESE, TranslateLanguage.VIETNAMESE,
        )
    }

    fun label(code: String) = languages.firstOrNull { it.code == code }?.label ?: code

    fun detect(text: String): String {
        var han = 0; var kana = 0; var hangul = 0; var latin = 0
        for (c in text) {
            when (Character.UnicodeBlock.of(c)) {
                Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS -> han++
                Character.UnicodeBlock.HIRAGANA, Character.UnicodeBlock.KATAKANA -> kana++
                Character.UnicodeBlock.HANGUL_SYLLABLES -> hangul++
                else -> if (c.isLetter()) latin++
            }
        }
        return when {
            kana > 0 && kana * 5 > han -> TranslateLanguage.JAPANESE
            hangul > han -> TranslateLanguage.KOREAN
            han * 2 >= latin && han > 0 -> TranslateLanguage.CHINESE
            else -> TranslateLanguage.ENGLISH
        }
    }

    suspend fun translate(texts: List<String>, source: String, target: String, onDownloading: () -> Unit = {}): List<String> {
        if (source == target) return texts
        val client = Translation.getClient(
            TranslatorOptions.Builder().setSourceLanguage(source).setTargetLanguage(target).build()
        )
        try {
            onDownloading()
            // The language pack comes from Google servers; fail clearly instead of spinning forever.
            // (The task resolves to Void, so return a marker to tell success from timeout.)
            withTimeoutOrNull(90_000) { client.downloadModelIfNeeded(DownloadConditions.Builder().build()).await(); true }
                ?: throw java.io.IOException("语言包下载超时，请检查网络（需能访问 Google 服务）")
            return texts.map { t -> if (t.isBlank()) t else client.translate(t).await() }
        } finally {
            client.close()
        }
    }
}
