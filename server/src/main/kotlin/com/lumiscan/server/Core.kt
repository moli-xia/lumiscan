package com.lumiscan.server

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

val AppJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
}

data class Config(
    val host: String,
    val port: Int,
    val dataDir: File,
    val zone: ZoneId,
    val cookieSecure: Boolean,
    val adminUser: String,
    val adminPassword: String?,
) {
    companion object {
        private fun env(k: String, def: String) = System.getenv(k)?.takeIf { it.isNotBlank() } ?: def

        fun fromEnv() = Config(
            host = env("LUMI_HOST", "0.0.0.0"),
            port = env("LUMI_PORT", "8080").toInt(),
            dataDir = File(env("LUMI_DATA", "./data")).absoluteFile,
            zone = ZoneId.of(env("LUMI_TZ", "Asia/Shanghai")),
            cookieSecure = env("LUMI_COOKIE_SECURE", "true").toBoolean(),
            adminUser = env("LUMI_ADMIN_USER", "admin"),
            adminPassword = System.getenv("LUMI_ADMIN_PASSWORD"),
        )
    }
}

/** An error the client is meant to see: HTTP status + stable code + Chinese message. */
class ApiError(val status: Int, val code: String, override val message: String) : RuntimeException(message)

fun bad(message: String) = ApiError(400, "invalid_request", message)
fun notFound(message: String = "记录不存在") = ApiError(404, "not_found", message)

@Serializable
data class ErrorBody(val error: String, val message: String)

@Serializable
data class Ok(val ok: Boolean = true)

/** Which daily counter a feature draws from. */
enum class QuotaKind { CHAT, IMAGE, OCR }

/** AI capabilities a group can be granted; the key is what the app sends. */
enum class Feature(val key: String, val label: String, val vision: Boolean, val kind: QuotaKind = QuotaKind.CHAT) {
    SUMMARY("summary", "文档总结", false),
    QA("qa", "文档问答", false),
    POLISH("polish", "纠错润色", false),
    TRANSLATE("translate", "AI 翻译", false),
    SOLVE("solve", "拍题解答", true),
    OCR("ocr", "AI 文字识别", true, QuotaKind.OCR),
    /** Locating objects costs about as much as recognising a page, so it counts as one. */
    COUNT("count", "AI 拍照计数", true, QuotaKind.OCR),
    HANDWRITING("handwriting", "AI 去手写", true, QuotaKind.IMAGE),
    WATERMARK("watermark", "AI 去水印", true, QuotaKind.IMAGE),
    ERASE("erase", "AI 消除", true, QuotaKind.IMAGE),
    RESTORE("restore", "AI 老照片修复", true, QuotaKind.IMAGE),
    UPSCALE("upscale", "AI 变清晰", true, QuotaKind.IMAGE),
    ID_PHOTO("id_photo", "AI 证件照", true, QuotaKind.IMAGE);

    companion object {
        fun of(key: String?) = entries.firstOrNull { it.key == key }
        fun parse(csv: String): Set<String> = csv.split(',').map { it.trim() }.filter { k -> entries.any { it.key == k } }.toSet()
    }
}

/** Key/value settings editable from the admin panel. */
object Settings {
    const val REGISTRATION_OPEN = "registration_open"
    const val INVITE_CODE = "invite_code"
    const val AI_BASE_URL = "ai_base_url"
    const val AI_API_KEY = "ai_api_key"
    const val AI_TEXT_MODEL = "ai_text_model"
    const val AI_VISION_MODEL = "ai_vision_model"
    const val AI_MAX_OUTPUT_TOKENS = "ai_max_output_tokens"
    const val AI_TIMEOUT_SECONDS = "ai_timeout_seconds"
    const val AI_EXTRA_BODY = "ai_extra_body"
    const val DASHSCOPE_BASE_URL = "dashscope_base_url"
    const val DASHSCOPE_API_KEY = "dashscope_api_key"
    const val OCR_MODEL = "ocr_model"
    const val DETECT_MODEL = "detect_model"
    const val IDPHOTO_MODEL = "idphoto_model"
    const val EDIT_MODEL = "edit_model"
    const val RESTORE_MODEL = "restore_model"
    const val UPSCALE_MODEL = "upscale_model"
    const val IMAGE_AI_AUTO = "image_ai_auto"
    const val MODEL_PRICES = "model_prices"
    const val ANNOUNCEMENT = "announcement"
    const val MIN_VERSION_CODE = "min_version_code"
    const val LATEST_VERSION_CODE = "latest_version_code"
    const val UPDATE_URL = "update_url"
    const val UPDATE_NOTE = "update_note"
    const val PRIVACY_POLICY = "privacy_policy"
    const val USER_TERMS = "user_terms"

    fun defaults(): Map<String, String> = linkedMapOf(
        REGISTRATION_OPEN to "true",
        INVITE_CODE to "",
        // Alibaba Cloud Bailian (Qwen) OpenAI-compatible endpoint; any OpenAI-compatible provider works.
        AI_BASE_URL to "https://dashscope.aliyuncs.com/compatible-mode/v1",
        AI_API_KEY to "",
        AI_TEXT_MODEL to "qwen-plus",
        AI_VISION_MODEL to "qwen3-vl-plus",
        AI_MAX_OUTPUT_TOKENS to "4000",
        AI_TIMEOUT_SECONDS to "90",
        AI_EXTRA_BODY to "",
        // Image / OCR models use Bailian's native API (same API key as above unless set separately).
        DASHSCOPE_BASE_URL to "https://dashscope.aliyuncs.com/api/v1",
        DASHSCOPE_API_KEY to "",
        OCR_MODEL to "qwen3.5-ocr",
        DETECT_MODEL to "qwen3-vl-plus",
        // qwen-image-3.0-pro keeps the framing exactly (the cut-out is re-cropped on the phone);
        // 3.0 is cheaper and good enough for erase / restore. (The qwen-image-edit series was retired.)
        IDPHOTO_MODEL to "qwen-image-3.0-pro",
        EDIT_MODEL to "qwen-image-3.0",
        RESTORE_MODEL to "qwen-image-3.0",
        UPSCALE_MODEL to "wanx2.1-imageedit",
        // 证件照 / 老照片 / 变清晰 open with the free on-phone result; AI (≈0.14–0.2 元 a call) on request.
        IMAGE_AI_AUTO to "false",
        MODEL_PRICES to Costs.DEFAULT_PRICES,
        ANNOUNCEMENT to "",
        MIN_VERSION_CODE to "0",
        LATEST_VERSION_CODE to "0",
        UPDATE_URL to "",
        UPDATE_NOTE to "",
        PRIVACY_POLICY to resource("/policy/privacy.txt"),
        USER_TERMS to resource("/policy/terms.txt"),
    )

    private fun resource(path: String) = Settings::class.java.getResource(path)?.readText().orEmpty()
}

class SettingsStore(private val db: Db) {
    fun get(key: String): String = db.one("SELECT value FROM settings WHERE key=?", key) { it.getString(1) } ?: Settings.defaults()[key].orEmpty()
    fun bool(key: String) = get(key).toBoolean()
    fun int(key: String, def: Int) = get(key).trim().toIntOrNull() ?: def
    fun all(): Map<String, String> = db.query("SELECT key, value FROM settings") { it.getString(1) to it.getString(2) }.toMap()
    fun set(key: String, value: String) {
        db.exec("INSERT INTO settings(key, value) VALUES(?, ?) ON CONFLICT(key) DO UPDATE SET value=excluded.value", key, value)
    }
}

/**
 * Sends a request, retrying once when the connection drops before any response arrives (a pooled
 * connection the upstream had already closed) and up to twice, with a short wait, on HTTP 429.
 * Timeouts are not retried.
 */
fun java.net.http.HttpClient.sendRetrying(req: java.net.http.HttpRequest): java.net.http.HttpResponse<String> {
    var throttled = 0
    while (true) {
        val resp = try {
            send(req, java.net.http.HttpResponse.BodyHandlers.ofString())
        } catch (e: java.net.http.HttpTimeoutException) {
            throw e
        } catch (e: java.io.IOException) {
            send(req, java.net.http.HttpResponse.BodyHandlers.ofString())
        }
        // Rate limits are per model and per second (image models allow only a few), so a short
        // wait usually gets through when several users hit the same model at once.
        if (resp.statusCode() != 429 || throttled >= 2) return resp
        throttled++
        Thread.sleep(1500L * throttled)
    }
}

/** Everything a request handler needs; one instance per server. */
class Services(val cfg: Config, val db: Db) {
    val log = LoggerFactory.getLogger("lumiscan")
    val settings = SettingsStore(db)
    val ai = AiGateway(this)
    val vision = VisionGateway(this)

    val loginIpLimiter = RateLimiter(30, 10 * MINUTE)
    val loginFailLimiter = RateLimiter(5, 15 * MINUTE)
    val registerLimiter = RateLimiter(10, 60 * MINUTE)
    val adminIpLimiter = RateLimiter(20, 10 * MINUTE)
    val adminFailLimiter = RateLimiter(5, 15 * MINUTE)
    val feedbackLimiter = RateLimiter(10, 24 * HOUR)
    val limiters get() = listOf(loginIpLimiter, loginFailLimiter, registerLimiter, adminIpLimiter, adminFailLimiter, feedbackLimiter)

    private val inFlight = java.util.concurrent.ConcurrentHashMap<Long, java.util.concurrent.atomic.AtomicInteger>()

    /** Runs [block] holding one of the user's two concurrent AI slots. */
    suspend fun <T> userSlot(userId: Long, block: suspend () -> T): T {
        val counter = inFlight.computeIfAbsent(userId) { java.util.concurrent.atomic.AtomicInteger() }
        if (counter.incrementAndGet() > 2) {
            counter.decrementAndGet()
            throw ApiError(429, "too_many_requests", "上一个 AI 请求还在处理中，请稍候")
        }
        try {
            return block()
        } finally {
            if (counter.decrementAndGet() <= 0) inFlight.remove(userId, counter)
        }
    }

    fun logAi(
        userId: Long, feature: Feature, model: String, prompt: Long, completion: Long,
        inputChars: Int, status: String, error: String?, latency: Int, ip: String,
    ) {
        db.exec(
            "INSERT INTO ai_logs(user_id, feature, model, prompt_tokens, completion_tokens, input_chars, status, error, latency_ms, ip, created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
            userId, feature.key, model, prompt, completion, inputChars, status, error?.take(300), latency, ip, now(),
        )
    }

    fun now() = System.currentTimeMillis()
    fun today(): String = LocalDate.now(cfg.zone).toString()
    fun month(): String = YearMonth.now(cfg.zone).toString()
    fun dayStart(date: LocalDate = LocalDate.now(cfg.zone)): Long = date.atStartOfDay(cfg.zone).toInstant().toEpochMilli()

    companion object {
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
        const val DAY = 24 * HOUR
        const val SESSION_TTL = 30 * DAY
        const val ADMIN_SESSION_TTL = 12 * HOUR
    }
}
