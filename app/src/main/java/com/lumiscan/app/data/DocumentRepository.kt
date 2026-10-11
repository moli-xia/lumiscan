package com.lumiscan.app.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/** A page about to be stored: files live in the scratch directory and are copied in. */
data class NewPage(
    val source: File,
    val image: File,
    val quad: List<Float>? = null,
    val filter: FilterType = FilterType.ORIGINAL,
)

/**
 * File-backed document store. Each document is a directory holding `doc.json` and its page
 * images. All methods do blocking IO and must be called off the main thread.
 */
class DocumentRepository(private val root: File) {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }
    private val _docs = MutableStateFlow<List<DocumentData>>(emptyList())
    val docs: StateFlow<List<DocumentData>> = _docs.asStateFlow()

    fun load() {
        root.mkdirs()
        val list = root.listFiles()?.mapNotNull { dir ->
            val f = File(dir, META)
            if (!f.exists()) return@mapNotNull null
            runCatching { json.decodeFromString(DocumentData.serializer(), f.readText()) }.getOrNull()
        }.orEmpty()
        publish(list)
    }

    fun get(id: String): DocumentData? = _docs.value.firstOrNull { it.id == id }

    fun dir(id: String) = File(root, id)
    fun imageFile(doc: DocumentData, page: PageData) = File(dir(doc.id), page.image)
    fun sourceFile(doc: DocumentData, page: PageData) = File(dir(doc.id), page.source)
    fun imageFiles(doc: DocumentData) = doc.pages.map { imageFile(doc, it) }

    @Synchronized
    fun create(name: String, kind: DocKind, pages: List<NewPage>): DocumentData {
        val id = "d" + UUID.randomUUID().toString().replace("-", "").take(16)
        dir(id).mkdirs()
        val now = System.currentTimeMillis()
        val doc = DocumentData(id, name, kind, now, now, pages.map { storePage(id, it) })
        write(doc)
        return doc
    }

    @Synchronized
    fun addPages(id: String, pages: List<NewPage>): DocumentData? {
        val doc = get(id) ?: return null
        val updated = doc.copy(pages = doc.pages + pages.map { storePage(id, it) }, updatedAt = now())
        write(updated)
        return updated
    }

    /** Replaces the processed image of a page; optionally also its crop/filter/source. */
    @Synchronized
    fun updatePage(
        id: String,
        index: Int,
        image: File,
        quad: List<Float>? = null,
        filter: FilterType? = null,
        source: File? = null,
        keepQuad: Boolean = true,
    ): DocumentData? {
        val doc = get(id) ?: return null
        val old = doc.pages.getOrNull(index) ?: return null
        val version = now()
        val imageName = "${old.id}_$version.jpg"
        image.copyTo(File(dir(id), imageName), overwrite = true)
        if (old.image != old.source) File(dir(id), old.image).delete()
        var sourceName = old.source
        if (source != null) {
            sourceName = "${old.id}_src_$version.jpg"
            source.copyTo(File(dir(id), sourceName), overwrite = true)
            File(dir(id), old.source).delete()
        }
        val page = old.copy(
            image = imageName,
            source = sourceName,
            quad = if (quad != null || !keepQuad) quad else old.quad,
            filter = filter ?: old.filter,
            ocrText = null,
            version = version,
        )
        val updated = doc.copy(pages = doc.pages.toMutableList().also { it[index] = page }, updatedAt = version)
        write(updated)
        return updated
    }

    @Synchronized
    fun setOcrText(id: String, pageId: String, text: String) {
        val doc = get(id) ?: return
        write(doc.copy(pages = doc.pages.map { if (it.id == pageId) it.copy(ocrText = text) else it }))
    }

    @Synchronized
    fun deletePages(id: String, indices: Set<Int>) {
        val doc = get(id) ?: return
        val (removed, kept) = doc.pages.withIndex().partition { it.index in indices }
        removed.forEach { (_, p) -> File(dir(id), p.image).delete(); File(dir(id), p.source).delete() }
        if (kept.isEmpty()) delete(listOf(id)) else write(doc.copy(pages = kept.map { it.value }, updatedAt = now()))
    }

    @Synchronized
    fun movePage(id: String, from: Int, to: Int) {
        val doc = get(id) ?: return
        if (from !in doc.pages.indices || to !in doc.pages.indices) return
        val pages = doc.pages.toMutableList()
        pages.add(to, pages.removeAt(from))
        write(doc.copy(pages = pages, updatedAt = now()))
    }

    @Synchronized
    fun rename(id: String, name: String) {
        val doc = get(id) ?: return
        write(doc.copy(name = name.ifBlank { doc.name }, updatedAt = now()))
    }

    @Synchronized
    fun delete(ids: Collection<String>) {
        ids.forEach { dir(it).deleteRecursively() }
        publish(_docs.value.filterNot { it.id in ids })
    }

    /** Merges documents in the given order into a new document; the originals are kept. */
    @Synchronized
    fun merge(ids: List<String>, name: String): DocumentData? {
        val docs = ids.mapNotNull { get(it) }
        if (docs.isEmpty()) return null
        val pages = docs.flatMap { d -> d.pages.map { p -> NewPage(sourceFile(d, p), imageFile(d, p), p.quad, p.filter) } }
        return create(name, DocKind.SCAN, pages)
    }

    fun search(query: String): List<DocumentData> {
        val q = query.trim()
        if (q.isEmpty()) return _docs.value
        return _docs.value.filter { d ->
            d.name.contains(q, ignoreCase = true) || d.pages.any { it.ocrText?.contains(q, ignoreCase = true) == true }
        }
    }

    private fun storePage(docId: String, p: NewPage): PageData {
        val pid = "p" + UUID.randomUUID().toString().replace("-", "").take(12)
        val version = now()
        val srcName = "${pid}_src.jpg"
        val imgName = "${pid}_$version.jpg"
        p.source.copyTo(File(dir(docId), srcName), overwrite = true)
        p.image.copyTo(File(dir(docId), imgName), overwrite = true)
        return PageData(pid, srcName, imgName, p.quad, p.filter, version = version)
    }

    private fun write(doc: DocumentData) {
        val dir = dir(doc.id).apply { mkdirs() }
        val tmp = File(dir, "$META.tmp")
        tmp.writeText(json.encodeToString(DocumentData.serializer(), doc))
        tmp.renameTo(File(dir, META))
        publish(_docs.value.filterNot { it.id == doc.id } + doc)
    }

    private fun publish(list: List<DocumentData>) {
        _docs.value = list.sortedByDescending { it.updatedAt }
    }

    private fun now() = System.currentTimeMillis()

    companion object {
        private const val META = "doc.json"
    }
}
