package org.zotero.android.database.requests

import io.realm.Realm
import io.realm.kotlin.where
import org.zotero.android.database.DbError
import org.zotero.android.database.DbRequest
import org.zotero.android.database.DbResponseRequest
import org.zotero.android.database.objects.FieldKeys
import org.zotero.android.database.objects.ItemTypes
import org.zotero.android.database.objects.RItem
import org.zotero.android.database.objects.RItemField
import org.zotero.android.database.objects.UpdatableChangeType
import org.zotero.android.sync.LibraryIdentifier
import java.util.Date

data class AttachmentReplacementData(
    val filename: String,
    val contentType: String,
    val oldMd5: String?,
)

class ReadAttachmentReplacementDataDbRequest(
    private val key: String,
    private val libraryId: LibraryIdentifier,
) : DbResponseRequest<AttachmentReplacementData> {
    override val needsWrite: Boolean
        get() = false

    override fun process(database: Realm): AttachmentReplacementData {
        val item = database.where<RItem>().key(key, libraryId).findFirst()
            ?: throw DbError.objectNotFound
        if (item.rawType != ItemTypes.attachment) {
            throw DbError.invalidRequest
        }

        val filename = item.fields
            .where()
            .key(FieldKeys.Item.Attachment.filename)
            .findFirst()
            ?.value
            ?: throw DbError.objectNotFound
        val contentType = item.fields
            .where()
            .key(FieldKeys.Item.Attachment.contentType)
            .findFirst()
            ?.value
            ?: "application/pdf"
        if (contentType != "application/pdf" && !filename.endsWith(".pdf", ignoreCase = true)) {
            throw DbError.invalidRequest
        }

        val oldMd5 = item.backendMd5
            .takeIf { it.isNotEmpty() && it != "null" }
        return AttachmentReplacementData(
            filename = filename,
            contentType = contentType,
            oldMd5 = oldMd5,
        )
    }
}

class StoreAttachmentReplacementFileDbRequest(
    private val key: String,
    private val libraryId: LibraryIdentifier,
    private val md5: String,
    private val mtime: Long,
) : DbRequest {
    override val needsWrite: Boolean
        get() = true

    override fun process(database: Realm) {
        val item = database.where<RItem>().key(key, libraryId).findFirst()
            ?: throw DbError.objectNotFound
        if (item.rawType != ItemTypes.attachment) {
            throw DbError.invalidRequest
        }

        item.attachmentNeedsSync = true
        item.fileDownloaded = true
        item.changeType = UpdatableChangeType.user.name
        item.dateModified = Date()
        item.field(FieldKeys.Item.Attachment.md5, database).value = md5
        item.field(FieldKeys.Item.Attachment.mtime, database).value = mtime.toString()
    }

    private fun RItem.field(key: String, database: Realm): RItemField {
        val existing = fields.where().key(key).findFirst()
        if (existing != null) {
            existing.changed = false
            return existing
        }
        return database.createEmbeddedObject(RItemField::class.java, this, "fields").apply {
            this.key = key
            this.baseKey = null
            this.changed = false
        }
    }
}
