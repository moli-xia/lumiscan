package com.lumiscan.app.data

import kotlinx.serialization.Serializable

@Serializable
enum class DocKind { SCAN, ID_CARD, ID_PHOTO, IMAGE }

@Serializable
enum class FilterType(val label: String) {
    ORIGINAL("原图"),
    ENHANCE("增强锐化"),
    BRIGHTEN("增亮"),
    NO_SHADOW("去阴影"),
    BW("黑白"),
    GRAY("灰度"),
    INK_SAVE("省墨"),
}

@Serializable
data class PageData(
    val id: String,
    /** Uncropped capture, relative to the document directory. */
    val source: String,
    /** Final processed page image, relative to the document directory. */
    val image: String,
    /** Crop quad in [source] pixel coordinates: x0,y0 (TL), x1,y1 (TR), x2,y2 (BR), x3,y3 (BL). */
    val quad: List<Float>? = null,
    val filter: FilterType = FilterType.ENHANCE,
    val ocrText: String? = null,
    val version: Long = 0,
)

@Serializable
data class DocumentData(
    val id: String,
    val name: String,
    val kind: DocKind = DocKind.SCAN,
    val createdAt: Long,
    val updatedAt: Long,
    val pages: List<PageData>,
)
