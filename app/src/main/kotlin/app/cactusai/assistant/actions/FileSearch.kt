package app.cactusai.assistant.actions

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import app.cactusai.core.match.TextNorm
import android.os.SystemClock
import java.util.ArrayDeque

data class FileHit(val name: String, val uri: Uri)

/**
 * Looks for file names inside folders the user picked with Android's folder picker (Storage Access
 * Framework). It cannot see anything else: Android does not let apps search the whole phone.
 */
class FileSearch(private val context: Context) {
    fun search(treeUris: Set<String>, query: String, limit: Int = 20, budgetMs: Long = 4_000): List<FileHit> {
        val q = TextNorm.normalize(query)
        if (q.isEmpty()) return emptyList()
        val deadline = SystemClock.elapsedRealtime() + budgetMs
        val hits = ArrayList<FileHit>()
        for (tree in treeUris) {
            val treeUri = Uri.parse(tree)
            val rootId = try {
                DocumentsContract.getTreeDocumentId(treeUri)
            } catch (_: IllegalArgumentException) {
                continue
            }
            val queue = ArrayDeque<Pair<String, Int>>()
            queue.add(rootId to 0)
            var visited = 0
            while (queue.isNotEmpty() && hits.size < limit && visited < MAX_VISITED && SystemClock.elapsedRealtime() < deadline) {
                val (docId, depth) = queue.poll() ?: break
                val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
                val projection = arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                )
                try {
                    context.contentResolver.query(children, projection, null, null, null)?.use { c ->
                        while (c.moveToNext() && hits.size < limit) {
                            visited++
                            val id = c.getString(0) ?: continue
                            val name = c.getString(1) ?: continue
                            val mime = c.getString(2)
                            if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                                if (depth < MAX_DEPTH) queue.add(id to depth + 1)
                            } else if (TextNorm.normalize(name).contains(q)) {
                                hits += FileHit(name, DocumentsContract.buildDocumentUriUsingTree(treeUri, id))
                            }
                        }
                    }
                } catch (_: SecurityException) {
                    break // permission to this folder was revoked
                } catch (_: IllegalArgumentException) {
                    break
                }
            }
        }
        return hits
    }

    private companion object {
        const val MAX_DEPTH = 6
        const val MAX_VISITED = 8_000
    }
}
