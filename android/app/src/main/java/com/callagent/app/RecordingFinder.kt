package com.callagent.app

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.core.content.ContextCompat

/**
 * Lists recent audio files: first from Android's media index (finds recordings of any brand
 * without knowing the folder), then from the folder the user picked, if any.
 */
class RecordingFinder(private val context: Context, private val prefs: Prefs) {

    companion object {
        val AUDIO_PERMISSION: String =
            if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
            else Manifest.permission.READ_EXTERNAL_STORAGE
    }

    fun hasPermission() =
        ContextCompat.checkSelfPermission(context, AUDIO_PERMISSION) == PackageManager.PERMISSION_GRANTED

    fun filesSince(sinceMillis: Long): List<AudioFile> {
        val out = mutableListOf<AudioFile>()
        if (hasPermission()) out += fromMediaStore(sinceMillis)
        out += fromPickedFolder(sinceMillis)
        return out.distinctBy { it.id }
    }

    private fun fromMediaStore(sinceMillis: Long): List<AudioFile> {
        val collection = if (Build.VERSION.SDK_INT >= 29) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }
        @Suppress("DEPRECATION")
        val pathColumn = if (Build.VERSION.SDK_INT >= 29) MediaStore.Audio.Media.RELATIVE_PATH else MediaStore.Audio.Media.DATA
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            pathColumn,
            MediaStore.Audio.Media.DATE_MODIFIED,
            MediaStore.Audio.AudioColumns.DURATION,
            MediaStore.Audio.Media.SIZE,
        )
        val out = mutableListOf<AudioFile>()
        context.contentResolver.query(
            collection,
            projection,
            "${MediaStore.Audio.Media.DATE_MODIFIED} >= ?",
            arrayOf((sinceMillis / 1000).toString()), // this column is in seconds
            null,
        )?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(1) ?: continue
                if (!RecordingMatcher.hasAudioExtension(name)) continue
                out += AudioFile(
                    id = ContentUris.withAppendedId(collection, c.getLong(0)).toString(),
                    name = name,
                    path = c.getString(2).orEmpty(),
                    modifiedAt = c.getLong(3) * 1000,
                    durationMs = c.getLong(4),
                    size = c.getLong(5),
                )
            }
        }
        return out
    }

    private fun fromPickedFolder(sinceMillis: Long): List<AudioFile> {
        val tree = prefs.recordingsTreeUri?.let(Uri::parse) ?: return emptyList()
        val out = mutableListOf<AudioFile>()

        fun walk(docId: String, path: String, depth: Int) {
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
            val projection = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                DocumentsContract.Document.COLUMN_SIZE,
            )
            context.contentResolver.query(children, projection, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0) ?: continue
                    val name = c.getString(1).orEmpty()
                    val mime = c.getString(2).orEmpty()
                    val modified = c.getLong(3)
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        if (depth < 2) walk(id, "$path$name/", depth + 1)
                    } else if (modified >= sinceMillis && RecordingMatcher.hasAudioExtension(name)) {
                        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)
                        out += AudioFile(uri.toString(), name, path, modified, durationOf(uri), c.getLong(4), true)
                    }
                }
            }
        }

        try {
            walk(DocumentsContract.getTreeDocumentId(tree), "", 0)
        } catch (e: Exception) {
            // folder deleted or access withdrawn; media-index results still apply
        }
        return out
    }

    private fun durationOf(uri: Uri): Long {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(context, uri)
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (e: Exception) {
            0L
        } finally {
            r.release()
        }
    }
}
