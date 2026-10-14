package dev.openbg.open_background_geolocation

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Uploads queued locations to `http.url`. A 2xx response deletes the uploaded records; anything
 * else leaves them queued for the next attempt. Runs on a single worker so uploads never overlap.
 */
object HttpSync {
    private val worker = Executors.newSingleThreadExecutor()
    private val queued = AtomicBoolean(false)

    /** Uploads if `autoSync` is on, `url` is set and the threshold / network constraints allow. */
    fun autoSync(ctx: Context) {
        if (!Settings.bool("http", "autoSync", true)) return
        if (Settings.string("http", "url").isEmpty()) return
        if (Db.count() < Settings.int("http", "autoSyncThreshold", 0)) return
        if (Settings.bool("http", "disableAutoSyncOnCellular") && !isUnmetered(ctx)) return
        if (queued.getAndSet(true)) return // a run is already waiting; it will drain everything
        worker.execute { run(ctx) { _, _ -> } }
    }

    /** Manual sync. [cb] receives the uploaded records and an error message, if any. */
    fun sync(ctx: Context, cb: (List<Map<String, Any?>>, String?) -> Unit) {
        if (Settings.string("http", "url").isEmpty()) {
            return cb(emptyList(), "http.url is not configured")
        }
        worker.execute { run(ctx, cb) }
    }

    private fun run(ctx: Context, cb: (List<Map<String, Any?>>, String?) -> Unit) {
        queued.set(false)
        val uploaded = mutableListOf<Map<String, Any?>>()
        var error: String? = null
        try {
            val url = Settings.string("http", "url")
            val batch = Settings.bool("http", "batchSync")
            val max = Settings.int("http", "maxBatchSize", -1)
            val order = Settings.string("persistence", "locationsOrderDirection", "ASC")
            val limit: Int? = if (batch) (if (max > 0) max else null) else 100

            loop@ while (true) {
                val rows = Db.query(order, limit, null)
                if (rows.isEmpty()) break
                val groups = if (batch) listOf(rows) else rows.map { listOf(it) }
                for (group in groups) {
                    if (!post(url, group, batch)) {
                        error = "Upload failed"
                        break@loop
                    }
                    group.forEach { Db.delete(it.getString("uuid")) }
                    uploaded.addAll(group.map { it.toMap() })
                }
            }
        } catch (e: Exception) {
            error = e.message ?: "Upload failed"
            EventBus.emit("http", mapOf("success" to false, "status" to 0, "responseText" to error))
        }
        cb(uploaded, error)
    }

    /** Builds the JSON body: `{rootProperty: record | [records], ...params}`. */
    internal fun buildBody(records: List<JSONObject>, batch: Boolean): JSONObject {
        val root = Settings.string("http", "rootProperty", "location")
        val params = Settings.obj("http", "params")
        val payload: Any = if (batch) JSONArray(records) else records.first()
        val body = JSONObject()
        if (root == ".") {
            if (payload is JSONObject) payload.keys().forEach { body.put(it, payload.get(it)) }
            else body.put("location", payload)
        } else {
            body.put(root, payload)
        }
        params?.keys()?.forEach { body.put(it, params.get(it)) }
        return body
    }

    private fun post(url: String, records: List<JSONObject>, batch: Boolean): Boolean {
        val timeout = Settings.int("http", "timeout", 60000)
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = Settings.string("http", "method", "POST").uppercase()
            conn.connectTimeout = timeout
            conn.readTimeout = timeout
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            Settings.obj("http", "headers")?.let { h ->
                h.keys().forEach { conn.setRequestProperty(it, h.get(it).toString()) }
            }
            conn.outputStream.use { it.write(buildBody(records, batch).toString().toByteArray()) }

            val status = conn.responseCode
            val stream = if (status < 400) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            val ok = status in 200..299
            EventBus.emit("http", mapOf("success" to ok, "status" to status, "responseText" to text))
            return ok
        } finally {
            conn.disconnect()
        }
    }

    private fun isUnmetered(ctx: Context): Boolean {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }
}
