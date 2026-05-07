package org.zotero.android.screens.share

import android.widget.Toast
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import org.zotero.android.screens.retrievemetadata.data.RetrieveMetadataState
import org.zotero.android.screens.settings.elements.NewSettingsDivider
import org.zotero.android.screens.share.ShareViewEffect.NavigateBack
import org.zotero.android.screens.share.ShareViewEffect.NavigateToCollectionPickerScreen
import org.zotero.android.screens.share.ShareViewEffect.NavigateToTagPickerScreen
import org.zotero.android.screens.share.bottompanel.ShareFailureBottomPanel
import org.zotero.android.screens.share.sections.ShareCollectionsSection
import org.zotero.android.screens.share.sections.ShareParsedItemSection
import org.zotero.android.screens.share.sections.ShareRecognizeItemSection
import org.zotero.android.screens.share.sections.ShareTagsSection
import org.zotero.android.screens.share.data.ProcessedAttachment
import org.zotero.android.uicomponents.CustomScaffoldM3
import org.zotero.android.uicomponents.button.SecondaryButton
import org.zotero.android.uicomponents.themem3.AppThemeM3

@Composable
internal fun ShareScreen(
    navigateToTagPicker: () -> Unit,
    navigateToCollectionPicker: () -> Unit,
    onBack: () -> Unit,
    viewModel: ShareViewModel = hiltViewModel(),
) {
    AppThemeM3 {
        val viewState by viewModel.viewStates.observeAsState(ShareViewState())
        val viewEffect by viewModel.viewEffects.observeAsState()
        val context = LocalContext.current
        LaunchedEffect(key1 = viewModel) {
            viewModel.init()
        }

        LaunchedEffect(key1 = viewEffect) {
            when (val effect = viewEffect?.consume()) {
                NavigateBack -> onBack()
                NavigateToTagPickerScreen -> {
                    navigateToTagPicker()
                }

                NavigateToCollectionPickerScreen -> {
                    navigateToCollectionPicker()
                }

                is ShareViewEffect.ShowToast -> {
                    Toast.makeText(context, effect.message, Toast.LENGTH_LONG).show()
                }

                is ShareViewEffect.ShowToastAndNavigateBack -> {
                    Toast.makeText(context, effect.message, Toast.LENGTH_LONG).show()
                    onBack()
                }

                null -> Unit
            }
        }
        val isSubmitting = viewState.isSubmitting
        val isRetrieveMetadataLoading =
            viewState.retrieveMetadataState is RetrieveMetadataState.loading
        val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
        CustomScaffoldM3(
            scrollBehavior = scrollBehavior,
            topBar = {
                ShareScreenTopBar(
                    scrollBehavior = scrollBehavior,
                    onCancelClicked = onBack,
                    onSave = {
                        viewModel.submitAsync()
                    },
                    isLeftButtonEnabled = !isSubmitting,
                    isRightButtonEnabled = !isSubmitting && viewState.attachmentState.isSubmittable && !isRetrieveMetadataLoading,
                    attachmentError = viewState.attachmentState.error,
                    isLoading = isSubmitting || isRetrieveMetadataLoading,
                )
            },
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
            ) {
                item {
                    Spacer(modifier = Modifier.height(6.dp))

                    val retrieveMetadataState = viewState.retrieveMetadataState
                    if (retrieveMetadataState is RetrieveMetadataState.success) {
                        ShareRecognizeItemSection(retrieveMetadataState)
                    } else {
                        ShareParsedItemSection(
                            item = viewState.expectedItem,
                            attachment = viewState.expectedAttachment,
                            attachmentState = viewState.attachmentState,
                            title = viewState.title,
                            itemTitle = viewModel::itemTitle
                        )
                    }

                    NewSettingsDivider()
                }

                item {
                    val hasReplaceablePdf = viewState.pdfReplacementTarget != null &&
                            viewState.attachmentState.isSubmittable &&
                            viewState.processedAttachment.isPdfFileAttachment()
                    if (hasReplaceablePdf) {
                        SecondaryButton(
                            text = "Replace last opened PDF",
                            onClick = viewModel::replaceLastOpenedPdfAsync,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            isEnabled = !isSubmitting,
                            isLoading = isSubmitting,
                        )
                        NewSettingsDivider()
                    }
                }

                item {
                    ShareCollectionsSection(
                        collectionPickerState = viewState.collectionPickerState,
                        recents = viewState.recents,
                        navigateToMoreCollections = {
                            viewModel.navigateToCollectionPicker()
                        }, onCollectionClicked = { collection, library ->
                            viewModel.setFromRecent(collection, library)
                        })
                    NewSettingsDivider()
                }

                item {
                    ShareTagsSection(
                        navigateToTagPicker = viewModel::navigateToTagPicker,
                        tags = viewState.tags
                    )
                }
                item {
                    Spacer(modifier = Modifier.height(30.dp))
                    val hasItem = viewState.processedAttachment != null
                    ShareFailureBottomPanel(
                        viewModel = viewModel,
                        state = viewState.attachmentState,
                        itemState = viewState.itemPickerState,
                        hasItem = hasItem,
                        isSubmitting = isSubmitting
                    )
                }
            }
        }
    }
}

private fun ProcessedAttachment?.isPdfFileAttachment(): Boolean {
    return when (this) {
        is ProcessedAttachment.file -> file.extension.equals("pdf", ignoreCase = true)
        is ProcessedAttachment.itemWithAttachment -> attachmentFile.extension.equals("pdf", ignoreCase = true)
        is ProcessedAttachment.item, null -> false
    }
}
