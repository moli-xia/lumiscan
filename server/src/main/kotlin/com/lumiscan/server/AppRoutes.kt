package com.lumiscan.server

import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.origin
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.sql.SQLException

@Serializable
data class AppConfig(
    val registrationOpen: Boolean,
    val inviteRequired: Boolean,
    val announcement: String,
    val minVersionCode: Int,
    val latestVersionCode: Int,
    val updateUrl: String,
    val updateNote: String,
    val features: List<FeatureInfo>,
    /** Image tools run the AI as soon as they open (otherwise only when the user taps it). */
    val imageAiAuto: Boolean,
)

@Serializable
data class FeatureInfo(val key: String, val label: String)

@Serializable
data class RegisterRequest(val username: String, val password: String, val inviteCode: String? = null, val device: String = "")

@Serializable
data class LoginRequest(val username: String, val password: String, val device: String = "")

@Serializable
data class AuthResponse(val token: String, val profile: Profile)

@Serializable
data class UpdateProfileRequest(val nickname: String? = null, val email: String? = null)

@Serializable
data class ChangePasswordRequest(val oldPassword: String, val newPassword: String)

@Serializable
data class DeleteAccountRequest(val password: String)

@Serializable
data class FeedbackRequest(val content: String, val contact: String = "")

@Serializable
data class PolicyResponse(val title: String, val content: String)

fun ApplicationCall.ip(): String = request.origin.remoteAddress

private fun ApplicationCall.appVersion(): String = request.headers["X-App-Version"].orEmpty()

private fun ApplicationCall.bearer(): String? =
    request.headers[HttpHeaders.Authorization]?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }?.substring(7)?.trim()?.takeIf { it.isNotEmpty() }

suspend fun ApplicationCall.requireUser(s: Services): UserRow {
    val token = bearer() ?: throw ApiError(401, "unauthorized", "请先登录")
    val ip = ip()
    return withContext(Dispatchers.IO) { s.authenticate(token, ip) }
}

fun Route.appRoutes(s: Services) = route("/api/v1") {
    get("/config") {
        val st = s.settings
        call.respond(
            AppConfig(
                registrationOpen = st.bool(Settings.REGISTRATION_OPEN),
                inviteRequired = st.get(Settings.INVITE_CODE).isNotBlank(),
                announcement = st.get(Settings.ANNOUNCEMENT),
                minVersionCode = st.int(Settings.MIN_VERSION_CODE, 0),
                latestVersionCode = st.int(Settings.LATEST_VERSION_CODE, 0),
                updateUrl = st.get(Settings.UPDATE_URL),
                updateNote = st.get(Settings.UPDATE_NOTE),
                features = Feature.entries.map { FeatureInfo(it.key, it.label) },
                imageAiAuto = st.bool(Settings.IMAGE_AI_AUTO),
            )
        )
    }

    get("/policy/{kind}") {
        val (title, key) = when (call.parameters["kind"]) {
            "privacy" -> "隐私政策" to Settings.PRIVACY_POLICY
            "terms" -> "用户协议" to Settings.USER_TERMS
            else -> throw notFound()
        }
        call.respond(PolicyResponse(title, s.settings.get(key)))
    }

    post("/auth/register") {
        val req = call.receive<RegisterRequest>()
        val ip = call.ip()
        if (!s.settings.bool(Settings.REGISTRATION_OPEN)) throw ApiError(403, "registration_closed", "暂未开放注册")
        if (!s.registerLimiter.allow(ip)) throw ApiError(429, "rate_limited", "注册过于频繁，请稍后再试")
        val username = Validate.username(req.username)
        val password = Validate.password(req.password)
        val invite = s.settings.get(Settings.INVITE_CODE).trim()
        if (invite.isNotEmpty() && req.inviteCode?.trim() != invite) throw ApiError(400, "bad_invite", "邀请码不正确")
        if (s.userByName(username) != null) throw ApiError(409, "username_taken", "该账号已被注册")

        val hash = withContext(Dispatchers.Default) { Passwords.hash(password) }
        val id = withContext(Dispatchers.IO) {
            try {
                s.db.insert(
                    "INSERT INTO users(username, password_hash, group_id, created_at, register_ip) VALUES(?,?,?,?,?)",
                    username, hash, s.defaultGroup().id, s.now(), ip,
                )
            } catch (e: SQLException) {
                if (e.message?.contains("UNIQUE", true) == true) throw ApiError(409, "username_taken", "该账号已被注册")
                throw e
            }
        }
        val u = s.user(id)!!
        val token = s.createSession(u, req.device, call.appVersion(), ip)
        s.log.info("user registered: {} ({}) from {}", username, id, ip)
        call.respond(AuthResponse(token, s.profile(id)))
    }

    post("/auth/login") {
        val req = call.receive<LoginRequest>()
        val ip = call.ip()
        if (!s.loginIpLimiter.allow(ip)) throw ApiError(429, "rate_limited", "尝试过于频繁，请稍后再试")
        val key = req.username.trim().lowercase()
        if (s.loginFailLimiter.blocked(key)) throw ApiError(429, "locked", "密码错误次数过多，请 15 分钟后再试")
        val u = s.userByName(req.username)
        val ok = withContext(Dispatchers.Default) { Passwords.verify(req.password, u?.passwordHash ?: Passwords.dummyHash) && u != null }
        if (!ok || u == null) {
            s.loginFailLimiter.hit(key)
            throw ApiError(401, "bad_credentials", "账号或密码错误")
        }
        if (u.status == "banned") throw ApiError(403, "banned", banMessage(u))
        s.loginFailLimiter.reset(key)
        val token = withContext(Dispatchers.IO) { s.createSession(u, req.device, call.appVersion(), ip) }
        call.respond(AuthResponse(token, s.profile(u.id)))
    }

    post("/auth/logout") {
        call.bearer()?.let { t -> s.db.exec("DELETE FROM sessions WHERE token_hash=?", Tokens.sha256(t)) }
        call.respond(Ok())
    }

    get("/me") {
        val u = call.requireUser(s)
        call.respond(s.profile(u.id))
    }

    val updateProfile: suspend RoutingContext.() -> Unit = {
        val u = call.requireUser(s)
        val req = call.receive<UpdateProfileRequest>()
        req.nickname?.let { s.db.exec("UPDATE users SET nickname=? WHERE id=?", Validate.nickname(it), u.id) }
        req.email?.let { s.db.exec("UPDATE users SET email=? WHERE id=?", Validate.email(it), u.id) }
        call.respond(s.profile(u.id))
    }
    patch("/me", updateProfile)
    // POST alias: Android's HttpURLConnection can't send PATCH.
    post("/me", updateProfile)

    post("/me/password") {
        val u = call.requireUser(s)
        val req = call.receive<ChangePasswordRequest>()
        val ok = withContext(Dispatchers.Default) { Passwords.verify(req.oldPassword, u.passwordHash) }
        if (!ok) throw ApiError(400, "bad_password", "原密码不正确")
        val hash = withContext(Dispatchers.Default) { Passwords.hash(Validate.password(req.newPassword)) }
        val current = Tokens.sha256(call.bearer()!!)
        s.db.tx {
            s.db.exec("UPDATE users SET password_hash=? WHERE id=?", hash, u.id)
            // Sign out every other device.
            s.db.exec("DELETE FROM sessions WHERE user_id=? AND token_hash<>?", u.id, current)
        }
        call.respond(Ok())
    }

    post("/me/delete") {
        val u = call.requireUser(s)
        val req = call.receive<DeleteAccountRequest>()
        val ok = withContext(Dispatchers.Default) { Passwords.verify(req.password, u.passwordHash) }
        if (!ok) throw ApiError(400, "bad_password", "密码不正确")
        // Sessions, usage and AI logs go with the user (ON DELETE CASCADE); feedback is kept anonymised.
        s.db.exec("DELETE FROM users WHERE id=?", u.id)
        s.log.info("user {} ({}) deleted their account", u.id, u.username)
        call.respond(Ok())
    }

    post("/ai/chat") {
        val u = call.requireUser(s)
        val req = call.receive<AiRequest>()
        call.respond(s.ai.chat(u, req, call.ip()))
    }

    post("/vision/ocr") {
        val u = call.requireUser(s)
        call.respond(s.vision.ocr(u, call.receive<OcrRequest>(), call.ip()))
    }

    post("/vision/detect") {
        val u = call.requireUser(s)
        call.respond(s.vision.detect(u, call.receive<DetectRequest>(), call.ip()))
    }

    post("/vision/count") {
        val u = call.requireUser(s)
        call.respond(s.vision.count(u, call.receive<CountRequest>(), call.ip()))
    }

    post("/vision/edit") {
        val u = call.requireUser(s)
        call.respond(s.vision.edit(u, call.receive<EditRequest>(), call.ip()))
    }

    post("/feedback") {
        val u = call.requireUser(s)
        val req = call.receive<FeedbackRequest>()
        val content = req.content.trim()
        if (content.length !in 5..2000) throw bad("反馈内容需为 5–2000 字")
        if (!s.feedbackLimiter.allow(u.id.toString())) throw ApiError(429, "rate_limited", "今天提交的反馈太多了，明天再来吧")
        val device = s.db.one("SELECT device FROM sessions WHERE token_hash=?", Tokens.sha256(call.bearer()!!)) { it.getString(1) }.orEmpty()
        s.db.insert(
            "INSERT INTO feedback(user_id, username, content, contact, app_version, device, created_at) VALUES(?,?,?,?,?,?,?)",
            u.id, u.username, content, req.contact.trim().take(100), call.appVersion().take(40), device, s.now(),
        )
        call.respond(Ok())
    }
}
