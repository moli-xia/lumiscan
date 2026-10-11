package com.lumiscan.app.data.remote

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.lumiscan.app.BuildConfig
import com.lumiscan.app.R
import com.lumiscan.app.ScanApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

@Serializable
data class GroupInfo(val id: Long, val name: String, val expiresAt: Long? = null)

/** AI limits for the signed-in user; -1 means unlimited. */
@Serializable
data class QuotaInfo(
    val dailyRequests: Int,
    val dailyUsed: Int,
    val monthlyTokens: Long,
    val monthlyUsed: Long,
    val dailyImages: Int = 0,
    val imagesUsed: Int = 0,
    val dailyOcrPages: Int = 0,
    val ocrUsed: Int = 0,
    val maxInputChars: Int,
    val maxDevices: Int,
    val features: List<String> = emptyList(),
) {
    val dailyLeft: Int get() = if (dailyRequests < 0) Int.MAX_VALUE else (dailyRequests - dailyUsed).coerceAtLeast(0)
    val imagesLeft: Int get() = if (dailyImages < 0) Int.MAX_VALUE else (dailyImages - imagesUsed).coerceAtLeast(0)
    val ocrLeft: Int get() = if (dailyOcrPages < 0) Int.MAX_VALUE else (dailyOcrPages - ocrUsed).coerceAtLeast(0)
}

@Serializable
data class Profile(
    val id: Long,
    val username: String,
    val nickname: String = "",
    val email: String? = null,
    val createdAt: Long = 0,
    val group: GroupInfo,
    val quota: QuotaInfo,
) {
    val displayName: String get() = nickname.ifBlank { username }
}

@Serializable
data class AuthResponse(val token: String, val profile: Profile)

@Serializable
data class AppConfig(
    val registrationOpen: Boolean = true,
    val inviteRequired: Boolean = false,
    val announcement: String = "",
    val minVersionCode: Int = 0,
    val latestVersionCode: Int = 0,
    val updateUrl: String = "",
    val updateNote: String = "",
    /** Image tools run the AI as soon as they open; otherwise only when the user taps it. */
    val imageAiAuto: Boolean = false,
)

@Serializable
data class AiMessage(val role: String, val content: String)

@Serializable
data class AiRequest(
    val feature: String,
    val text: String = "",
    val question: String? = null,
    val targetLang: String? = null,
    val image: String? = null,
    val history: List<AiMessage> = emptyList(),
)

@Serializable
data class AiResponse(val content: String, val model: String = "", val quota: QuotaInfo? = null)

@Serializable
data class PolicyText(val title: String, val content: String)

@Serializable
data class OcrRequest(val image: String, val width: Int, val height: Int, val task: String)

@Serializable
data class OcrCell(val r0: Int, val r1: Int, val c0: Int, val c1: Int, val text: String)

/** Layout block from cloud OCR; coordinates are page fractions (0..1). */
@Serializable
data class OcrBlock(
    val type: String,
    val text: String,
    val x0: Double, val y0: Double, val x1: Double, val y1: Double,
    val rows: Int = 0,
    val cols: Int = 0,
    val cells: List<OcrCell> = emptyList(),
)

@Serializable
data class OcrResponse(val text: String = "", val blocks: List<OcrBlock> = emptyList(), val quota: QuotaInfo? = null)

@Serializable
data class DetectRequest(val image: String, val target: String)

@Serializable
data class DetectBox(val x0: Double, val y0: Double, val x1: Double, val y1: Double, val label: String = "")

@Serializable
data class DetectResponse(val boxes: List<DetectBox> = emptyList(), val quota: QuotaInfo? = null)

@Serializable
data class CountRequest(val image: String, val hint: String = "")

/** Object centre as fractions of the image. */
@Serializable
data class CountPoint(val x: Double, val y: Double)

@Serializable
data class CountResponse(val points: List<CountPoint> = emptyList(), val label: String = "", val quota: QuotaInfo? = null)

@Serializable
data class EditOptions(val outfit: String = "", val colorize: Boolean = true)

@Serializable
data class EditRequest(val feature: String, val image: String, val width: Int, val height: Int, val options: EditOptions = EditOptions())

@Serializable
data class EditResponse(val url: String, val quota: QuotaInfo? = null)

@Serializable
private data class ErrorBody(val error: String = "", val message: String = "")

@Serializable
private data class LoginBody(val username: String, val password: String, val device: String)

@Serializable
private data class RegisterBody(val username: String, val password: String, val inviteCode: String?, val device: String)

@Serializable
private data class ProfileBody(val nickname: String? = null, val email: String? = null)

@Serializable
private data class PasswordBody(val oldPassword: String, val newPassword: String)

@Serializable
private data class DeleteBody(val password: String)

@Serializable
private data class FeedbackBody(val content: String, val contact: String)

@Serializable
private class OkBody

/** [status] 0 means the server couldn't be reached. [message] is ready to show to the user. */
class ApiException(val status: Int, val code: String, message: String) : Exception(message)

/** The LumiScan backend (see server/). Calls are main-safe suspend functions. */
object Api {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }
    private val base = BuildConfig.API_BASE.trimEnd('/')

    /** Bearer token of the signed-in user, kept in sync by [com.lumiscan.app.data.Account]. */
    @Volatile
    var token: String? = null

    /** Called on the main thread when the server rejects the token (expired, kicked, banned). */
    var onUnauthorized: ((message: String) -> Unit)? = null

    private val sslFactory: SSLSocketFactory by lazy { PinnedTls.socketFactory(ScanApp.instance) }

    val deviceName: String = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
    private val userAgent = "LumiScan/${BuildConfig.VERSION_NAME} (Android ${Build.VERSION.RELEASE}; $deviceName)"

    suspend fun config(): AppConfig = get("/api/v1/config", auth = false)
    suspend fun policy(kind: String): PolicyText = get("/api/v1/policy/$kind", auth = false)

    suspend fun login(username: String, password: String): AuthResponse =
        post("/api/v1/auth/login", LoginBody(username.trim(), password, deviceName), auth = false)

    suspend fun register(username: String, password: String, inviteCode: String?): AuthResponse =
        post("/api/v1/auth/register", RegisterBody(username.trim(), password, inviteCode?.trim()?.ifEmpty { null }, deviceName), auth = false)

    suspend fun logout() {
        post<_, OkBody>("/api/v1/auth/logout", OkBody())
    }

    suspend fun me(): Profile = get("/api/v1/me")
    suspend fun updateProfile(nickname: String? = null, email: String? = null): Profile = post("/api/v1/me", ProfileBody(nickname, email))
    suspend fun changePassword(old: String, new: String) {
        post<_, OkBody>("/api/v1/me/password", PasswordBody(old, new))
    }
    suspend fun deleteAccount(password: String) {
        post<_, OkBody>("/api/v1/me/delete", DeleteBody(password))
    }
    suspend fun feedback(content: String, contact: String) {
        post<_, OkBody>("/api/v1/feedback", FeedbackBody(content, contact))
    }

    /** AI calls can take a while (long documents, vision models). */
    suspend fun ai(req: AiRequest): AiResponse = post("/api/v1/ai/chat", req, timeoutMs = 150_000)

    suspend fun ocr(req: OcrRequest): OcrResponse = post("/api/v1/vision/ocr", req, timeoutMs = 150_000)
    suspend fun detect(req: DetectRequest): DetectResponse = post("/api/v1/vision/detect", req, timeoutMs = 120_000)
    suspend fun count(req: CountRequest): CountResponse = post("/api/v1/vision/count", req, timeoutMs = 200_000)
    suspend fun edit(req: EditRequest): EditResponse = post("/api/v1/vision/edit", req, timeoutMs = 180_000)

    /** Downloads an AI result image (a temporary Alibaba Cloud OSS link with a public certificate). */
    suspend fun download(url: String): ByteArray = withContext(Dispatchers.IO) {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 60_000
            if (conn.responseCode !in 200..299) throw ApiException(conn.responseCode, "download", "结果图片下载失败（${conn.responseCode}）")
            conn.inputStream.use { it.readBytes() }
        } catch (e: IOException) {
            throw ApiException(0, "network", "结果图片下载失败，请检查网络后重试")
        } finally {
            conn.disconnect()
        }
    }

    private suspend inline fun <reified R> get(path: String, auth: Boolean = true): R =
        request("GET", path, null, auth, serializer<R>(), 20_000)

    private suspend inline fun <reified B, reified R> post(path: String, body: B, auth: Boolean = true, timeoutMs: Int = 20_000): R =
        request("POST", path, json.encodeToString(serializer<B>(), body), auth, serializer<R>(), timeoutMs)

    private suspend fun <R> request(method: String, path: String, body: String?, auth: Boolean, out: KSerializer<R>, timeoutMs: Int): R =
        withContext(Dispatchers.IO) {
            val sentToken = if (auth) token else null
            if (auth && sentToken == null) throw ApiException(401, "unauthorized", "请先登录")
            val conn = (URL(base + path).openConnection() as HttpURLConnection).apply {
                if (this is HttpsURLConnection) sslSocketFactory = sslFactory
                requestMethod = method
                connectTimeout = 10_000
                readTimeout = timeoutMs
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", userAgent)
                setRequestProperty("X-App-Version", BuildConfig.VERSION_NAME)
                sentToken?.let { setRequestProperty("Authorization", "Bearer $it") }
            }
            try {
                if (body != null) {
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    conn.outputStream.use { it.write(body.toByteArray()) }
                }
                val status = conn.responseCode
                val text = (if (status in 200..299) conn.inputStream else conn.errorStream)?.use { it.readBytes().decodeToString() }.orEmpty()
                if (status in 200..299) {
                    return@withContext runCatching { json.decodeFromString(out, text) }
                        .getOrElse { throw ApiException(status, "bad_response", "服务器返回的数据无法识别，请升级 App 后重试") }
                }
                val err = runCatching { json.decodeFromString(ErrorBody.serializer(), text) }.getOrNull()
                val message = err?.message?.takeIf { it.isNotBlank() } ?: "服务器错误（$status），请稍后再试"
                // Only a rejected token means "signed out"; a 401 from login is just a wrong password.
                if (status == 401 && sentToken != null && sentToken == token) {
                    Handler(Looper.getMainLooper()).post { onUnauthorized?.invoke(message) }
                }
                if (status == 403 && err?.error == "banned" && sentToken != null) {
                    Handler(Looper.getMainLooper()).post { onUnauthorized?.invoke(message) }
                }
                throw ApiException(status, err?.error ?: "http_$status", message)
            } catch (e: SSLException) {
                throw ApiException(0, "tls", "安全连接失败，请检查网络或手机时间是否正确")
            } catch (e: IOException) {
                throw ApiException(0, "network", "无法连接服务器，请检查网络后重试")
            } finally {
                conn.disconnect()
            }
        }
}

/**
 * Trusts the system CAs plus the server's own certificate (res/raw/server_cert.pem), so the app
 * works against the IP-only server with a self-signed certificate today and keeps working
 * unchanged once a domain with a public certificate is set up.
 */
private object PinnedTls {
    fun socketFactory(ctx: Context): SSLSocketFactory {
        val system = trustManager(null)
        val pinned = runCatching {
            val cert = ctx.resources.openRawResource(R.raw.server_cert).use { CertificateFactory.getInstance("X.509").generateCertificate(it) }
            val ks = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                load(null, null)
                setCertificateEntry("lumiscan", cert)
            }
            trustManager(ks)
        }.getOrNull()
        val combined = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = system.checkClientTrusted(chain, authType)

            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                try {
                    system.checkServerTrusted(chain, authType)
                } catch (e: CertificateException) {
                    (pinned ?: throw e).checkServerTrusted(chain, authType)
                }
            }

            override fun getAcceptedIssuers(): Array<X509Certificate> = system.acceptedIssuers + (pinned?.acceptedIssuers ?: emptyArray())
        }
        return SSLContext.getInstance("TLS").apply { init(null, arrayOf(combined), null) }.socketFactory
    }

    private fun trustManager(ks: KeyStore?): X509TrustManager =
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(ks) }
            .trustManagers.filterIsInstance<X509TrustManager>().first()
}
