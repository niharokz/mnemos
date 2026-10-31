package com.nihars.mnemos.core.data.remote

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** A Mnemos server sign-in, kept in the app's private settings (never the password). */
data class ServerAccount(val url: String, val user: String, val cookie: String, val readOnly: Boolean = false) {
    /** "notes.example.com" for labels. */
    val host: String get() = url.removePrefix("https://").removePrefix("http://").trimEnd('/')
}

/** The server answered with an error; [status] is the HTTP code (409 conflict, 401 signed out …). */
class ServerException(val status: Int, message: String, val body: JSONObject? = null) : IOException(message)

/** No usable network, or the server could not be reached. Edits are queued instead. */
class OfflineException(cause: Throwable) : IOException("Offline — changes are kept on the phone and sent later.", cause)

/** One file or folder from GET /api/tree. */
data class RemoteNode(val path: String, val name: String, val dir: Boolean, val size: Long, val mtime: Long)

/** One trash item from GET /api/trash. */
data class RemoteTrashItem(val name: String, val dir: Boolean, val size: Long, val mtime: Long, val original: String?)

/**
 * The web app's API, spoken from the phone. The same rules as in the browser apply: every
 * request carries the X-Mnemos header and the session cookie; the server checks versions,
 * keeps the trash and refuses changes from the read-only demo.
 */
class ServerClient(private val baseUrl: String, @Volatile var cookie: String = "") {

    /** Signs in and returns the account (with its session cookie). */
    fun login(user: String, password: String): ServerAccount {
        val body = JSONObject().put("user", user).put("password", password)
        val conn = open("POST", "/api/login")
        send(conn, body.toString().toByteArray(), "application/json")
        val out = read(conn)
        val setCookie = conn.headerFields.entries.firstOrNull { it.key.equals("Set-Cookie", ignoreCase = true) }?.value.orEmpty()
        val session = setCookie.map { it.substringBefore(';') }.firstOrNull { it.contains("mnemos") && it.substringAfter('=').isNotEmpty() }
            ?: throw ServerException(500, "The server did not start a session. Is this a Mnemos server?")
        cookie = session
        return ServerAccount(baseUrl, out.optString("user", user), session, out.optBoolean("read_only"))
    }

    fun tree(): List<RemoteNode> {
        val out = ArrayList<RemoteNode>()
        fun walk(o: JSONObject) {
            val kids = o.optJSONArray("children") ?: return
            for (i in 0 until kids.length()) {
                val c = kids.getJSONObject(i)
                out += RemoteNode(c.getString("path"), c.getString("name"), c.optBoolean("dir"), c.optLong("size"), c.optLong("mtime"))
                if (c.optBoolean("dir")) walk(c)
            }
        }
        walk(json("GET", "/api/tree"))
        return out
    }

    /** The file's exact bytes (images too). */
    fun raw(path: String): ByteArray {
        val conn = open("GET", "/raw/" + path.split('/').joinToString("/") { enc(it).replace("+", "%20") })
        return readBytes(conn)
    }

    /** The file's current version, or null when it is gone. */
    fun version(path: String): String? = try {
        json("GET", "/api/version?path=" + enc(path)).optString("version").ifEmpty { null }
    } catch (e: ServerException) {
        if (e.status == 404) null else throw e
    }

    /** Saves text when the server still has [baseVersion]; returns the new version. 409 = changed there. */
    fun write(path: String, content: String, baseVersion: String): String =
        json("PUT", "/api/file?path=" + enc(path), JSONObject().put("content", content).put("version", baseVersion)).getString("version")

    /** Creates an empty note or a folder; returns its path. */
    fun create(path: String, folder: Boolean): String =
        json("POST", "/api/new", JSONObject().put("path", path).put("folder", folder)).getString("path")

    /** Stores an image at exactly [path] (never overwrites). */
    fun upload(path: String, bytes: ByteArray): String {
        val conn = open("POST", "/api/upload?path=" + enc(path))
        send(conn, bytes, "application/octet-stream")
        return read(conn).getString("path")
    }

    fun move(from: String, to: String): String = json("POST", "/api/move", JSONObject().put("from", from).put("to", to)).getString("path")

    fun trash(path: String): String = json("POST", "/api/trash", JSONObject().put("path", path)).getString("trash")

    fun restore(name: String, to: String): String = json("POST", "/api/restore", JSONObject().put("name", name).put("to", to)).getString("path")

    fun trashList(): List<RemoteTrashItem> {
        val a = jsonArray("GET", "/api/trash")
        return (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            RemoteTrashItem(o.getString("name"), o.optBoolean("dir"), o.optLong("size"), o.optLong("mtime"), o.optString("original").ifEmpty { null })
        }
    }

    /** The server's Cleanup: preview ([run] = false) or run it. Same report as on the phone. */
    fun cleanup(days: Int, run: Boolean): com.nihars.mnemos.core.data.CleanupReport {
        val o = if (run) json("POST", "/api/cleanup?days=$days") else json("GET", "/api/cleanup?days=$days")
        fun list(k: String) = o.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
        return com.nihars.mnemos.core.data.CleanupReport(o.optBoolean("dry_run", !run), o.optInt("days", days), list("moved"), list("deleted"), o.optLong("freed_size"))
    }

    fun logout() {
        runCatching { json("POST", "/api/logout") }
        cookie = ""
    }

    // ---- HTTP ------------------------------------------------------------------

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    private fun open(method: String, path: String): HttpURLConnection {
        val conn = try {
            URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection
        } catch (e: IOException) {
            throw OfflineException(e)
        }
        conn.requestMethod = method
        conn.connectTimeout = 10_000
        conn.readTimeout = 30_000
        conn.instanceFollowRedirects = false
        conn.setRequestProperty("X-Mnemos", "1")
        conn.setRequestProperty("Accept", "application/json")
        if (cookie.isNotEmpty()) conn.setRequestProperty("Cookie", cookie)
        return conn
    }

    private fun send(conn: HttpURLConnection, bytes: ByteArray, type: String) {
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", type)
        conn.setFixedLengthStreamingMode(bytes.size)
        try {
            conn.outputStream.use { it.write(bytes) }
        } catch (e: IOException) {
            throw OfflineException(e)
        }
    }

    private fun json(method: String, path: String, body: JSONObject? = null): JSONObject {
        val conn = open(method, path)
        if (body != null) send(conn, body.toString().toByteArray(), "application/json")
        return read(conn)
    }

    private fun jsonArray(method: String, path: String): JSONArray {
        val text = String(readBytes(open(method, path)))
        return if (text.isBlank() || text.trim() == "null") JSONArray() else JSONArray(text)
    }

    private fun read(conn: HttpURLConnection): JSONObject {
        val text = String(readBytes(conn))
        return if (text.isBlank() || text.trim() == "null") JSONObject() else JSONObject(text)
    }

    private fun readBytes(conn: HttpURLConnection): ByteArray {
        val code = try {
            conn.responseCode
        } catch (e: IOException) {
            throw OfflineException(e)
        }
        if (code in 200..299) return conn.inputStream.use { it.readBytes() }
        val err = runCatching { JSONObject(String(conn.errorStream?.use { it.readBytes() } ?: ByteArray(0))) }.getOrNull()
        val msg = err?.optString("error")?.ifEmpty { null } ?: when (code) {
            401 -> "Signed out of the server — sign in again in Settings → Storage."
            403 -> "The server refused this (banned address, or the read-only demo)."
            in 300..399 -> "The server redirected; use its https:// address."
            else -> "The server answered $code."
        }
        throw ServerException(code, msg, err)
    }
}
