package ch.nokillswit

import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

/** Request bodies a client has compressed (`Content-Encoding`), for the tests that pin that none is ever decoded (checkup C-SEC-1). */

/** [bytes] gzipped (RFC 1952). */
internal fun gzip(bytes: ByteArray): ByteArray =
    ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(bytes) } }.toByteArray()

/**
 * [json] behind [padding] bytes of leading whitespace: VALID JSON only once inflated, and 8 MiB of it gzips to about 8 KiB, under
 * every small-body ceiling (the shape of a decompression bomb, at a size that is cheap to inflate if the server ever does).
 */
internal fun inflatesToJson(json: String, padding: Int = 8 * 1024 * 1024): ByteArray =
    ByteArray(padding) { ' '.code.toByte() } + json.toByteArray()

/** The two compressed spellings a request body can take: `gzip` and gzip-of-gzip (`Content-Encoding: gzip, gzip`). */
internal fun compressedBodies(json: String): Map<String, ByteArray> {
    val once = gzip(inflatesToJson(json))
    return mapOf("gzip" to once, "gzip, gzip" to gzip(once))
}
