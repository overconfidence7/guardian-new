package app.guardianmode.cloud

import com.google.firebase.Timestamp
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import java.util.Date

/** Converts between Firestore's Map/List values and kotlinx JSON trees. */
object FirestoreJson {

    fun toJson(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is String -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        is Timestamp -> JsonPrimitive(value.toDate().time)
        is Date -> JsonPrimitive(value.time)
        is Map<*, *> -> JsonObject(value.entries.associate { (k, v) -> k.toString() to toJson(v) })
        is List<*> -> JsonArray(value.map(::toJson))
        else -> JsonPrimitive(value.toString())
    }

    fun fromJson(element: JsonElement): Any? = when (element) {
        JsonNull -> null
        is JsonObject -> element.mapValues { fromJson(it.value) }
        is JsonArray -> element.map(::fromJson)
        is JsonPrimitive -> when {
            element.isString -> element.content
            element.booleanOrNull != null -> element.booleanOrNull
            element.longOrNull != null -> element.longOrNull
            else -> element.doubleOrNull
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun toMap(element: JsonElement): Map<String, Any?> = fromJson(element) as Map<String, Any?>
}
