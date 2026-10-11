package com.lumiscan.app

import android.app.Application
import android.util.Log
import com.lumiscan.app.data.Account
import com.lumiscan.app.data.AppSettings
import com.lumiscan.app.data.DocumentRepository
import com.lumiscan.app.data.SignatureStore
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import org.opencv.android.OpenCVLoader
import java.io.File

class ScanApp : Application() {

    lateinit var repository: DocumentRepository
        private set
    lateinit var settings: AppSettings
        private set
    lateinit var signatures: SignatureStore
        private set
    lateinit var account: Account
        private set

    /** App-wide back stack (kept here so it survives activity recreation). */
    val navigator = com.lumiscan.app.nav.Navigator()

    override fun onCreate() {
        super.onCreate()
        instance = this
        if (!OpenCVLoader.initLocal()) Log.e("ScanApp", "OpenCV failed to load")
        PDFBoxResourceLoader.init(applicationContext)
        settings = AppSettings(this)
        repository = DocumentRepository(File(filesDir, "docs")).also { it.load() }
        signatures = SignatureStore(File(filesDir, "signatures"))
        account = Account(this)
        // Scratch images from earlier sessions are no longer referenced by anything.
        Thread {
            val cutoff = System.currentTimeMillis() - 24 * 3600 * 1000L
            File(cacheDir, "work").listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.deleteRecursively() }
        }.start()
    }

    /** Scratch directory for intermediate images; cleared from the settings page. */
    val workDir: File get() = File(cacheDir, "work").apply { mkdirs() }

    /** Exported deliverables (PDF / Word / Excel / TXT …) listed under 文件 › 导出文件. */
    val exportDir: File get() = File(filesDir, "exports").apply { mkdirs() }

    companion object {
        lateinit var instance: ScanApp
            private set
    }
}
