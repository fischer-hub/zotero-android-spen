package org.zotero.android.helpers

import android.annotation.SuppressLint
import android.app.Application
import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.core.net.toUri
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import okhttp3.internal.closeQuietly
import org.zotero.android.translator.data.AttachmentState
import timber.log.Timber
import java.io.File
import javax.inject.Inject

class GetUriDetailsUseCase @Inject constructor(
    private val application: Application,
    private val dispatcher: CoroutineDispatcher
) {
    @SuppressLint("Range")
    fun getFullName(uri: Uri): String? {
        try {
            application.contentResolver.query(
                uri,
                null,
                null,
                null,
                null
            ).use { cursor ->
                if (cursor != null && cursor.moveToFirst()) {
                    return cursor.getString(cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME))
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "GetUriDetailsUseCase: can't query display name for shared URI")
        }
        return inferFileName(uri)
    }

    fun getExtension(uri: Uri): String? {
        try {
            val extension: String?
            if (uri.scheme == ContentResolver.SCHEME_CONTENT) {
                val mime = MimeTypeMap.getSingleton()
                extension = mime.getExtensionFromMimeType(application.contentResolver.getType(uri))
            } else {
                extension = MimeTypeMap.getFileExtensionFromUrl(Uri.fromFile(File(uri.path!!)).toString())
            }
            if (!extension.isNullOrEmpty()) {
                return extension
            }
        } catch (e: Exception) {
            Timber.w(e, "GetUriDetailsUseCase: can't query extension for shared URI")
        }
        val inferredFileName = inferFileName(uri)
        val inferredExtension = inferredFileName
            ?.substringAfterLast('.', missingDelimiterValue = "")
            ?.takeIf { it.isNotEmpty() && it != inferredFileName }
        return inferredExtension ?: "pdf"
    }

    suspend fun getMimeType(uri: String): MimeType? = withContext(dispatcher) {
        val contentUri = uri.toUri()
        application.contentResolver.getType(contentUri)
    }

    suspend fun copyFile(fromUri: Uri, toFile: File) = withContext(dispatcher) {
        Timber.i("GetUriDetailsUseCase: copy file to attachment folder")

        try {
            val uriInputStream = application.contentResolver.openInputStream(fromUri)!!
            FileHelper.copyInputStreamToFile(uriInputStream, toFile)
            uriInputStream.closeQuietly()
        } catch (error: Exception) {
            Timber.e(error, "GetUriDetailsUseCase: can't copy file")
            throw AttachmentState.Error.fileMissing
        }
    }

    private fun inferFileName(uri: Uri): String? {
        val lastSegment = uri.lastPathSegment ?: return null
        val name = lastSegment
            .substringAfterLast('/')
            .substringAfterLast(':')
        return name.takeIf { it.isNotBlank() }
    }
}
