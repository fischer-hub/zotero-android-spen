package org.zotero.android.pdf.reader

import android.graphics.Color as AndroidColor
import android.view.MotionEvent
import androidx.compose.animation.core.tween
import androidx.compose.animation.rememberSplineBasedDecay
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.pspdfkit.ui.special_mode.controller.AnnotationTool
import org.zotero.android.pdf.reader.toolbar.PdfReaderAnnotationCreationToolbar
import org.zotero.android.screens.allitems.ExportingAnnotatedPdfLoadingIndicator
import org.zotero.android.screens.allitems.GeneratingBibliographyLoadingIndicator
import kotlin.math.hypot
import kotlin.math.min


@Composable
internal fun PdfReaderPspdfKitBox(
    vMInterface: PdfReaderVMInterface,
    viewState: PdfReaderViewState
) {
    val density = LocalDensity.current
    val positionalThreshold = { distance: Float -> distance * 0.5f }
    val velocityThreshold = { with(density) { 1000.dp.toPx() } }
    val animationSpec = tween<Float>()

    var shouldShowSnapTargetAreas: Boolean by remember { mutableStateOf(false) }
    val confirmValueChange = { newValue: DragAnchors ->
        shouldShowSnapTargetAreas = false
        true
    }
    val decayAnimationSpec = rememberSplineBasedDecay<Float>()
    val anchoredDraggableState = rememberSaveable(
        saver = AnchoredDraggableState.Saver(
            snapAnimationSpec = animationSpec,
            positionalThreshold = positionalThreshold,
            velocityThreshold = velocityThreshold,
            confirmValueChange = confirmValueChange,
            decayAnimationSpec = decayAnimationSpec
        )
    ) {
        AnchoredDraggableState(
            initialValue = DragAnchors.Start,
            positionalThreshold = positionalThreshold,
            velocityThreshold = velocityThreshold,
            snapAnimationSpec = animationSpec,
            decayAnimationSpec = decayAnimationSpec,
            confirmValueChange = confirmValueChange,
        )
    }
    val rightTargetAreaXOffset = with(density) { 92.dp.toPx() }
    val predictionState = remember { StylusPredictionState() }
    val predictionMaxDistance = with(density) { 48.dp.toPx() }
    val predictedTail = predictionState.predictedTail
    val predictionLineWidth = with(density) {
        maxOf(1.5.dp.toPx(), vMInterface.activeLineWidth.dp.toPx())
    }
    val predictionColor = remember(vMInterface.toolColors[AnnotationTool.INK]) {
        vMInterface.toolColors[AnnotationTool.INK]
            ?.let { hex ->
                runCatching { Color(AndroidColor.parseColor(hex)) }.getOrNull()
            }
            ?: Color.Black
    }.copy(alpha = 0.55f)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(NavigationBarDefaults.windowInsets)
            .onSizeChanged { layoutSize ->
                val dragEndPoint = layoutSize.width - rightTargetAreaXOffset
                anchoredDraggableState.updateAnchors(
                    DraggableAnchors {
                        DragAnchors.entries
                            .forEach { anchor ->
                                anchor at dragEndPoint * anchor.fraction
                            }
                    }
                )
            }
    ) {
        PdfReaderPspdfKitView(
            vMInterface = vMInterface,
            onMotionEvent = { event ->
                if (vMInterface.activeAnnotationTool == AnnotationTool.INK) {
                    predictionState.onMotionEvent(
                        event = event,
                        maxPredictionDistance = predictionMaxDistance
                    )
                } else {
                    predictionState.clear()
                }
            }
        )
        if (predictedTail != null) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawLine(
                    color = predictionColor,
                    start = predictedTail.start,
                    end = predictedTail.end,
                    strokeWidth = predictionLineWidth,
                    cap = StrokeCap.Round
                )
            }
        }
        if (viewState.showCreationToolbar) {
            PdfReaderAnnotationCreationToolbar(
                viewState = viewState,
                vMInterface = vMInterface,
                state = anchoredDraggableState,
                onShowSnapTargetAreas = { shouldShowSnapTargetAreas = true },
                shouldShowSnapTargetAreas = shouldShowSnapTargetAreas
            )
        }
        if (viewState.isGeneratingBibliography) {
            GeneratingBibliographyLoadingIndicator()
        }
        if (viewState.isExportingAnnotatedPdf) {
            ExportingAnnotatedPdfLoadingIndicator()
        }
    }
}

enum class DragAnchors(val fraction: Float) {
    Start(0f),
    End(1f),
}

private class StylusPredictionState {
    var predictedTail: PredictedTail? by mutableStateOf(null)
        private set

    private var pointerId: Int? = null
    private var lastSample: TimedPoint? = null

    fun onMotionEvent(event: MotionEvent, maxPredictionDistance: Float) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_POINTER_DOWN -> {
                val pointerIndex = event.stylusPointerIndex() ?: return clear()
                pointerId = event.getPointerId(pointerIndex)
                lastSample = TimedPoint(
                    point = Offset(event.getX(pointerIndex), event.getY(pointerIndex)),
                    eventTime = event.eventTime
                )
                predictedTail = null
            }

            MotionEvent.ACTION_MOVE -> {
                val pointerIndex = pointerId
                    ?.let { event.findPointerIndex(it) }
                    ?.takeIf { it >= 0 }
                    ?: event.stylusPointerIndex()
                    ?: return clear()

                for (historyIndex in 0 until event.historySize) {
                    updatePrediction(
                        point = Offset(
                            event.getHistoricalX(pointerIndex, historyIndex),
                            event.getHistoricalY(pointerIndex, historyIndex)
                        ),
                        eventTime = event.getHistoricalEventTime(historyIndex),
                        maxPredictionDistance = maxPredictionDistance
                    )
                }
                updatePrediction(
                    point = Offset(event.getX(pointerIndex), event.getY(pointerIndex)),
                    eventTime = event.eventTime,
                    maxPredictionDistance = maxPredictionDistance
                )
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_POINTER_UP,
            MotionEvent.ACTION_CANCEL -> {
                val activePointerId = pointerId
                if (activePointerId == null || event.getPointerId(event.actionIndex) == activePointerId) {
                    clear()
                }
            }
        }
    }

    fun clear() {
        pointerId = null
        lastSample = null
        predictedTail = null
    }

    private fun updatePrediction(point: Offset, eventTime: Long, maxPredictionDistance: Float) {
        val previous = lastSample
        lastSample = TimedPoint(point = point, eventTime = eventTime)

        if (previous == null) {
            predictedTail = null
            return
        }

        val deltaTime = (eventTime - previous.eventTime).toFloat()
        if (deltaTime <= 0f || deltaTime > 64f) {
            predictedTail = null
            return
        }

        val delta = point - previous.point
        val distance = hypot(delta.x, delta.y)
        if (distance < 0.5f) {
            predictedTail = null
            return
        }

        val predictedDistance = min(distance / deltaTime * PREDICTION_MS, maxPredictionDistance)
        val predictedPoint = point + delta / distance * predictedDistance
        predictedTail = PredictedTail(start = point, end = predictedPoint)
    }

    private fun MotionEvent.stylusPointerIndex(): Int? {
        for (index in 0 until pointerCount) {
            when (getToolType(index)) {
                MotionEvent.TOOL_TYPE_STYLUS,
                MotionEvent.TOOL_TYPE_ERASER -> return index
            }
        }
        return null
    }

    private companion object {
        const val PREDICTION_MS = 24f
    }
}

private data class TimedPoint(
    val point: Offset,
    val eventTime: Long,
)

private data class PredictedTail(
    val start: Offset,
    val end: Offset,
)
