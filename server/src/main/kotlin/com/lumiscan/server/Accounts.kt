package com.lumiscan.server

import kotlinx.serialization.Serializable
import java.sql.ResultSet

data class GroupRow(
    val id: Long,
    val name: String,
    val dailyAiRequests: Int,
    val monthlyAiTokens: Long,
    val dailyImageRequests: Int,
    val dailyOcrPages: Int,
    val maxInputChars: Int,
    val maxDevices: Int,
    val features: Set<String>,
    val isDefault: Boolean,
)

data class UserRow(
    val id: Long,
    val username: String,
    val email: String?,
    val nickname: String,
    val passwordHash: String,
    val groupId: Long,
    val groupExpiresAt: Long?,
    val status: String,
    val banReason: String?,
    val dailyOverride: Int?,
    val monthlyOverride: Long?,
    val createdAt: Long,
)

/** Limits after applying the user's group and any per-user overrides. -1 means unlimited. */
data class Limits(
    val group: GroupRow,
    val dailyRequests: Int,
    val monthlyTokens: Long,
    val dailyImages: Int,
    val dailyOcrPages: Int,
    val maxInputChars: Int,
    val maxDevices: Int,
    val features: Set<String>,
) {
    fun daily(kind: QuotaKind): Int = when (kind) {
        QuotaKind.CHAT -> dailyRequests
        QuotaKind.IMAGE -> dailyImages
        QuotaKind.OCR -> dailyOcrPages
    }
}

@Serializable
data class GroupInfo(val id: Long, val name: String, val expiresAt: Long? = null)

@Serializable
data class QuotaInfo(
    val dailyRequests: Int,
    val dailyUsed: Int,
    val monthlyTokens: Long,
    val monthlyUsed: Long,
    val dailyImages: Int,
    val imagesUsed: Int,
    val dailyOcrPages: Int,
    val ocrUsed: Int,
    val maxInputChars: Int,
    val maxDevices: Int,
    val features: List<String>,
)

@Serializable
data class Profile(
    val id: Long,
    val username: String,
    val nickname: String,
    val email: String? = null,
    val createdAt: Long,
    val group: GroupInfo,
    val quota: QuotaInfo,
)

private fun groupOf(rs: ResultSet) = GroupRow(
    id = rs.getLong("id"),
    name = rs.getString("name"),
    dailyAiRequests = rs.getInt("daily_ai_requests"),
    monthlyAiTokens = rs.getLong("monthly_ai_tokens"),
    dailyImageRequests = rs.getInt("daily_image_requests"),
    dailyOcrPages = rs.getInt("daily_ocr_pages"),
    maxInputChars = rs.getInt("max_input_chars"),
    maxDevices = rs.getInt("max_devices"),
    features = Feature.parse(rs.getString("features")),
    isDefault = rs.getInt("is_default") == 1,
)

private fun userOf(rs: ResultSet) = UserRow(
    id = rs.getLong("id"),
    username = rs.getString("username"),
    email = rs.getString("email"),
    nickname = rs.getString("nickname"),
    passwordHash = rs.getString("password_hash"),
    groupId = rs.getLong("group_id"),
    groupExpiresAt = rs.longOrNull("group_expires_at"),
    status = rs.getString("status"),
    banReason = rs.getString("ban_reason"),
    dailyOverride = rs.intOrNull("daily_ai_requests"),
    monthlyOverride = rs.longOrNull("monthly_ai_tokens"),
    createdAt = rs.getLong("created_at"),
)

fun Services.group(id: Long): GroupRow? = db.one("SELECT * FROM user_groups WHERE id=?", id, map = ::groupOf)

fun Services.defaultGroup(): GroupRow =
    db.one("SELECT * FROM user_groups ORDER BY is_default DESC, sort, id LIMIT 1", map = ::groupOf)
        ?: error("no user groups defined")

fun Services.user(id: Long): UserRow? = db.one("SELECT * FROM users WHERE id=?", id, map = ::userOf)

fun Services.userByName(username: String): UserRow? = db.one("SELECT * FROM users WHERE username=?", username.trim(), map = ::userOf)

/**
 * Moves users whose timed membership (e.g. a month of 会员) has ended back to the default group.
 * Per-user quota overrides belong to that membership, so they are cleared too.
 */
fun Services.expireMemberships(userId: Long? = null): Int {
    val def = defaultGroup()
    val sql = "UPDATE users SET group_id=?, group_expires_at=NULL, daily_ai_requests=NULL, monthly_ai_tokens=NULL " +
        "WHERE group_expires_at IS NOT NULL AND group_expires_at <= ?" + (if (userId != null) " AND id=?" else "")
    val n = if (userId != null) db.exec(sql, def.id, now(), userId) else db.exec(sql, def.id, now())
    if (n > 0) log.info("{} membership(s) expired, moved to '{}'", n, def.name)
    return n
}

fun Services.limits(u: UserRow): Limits {
    val cur = if (u.groupExpiresAt != null && u.groupExpiresAt <= now() && expireMemberships(u.id) > 0) user(u.id)!! else u
    val g = group(cur.groupId) ?: defaultGroup()
    return Limits(
        group = g,
        dailyRequests = cur.dailyOverride ?: g.dailyAiRequests,
        monthlyTokens = cur.monthlyOverride ?: g.monthlyAiTokens,
        dailyImages = g.dailyImageRequests,
        dailyOcrPages = g.dailyOcrPages,
        maxInputChars = g.maxInputChars,
        maxDevices = g.maxDevices,
        features = g.features,
    )
}

/** Column in usage_daily that counts each kind of call. */
private fun QuotaKind.column() = when (this) {
    QuotaKind.CHAT -> "ai_requests"
    QuotaKind.IMAGE -> "image_requests"
    QuotaKind.OCR -> "ocr_pages"
}

fun Services.dailyUsed(userId: Long, kind: QuotaKind = QuotaKind.CHAT): Int =
    db.long("SELECT ${kind.column()} FROM usage_daily WHERE user_id=? AND day=?", userId, today()).toInt()

fun Services.monthlyUsed(userId: Long): Long =
    db.long("SELECT COALESCE(SUM(ai_tokens), 0) FROM usage_daily WHERE user_id=? AND day LIKE ?", userId, "${month()}-%")

fun Services.quotaInfo(u: UserRow, lim: Limits = limits(u)) = QuotaInfo(
    dailyRequests = lim.dailyRequests,
    dailyUsed = dailyUsed(u.id),
    monthlyTokens = lim.monthlyTokens,
    monthlyUsed = monthlyUsed(u.id),
    dailyImages = lim.dailyImages,
    imagesUsed = dailyUsed(u.id, QuotaKind.IMAGE),
    dailyOcrPages = lim.dailyOcrPages,
    ocrUsed = dailyUsed(u.id, QuotaKind.OCR),
    maxInputChars = lim.maxInputChars,
    maxDevices = lim.maxDevices,
    features = Feature.entries.map { it.key }.filter { it in lim.features },
)

fun Services.profile(id: Long): Profile {
    val u = user(id) ?: throw ApiError(401, "unauthorized", "账号不存在或已注销")
    val lim = limits(u)
    val fresh = if (lim.group.id != u.groupId) user(id)!! else u
    return Profile(
        id = fresh.id,
        username = fresh.username,
        nickname = fresh.nickname,
        email = fresh.email,
        createdAt = fresh.createdAt,
        group = GroupInfo(lim.group.id, lim.group.name, fresh.groupExpiresAt),
        quota = quotaInfo(fresh, lim),
    )
}

/**
 * Counts one call against today's quota of its [kind] before calling the model, so parallel
 * requests can't overshoot. Throws a 429 [ApiError] when the daily count (or, for chat, the
 * monthly tokens) is used up.
 */
fun Services.reserveRequest(u: UserRow, lim: Limits, kind: QuotaKind = QuotaKind.CHAT) {
    val day = today()
    val col = kind.column()
    db.tx {
        val used = db.long("SELECT $col FROM usage_daily WHERE user_id=? AND day=?", u.id, day)
        val limit = lim.daily(kind)
        if (limit >= 0 && used >= limit) {
            val what = when (kind) {
                QuotaKind.CHAT -> "今日 AI 对话次数"
                QuotaKind.IMAGE -> "今日 AI 图像处理次数"
                QuotaKind.OCR -> "今日 AI 识别页数"
            }
            throw ApiError(429, "quota_daily", "${what}已用完（$used/$limit），明天 0 点恢复")
        }
        if (kind == QuotaKind.CHAT) {
            val monthUsed = monthlyUsed(u.id)
            if (lim.monthlyTokens >= 0 && monthUsed >= lim.monthlyTokens) {
                throw ApiError(429, "quota_monthly", "本月 AI 额度已用完，下月 1 日恢复")
            }
        }
        db.exec(
            "INSERT INTO usage_daily(user_id, day, $col) VALUES(?, ?, 1) ON CONFLICT(user_id, day) DO UPDATE SET $col = $col + 1",
            u.id, day,
        )
    }
}

fun Services.refundRequest(userId: Long, kind: QuotaKind = QuotaKind.CHAT) {
    val col = kind.column()
    db.exec("UPDATE usage_daily SET $col = MAX($col - 1, 0) WHERE user_id=? AND day=?", userId, today())
}

fun Services.addTokens(userId: Long, tokens: Long) {
    db.exec(
        "INSERT INTO usage_daily(user_id, day, ai_requests, ai_tokens) VALUES(?, ?, 0, ?) " +
            "ON CONFLICT(user_id, day) DO UPDATE SET ai_tokens = ai_tokens + excluded.ai_tokens",
        userId, today(), tokens,
    )
}

/** Opens a login session, signing out the least recently used devices beyond the group's limit. */
fun Services.createSession(u: UserRow, device: String, appVersion: String, ip: String): String {
    val token = Tokens.newToken()
    val now = now()
    val maxDevices = limits(u).maxDevices
    db.tx {
        if (maxDevices > 0) {
            val existing = db.query("SELECT token_hash FROM sessions WHERE user_id=? ORDER BY last_seen_at DESC", u.id) { it.getString(1) }
            existing.drop(maxDevices - 1).forEach { db.exec("DELETE FROM sessions WHERE token_hash=?", it) }
        }
        db.exec(
            "INSERT INTO sessions(token_hash, user_id, device, app_version, ip, created_at, last_seen_at, expires_at) VALUES(?,?,?,?,?,?,?,?)",
            Tokens.sha256(token), u.id, device.take(80), appVersion.take(40), ip, now, now, now + Services.SESSION_TTL,
        )
        db.exec("UPDATE users SET last_login_at=?, last_login_ip=?, last_seen_at=? WHERE id=?", now, ip, now, u.id)
    }
    return token
}

/** Resolves a bearer token to its user; extends the session while it's being used. */
fun Services.authenticate(token: String, ip: String): UserRow {
    val now = now()
    val hash = Tokens.sha256(token)
    val s = db.one("SELECT user_id, last_seen_at, expires_at FROM sessions WHERE token_hash=?", hash) {
        Triple(it.getLong(1), it.getLong(2), it.getLong(3))
    } ?: throw ApiError(401, "unauthorized", "登录已过期，请重新登录")
    if (s.third <= now) {
        db.exec("DELETE FROM sessions WHERE token_hash=?", hash)
        throw ApiError(401, "unauthorized", "登录已过期，请重新登录")
    }
    val u = user(s.first) ?: throw ApiError(401, "unauthorized", "账号不存在或已注销")
    if (u.status == "banned") {
        db.exec("DELETE FROM sessions WHERE user_id=?", u.id)
        throw ApiError(403, "banned", banMessage(u))
    }
    // Write at most every 5 minutes per session.
    if (now - s.second > 5 * Services.MINUTE) {
        db.exec("UPDATE sessions SET last_seen_at=?, expires_at=?, ip=? WHERE token_hash=?", now, now + Services.SESSION_TTL, ip, hash)
        db.exec("UPDATE users SET last_seen_at=? WHERE id=?", now, u.id)
    }
    return u
}

fun banMessage(u: UserRow) = "账号已被封禁" + (u.banReason?.takeIf { it.isNotBlank() }?.let { "：$it" } ?: "，如有疑问请联系客服")
