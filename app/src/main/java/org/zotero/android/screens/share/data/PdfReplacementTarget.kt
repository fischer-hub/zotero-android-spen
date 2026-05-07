package org.zotero.android.screens.share.data

import org.zotero.android.database.objects.RCustomLibraryType
import org.zotero.android.sync.LibraryIdentifier

data class PdfReplacementTarget(
    val key: String,
    val libraryKind: String,
    val customLibraryType: String?,
    val groupId: Int?,
    val displayName: String?,
) {
    val libraryId: LibraryIdentifier?
        get() {
            return when (libraryKind) {
                LIBRARY_KIND_CUSTOM -> {
                    val type = customLibraryType?.let { RCustomLibraryType.valueOf(it) }
                        ?: return null
                    LibraryIdentifier.custom(type)
                }
                LIBRARY_KIND_GROUP -> {
                    val groupId = groupId ?: return null
                    LibraryIdentifier.group(groupId)
                }
                else -> null
            }
        }

    companion object {
        private const val LIBRARY_KIND_CUSTOM = "custom"
        private const val LIBRARY_KIND_GROUP = "group"

        fun from(
            key: String,
            libraryId: LibraryIdentifier,
            displayName: String?,
        ): PdfReplacementTarget {
            return when (libraryId) {
                is LibraryIdentifier.custom -> {
                    PdfReplacementTarget(
                        key = key,
                        libraryKind = LIBRARY_KIND_CUSTOM,
                        customLibraryType = libraryId.type.name,
                        groupId = null,
                        displayName = displayName,
                    )
                }
                is LibraryIdentifier.group -> {
                    PdfReplacementTarget(
                        key = key,
                        libraryKind = LIBRARY_KIND_GROUP,
                        customLibraryType = null,
                        groupId = libraryId.groupId,
                        displayName = displayName,
                    )
                }
            }
        }
    }
}
