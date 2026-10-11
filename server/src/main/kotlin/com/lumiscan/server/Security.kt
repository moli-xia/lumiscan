package com.lumiscan.server

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

private val rnd = SecureRandom()

/** PBKDF2-SHA256 password hashes, stored as `pbkdf2_sha256$iterations$salt$hash`. */
object Passwords {
    private const val ITERATIONS = 210_000

    fun hash(password: String): String {
        val salt = ByteArray(16).also(rnd::nextBytes)
        val h = derive(password, salt, ITERATIONS)
        val b64 = Base64.getEncoder()
        return "pbkdf2_sha256\$$ITERATIONS\$${b64.encodeToString(salt)}\$${b64.encodeToString(h)}"
    }

    fun verify(password: String, stored: String): Boolean {
        val p = stored.split('$')
        if (p.size != 4 || p[0] != "pbkdf2_sha256") return false
        val iterations = p[1].toIntOrNull() ?: return false
        val b64 = Base64.getDecoder()
        val salt = runCatching { b64.decode(p[2]) }.getOrNull() ?: return false
        val expected = runCatching { b64.decode(p[3]) }.getOrNull() ?: return false
        return MessageDigest.isEqual(derive(password, salt, iterations), expected)
    }

    private fun derive(password: String, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, 256)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    }

    /** Burned on unknown usernames so a login takes as long whether or not the account exists. */
    val dummyHash: String by lazy { hash("not-a-real-password") }
}

object Tokens {
    /** 256-bit bearer token; only its SHA-256 is stored server-side. */
    fun newToken(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(rnd::nextBytes))

    fun sha256(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    private const val PW_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789"

    /** Readable random password (letters + digits, no look-alike characters). */
    fun randomPassword(length: Int = 12): String {
        while (true) {
            val s = (1..length).map { PW_CHARS[rnd.nextInt(PW_CHARS.length)] }.joinToString("")
            if (s.any { it.isDigit() } && s.any { it.isLetter() }) return s
        }
    }
}

/** Sliding-window counter kept in memory (per key: IP, username, user id…). */
class RateLimiter(private val max: Int, private val windowMs: Long) {
    private val hits = ConcurrentHashMap<String, ArrayDeque<Long>>()

    private fun window(key: String, now: Long): ArrayDeque<Long> {
        val q = hits.computeIfAbsent(key) { ArrayDeque() }
        while (q.isNotEmpty() && now - q.first() > windowMs) q.removeFirst()
        return q
    }

    fun blocked(key: String): Boolean {
        val q = hits[key] ?: return false
        synchronized(q) { return window(key, System.currentTimeMillis()).size >= max }
    }

    fun hit(key: String) {
        val now = System.currentTimeMillis()
        val q = hits.computeIfAbsent(key) { ArrayDeque() }
        synchronized(q) { window(key, now).addLast(now) }
    }

    /** Records a hit unless the key is already over the limit. */
    fun allow(key: String): Boolean {
        val now = System.currentTimeMillis()
        val q = hits.computeIfAbsent(key) { ArrayDeque() }
        synchronized(q) {
            val w = window(key, now)
            if (w.size >= max) return false
            w.addLast(now)
            return true
        }
    }

    fun reset(key: String) {
        hits.remove(key)
    }

    fun cleanup() {
        val now = System.currentTimeMillis()
        hits.entries.removeIf { (_, q) -> synchronized(q) { q.isEmpty() || now - q.last() > windowMs } }
    }
}

object Validate {
    private val usernameRe = Regex("^[A-Za-z0-9_.@-]{3,32}$")
    private val emailRe = Regex("^[^@\\s]{1,64}@[^@\\s]+\\.[^@\\s]{2,}$")

    fun username(s: String): String {
        val u = s.trim()
        if (!usernameRe.matches(u)) throw bad("账号需为 3–32 位字母、数字或 _ . @ -")
        return u
    }

    fun password(s: String): String {
        if (s.length !in 8..64) throw bad("密码长度需为 8–64 位")
        if (!s.any { it.isLetter() } || !s.any { it.isDigit() }) throw bad("密码需同时包含字母和数字")
        return s
    }

    fun email(s: String?): String? {
        val e = s?.trim().orEmpty()
        if (e.isEmpty()) return null
        if (e.length > 100 || !emailRe.matches(e)) throw bad("邮箱格式不正确")
        return e
    }

    fun nickname(s: String?): String {
        val n = s?.trim().orEmpty()
        if (n.length > 20) throw bad("昵称最多 20 个字")
        if (n.any { it.isISOControl() }) throw bad("昵称包含非法字符")
        return n
    }
}
