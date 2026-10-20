package com.mcbox.core.util

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

val JsonPretty: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    encodeDefaults = true
    prettyPrint = true
}

val JsonCompact: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    encodeDefaults = true
}

fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

fun JsonObject.bool(key: String, def: Boolean = false): Boolean =
    (this[key] as? JsonPrimitive)?.contentOrNull?.let { it == "true" || it == "1" } ?: def

fun JsonObject.int(key: String, def: Int = 0): Int =
    (this[key] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: def

fun JsonObject.arr(key: String): JsonArray = (this[key] as? JsonArray) ?: JsonArray(emptyList())

fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

fun JsonElement.asString(): String? = (this as? JsonPrimitive)?.contentOrNull