package com.lumiscan.server

import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

/** Ordered schema migrations; the applied count is kept in meta.schema_version. */
object Schema {
    private val migrations = listOf(
        """
        CREATE TABLE IF NOT EXISTS meta(key TEXT PRIMARY KEY, value TEXT NOT NULL);

        CREATE TABLE user_groups(
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            name TEXT NOT NULL UNIQUE,
            description TEXT NOT NULL DEFAULT '',
            daily_ai_requests INTEGER NOT NULL DEFAULT 20,
            monthly_ai_tokens INTEGER NOT NULL DEFAULT 200000,
            max_input_chars INTEGER NOT NULL DEFAULT 8000,
            max_devices INTEGER NOT NULL DEFAULT 2,
            features TEXT NOT NULL DEFAULT '',
            is_default INTEGER NOT NULL DEFAULT 0,
            sort INTEGER NOT NULL DEFAULT 0,
            created_at INTEGER NOT NULL
        );

        CREATE TABLE users(
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            username TEXT NOT NULL UNIQUE COLLATE NOCASE,
            email TEXT,
            nickname TEXT NOT NULL DEFAULT '',
            password_hash TEXT NOT NULL,
            group_id INTEGER NOT NULL REFERENCES user_groups(id),
            group_expires_at INTEGER,
            status TEXT NOT NULL DEFAULT 'active',
            ban_reason TEXT,
            daily_ai_requests INTEGER,
            monthly_ai_tokens INTEGER,
            note TEXT NOT NULL DEFAULT '',
            created_at INTEGER NOT NULL,
            register_ip TEXT,
            last_login_at INTEGER,
            last_login_ip TEXT,
            last_seen_at INTEGER
        );
        CREATE INDEX idx_users_group ON users(group_id);
        CREATE INDEX idx_users_created ON users(created_at);

        CREATE TABLE sessions(
            token_hash TEXT PRIMARY KEY,
            user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
            device TEXT NOT NULL DEFAULT '',
            app_version TEXT NOT NULL DEFAULT '',
            ip TEXT,
            created_at INTEGER NOT NULL,
            last_seen_at INTEGER NOT NULL,
            expires_at INTEGER NOT NULL
        );
        CREATE INDEX idx_sessions_user ON sessions(user_id);

        CREATE TABLE usage_daily(
            user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
            day TEXT NOT NULL,
            ai_requests INTEGER NOT NULL DEFAULT 0,
            ai_tokens INTEGER NOT NULL DEFAULT 0,
            PRIMARY KEY(user_id, day)
        );
        CREATE INDEX idx_usage_day ON usage_daily(day);

        CREATE TABLE ai_logs(
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            user_id INTEGER REFERENCES users(id) ON DELETE CASCADE,
            feature TEXT NOT NULL,
            model TEXT NOT NULL DEFAULT '',
            prompt_tokens INTEGER NOT NULL DEFAULT 0,
            completion_tokens INTEGER NOT NULL DEFAULT 0,
            input_chars INTEGER NOT NULL DEFAULT 0,
            status TEXT NOT NULL,
            error TEXT,
            latency_ms INTEGER NOT NULL DEFAULT 0,
            ip TEXT,
            created_at INTEGER NOT NULL
        );
        CREATE INDEX idx_ai_logs_user ON ai_logs(user_id, created_at);
        CREATE INDEX idx_ai_logs_time ON ai_logs(created_at);

        CREATE TABLE admins(
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            username TEXT NOT NULL UNIQUE COLLATE NOCASE,
            password_hash TEXT NOT NULL,
            role TEXT NOT NULL DEFAULT 'operator',
            must_change_password INTEGER NOT NULL DEFAULT 0,
            created_at INTEGER NOT NULL,
            last_login_at INTEGER,
            last_login_ip TEXT
        );

        CREATE TABLE admin_sessions(
            token_hash TEXT PRIMARY KEY,
            admin_id INTEGER NOT NULL REFERENCES admins(id) ON DELETE CASCADE,
            ip TEXT,
            created_at INTEGER NOT NULL,
            expires_at INTEGER NOT NULL
        );

        CREATE TABLE settings(key TEXT PRIMARY KEY, value TEXT NOT NULL);

        CREATE TABLE audit_logs(
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            admin_id INTEGER,
            admin_name TEXT NOT NULL,
            action TEXT NOT NULL,
            target TEXT NOT NULL DEFAULT '',
            detail TEXT NOT NULL DEFAULT '',
            ip TEXT,
            created_at INTEGER NOT NULL
        );
        CREATE INDEX idx_audit_time ON audit_logs(created_at);

        CREATE TABLE feedback(
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            user_id INTEGER REFERENCES users(id) ON DELETE SET NULL,
            username TEXT NOT NULL DEFAULT '',
            content TEXT NOT NULL,
            contact TEXT NOT NULL DEFAULT '',
            app_version TEXT NOT NULL DEFAULT '',
            device TEXT NOT NULL DEFAULT '',
            status TEXT NOT NULL DEFAULT 'open',
            admin_note TEXT NOT NULL DEFAULT '',
            created_at INTEGER NOT NULL
        )
        """,
        // v2: image / OCR quotas and the vision features.
        """
        ALTER TABLE user_groups ADD COLUMN daily_image_requests INTEGER NOT NULL DEFAULT 5;
        ALTER TABLE user_groups ADD COLUMN daily_ocr_pages INTEGER NOT NULL DEFAULT 50;
        UPDATE user_groups SET
            daily_image_requests = CASE WHEN daily_ai_requests < 0 THEN -1 WHEN daily_ai_requests >= 100 THEN 100 ELSE 5 END,
            daily_ocr_pages = CASE WHEN daily_ai_requests < 0 THEN -1 WHEN daily_ai_requests >= 100 THEN 1000 ELSE 50 END;
        UPDATE user_groups SET features = CASE WHEN features = '' THEN '' ELSE features || ',' END ||
            'ocr,handwriting,watermark,erase,restore,upscale,id_photo';
        ALTER TABLE usage_daily ADD COLUMN image_requests INTEGER NOT NULL DEFAULT 0;
        ALTER TABLE usage_daily ADD COLUMN ocr_pages INTEGER NOT NULL DEFAULT 0;
        UPDATE settings SET value = 'qwen3-vl-plus' WHERE key = 'ai_vision_model' AND value = 'qwen-vl-plus'
        """,
        // v3: Bailian retired the qwen-image-edit series (2026-10-10); qwen-image-2.0 leaves grain on photos.
        """
        UPDATE settings SET value = 'qwen-image-3.0'
            WHERE key IN ('edit_model', 'restore_model') AND (value LIKE 'qwen-image-edit%' OR value LIKE 'qwen-image-2.0%')
        """,
        // v4: AI 拍照计数, granted to the groups that have AI recognition.
        """
        UPDATE user_groups SET features = features || ',count' WHERE ',' || features || ',' LIKE '%,ocr,%'
        """,
    )

    fun migrate(db: Db) {
        db.script("CREATE TABLE IF NOT EXISTS meta(key TEXT PRIMARY KEY, value TEXT NOT NULL)")
        val current = db.one("SELECT value FROM meta WHERE key='schema_version'") { it.getString(1).toInt() } ?: 0
        for (v in current until migrations.size) {
            db.tx {
                db.script(migrations[v])
                db.exec("INSERT INTO meta(key, value) VALUES('schema_version', ?) ON CONFLICT(key) DO UPDATE SET value=excluded.value", (v + 1).toString())
            }
        }
    }
}

/** First-run data: default groups, settings and the initial super admin. */
object Seed {
    private val log = LoggerFactory.getLogger("seed")

    /** SHA-256 of earlier default policy texts. v1 said recognition and the image tools ran on the phone. */
    private val OUTDATED_POLICIES = mapOf(
        Settings.PRIVACY_POLICY to setOf(
            "0086187308efc90a4bf852d82af6d3add4f2cbc93101a4ee150c4efd60a2f12d",
            // 2026-10-10: before AI 拍照计数 was listed.
            "568db5761fd512d3763191cf849402cc88637e5e6e31811fbb46b35ec180ed6c",
        ),
    )

    fun run(db: Db, cfg: Config) {
        val now = System.currentTimeMillis()
        if (db.long("SELECT COUNT(*) FROM user_groups") == 0L) {
            val all = Feature.entries.joinToString(",") { it.key }
            val sql = "INSERT INTO user_groups(name, description, daily_ai_requests, monthly_ai_tokens, daily_image_requests, daily_ocr_pages, " +
                "max_input_chars, max_devices, features, is_default, sort, created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)"
            // AI image calls cost ≈0.14–0.2 元 each, recognition ≈0.002 元 a page.
            db.insert(sql, "免费用户", "新注册用户的默认分组", 20, 200_000, 2, 50, 6_000, 2, all, 1, 0, now)
            db.insert(sql, "会员", "付费或赠送的会员，额度更高", 300, 5_000_000, 100, 1000, 30_000, 5, all, 0, 1, now)
            db.insert(sql, "内部测试", "员工与测试账号，不限额度", -1, -1, -1, -1, 100_000, 10, all, 0, 2, now)
        }
        for ((k, v) in Settings.defaults()) {
            db.exec("INSERT INTO settings(key, value) VALUES(?, ?) ON CONFLICT(key) DO NOTHING", k, v)
        }
        // A policy text shipped with an earlier version is replaced with the current one, unless an
        // admin has edited it (then it no longer matches a known default).
        for ((key, oldDefaults) in OUTDATED_POLICIES) {
            val current = db.one("SELECT value FROM settings WHERE key=?", key) { it.getString(1) } ?: continue
            if (Tokens.sha256(current) in oldDefaults) {
                db.exec("UPDATE settings SET value=? WHERE key=?", Settings.defaults()[key], key)
                log.info("{} updated to the current default text", key)
            }
        }
        // The initial password is only useful until it has been changed.
        if (db.long("SELECT COUNT(*) FROM admins WHERE must_change_password = 1") == 0L) File(cfg.dataDir, "initial-admin.txt").delete()
        if (db.long("SELECT COUNT(*) FROM admins") == 0L) {
            val password = cfg.adminPassword?.takeIf { it.isNotBlank() } ?: Tokens.randomPassword(14)
            db.insert(
                "INSERT INTO admins(username, password_hash, role, must_change_password, created_at) VALUES(?,?,?,?,?)",
                cfg.adminUser, Passwords.hash(password), "super", 1, now,
            )
            val f = File(cfg.dataDir, "initial-admin.txt")
            f.writeText("管理后台初始账号：${cfg.adminUser}\n初始密码：$password\n首次登录后会要求修改密码，修改后请删除本文件。\n")
            runCatching { Files.setPosixFilePermissions(f.toPath(), PosixFilePermissions.fromString("rw-------")) }
            log.warn("Created initial admin '{}'; password written to {}", cfg.adminUser, f.absolutePath)
        }
    }
}
