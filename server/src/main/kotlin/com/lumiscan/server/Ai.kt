package com.lumiscan.server

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

@Serializable
data class AiMessage(val role: String, val content: String)

@Serializable
data class AiRequest(
    val feature: String,
    /** Document text (summary / qa / polish / translate) or an optional note for solve. */
    val text: String = "",
    /** The user's question for qa / follow-ups. */
    val question: String? = null,
    /** Target language name for translate, e.g. "英语". */
    val targetLang: String? = null,
    /** JPEG image as base64 (solve only). */
    val image: String? = null,
    /** Earlier turns of this conversation, oldest first. */
    val history: List<AiMessage> = emptyList(),
)

@Serializable
data class AiUsage(val promptTokens: Long, val completionTokens: Long)

@Serializable
data class AiResponse(val content: String, val model: String, val usage: AiUsage, val quota: QuotaInfo)

data class AiConfig(
    val baseUrl: String,
    val apiKey: String,
    val textModel: String,
    val visionModel: String,
    val maxOutputTokens: Int,
    val timeoutSeconds: Int,
    val extraBody: JsonObject?,
)

class UpstreamError(val status: Int, message: String) : RuntimeException(message)

data class Completion(val content: String, val promptTokens: Long, val completionTokens: Long)

/**
 * Calls the configured OpenAI-compatible chat-completions endpoint (阿里云百炼 / 火山方舟 / DeepSeek /
 * 智谱…) on behalf of app users, enforcing group features and quotas. Prompts live here, not in
 * the app, so the endpoint can't be used as a general-purpose free chatbot.
 */
class AiGateway(private val s: Services) {
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

    fun config(): AiConfig {
        val st = s.settings
        val extra = st.get(Settings.AI_EXTRA_BODY).trim().takeIf { it.isNotEmpty() }?.let {
            runCatching { AppJson.parseToJsonElement(it).jsonObject }.getOrNull()
        }
        return AiConfig(
            baseUrl = st.get(Settings.AI_BASE_URL).trim().trimEnd('/'),
            apiKey = st.get(Settings.AI_API_KEY).trim(),
            textModel = st.get(Settings.AI_TEXT_MODEL).trim(),
            visionModel = st.get(Settings.AI_VISION_MODEL).trim(),
            maxOutputTokens = st.int(Settings.AI_MAX_OUTPUT_TOKENS, 4000).coerceIn(100, 32_000),
            timeoutSeconds = st.int(Settings.AI_TIMEOUT_SECONDS, 90).coerceIn(10, 300),
            extraBody = extra,
        )
    }

    suspend fun chat(u: UserRow, req: AiRequest, ip: String): AiResponse {
        val feature = Feature.of(req.feature)?.takeIf { it.kind == QuotaKind.CHAT } ?: throw bad("未知的 AI 功能")
        val lim = s.limits(u)
        val inputChars = req.text.length + (req.question?.length ?: 0) + req.history.sumOf { it.content.length }

        fun reject(e: ApiError): Nothing {
            s.logAi(u.id, feature, "", 0, 0, inputChars, "rejected", e.message, 0, ip)
            throw e
        }

        if (feature.key !in lim.features) reject(ApiError(403, "feature_disabled", "当前账号分组（${lim.group.name}）未开通「${feature.label}」"))
        if (!feature.vision && req.text.isBlank()) throw bad("内容不能为空")
        if (feature == Feature.QA && req.question.isNullOrBlank()) throw bad("请输入问题")
        if (feature.vision && req.image.isNullOrBlank()) throw bad("请上传图片")
        if ((req.image?.length ?: 0) > MAX_IMAGE_B64) throw bad("图片过大，请压缩后再试")
        if (req.history.size > 20 || req.history.any { it.role !in setOf("user", "assistant") }) throw bad("对话记录格式不正确")
        if (lim.maxInputChars >= 0 && inputChars > lim.maxInputChars) {
            reject(ApiError(413, "input_too_long", "内容过长（$inputChars 字），当前分组单次上限 ${lim.maxInputChars} 字"))
        }
        if (inputChars > HARD_INPUT_LIMIT) throw ApiError(413, "input_too_long", "内容过长（$inputChars 字），请分段处理")

        val cfg = config()
        if (cfg.apiKey.isEmpty() || cfg.baseUrl.isEmpty()) throw ApiError(503, "ai_not_configured", "AI 服务尚未配置，请联系管理员")
        val model = if (feature.vision) cfg.visionModel else cfg.textModel

        return s.userSlot(u.id) {
            try {
                s.reserveRequest(u, lim)
            } catch (e: ApiError) {
                reject(e)
            }
            val started = System.currentTimeMillis()
            val result = try {
                complete(cfg, model, messages(feature, req), cfg.maxOutputTokens)
            } catch (e: Throwable) {
                s.refundRequest(u.id)
                val elapsed = (System.currentTimeMillis() - started).toInt()
                s.logAi(u.id, feature, model, 0, 0, inputChars, "error", e.message ?: e.javaClass.simpleName, elapsed, ip)
                s.log.warn("AI call failed for user {} ({}): {}", u.id, feature.key, e.message)
                throw when (e) {
                    is java.net.http.HttpTimeoutException -> ApiError(504, "ai_timeout", "AI 响应超时，请稍后重试或缩短内容")
                    is UpstreamError -> ApiError(502, "ai_failed", "AI 服务暂时不可用（${e.status}），请稍后再试")
                    else -> ApiError(502, "ai_failed", "AI 服务暂时不可用，请稍后再试")
                }
            }
            val elapsed = (System.currentTimeMillis() - started).toInt()
            s.addTokens(u.id, result.promptTokens + result.completionTokens)
            s.logAi(u.id, feature, model, result.promptTokens, result.completionTokens, inputChars, "ok", null, elapsed, ip)
            AiResponse(
                content = result.content.trim(),
                model = model,
                usage = AiUsage(result.promptTokens, result.completionTokens),
                quota = s.quotaInfo(u),
            )
        }
    }

    private fun messages(f: Feature, req: AiRequest): JsonArray = buildJsonArray {
        fun msg(role: String, content: String) = addJsonObject { put("role", role); put("content", content) }
        fun history() = req.history.takeLast(20).forEach { msg(it.role, it.content.take(8000)) }

        when (f) {
            Feature.SUMMARY -> {
                msg("system", BASE + "请总结用户提供的文档：先用一两句话概括主旨，再分条列出关键要点。日期、金额、人名、地点、结论等关键信息必须保留。")
                msg("user", "文档内容：\n${req.text}")
            }
            Feature.QA -> {
                msg("system", BASE + "根据用户提供的文档内容回答问题。优先依据文档作答并尽量引用原文要点；文档中没有相关信息时，先明确说明「文档中未提及」，再给出一般性建议并注明。")
                msg("user", "文档内容：\n${req.text}")
                msg("assistant", "我已阅读这份文档，请提问。")
                history()
                msg("user", req.question.orEmpty())
            }
            Feature.POLISH -> {
                msg("system", BASE + "用户给出的文本来自 OCR 文字识别，可能有错别字、形近字、错误断行、多余空格和标点错误。请修正这些错误，保持原文的语言、意思和段落结构，不要增删内容，不要解释，只输出修正后的全文。")
                msg("user", req.text)
            }
            Feature.TRANSLATE -> {
                val lang = req.targetLang?.trim()?.take(20)?.takeIf { it.isNotEmpty() } ?: "中文"
                msg("system", "你是专业翻译。将用户提供的文本翻译成$lang。保持原有的段落和换行，专有名词准确，语气自然。只输出译文，不要任何解释或前言。")
                msg("user", req.text)
            }
            Feature.SOLVE -> {
                msg("system", BASE + "你是耐心的解题老师。识别图片中的题目，先写出题目内容，再给出清晰的解题步骤和最终答案。数学公式用普通文本书写（如 x^2、sqrt(2)、a/b）。图片中有多道题时逐题解答；看不清的地方要说明。")
                addJsonObject {
                    put("role", "user")
                    putJsonArray("content") {
                        addJsonObject {
                            put("type", "text")
                            put("text", req.text.ifBlank { "请解答图中的题目。" })
                        }
                        addJsonObject {
                            put("type", "image_url")
                            putJsonObject("image_url") { put("url", "data:image/jpeg;base64,${req.image}") }
                        }
                    }
                }
                history()
                req.question?.takeIf { it.isNotBlank() }?.let { msg("user", it) }
            }
            else -> error("not a chat feature: $f")
        }
    }

    suspend fun complete(cfg: AiConfig, model: String, messages: JsonArray, maxTokens: Int): Completion = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            cfg.extraBody?.forEach { (k, v) -> put(k, v) }
            put("model", model)
            put("messages", messages)
            put("max_tokens", maxTokens)
            put("temperature", 0.3)
            put("stream", false)
        }
        val request = HttpRequest.newBuilder(URI.create("${cfg.baseUrl}/chat/completions"))
            .timeout(Duration.ofSeconds(cfg.timeoutSeconds.toLong()))
            .header("Authorization", "Bearer ${cfg.apiKey}")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
            .build()
        val resp = http.sendRetrying(request)
        val json = runCatching { AppJson.parseToJsonElement(resp.body()).jsonObject }.getOrNull()
        if (resp.statusCode() !in 200..299 || json == null) {
            val msg = json?.get("error")?.let { e -> (e as? JsonObject)?.get("message")?.jsonPrimitive?.contentOrNull ?: e.toString() }
                ?: resp.body().take(200)
            throw UpstreamError(resp.statusCode(), msg)
        }
        val message = json["choices"]?.jsonArray?.firstOrNull()?.jsonObject?.get("message")?.jsonObject
            ?: throw UpstreamError(resp.statusCode(), "响应中没有 choices")
        val content = when (val c = message["content"]) {
            is JsonPrimitive -> c.contentOrNull.orEmpty()
            is JsonArray -> c.joinToString("") { (it as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull.orEmpty() }
            else -> ""
        }
        val usage = json["usage"] as? JsonObject
        val inChars = messages.toString().length
        Completion(
            content = content,
            // Providers that omit usage are billed by an estimate (~1.5 Chinese chars per token).
            promptTokens = usage?.get("prompt_tokens")?.jsonPrimitive?.longOrNull ?: (inChars / 1.5).toLong(),
            completionTokens = usage?.get("completion_tokens")?.jsonPrimitive?.longOrNull ?: (content.length / 1.5).toLong(),
        )
    }

    /** Admin "test connection": one tiny request with the saved settings. */
    suspend fun test(vision: Boolean): Pair<String, Completion> {
        val cfg = config()
        if (cfg.apiKey.isEmpty()) throw ApiError(400, "ai_not_configured", "请先填写 API Key 并保存")
        val model = if (vision) cfg.visionModel else cfg.textModel
        val msgs = buildJsonArray { addJsonObject { put("role", "user"); put("content", "请只回复四个字：连接成功") } }
        return model to complete(cfg, model, msgs, 20)
    }

    private companion object {
        const val BASE = "你是「光点扫描」App 内置的 AI 助手，默认用简体中文回答。输出纯文本，不要使用 Markdown 标记（如 #、**、```、表格），列表用「1.」「•」等普通字符。"
        const val MAX_IMAGE_B64 = 6_000_000
        const val HARD_INPUT_LIMIT = 120_000
    }
}
