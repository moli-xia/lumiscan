package com.lumiscan.server

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

/**
 * Price of one model (元) for the admin's spend estimate: per successful call (image models) and
 * per million input / output tokens (chat, OCR, detection). Failed calls cost nothing.
 */
@Serializable
data class ModelPrice(val perCall: Double = 0.0, val input: Double = 0.0, val output: Double = 0.0)

@Serializable
data class ModelCost(val model: String, val calls: Long, val promptTokens: Long, val completionTokens: Long, val cost: Double, val priced: Boolean)

/** Estimated model spend from the call log; the Bailian bill is the authority. */
object Costs {
    private val serializer = MapSerializer(String.serializer(), ModelPrice.serializer())

    /** Bailian list prices (Beijing region, 2026-10); qwen-image-3.0-pro is the effective price on a real bill. */
    val DEFAULT_PRICES: String = """
        {
          "qwen-image-3.0-pro": {"perCall": 0.19},
          "qwen-image-3.0": {"perCall": 0.2},
          "qwen-image-2.0": {"perCall": 0.2},
          "qwen-image-2.0-pro": {"perCall": 0.5},
          "wanx2.1-imageedit": {"perCall": 0.14},
          "qwen3.5-ocr": {"input": 0.5, "output": 2},
          "qwen3-vl-plus": {"input": 1, "output": 10},
          "qwen-plus": {"input": 0.8, "output": 2}
        }
    """.trimIndent()

    fun parse(json: String): Map<String, ModelPrice> = AppJson.decodeFromString(serializer, json)

    fun prices(s: Services): Map<String, ModelPrice> =
        runCatching { parse(s.settings.get(Settings.MODEL_PRICES)) }.getOrElse { parse(DEFAULT_PRICES) }

    /** Spend per model since [from] (ms), most expensive first. */
    fun since(s: Services, from: Long): List<ModelCost> {
        val prices = prices(s)
        return s.db.query(
            "SELECT model, COUNT(*), COALESCE(SUM(prompt_tokens),0), COALESCE(SUM(completion_tokens),0) FROM ai_logs " +
                "WHERE status='ok' AND created_at >= ? GROUP BY model",
            from,
        ) { r ->
            val model = r.getString(1); val calls = r.getLong(2); val pt = r.getLong(3); val ct = r.getLong(4)
            val p = prices[model]
            val cost = if (p == null) 0.0 else p.perCall * calls + p.input * pt / 1e6 + p.output * ct / 1e6
            ModelCost(model, calls, pt, ct, cost, p != null)
        }.sortedByDescending { it.cost }
    }
}
