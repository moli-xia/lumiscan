package com.lumiscan.app.export

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.core.content.FileProvider
import com.lumiscan.app.ScanApp
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Sharing, opening and saving files to public storage (Downloads / Pictures). */
object Share {

    private const val FOLDER = "光点扫描"

    fun mime(file: File): String = when (file.extension.lowercase()) {
        "pdf" -> "application/pdf"
        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        "txt" -> "text/plain"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "zip" -> "application/zip"
        else -> "*/*"
    }

    fun uri(ctx: Context, file: File): Uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)

    fun share(ctx: Context, files: List<File>) {
        if (files.isEmpty()) return
        val mimes = files.map { mime(it) }.distinct()
        val type = if (mimes.size == 1) mimes[0] else "*/*"
        val intent = if (files.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uri(ctx, files[0]))
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(files.map { uri(ctx, it) }))
        }
        intent.type = type
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(Intent.createChooser(intent, "分享").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun shareText(ctx: Context, text: String) {
        val intent = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        ctx.startActivity(Intent.createChooser(intent, "分享").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun open(ctx: Context, file: File) {
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri(ctx, file), mime(file))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { ctx.startActivity(Intent.createChooser(intent, "打开方式").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { toast(ctx, "没有可打开此文件的应用") }
    }

    fun copyText(ctx: Context, text: String) {
        val cm = ctx.getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newPlainText("text", text))
        toast(ctx, "已复制到剪贴板")
    }

    /** Copies [file] to Download/光点扫描 (images go to Pictures/光点扫描). */
    fun saveToDevice(ctx: Context, file: File): Boolean = runCatching {
        val m = mime(file)
        val isImage = m.startsWith("image/")
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
            put(MediaStore.MediaColumns.MIME_TYPE, m)
            put(MediaStore.MediaColumns.RELATIVE_PATH, (if (isImage) Environment.DIRECTORY_PICTURES else Environment.DIRECTORY_DOWNLOADS) + "/" + FOLDER)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val collection = if (isImage) MediaStore.Images.Media.EXTERNAL_CONTENT_URI else MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val cr = ctx.contentResolver
        val uri = cr.insert(collection, values) ?: error("insert failed")
        cr.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
        values.clear(); values.put(MediaStore.MediaColumns.IS_PENDING, 0)
        cr.update(uri, values, null, null)
        true
    }.onFailure { android.util.Log.w("Share", "saveToDevice failed for ${file.name}", it) }.getOrDefault(false)

    fun saveAll(ctx: Context, files: List<File>) {
        val ok = files.count { saveToDevice(ctx, it) }
        val where = if (files.all { mime(it).startsWith("image/") }) "相册 Pictures/$FOLDER" else "Download/$FOLDER"
        toast(ctx, if (ok == files.size) "已保存 $ok 个文件到 $where" else "保存失败 ${files.size - ok} 个")
    }

    fun saveBitmap(ctx: Context, bmp: Bitmap, prefix: String, png: Boolean = false): Boolean {
        val f = exportFile(prefix, if (png) "png" else "jpg")
        f.outputStream().use { bmp.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 95, it) }
        return saveToDevice(ctx, f)
    }

    fun exportFile(name: String, ext: String): File {
        val safe = name.replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_").take(40).ifBlank { "文件" }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        var f = File(ScanApp.instance.exportDir, "${safe}_$stamp.$ext")
        var i = 1
        while (f.exists()) f = File(ScanApp.instance.exportDir, "${safe}_${stamp}_${i++}.$ext")
        return f
    }

    fun toast(ctx: Context, msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
}
