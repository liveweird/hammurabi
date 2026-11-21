package ch.nokillswit.adrs

import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.cancel
import io.ktor.utils.io.jvm.javaio.toOutputStream
import java.time.LocalDate
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** How many ADRs the bulk export reads (and holds in memory) at a time. */
internal const val EXPORT_BATCH_SIZE = 25

/**
 * One file per ADR (`ADR-0042-slug.md`) and, last, the `index.md` of what was actually written (an ADR deleted meanwhile is skipped).
 * Only a COMPLETE archive is finished: when a batch fails the channel is cancelled with the cause (the engine then drops the connection
 * without a clean end, so the client's download fails) and the zip is never finished, so no central directory is ever written. A
 * `use {}` here would finish it on the way out and serve a valid-looking archive of the batches before the failure.
 */
internal suspend fun ByteWriteChannel.writeArchive(rows: List<AdrExportRow>, exports: AdrExportService, exportedOn: LocalDate) {
    val zip = ZipOutputStream(toOutputStream())
    try {
        val written = mutableListOf<AdrIndexEntry>()
        for (batch in rows.chunked(EXPORT_BATCH_SIZE)) {
            for (document in exports.documents(batch.map { it.id })) {
                zip.addFile(adrExportFileName(document.number, document.name), renderAdrMarkdown(document, exportedOn), document.updatedAt)
                written += AdrIndexEntry(document.number, document.name, document.status)
            }
            zip.flush()
        }
        zip.addFile("index.md", renderAdrIndex(written, exportedOn), System.currentTimeMillis())
    } catch (failure: Throwable) {
        cancel(failure)
        // Releases the deflater; the cancelled channel refuses the bytes `close` would write, which is the point.
        runCatching { zip.close() }
        throw failure
    }
    zip.close()
}

private fun ZipOutputStream.addFile(name: String, text: String, modifiedAt: Long) {
    putNextEntry(ZipEntry(name).apply { time = modifiedAt })
    write(text.toByteArray(Charsets.UTF_8))
    closeEntry()
}
