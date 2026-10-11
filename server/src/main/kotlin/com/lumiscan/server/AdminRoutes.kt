package com.lumiscan.server

import io.ktor.http.Cookie
import io.ktor.http.CookieEncoding
import io.ktor.http.HttpMethod
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.httpMethod
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.sql.SQLException
import java.time.LocalDate

const val ADMIN_COOKIE = "lumi_admin"

data class AdminCtx(val id: Long, val username: String, val role: String, val mustChange: Boolean, val ip: String) {
    val isSuper get() = role == "super"
}

/**
 * Admin auth: an HttpOnly, SameSite=Strict cookie, plus a custom header on every write so a
 * cross-site form can't drive the API even in browsers that ignore SameSite.
 */
suspend fun ApplicationCall.requireAdmin(s: Services, superOnly: Boolean = false, allowMustChange: Boolean = false): AdminCtx {
    val token = request.cookies[ADMIN_COOKIE] ?: throw ApiError(401, "unauthorized", "请先登录")
    val ip = ip()
    val a = withContext(Dispatchers.IO) {
        s.db.one(
            "SELECT a.id, a.username, a.role, a.must_change_password FROM admin_sessions x JOIN admins a ON a.id = x.admin_id WHERE x.token_hash=? AND x.expires_at > ?",
            Tokens.sha256(token), s.now(),
        ) { AdminCtx(it.getLong(1), it.getString(2), it.getString(3), it.getInt(4) == 1, ip) }
    } ?: throw ApiError(401, "unauthorized", "登录已过期，请重新登录")
    if (request.httpMethod != HttpMethod.Get && request.headers["X-Lumi-Admin"] != "1") throw ApiError(403, "csrf", "请求被拒绝")
    if (a.mustChange && !allowMustChange) throw ApiError(403, "must_change_password", "请先修改初始密码")
    if (superOnly && !a.isSuper) throw ApiError(403, "forbidden", "仅超级管理员可以执行此操作")
    return a
}

fun Services.audit(a: AdminCtx, action: String, target: String = "", detail: String = "") {
    db.exec(
        "INSERT INTO audit_logs(admin_id, admin_name, action, target, detail, ip, created_at) VALUES(?,?,?,?,?,?,?)",
        a.id, a.username, action, target, detail.take(1000), a.ip, now(),
    )
}

// ---- JSON body helpers -------------------------------------------------------------------

private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull

private fun JsonObject.bool(k: String): Boolean? = (this[k] as? JsonPrimitive)?.let { it.booleanOrNull ?: it.contentOrNull?.toBooleanStrictOrNull() }

/** Number field where null / "" means "not set". */
private fun JsonObject.nullableLong(k: String, label: String): Long? {
    val c = str(k)?.trim().orEmpty()
    if (c.isEmpty()) return null
    return c.toLongOrNull() ?: throw bad("$label 必须是整数")
}

private fun JsonObject.requiredLong(k: String, label: String, range: LongRange): Long {
    val v = nullableLong(k, label) ?: throw bad("请填写$label")
    if (v !in range) throw bad("$label 需在 ${range.first}–${range.last} 之间")
    return v
}

private fun ApplicationCall.idParam(): Long = parameters["id"]?.toLongOrNull() ?: throw notFound()

private fun ApplicationCall.paging(): Pair<Int, Int> {
    val p = request.queryParameters
    return (p["page"]?.toIntOrNull()?.coerceAtLeast(1) ?: 1) to (p["size"]?.toIntOrNull()?.coerceIn(1, 100) ?: 20)
}

private fun pageJson(items: List<JsonObject>, total: Long, page: Int, size: Int) = buildJsonObject {
    put("items", JsonArray(items))
    put("total", total)
    put("page", page)
    put("size", size)
}

private fun maskKey(k: String) = when {
    k.isEmpty() -> ""
    k.length <= 8 -> "****"
    else -> k.take(3) + "****" + k.takeLast(4)
}

private val GROUP_COLUMNS = "g.id, g.name, g.description, g.daily_ai_requests, g.monthly_ai_tokens, g.daily_image_requests, g.daily_ocr_pages, " +
    "g.max_input_chars, g.max_devices, g.features, g.is_default, g.sort, g.created_at"

private val USER_COLUMNS = "u.id, u.username, u.nickname, u.email, u.status, u.ban_reason, u.group_id, g.name AS group_name, u.group_expires_at, " +
    "u.daily_ai_requests, u.monthly_ai_tokens, u.note, u.created_at, u.register_ip, u.last_login_at, u.last_login_ip, u.last_seen_at"

/** Validated column values for creating / updating a group. */
private fun groupValues(b: JsonObject): List<Pair<String, Any?>> {
    val name = b.str("name")?.trim().orEmpty()
    if (name.length !in 1..20) throw bad("分组名称需为 1–20 个字")
    val desc = b.str("description")?.trim().orEmpty()
    if (desc.length > 100) throw bad("分组说明最多 100 字")
    val features = when (val f = b["features"]) {
        is JsonArray -> f.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.joinToString(",")
        else -> b.str("features").orEmpty()
    }.let { csv -> Feature.entries.map { it.key }.filter { it in Feature.parse(csv) }.joinToString(",") }
    return listOf(
        "name" to name,
        "description" to desc,
        "daily_ai_requests" to b.requiredLong("daily_ai_requests", "每日 AI 次数", -1L..1_000_000L),
        "monthly_ai_tokens" to b.requiredLong("monthly_ai_tokens", "每月 AI Tokens", -1L..100_000_000_000L),
        "daily_image_requests" to (if ("daily_image_requests" in b) b.requiredLong("daily_image_requests", "每日 AI 图像处理次数", -1L..1_000_000L) else 5L),
        "daily_ocr_pages" to (if ("daily_ocr_pages" in b) b.requiredLong("daily_ocr_pages", "每日 AI 识别页数", -1L..1_000_000L) else 50L),
        "max_input_chars" to b.requiredLong("max_input_chars", "单次输入字数", 100L..120_000L),
        "max_devices" to b.requiredLong("max_devices", "同时登录设备数", 1L..50L),
        "features" to features,
        "sort" to (b.nullableLong("sort", "排序") ?: 0L),
    )
}

fun Route.adminRoutes(s: Services) = route("/admin/api") {

    // ---- session ----

    post("/login") {
        val b = call.receive<JsonObject>()
        val username = b.str("username")?.trim().orEmpty()
        val password = b.str("password").orEmpty()
        val ip = call.ip()
        if (!s.adminIpLimiter.allow(ip)) throw ApiError(429, "rate_limited", "尝试过于频繁，请稍后再试")
        val key = username.lowercase()
        if (s.adminFailLimiter.blocked(key)) throw ApiError(429, "locked", "密码错误次数过多，请 15 分钟后再试")
        data class Row(val id: Long, val username: String, val hash: String, val role: String, val mustChange: Boolean)
        val row = s.db.one("SELECT id, username, password_hash, role, must_change_password FROM admins WHERE username=?", username) {
            Row(it.getLong(1), it.getString(2), it.getString(3), it.getString(4), it.getInt(5) == 1)
        }
        val ok = withContext(Dispatchers.Default) { Passwords.verify(password, row?.hash ?: Passwords.dummyHash) && row != null }
        if (!ok || row == null) {
            s.adminFailLimiter.hit(key)
            s.log.warn("admin login failed for '{}' from {}", username, ip)
            throw ApiError(401, "bad_credentials", "账号或密码错误")
        }
        s.adminFailLimiter.reset(key)
        val token = Tokens.newToken()
        val now = s.now()
        s.db.exec("INSERT INTO admin_sessions(token_hash, admin_id, ip, created_at, expires_at) VALUES(?,?,?,?,?)", Tokens.sha256(token), row.id, ip, now, now + Services.ADMIN_SESSION_TTL)
        s.db.exec("UPDATE admins SET last_login_at=?, last_login_ip=? WHERE id=?", now, ip, row.id)
        call.response.cookies.append(
            Cookie(
                name = ADMIN_COOKIE, value = token, encoding = CookieEncoding.RAW,
                maxAge = (Services.ADMIN_SESSION_TTL / 1000).toInt(), path = "/admin",
                secure = s.cfg.cookieSecure, httpOnly = true, extensions = mapOf("SameSite" to "Strict"),
            )
        )
        s.audit(AdminCtx(row.id, row.username, row.role, row.mustChange, ip), "登录后台")
        call.respond(buildJsonObject { putJsonObject("admin") { put("id", row.id); put("username", row.username); put("role", row.role); put("mustChangePassword", row.mustChange) } })
    }

    post("/logout") {
        call.request.cookies[ADMIN_COOKIE]?.let { s.db.exec("DELETE FROM admin_sessions WHERE token_hash=?", Tokens.sha256(it)) }
        call.response.cookies.append(Cookie(ADMIN_COOKIE, "", CookieEncoding.RAW, maxAge = 0, path = "/admin", secure = s.cfg.cookieSecure, httpOnly = true, extensions = mapOf("SameSite" to "Strict")))
        call.respond(Ok())
    }

    get("/me") {
        val a = call.requireAdmin(s, allowMustChange = true)
        call.respond(buildJsonObject {
            putJsonObject("admin") { put("id", a.id); put("username", a.username); put("role", a.role); put("mustChangePassword", a.mustChange) }
            putJsonArray("features") { Feature.entries.forEach { f -> addJsonObject { put("key", f.key); put("label", f.label) } } }
        })
    }

    post("/me/password") {
        val a = call.requireAdmin(s, allowMustChange = true)
        val b = call.receive<JsonObject>()
        val hash = s.db.one("SELECT password_hash FROM admins WHERE id=?", a.id) { it.getString(1) } ?: throw notFound()
        val ok = withContext(Dispatchers.Default) { Passwords.verify(b.str("oldPassword").orEmpty(), hash) }
        if (!ok) throw ApiError(400, "bad_password", "原密码不正确")
        val newPw = b.str("newPassword").orEmpty()
        if (newPw.length < 10) throw bad("管理员密码至少 10 位")
        Validate.password(newPw)
        val newHash = withContext(Dispatchers.Default) { Passwords.hash(newPw) }
        val current = Tokens.sha256(call.request.cookies[ADMIN_COOKIE]!!)
        s.db.tx {
            s.db.exec("UPDATE admins SET password_hash=?, must_change_password=0 WHERE id=?", newHash, a.id)
            s.db.exec("DELETE FROM admin_sessions WHERE admin_id=? AND token_hash<>?", a.id, current)
        }
        if (s.db.long("SELECT COUNT(*) FROM admins WHERE must_change_password = 1") == 0L) java.io.File(s.cfg.dataDir, "initial-admin.txt").delete()
        s.audit(a, "修改自己的密码")
        call.respond(Ok())
    }

    // ---- dashboard ----

    get("/dashboard") {
        call.requireAdmin(s)
        s.expireMemberships()
        val now = s.now()
        val todayStart = s.dayStart()
        val today = s.today()
        val todayUsage = s.db.one("SELECT COALESCE(SUM(ai_requests),0), COALESCE(SUM(ai_tokens),0) FROM usage_daily WHERE day=?", today) { it.getLong(1) to it.getLong(2) }!!
        val cfg = s.ai.config()
        // Cached for 30 minutes; a retired model shows up as a warning on the dashboard.
        val retired = runCatching { s.vision.checkModels() }.getOrDefault(emptyList()).filter { it.available == false }
        call.respond(buildJsonObject {
            putJsonArray("retiredModels") {
                retired.forEach { m -> addJsonObject { put("label", m.label); put("model", m.model) } }
            }
            putJsonObject("users") {
                put("total", s.db.long("SELECT COUNT(*) FROM users"))
                put("today", s.db.long("SELECT COUNT(*) FROM users WHERE created_at >= ?", todayStart))
                put("activeToday", s.db.long("SELECT COUNT(*) FROM users WHERE last_seen_at >= ?", todayStart))
                put("active7d", s.db.long("SELECT COUNT(*) FROM users WHERE last_seen_at >= ?", now - 7 * Services.DAY))
                put("banned", s.db.long("SELECT COUNT(*) FROM users WHERE status='banned'"))
            }
            putJsonObject("ai") {
                put("todayRequests", todayUsage.first)
                put("todayTokens", todayUsage.second)
                put("monthTokens", s.db.long("SELECT COALESCE(SUM(ai_tokens),0) FROM usage_daily WHERE day LIKE ?", "${s.month()}-%"))
                put("todayErrors", s.db.long("SELECT COUNT(*) FROM ai_logs WHERE status='error' AND created_at >= ?", todayStart))
                put("todayImages", s.db.long("SELECT COALESCE(SUM(image_requests),0) FROM usage_daily WHERE day=?", today))
                put("todayOcr", s.db.long("SELECT COALESCE(SUM(ocr_pages),0) FROM usage_daily WHERE day=?", today))
                put("todayRejected", s.db.long("SELECT COUNT(*) FROM ai_logs WHERE status='rejected' AND created_at >= ?", todayStart))
                put("configured", cfg.apiKey.isNotEmpty())
                put("textModel", cfg.textModel)
                put("visionModel", cfg.visionModel)
            }
            putJsonArray("trend") {
                val start = LocalDate.now(s.cfg.zone).minusDays(13)
                for (i in 0 until 14) {
                    val d = start.plusDays(i.toLong())
                    val from = s.dayStart(d)
                    val to = s.dayStart(d.plusDays(1))
                    val u = s.db.one("SELECT COALESCE(SUM(ai_requests),0), COALESCE(SUM(ai_tokens),0) FROM usage_daily WHERE day=?", d.toString()) { it.getLong(1) to it.getLong(2) }!!
                    addJsonObject {
                        put("day", d.toString())
                        put("newUsers", s.db.long("SELECT COUNT(*) FROM users WHERE created_at >= ? AND created_at < ?", from, to))
                        put("aiRequests", u.first)
                        put("aiTokens", u.second)
                    }
                }
            }
            put("groups", JsonArray(s.db.rows("SELECT g.id, g.name, COUNT(u.id) AS users FROM user_groups g LEFT JOIN users u ON u.group_id = g.id GROUP BY g.id ORDER BY g.sort, g.id")))
            put(
                "topUsers",
                JsonArray(
                    s.db.rows(
                        "SELECT u.id, u.username, u.nickname, d.ai_requests, d.ai_tokens FROM usage_daily d JOIN users u ON u.id = d.user_id WHERE d.day=? ORDER BY d.ai_tokens DESC, d.ai_requests DESC LIMIT 10",
                        today,
                    )
                )
            )
            put("registrationOpen", s.settings.bool(Settings.REGISTRATION_OPEN))
            put("openFeedback", s.db.long("SELECT COUNT(*) FROM feedback WHERE status='open'"))
            // Estimated model spend (call log × the price table in settings).
            val monthCosts = Costs.since(s, s.dayStart(LocalDate.now(s.cfg.zone).withDayOfMonth(1)))
            putJsonObject("cost") {
                put("today", Costs.since(s, todayStart).sumOf { it.cost })
                put("month", monthCosts.sumOf { it.cost })
                put("byModel", AppJson.encodeToJsonElement(kotlinx.serialization.builtins.ListSerializer(ModelCost.serializer()), monthCosts))
            }
        })
    }

    // ---- users ----

    get("/users") {
        call.requireAdmin(s)
        s.expireMemberships()
        val p = call.request.queryParameters
        val where = mutableListOf<String>()
        val args = mutableListOf<Any?>()
        p["q"]?.trim()?.takeIf { it.isNotEmpty() }?.let { q ->
            val like = "%" + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
            where += "(u.username LIKE ? ESCAPE '\\' OR u.nickname LIKE ? ESCAPE '\\' OR u.email LIKE ? ESCAPE '\\' OR u.id = ?)"
            args.addAll(listOf(like, like, like, q.toLongOrNull() ?: -1L))
        }
        p["groupId"]?.toLongOrNull()?.let { where += "u.group_id = ?"; args += it }
        p["status"]?.takeIf { it == "active" || it == "banned" }?.let { where += "u.status = ?"; args += it }
        val w = if (where.isEmpty()) "" else "WHERE " + where.joinToString(" AND ")
        val order = when (p["sort"]) {
            "oldest" -> "u.id ASC"
            "active" -> "u.last_seen_at DESC NULLS LAST, u.id DESC"
            "usage" -> "today_tokens DESC, today_requests DESC, u.id DESC"
            else -> "u.id DESC"
        }
        val (page, size) = call.paging()
        val total = s.db.long("SELECT COUNT(*) FROM users u $w", *args.toTypedArray())
        val items = s.db.rows(
            "SELECT $USER_COLUMNS, COALESCE(d.ai_requests,0) AS today_requests, COALESCE(d.ai_tokens,0) AS today_tokens, " +
                "COALESCE(d.image_requests,0) AS today_images, COALESCE(d.ocr_pages,0) AS today_ocr, " +
                "(SELECT COUNT(*) FROM sessions x WHERE x.user_id = u.id) AS sessions " +
                "FROM users u JOIN user_groups g ON g.id = u.group_id LEFT JOIN usage_daily d ON d.user_id = u.id AND d.day = ? " +
                "$w ORDER BY $order LIMIT ? OFFSET ?",
            s.today(), *args.toTypedArray(), size, (page - 1) * size,
        )
        call.respond(pageJson(items, total, page, size))
    }

    post("/users") {
        val a = call.requireAdmin(s)
        val b = call.receive<JsonObject>()
        val username = Validate.username(b.str("username").orEmpty())
        val password = Validate.password(b.str("password").orEmpty())
        val groupId = b.nullableLong("groupId", "分组") ?: s.defaultGroup().id
        if (s.group(groupId) == null) throw bad("分组不存在")
        val hash = withContext(Dispatchers.Default) { Passwords.hash(password) }
        val id = try {
            s.db.insert(
                "INSERT INTO users(username, nickname, email, password_hash, group_id, group_expires_at, note, created_at, register_ip) VALUES(?,?,?,?,?,?,?,?,?)",
                username, Validate.nickname(b.str("nickname")), Validate.email(b.str("email")), hash, groupId,
                b.nullableLong("groupExpiresAt", "到期时间"), b.str("note")?.take(500).orEmpty(), s.now(), "admin",
            )
        } catch (e: SQLException) {
            if (e.message?.contains("UNIQUE", true) == true) throw ApiError(409, "username_taken", "该账号已存在")
            throw e
        }
        s.audit(a, "创建用户", "#$id $username")
        call.respond(buildJsonObject { put("id", id) })
    }

    get("/users/{id}") {
        call.requireAdmin(s)
        val id = call.idParam()
        val u = s.user(id) ?: throw notFound("用户不存在")
        val lim = s.limits(u)
        call.respond(buildJsonObject {
            put("user", s.db.row("SELECT $USER_COLUMNS FROM users u JOIN user_groups g ON g.id = u.group_id WHERE u.id=?", id)!!)
            put("quota", AppJson.encodeToJsonElement(QuotaInfo.serializer(), s.quotaInfo(u, lim)))
            put("sessions", JsonArray(s.db.rows("SELECT token_hash AS id, device, app_version, ip, created_at, last_seen_at, expires_at FROM sessions WHERE user_id=? ORDER BY last_seen_at DESC", id)))
            put("usage", JsonArray(s.db.rows("SELECT day, ai_requests, ai_tokens, image_requests, ocr_pages FROM usage_daily WHERE user_id=? ORDER BY day DESC LIMIT 30", id)))
            put("logs", JsonArray(s.db.rows("SELECT id, feature, model, prompt_tokens, completion_tokens, input_chars, status, error, latency_ms, ip, created_at FROM ai_logs WHERE user_id=? ORDER BY id DESC LIMIT 20", id)))
        })
    }

    patch("/users/{id}") {
        val a = call.requireAdmin(s)
        val id = call.idParam()
        val u = s.user(id) ?: throw notFound("用户不存在")
        val b = call.receive<JsonObject>()
        val sets = mutableListOf<Pair<String, Any?>>()
        val changes = mutableListOf<String>()
        if ("nickname" in b) sets += "nickname" to Validate.nickname(b.str("nickname"))
        if ("email" in b) sets += "email" to Validate.email(b.str("email"))
        if ("note" in b) sets += "note" to b.str("note")?.take(500).orEmpty()
        if ("groupId" in b) {
            val gid = b.nullableLong("groupId", "分组") ?: throw bad("请选择分组")
            val g = s.group(gid) ?: throw bad("分组不存在")
            if (gid != u.groupId) changes += "分组→${g.name}"
            sets += "group_id" to gid
        }
        if ("groupExpiresAt" in b) {
            val exp = b.nullableLong("groupExpiresAt", "到期时间")
            if (exp != u.groupExpiresAt) changes += "到期→" + (exp?.let { java.time.Instant.ofEpochMilli(it).atZone(s.cfg.zone).toLocalDate().toString() } ?: "永久")
            sets += "group_expires_at" to exp
        }
        if ("dailyAiRequests" in b) {
            val v = b.nullableLong("dailyAiRequests", "每日 AI 次数")?.also { if (it !in -1L..1_000_000L) throw bad("每日 AI 次数超出范围") }
            if (v?.toInt() != u.dailyOverride) changes += "每日次数→${v ?: "跟随分组"}"
            sets += "daily_ai_requests" to v
        }
        if ("monthlyAiTokens" in b) {
            val v = b.nullableLong("monthlyAiTokens", "每月 Tokens")?.also { if (it !in -1L..100_000_000_000L) throw bad("每月 Tokens 超出范围") }
            if (v != u.monthlyOverride) changes += "每月Tokens→${v ?: "跟随分组"}"
            sets += "monthly_ai_tokens" to v
        }
        var banned = false
        if ("status" in b) {
            val st = b.str("status")
            if (st != "active" && st != "banned") throw bad("状态不正确")
            if (st != u.status) changes += if (st == "banned") "封禁" else "解封"
            banned = st == "banned" && u.status != "banned"
            sets += "status" to st
        }
        if ("banReason" in b) sets += "ban_reason" to b.str("banReason")?.trim()?.take(100)
        if (sets.isNotEmpty()) {
            s.db.tx {
                s.db.exec("UPDATE users SET ${sets.joinToString(", ") { "${it.first}=?" }} WHERE id=?", *sets.map { it.second }.toTypedArray(), id)
                if (banned) s.db.exec("DELETE FROM sessions WHERE user_id=?", id)
            }
            s.audit(a, "修改用户", "#$id ${u.username}", changes.joinToString("；").ifEmpty { "资料" })
        }
        call.respond(Ok())
    }

    post("/users/{id}/reset-password") {
        val a = call.requireAdmin(s)
        val id = call.idParam()
        val u = s.user(id) ?: throw notFound("用户不存在")
        val pw = Tokens.randomPassword(10)
        val hash = withContext(Dispatchers.Default) { Passwords.hash(pw) }
        s.db.tx {
            s.db.exec("UPDATE users SET password_hash=? WHERE id=?", hash, id)
            s.db.exec("DELETE FROM sessions WHERE user_id=?", id)
        }
        s.audit(a, "重置用户密码", "#$id ${u.username}")
        call.respond(buildJsonObject { put("password", pw) })
    }

    post("/users/{id}/kick") {
        val a = call.requireAdmin(s)
        val id = call.idParam()
        val u = s.user(id) ?: throw notFound("用户不存在")
        val sessionId = runCatching { call.receive<JsonObject>() }.getOrNull()?.str("sessionId")
        val n = if (sessionId != null) s.db.exec("DELETE FROM sessions WHERE user_id=? AND token_hash=?", id, sessionId)
        else s.db.exec("DELETE FROM sessions WHERE user_id=?", id)
        s.audit(a, "强制下线", "#$id ${u.username}", "$n 台设备")
        call.respond(buildJsonObject { put("removed", n) })
    }

    delete("/users/{id}") {
        val a = call.requireAdmin(s)
        val id = call.idParam()
        val u = s.user(id) ?: throw notFound("用户不存在")
        s.db.exec("DELETE FROM users WHERE id=?", id)
        s.audit(a, "删除用户", "#$id ${u.username}")
        call.respond(Ok())
    }

    post("/users/batch") {
        val a = call.requireAdmin(s)
        val b = call.receive<JsonObject>()
        val ids = (b["ids"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.toLongOrNull() }?.distinct().orEmpty()
        if (ids.isEmpty() || ids.size > 500) throw bad("请选择 1–500 个用户")
        val marks = ids.joinToString(",") { "?" }
        val idArgs = ids.toTypedArray<Any?>()
        val action = b.str("action")
        val n = s.db.tx {
            when (action) {
                "group" -> {
                    val gid = b.nullableLong("groupId", "分组") ?: throw bad("请选择分组")
                    if (s.group(gid) == null) throw bad("分组不存在")
                    val exp = b.nullableLong("groupExpiresAt", "到期时间")
                    s.db.exec("UPDATE users SET group_id=?, group_expires_at=? WHERE id IN ($marks)", gid, exp, *idArgs)
                }
                "ban" -> s.db.exec("UPDATE users SET status='banned' WHERE id IN ($marks)", *idArgs)
                    .also { s.db.exec("DELETE FROM sessions WHERE user_id IN ($marks)", *idArgs) }
                "unban" -> s.db.exec("UPDATE users SET status='active' WHERE id IN ($marks)", *idArgs)
                "delete" -> s.db.exec("DELETE FROM users WHERE id IN ($marks)", *idArgs)
                else -> throw bad("未知操作")
            }
        }
        val label = mapOf("group" to "批量调整分组", "ban" to "批量封禁", "unban" to "批量解封", "delete" to "批量删除")[action]!!
        s.audit(a, label, "${ids.size} 个用户", ids.joinToString(",") { "#$it" }.take(500))
        call.respond(buildJsonObject { put("affected", n) })
    }

    // ---- groups ----

    get("/groups") {
        call.requireAdmin(s)
        call.respond(JsonArray(s.db.rows("SELECT $GROUP_COLUMNS, (SELECT COUNT(*) FROM users u WHERE u.group_id = g.id) AS users FROM user_groups g ORDER BY g.sort, g.id")))
    }

    post("/groups") {
        val a = call.requireAdmin(s)
        val v = groupValues(call.receive<JsonObject>())
        val id = try {
            s.db.insert(
                "INSERT INTO user_groups(${v.joinToString(",") { it.first }}, created_at) VALUES(${v.joinToString(",") { "?" }}, ?)",
                *v.map { it.second }.toTypedArray(), s.now(),
            )
        } catch (e: SQLException) {
            if (e.message?.contains("UNIQUE", true) == true) throw ApiError(409, "conflict", "分组名称已存在")
            throw e
        }
        s.audit(a, "创建分组", "#$id ${v[0].second}")
        call.respond(buildJsonObject { put("id", id) })
    }

    patch("/groups/{id}") {
        val a = call.requireAdmin(s)
        val id = call.idParam()
        s.group(id) ?: throw notFound("分组不存在")
        val v = groupValues(call.receive<JsonObject>())
        try {
            s.db.exec("UPDATE user_groups SET ${v.joinToString(",") { "${it.first}=?" }} WHERE id=?", *v.map { it.second }.toTypedArray(), id)
        } catch (e: SQLException) {
            if (e.message?.contains("UNIQUE", true) == true) throw ApiError(409, "conflict", "分组名称已存在")
            throw e
        }
        s.audit(a, "修改分组", "#$id ${v[0].second}", v.drop(2).joinToString("；") { "${it.first}=${it.second}" })
        call.respond(Ok())
    }

    post("/groups/{id}/default") {
        val a = call.requireAdmin(s)
        val id = call.idParam()
        val g = s.group(id) ?: throw notFound("分组不存在")
        s.db.tx {
            s.db.exec("UPDATE user_groups SET is_default=0")
            s.db.exec("UPDATE user_groups SET is_default=1 WHERE id=?", id)
        }
        s.audit(a, "设为默认分组", "#$id ${g.name}")
        call.respond(Ok())
    }

    delete("/groups/{id}") {
        val a = call.requireAdmin(s)
        val id = call.idParam()
        val g = s.group(id) ?: throw notFound("分组不存在")
        if (g.isDefault) throw bad("默认分组不能删除，请先把其他分组设为默认")
        val n = s.db.long("SELECT COUNT(*) FROM users WHERE group_id=?", id)
        if (n > 0) throw bad("该分组还有 $n 个用户，请先把他们移到其他分组")
        s.db.exec("DELETE FROM user_groups WHERE id=?", id)
        s.audit(a, "删除分组", "#$id ${g.name}")
        call.respond(Ok())
    }

    // ---- AI logs / feedback / audit ----

    get("/ai-logs") {
        call.requireAdmin(s)
        val p = call.request.queryParameters
        val where = mutableListOf<String>()
        val args = mutableListOf<Any?>()
        p["userId"]?.toLongOrNull()?.let { where += "l.user_id = ?"; args += it }
        p["username"]?.trim()?.takeIf { it.isNotEmpty() }?.let { where += "u.username = ?"; args += it }
        p["feature"]?.takeIf { Feature.of(it) != null }?.let { where += "l.feature = ?"; args += it }
        p["status"]?.takeIf { it in setOf("ok", "error", "rejected") }?.let { where += "l.status = ?"; args += it }
        val w = if (where.isEmpty()) "" else "WHERE " + where.joinToString(" AND ")
        val (page, size) = call.paging()
        val from = "FROM ai_logs l LEFT JOIN users u ON u.id = l.user_id $w"
        val total = s.db.long("SELECT COUNT(*) $from", *args.toTypedArray())
        val items = s.db.rows(
            "SELECT l.id, l.user_id, u.username, l.feature, l.model, l.prompt_tokens, l.completion_tokens, l.input_chars, l.status, l.error, l.latency_ms, l.ip, l.created_at " +
                "$from ORDER BY l.id DESC LIMIT ? OFFSET ?",
            *args.toTypedArray(), size, (page - 1) * size,
        )
        call.respond(pageJson(items, total, page, size))
    }

    get("/feedback") {
        call.requireAdmin(s)
        val status = call.request.queryParameters["status"]?.takeIf { it == "open" || it == "done" }
        val w = if (status != null) "WHERE status = ?" else ""
        val args = listOfNotNull(status).toTypedArray<Any?>()
        val (page, size) = call.paging()
        val total = s.db.long("SELECT COUNT(*) FROM feedback $w", *args)
        val items = s.db.rows("SELECT * FROM feedback $w ORDER BY id DESC LIMIT ? OFFSET ?", *args, size, (page - 1) * size)
        call.respond(pageJson(items, total, page, size))
    }

    patch("/feedback/{id}") {
        val a = call.requireAdmin(s)
        val id = call.idParam()
        val b = call.receive<JsonObject>()
        if ("status" in b) {
            val st = b.str("status")
            if (st != "open" && st != "done") throw bad("状态不正确")
            s.db.exec("UPDATE feedback SET status=? WHERE id=?", st, id)
        }
        if ("adminNote" in b) s.db.exec("UPDATE feedback SET admin_note=? WHERE id=?", b.str("adminNote")?.take(500).orEmpty(), id)
        s.audit(a, "处理反馈", "#$id")
        call.respond(Ok())
    }

    delete("/feedback/{id}") {
        val a = call.requireAdmin(s)
        val id = call.idParam()
        s.db.exec("DELETE FROM feedback WHERE id=?", id)
        s.audit(a, "删除反馈", "#$id")
        call.respond(Ok())
    }

    get("/audit-logs") {
        call.requireAdmin(s)
        val (page, size) = call.paging()
        val total = s.db.long("SELECT COUNT(*) FROM audit_logs")
        call.respond(pageJson(s.db.rows("SELECT * FROM audit_logs ORDER BY id DESC LIMIT ? OFFSET ?", size, (page - 1) * size), total, page, size))
    }

    // ---- settings ----

    get("/settings") {
        call.requireAdmin(s)
        val all = s.settings.all()
        call.respond(buildJsonObject {
            putJsonObject("values") {
                Settings.defaults().keys.forEach { k ->
                    val v = all[k] ?: Settings.defaults()[k].orEmpty()
                    put(k, if (k == Settings.AI_API_KEY || k == Settings.DASHSCOPE_API_KEY) maskKey(v) else v)
                }
            }
            put("hasApiKey", all[Settings.AI_API_KEY].orEmpty().isNotEmpty())
            put("hasDashscopeKey", all[Settings.DASHSCOPE_API_KEY].orEmpty().isNotEmpty())
        })
    }

    put("/settings") {
        val a = call.requireAdmin(s)
        val b = call.receive<JsonObject>()
        val updates = linkedMapOf<String, String>()
        for ((k, el) in b) {
            val v = (el as? JsonPrimitive)?.contentOrNull ?: continue
            when (k) {
                Settings.REGISTRATION_OPEN -> updates[k] = (v.toBooleanStrictOrNull() ?: throw bad("开放注册取值不正确")).toString()
                Settings.INVITE_CODE -> updates[k] = v.trim().also { if (it.length > 32) throw bad("邀请码最多 32 位") }
                Settings.AI_BASE_URL -> updates[k] = v.trim().trimEnd('/').also {
                    if (!(it.startsWith("https://") || it.startsWith("http://")) || it.length > 200) throw bad("接口地址需以 http(s):// 开头")
                }
                Settings.AI_API_KEY -> if (v.isNotBlank() && "****" !in v) updates[k] = v.trim()
                "ai_api_key_clear" -> if (v.toBoolean()) updates[Settings.AI_API_KEY] = ""
                Settings.AI_TEXT_MODEL, Settings.AI_VISION_MODEL, Settings.OCR_MODEL, Settings.DETECT_MODEL,
                Settings.IDPHOTO_MODEL, Settings.EDIT_MODEL, Settings.RESTORE_MODEL, Settings.UPSCALE_MODEL ->
                    updates[k] = v.trim().also { if (it.isEmpty() || it.length > 100) throw bad("模型名称不能为空") }
                Settings.DASHSCOPE_BASE_URL -> updates[k] = v.trim().trimEnd('/').also {
                    if (!(it.startsWith("https://") || it.startsWith("http://")) || it.length > 200) throw bad("百炼接口地址需以 http(s):// 开头")
                }
                Settings.IMAGE_AI_AUTO -> updates[k] = (v.toBooleanStrictOrNull() ?: throw bad("自动使用 AI 取值不正确")).toString()
                Settings.MODEL_PRICES -> updates[k] = v.trim().also {
                    val prices = runCatching { Costs.parse(it) }.getOrNull()
                    if (it.length > 5000 || prices == null || prices.values.any { p -> p.perCall < 0 || p.input < 0 || p.output < 0 }) {
                        throw bad("模型单价需为 JSON，例如 {\"qwen-image-3.0\": {\"perCall\": 0.2}, \"qwen-plus\": {\"input\": 0.8, \"output\": 2}}")
                    }
                }
                Settings.DASHSCOPE_API_KEY -> if (v.isNotBlank() && "****" !in v) updates[k] = v.trim()
                "dashscope_api_key_clear" -> if (v.toBoolean()) updates[Settings.DASHSCOPE_API_KEY] = ""
                Settings.AI_MAX_OUTPUT_TOKENS -> updates[k] = (v.trim().toIntOrNull()?.takeIf { it in 100..32_000 } ?: throw bad("最大输出 Tokens 需在 100–32000 之间")).toString()
                Settings.AI_TIMEOUT_SECONDS -> updates[k] = (v.trim().toIntOrNull()?.takeIf { it in 10..300 } ?: throw bad("超时需在 10–300 秒之间")).toString()
                Settings.AI_EXTRA_BODY -> updates[k] = v.trim().also {
                    if (it.isNotEmpty() && (it.length > 2000 || runCatching { AppJson.parseToJsonElement(it).jsonObject }.isFailure)) throw bad("附加参数需为 JSON 对象，例如 {\"enable_thinking\": false}")
                }
                Settings.ANNOUNCEMENT -> updates[k] = v.trim().also { if (it.length > 2000) throw bad("公告最多 2000 字") }
                Settings.MIN_VERSION_CODE, Settings.LATEST_VERSION_CODE -> updates[k] = (v.trim().ifEmpty { "0" }.toIntOrNull()?.takeIf { it >= 0 } ?: throw bad("版本号需为非负整数")).toString()
                Settings.UPDATE_URL -> updates[k] = v.trim().also {
                    if (it.isNotEmpty() && (!(it.startsWith("https://") || it.startsWith("http://")) || it.length > 300)) throw bad("下载地址需以 http(s):// 开头")
                }
                Settings.UPDATE_NOTE -> updates[k] = v.trim().also { if (it.length > 1000) throw bad("更新说明最多 1000 字") }
                Settings.PRIVACY_POLICY, Settings.USER_TERMS -> updates[k] = v.also { if (it.isBlank() || it.length > 50_000) throw bad("协议内容不能为空且不超过 5 万字") }
                else -> throw bad("未知设置项：$k")
            }
        }
        s.db.tx { updates.forEach { (k, v) -> s.settings.set(k, v) } }
        if (updates.isNotEmpty()) s.audit(a, "修改系统设置", updates.keys.joinToString(","))
        call.respond(Ok())
    }

    post("/settings/test-ai") {
        val a = call.requireAdmin(s)
        val vision = runCatching { call.receive<JsonObject>() }.getOrNull()?.bool("vision") == true
        val started = System.currentTimeMillis()
        val result = try {
            val (model, c) = s.ai.test(vision)
            buildJsonObject {
                put("ok", true); put("model", model); put("reply", c.content.take(200))
                put("latencyMs", System.currentTimeMillis() - started)
            }
        } catch (e: ApiError) {
            throw e
        } catch (e: Throwable) {
            buildJsonObject {
                put("ok", false)
                put("error", (if (e is UpstreamError) "HTTP ${e.status}：" else "") + (e.message ?: e.javaClass.simpleName))
                put("latencyMs", System.currentTimeMillis() - started)
            }
        }
        s.audit(a, "测试 AI 连接", if (vision) "视觉模型" else "文本模型", result["ok"]!!.jsonPrimitive.content)
        call.respond(result)
    }

    post("/settings/test-vision") {
        val a = call.requireAdmin(s)
        val started = System.currentTimeMillis()
        val sample = java.util.Base64.getEncoder().encodeToString(
            Settings::class.java.getResourceAsStream("/samples/ocr-test.jpg")!!.use { it.readBytes() },
        )
        val result = try {
            val text = s.vision.testOcr(sample)
            buildJsonObject { put("ok", true); put("reply", text.take(200)); put("latencyMs", System.currentTimeMillis() - started) }
        } catch (e: ApiError) {
            throw e
        } catch (e: Throwable) {
            buildJsonObject {
                put("ok", false)
                put("error", (if (e is UpstreamError) "HTTP ${e.status}：" else "") + (e.message ?: e.javaClass.simpleName))
                put("latencyMs", System.currentTimeMillis() - started)
            }
        }
        s.audit(a, "测试文字识别", s.settings.get(Settings.OCR_MODEL), result["ok"]!!.jsonPrimitive.content)
        call.respond(result)
    }

    post("/settings/check-models") {
        val a = call.requireAdmin(s)
        val list = s.vision.checkModels(force = true)
        s.audit(a, "检查模型", list.filter { it.available == false }.joinToString(",") { it.model }.ifEmpty { "全部可用" })
        call.respond(list)
    }

    // ---- admins ----

    get("/admins") {
        call.requireAdmin(s, superOnly = true)
        call.respond(JsonArray(s.db.rows("SELECT id, username, role, must_change_password, created_at, last_login_at, last_login_ip FROM admins ORDER BY id")))
    }

    post("/admins") {
        val a = call.requireAdmin(s, superOnly = true)
        val b = call.receive<JsonObject>()
        val username = Validate.username(b.str("username").orEmpty())
        val password = b.str("password").orEmpty()
        if (password.length < 10) throw bad("管理员密码至少 10 位")
        Validate.password(password)
        val role = b.str("role")?.takeIf { it == "super" || it == "operator" } ?: "operator"
        val hash = withContext(Dispatchers.Default) { Passwords.hash(password) }
        val id = try {
            s.db.insert("INSERT INTO admins(username, password_hash, role, must_change_password, created_at) VALUES(?,?,?,1,?)", username, hash, role, s.now())
        } catch (e: SQLException) {
            if (e.message?.contains("UNIQUE", true) == true) throw ApiError(409, "conflict", "管理员账号已存在")
            throw e
        }
        s.audit(a, "添加管理员", "#$id $username", role)
        call.respond(buildJsonObject { put("id", id) })
    }

    post("/admins/{id}/reset-password") {
        val a = call.requireAdmin(s, superOnly = true)
        val id = call.idParam()
        val name = s.db.one("SELECT username FROM admins WHERE id=?", id) { it.getString(1) } ?: throw notFound()
        val pw = Tokens.randomPassword(14)
        val hash = withContext(Dispatchers.Default) { Passwords.hash(pw) }
        s.db.tx {
            s.db.exec("UPDATE admins SET password_hash=?, must_change_password=1 WHERE id=?", hash, id)
            s.db.exec("DELETE FROM admin_sessions WHERE admin_id=?", id)
        }
        s.audit(a, "重置管理员密码", "#$id $name")
        call.respond(buildJsonObject { put("password", pw) })
    }

    delete("/admins/{id}") {
        val a = call.requireAdmin(s, superOnly = true)
        val id = call.idParam()
        if (id == a.id) throw bad("不能删除自己")
        val row = s.db.one("SELECT username, role FROM admins WHERE id=?", id) { it.getString(1) to it.getString(2) } ?: throw notFound()
        if (row.second == "super" && s.db.long("SELECT COUNT(*) FROM admins WHERE role='super'") <= 1) throw bad("至少保留一个超级管理员")
        s.db.exec("DELETE FROM admins WHERE id=?", id)
        s.audit(a, "删除管理员", "#$id ${row.first}")
        call.respond(Ok())
    }
}
