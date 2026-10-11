package com.lumiscan.server

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.staticResources
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.forwardedheaders.XForwardedHeaders
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.path
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.slf4j.event.Level
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter

fun main() {
    // Upstreams close idle keep-alive connections well before the JDK's 20-minute default.
    System.setProperty("jdk.httpclient.keepalive.timeout", "20")
    val cfg = Config.fromEnv()
    cfg.dataDir.mkdirs()
    val db = Db(File(cfg.dataDir, "lumiscan.db"))
    Schema.migrate(db)
    Seed.run(db, cfg)
    val services = Services(cfg, db)
    startJobs(services)
    services.log.info("LumiScan server listening on {}:{} (data: {})", cfg.host, cfg.port, cfg.dataDir)
    embeddedServer(CIO, port = cfg.port, host = cfg.host) { module(services) }.start(wait = true)
}

fun Application.module(s: Services) {
    install(XForwardedHeaders)
    install(ContentNegotiation) { json(AppJson) }
    install(CallLogging) {
        level = Level.INFO
        filter { it.request.path().startsWith("/api/") || it.request.path().startsWith("/admin/api/") }
    }
    install(StatusPages) {
        exception<ApiError> { call, e -> call.respond(HttpStatusCode.fromValue(e.status), ErrorBody(e.code, e.message)) }
        exception<BadRequestException> { call, _ -> call.respond(HttpStatusCode.BadRequest, ErrorBody("invalid_request", "请求格式不正确")) }
        exception<Throwable> { call, e ->
            s.log.error("unhandled error on ${call.request.path()}", e)
            call.respond(HttpStatusCode.InternalServerError, ErrorBody("internal", "服务器开小差了，请稍后再试"))
        }
        status(HttpStatusCode.NotFound) { call, code ->
            val p = call.request.path()
            if (p.startsWith("/api/") || p.startsWith("/admin/api/")) call.respond(code, ErrorBody("not_found", "接口不存在"))
        }
    }
    intercept(ApplicationCallPipeline.Plugins) {
        val h = call.response.headers
        h.append("X-Content-Type-Options", "nosniff")
        h.append("Referrer-Policy", "no-referrer")
        h.append("X-Frame-Options", "DENY")
        val path = call.request.path()
        if (path.startsWith("/admin")) {
            h.append(
                "Content-Security-Policy",
                "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; " +
                    "connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'",
            )
            h.append("Cache-Control", "no-store")
        }
    }
    routing {
        get("/healthz") { call.respondText("ok") }
        get("/") { call.respondRedirect("/admin/") }
        get("/admin") { call.respondRedirect("/admin/") }
        get("/privacy") { call.respondText(policyHtml("隐私政策", s.settings.get(Settings.PRIVACY_POLICY)), ContentType.Text.Html) }
        get("/terms") { call.respondText(policyHtml("用户协议", s.settings.get(Settings.USER_TERMS)), ContentType.Text.Html) }
        appRoutes(s)
        adminRoutes(s)
        staticResources("/admin", "admin", index = "index.html")
    }
}

private fun escapeHtml(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

/** Public policy pages (app-store listings link here). */
private fun policyHtml(title: String, body: String): String {
    val paragraphs = body.trim().split(Regex("\\n\\s*\\n")).joinToString("\n") { p ->
        "<p>" + escapeHtml(p.trim()).replace("\n", "<br>") + "</p>"
    }
    return """<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>$title · 光点扫描</title><style>body{margin:0;background:#f4f6fa;color:#1a1d26;font:15px/1.75 -apple-system,BlinkMacSystemFont,"PingFang SC","Microsoft YaHei",sans-serif}
main{max-width:760px;margin:0 auto;padding:24px 16px 48px}h1{font-size:22px}p{margin:0 0 12px}</style></head>
<body><main><h1>$title</h1>$paragraphs</main></body></html>"""
}

/** Hourly housekeeping and a daily SQLite snapshot (kept 14 days). */
private fun startJobs(s: Services) {
    CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
        delay(30_000)
        while (true) {
            runCatching { housekeeping(s) }.onFailure { s.log.error("housekeeping failed", it) }
            runCatching { backup(s) }.onFailure { s.log.error("backup failed", it) }
            delay(Services.HOUR)
        }
    }
}

private fun housekeeping(s: Services) {
    val now = s.now()
    s.db.exec("DELETE FROM sessions WHERE expires_at <= ?", now)
    s.db.exec("DELETE FROM admin_sessions WHERE expires_at <= ?", now)
    s.db.exec("DELETE FROM ai_logs WHERE created_at < ?", now - 180 * Services.DAY)
    s.db.exec("DELETE FROM audit_logs WHERE created_at < ?", now - 365 * Services.DAY)
    s.db.exec("DELETE FROM usage_daily WHERE day < ?", LocalDate.now(s.cfg.zone).minusDays(400).toString())
    s.expireMemberships()
    s.limiters.forEach { it.cleanup() }
}

private fun backup(s: Services) {
    val dir = File(s.cfg.dataDir, "backups").apply { mkdirs() }
    val name = "lumiscan-" + LocalDate.now(s.cfg.zone).format(DateTimeFormatter.BASIC_ISO_DATE) + ".db"
    val target = File(dir, name)
    if (target.exists()) return
    s.db.exec("VACUUM INTO ?", target.absolutePath)
    s.log.info("database backup written to {}", target)
    dir.listFiles { f -> f.name.startsWith("lumiscan-") && f.name.endsWith(".db") }
        ?.sortedByDescending { it.name }
        ?.drop(14)
        ?.forEach { it.delete() }
}
