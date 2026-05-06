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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
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
    val predictionState = remember { CurvedStylusPredictionState() }
    val predictionMaxDistance = with(density) { 24.dp.toPx() }
    val predictedCurve = predictionState.predictedCurve
    val predictionLineWidth = with(density) {
        minOf(
            2.5.dp.toPx(),
            maxOf(1.25.dp.toPx(), vMInterface.activeLineWidth.dp.toPx() * 0.55f)
        )
    }
    val predictionColor = remember(vMInterface.toolColors[AnnotationTool.INK]) {
        vMInterface.toolColors[AnnotationTool.INK]
            ?.let { hex ->
                runCatching { Color(AndroidColor.parseColor(hex)) }.getOrNull()
            }
            ?: Color.Black
    }.copy(alpha = 0.28f)

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
        if (predictedCurve != null) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val path = Path().apply {
                    moveTo(predictedCurve.start.x, predictedCurve.start.y)
                    cubicTo(
                        predictedCurve.control1.x,
                        predictedCurve.control1.y,
                        predictedCurve.control2.x,
                        predictedCurve.control2.y,
                        predictedCurve.end.x,
                        predictedCurve.end.y
                    )
                }
                drawPath(
                    path = path,
                    color = predictionColor,
                    style = Stroke(
                        width = predictionLineWidth,
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round
                    )
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

private class CurvedStylusPredictionState {
    var predictedCurve: PredictedCurve? by mutableStateOf(null)
        private set

    private var pointerId: Int? = null
    private val samples = ArrayDeque<TimedPoint>(3)

    fun onMotionEvent(event: MotionEvent, maxPredictionDistance: Float) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_POINTER_DOWN -> {
                val pointerIndex = event.stylusPointerIndex() ?: return clear()
                pointerId = event.getPointerId(pointerIndex)
                samples.clear()
                addSample(
                    TimedPoint(
                        point = Offset(event.getX(pointerIndex), event.getY(pointerIndex)),
                        eventTime = event.eventTime
                    )
                )
                predictedCurve = null
            }

            MotionEvent.ACTION_MOVE -> {
                val pointerIndex = pointerId
                    ?.let { event.findPointerIndex(it) }
                    ?.takeIf { it >= 0 }
                    ?: event.stylusPointerIndex()
                    ?: return clear()

                for (historyIndex in 0 until event.historySize) {
                    addSample(
                        TimedPoint(
                            point = Offset(
                                event.getHistoricalX(pointerIndex, historyIndex),
                                event.getHistoricalY(pointerIndex, historyIndex)
                            ),
                            eventTime = event.getHistoricalEventTime(historyIndex)
                        )
                    )
                }
                addSample(
                    TimedPoint(
                        point = Offset(event.getX(pointerIndex), event.getY(pointerIndex)),
                        eventTime = event.eventTime
                    )
                )
                predictedCurve = calculatePrediction(maxPredictionDistance)
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
        samples.clear()
        predictedCurve = null
    }

    private fun addSample(sample: TimedPoint) {
        if (samples.lastOrNull()?.eventTime == sample.eventTime) {
            return
        }
        samples.addLast(sample)
        while (samples.size > 3) {
            samples.removeFirst()
        }
    }

    private fun calculatePrediction(maxPredictionDistance: Float): PredictedCurve? {
        if (samples.size < 3) {
            return null
        }

        val first = samples[0]
        val second = samples[1]
        val current = samples[2]
        val firstDeltaTime = (second.eventTime - first.eventTime).toFloat()
        val secondDeltaTime = (current.eventTime - second.eventTime).toFloat()
        if (
            firstDeltaTime !in MIN_SAMPLE_MS..MAX_SAMPLE_MS ||
            secondDeltaTime !in MIN_SAMPLE_MS..MAX_SAMPLE_MS
        ) {
            return null
        }

        val firstVelocity = (second.point - first.point) / firstDeltaTime
        val currentVelocity = (current.point - second.point) / secondDeltaTime
        val speed = hypot(currentVelocity.x, currentVelocity.y)
        if (speed < MIN_SPEED_PX_PER_MS) {
            return null
        }

        val velocityDelta = currentVelocity - firstVelocity
        val acceleration = velocityDelta / ((firstDeltaTime + secondDeltaTime) * 0.5f)
        val predictionMillis = PREDICTION_MS * predictionScaleForTurn(
            previousVelocity = firstVelocity,
            currentVelocity = currentVelocity
        )
        if (predictionMillis <= 0f) {
            return null
        }

        var predictedDelta = currentVelocity * predictionMillis +
                acceleration * (0.5f * predictionMillis * predictionMillis)
        val predictedDistance = hypot(predictedDelta.x, predictedDelta.y)
        if (predictedDistance < MIN_PREDICTION_DISTANCE_PX) {
            return null
        }
        if (predictedDistance > maxPredictionDistance) {
            predictedDelta *= maxPredictionDistance / predictedDistance
        }

        val predictedVelocity = currentVelocity + acceleration * predictionMillis
        val end = current.point + predictedDelta
        val controlDistance = predictionMillis / 3f
        val control1 = current.point + currentVelocity * controlDistance
        val control2 = end - predictedVelocity * controlDistance
        return PredictedCurve(
            start = current.point,
            control1 = control1.limitControlPoint(
                origin = current.point,
                maxDistance = maxPredictionDistance
            ),
            control2 = control2.limitControlPoint(
                origin = end,
                maxDistance = maxPredictionDistance
            ),
            end = end
        )
    }

    private fun predictionScaleForTurn(previousVelocity: Offset, currentVelocity: Offset): Float {
        val previousSpeed = hypot(previousVelocity.x, previousVelocity.y)
        val currentSpeed = hypot(currentVelocity.x, currentVelocity.y)
        if (previousSpeed < MIN_SPEED_PX_PER_MS || currentSpeed < MIN_SPEED_PX_PER_MS) {
            return 0.65f
        }

        val dotProduct = previousVelocity.x * currentVelocity.x +
                previousVelocity.y * currentVelocity.y
        val cosine = (dotProduct / (previousSpeed * currentSpeed)).coerceIn(-1f, 1f)
        return when {
            cosine < SHARP_TURN_COSINE -> 0f
            cosine < SOFT_TURN_COSINE -> 0.35f
            else -> 1f
        }
    }

    private fun Offset.limitControlPoint(origin: Offset, maxDistance: Float): Offset {
        val delta = this - origin
        val distance = hypot(delta.x, delta.y)
        if (distance <= maxDistance || distance == 0f) {
            return this
        }
        return origin + delta / distance * maxDistance
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
        const val PREDICTION_MS = 14f
        const val MIN_SAMPLE_MS = 1f
        const val MAX_SAMPLE_MS = 48f
        const val MIN_SPEED_PX_PER_MS = 0.02f
        const val MIN_PREDICTION_DISTANCE_PX = 1.25f
        const val SHARP_TURN_COSINE = 0.15f
        const val SOFT_TURN_COSINE = 0.65f
    }
}

private data class TimedPoint(
    val point: Offset,
    val eventTime: Long,
)

private data class PredictedCurve(
    val start: Offset,
    val control1: Offset,
    val control2: Offset,
    val end: Offset,
)
