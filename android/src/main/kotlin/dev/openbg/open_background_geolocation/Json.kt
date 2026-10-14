package dev.openbg.open_background_geolocation

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

internal fun JSONObject.toMap(): Map<String, Any?> =
    keys().asSequence().associateWith { k -> opt(k).unwrapJson() }

internal fun JSONArray.toList(): List<Any?> = (0 until length()).map { opt(it).unwrapJson() }

private fun Any?.unwrapJson(): Any? = when (this) {
    null, JSONObject.NULL -> null
    is JSONObject -> toMap()
    is JSONArray -> toList()
    else -> this
}

internal fun Map<*, *>.toJson(): JSONObject = JSONObject(this)

internal fun isoTimestamp(millis: Long): String {
    val f = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
    f.timeZone = TimeZone.getTimeZone("UTC")
    return f.format(Date(millis))
}
