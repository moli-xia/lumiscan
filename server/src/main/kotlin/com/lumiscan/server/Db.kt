package com.lumiscan.server

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Types

/**
 * One SQLite connection shared by the whole server. Every call is synchronized, which is plenty
 * for this workload and keeps transactions trivially correct; callers run it off the event loop.
 */
class Db(val file: File) {
    private val conn: Connection

    init {
        file.absoluteFile.parentFile?.mkdirs()
        conn = DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}")
        conn.createStatement().use { st ->
            st.execute("PRAGMA journal_mode=WAL")
            st.execute("PRAGMA synchronous=NORMAL")
            st.execute("PRAGMA foreign_keys=ON")
            st.execute("PRAGMA busy_timeout=5000")
        }
    }

    private fun prepare(sql: String, args: Array<out Any?>): PreparedStatement {
        val ps = conn.prepareStatement(sql)
        args.forEachIndexed { i, a ->
            val idx = i + 1
            when (a) {
                null -> ps.setNull(idx, Types.NULL)
                is Boolean -> ps.setInt(idx, if (a) 1 else 0)
                is Int -> ps.setInt(idx, a)
                is Long -> ps.setLong(idx, a)
                is Double -> ps.setDouble(idx, a)
                is String -> ps.setString(idx, a)
                else -> ps.setString(idx, a.toString())
            }
        }
        return ps
    }

    @Synchronized
    fun exec(sql: String, vararg args: Any?): Int = prepare(sql, args).use { it.executeUpdate() }

    @Synchronized
    fun insert(sql: String, vararg args: Any?): Long {
        prepare(sql, args).use { it.executeUpdate() }
        return conn.createStatement().use { st -> st.executeQuery("SELECT last_insert_rowid()").use { rs -> rs.next(); rs.getLong(1) } }
    }

    @Synchronized
    fun <T> query(sql: String, vararg args: Any?, map: (ResultSet) -> T): List<T> =
        prepare(sql, args).use { ps -> ps.executeQuery().use { rs -> buildList { while (rs.next()) add(map(rs)) } } }

    fun <T> one(sql: String, vararg args: Any?, map: (ResultSet) -> T): T? = query(sql, *args, map = map).firstOrNull()

    fun long(sql: String, vararg args: Any?): Long = one(sql, *args) { it.getLong(1) } ?: 0L

    /** Rows as JSON objects keyed by column label — used by the admin API. */
    fun rows(sql: String, vararg args: Any?): List<JsonObject> = query(sql, *args) { rowJson(it) }

    fun row(sql: String, vararg args: Any?): JsonObject? = rows(sql, *args).firstOrNull()

    @Synchronized
    fun <T> tx(block: () -> T): T {
        conn.autoCommit = false
        try {
            val r = block()
            conn.commit()
            return r
        } catch (e: Throwable) {
            conn.rollback()
            throw e
        } finally {
            conn.autoCommit = true
        }
    }

    /** Runs a ';'-separated script (no triggers or string literals containing ';'). */
    @Synchronized
    fun script(sql: String) {
        conn.createStatement().use { st ->
            sql.split(";").map { it.trim() }.filter { it.isNotEmpty() }.forEach { st.execute(it) }
        }
    }

    companion object {
        fun rowJson(rs: ResultSet): JsonObject {
            val md = rs.metaData
            return buildJsonObject {
                for (i in 1..md.columnCount) {
                    val name = md.getColumnLabel(i)
                    when (val v = rs.getObject(i)) {
                        null -> put(name, JsonNull)
                        is Int -> put(name, v)
                        is Long -> put(name, v)
                        is Double -> put(name, v)
                        is Float -> put(name, v.toDouble())
                        is Number -> put(name, v.toLong())
                        is ByteArray -> put(name, JsonNull)
                        else -> put(name, v.toString())
                    }
                }
            }
        }
    }
}

fun ResultSet.longOrNull(col: String): Long? {
    val v = getLong(col)
    return if (wasNull()) null else v
}

fun ResultSet.intOrNull(col: String): Int? {
    val v = getInt(col)
    return if (wasNull()) null else v
}
