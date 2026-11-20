package ch.nokillswit.infra.json

import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * (De)serialization of the string-to-string `params` maps stored as JSON TEXT columns (`adr_events.params`): the interpolation
 * values the SPA uses to render a structured event in the viewer's language. Ported from Lettuce.
 */
private val paramsSerializer = MapSerializer(String.serializer(), String.serializer())

fun encodeParams(params: Map<String, String>): String = Json.encodeToString(paramsSerializer, params)

fun decodeParams(json: String): Map<String, String> = Json.decodeFromString(paramsSerializer, json)
