package org.zotero.android.screens.share


import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.Intent.EXTRA_STREAM
import android.content.Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
import android.content.Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
import android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
import android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.setContent
import com.pspdfkit.utils.getSupportParcelable
import dagger.hilt.android.AndroidEntryPoint
import org.zotero.android.architecture.BaseActivity
import org.zotero.android.ktx.enableEdgeToEdgeAndTranslucency
import org.zotero.android.screens.share.navigation.ShareRootNavigation
import org.zotero.android.uicomponents.themem3.AppThemeM3
import javax.inject.Inject

@AndroidEntryPoint
internal class ShareActivity : BaseActivity() {
    @Inject
    lateinit var shareRawAttachmentLoader: ShareRawAttachmentLoader

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdgeAndTranslucency()
        shareRawAttachmentLoader.loadFromIntent(intent)
        setContent {
            AppThemeM3 {
                ShareRootNavigation()
            }
        }
    }

    companion object {
        fun getIntent(
            extraIntent: Intent,
            context: Context,
        ): Intent {
            return Intent(context, ShareActivity::class.java).apply {
                data = extraIntent.data
                type = extraIntent.type
                putExtras(extraIntent)
                clipData = extraIntent.clipData ?: extraIntent
                    .extras
                    ?.getSupportParcelable(EXTRA_STREAM, Uri::class.java)
                    ?.let { uri ->
                        ClipData.newUri(context.contentResolver, "Shared file", uri)
                    }
                addFlags(extraIntent.flags and URI_PERMISSION_FLAGS)
            }
        }

        private const val URI_PERMISSION_FLAGS =
            FLAG_GRANT_READ_URI_PERMISSION or
                    FLAG_GRANT_WRITE_URI_PERMISSION or
                    FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                    FLAG_GRANT_PREFIX_URI_PERMISSION
    }

}
