package org.zotero.android.pdf.reader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint as AndroidPaint
import android.graphics.Path as AndroidPath
import androidx.compose.animation.core.tween
import androidx.compose.animation.rememberSplineBasedDecay
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.pspdfkit.ui.special_mode.controller.AnnotationTool
import org.zotero.android.pdf.reader.toolbar.PdfReaderAnnotationCreationToolbar
import org.zotero.android.screens.allitems.ExportingAnnotatedPdfLoadingIndicator
import org.zotero.android.screens.allitems.GeneratingBibliographyLoadingIndicator
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sqrt


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
    val pressureInkState = remember { PressureInkStrokeState() }
    val inkOverlayController = remember { PressureInkOverlayController() }
    val stylusButtonUndoState = remember { StylusButtonUndoState() }
    val predictionMaxDistance = with(density) { 38.dp.toPx() }
    val bakedInkBaseWidth = with(density) {
        maxOf(0.45.dp.toPx(), vMInterface.activeLineWidth.dp.toPx() * 0.72f)
    }
    val inkColorInt = remember(vMInterface.toolColors[AnnotationTool.INK]) {
        vMInterface.toolColors[AnnotationTool.INK]
            ?.let { hex ->
                runCatching { AndroidColor.parseColor(hex) }.getOrNull()
            }
            ?: AndroidColor.BLACK
    }
    val undoFromStylusButton = {
        pressureInkState.clear(inkOverlayController)
        if (vMInterface.canUndo()) {
            vMInterface.onUndoClick()
        }
    }

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
            inkOverlayController = inkOverlayController,
            onMotionEvent = { event ->
                if (
                    stylusButtonUndoState.onMotionEvent(
                        event = event,
                        onUndo = undoFromStylusButton
                    )
                ) {
                    true
                } else if (vMInterface.activeAnnotationTool == AnnotationTool.INK) {
                    pressureInkState.onMotionEvent(
                        event = event,
                        baseStrokeWidthPx = bakedInkBaseWidth,
                        predictionMaxDistancePx = predictionMaxDistance,
                        color = inkColorInt,
                        inkOverlayController = inkOverlayController,
                        onCommit = vMInterface::addBakedInkStroke
                    )
                } else {
                    pressureInkState.cancelActiveStroke(inkOverlayController)
                    false
                }
            },
            onKeyEvent = { event ->
                stylusButtonUndoState.onKeyEvent(
                    event = event,
                    onUndo = undoFromStylusButton
                )
            }
        )
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

private class StylusButtonUndoState {
    private var isButtonDown = false

    fun onKeyEvent(event: KeyEvent, onUndo: () -> Unit): Boolean {
        if (!event.isStylusUndoKey()) {
            return false
        }

        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            onUndo()
        }
        return event.action == KeyEvent.ACTION_DOWN || event.action == KeyEvent.ACTION_UP
    }

    fun onMotionEvent(event: MotionEvent, onUndo: () -> Unit): Boolean {
        if (!event.isStylusEvent()) {
            isButtonDown = false
            return false
        }

        val isButtonPress = event.actionMasked == MotionEvent.ACTION_BUTTON_PRESS &&
                event.isStylusUndoActionButton()
        val isButtonRelease = event.actionMasked == MotionEvent.ACTION_BUTTON_RELEASE &&
                event.isStylusUndoActionButton()
        val isButtonCurrentlyDown = event.hasStylusUndoButtonState()
        val shouldUndo = isButtonPress || (isButtonCurrentlyDown && !isButtonDown)

        if (shouldUndo) {
            onUndo()
        }

        isButtonDown = isButtonCurrentlyDown && !isButtonRelease
        return shouldUndo || isButtonRelease || isButtonDown
    }

    private fun MotionEvent.isStylusEvent(): Boolean {
        if (isFromSource(InputDevice.SOURCE_STYLUS)) {
            return true
        }
        for (index in 0 until pointerCount) {
            when (getToolType(index)) {
                MotionEvent.TOOL_TYPE_STYLUS,
                MotionEvent.TOOL_TYPE_ERASER -> return true
            }
        }
        return false
    }

    private fun MotionEvent.hasStylusUndoButtonState(): Boolean {
        return buttonState.hasStylusUndoButton()
    }

    private fun MotionEvent.isStylusUndoActionButton(): Boolean {
        return actionButton.hasStylusUndoButton()
    }

    private fun Int.hasStylusUndoButton(): Boolean {
        return this and MotionEvent.BUTTON_STYLUS_PRIMARY != 0 ||
                this and MotionEvent.BUTTON_STYLUS_SECONDARY != 0 ||
                this and MotionEvent.BUTTON_SECONDARY != 0
    }

    private fun KeyEvent.isStylusUndoKey(): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_STEM_PRIMARY,
            KeyEvent.KEYCODE_STYLUS_BUTTON_PRIMARY,
            KeyEvent.KEYCODE_STYLUS_BUTTON_SECONDARY,
            KeyEvent.KEYCODE_STYLUS_BUTTON_TERTIARY,
            KeyEvent.KEYCODE_STYLUS_BUTTON_TAIL -> true
            else -> false
        }
    }
}

private class PressureInkStrokeState {
    private var pointerId: Int? = null
    private val samples = mutableListOf<PressureSample>()
    private var commitGeneration = 0
    private var minStrokePressure = 1f
    private var maxStrokePressure = 0f
    private val pendingCommitHandler = Handler(Looper.getMainLooper())
    private val pendingCommits = mutableListOf<PendingPressureInkCommit>()
    private val flushPendingCommitsRunnable = Runnable { flushPendingCommits() }

    fun onMotionEvent(
        event: MotionEvent,
        baseStrokeWidthPx: Float,
        predictionMaxDistancePx: Float,
        color: Int,
        inkOverlayController: PressureInkOverlayController,
        onCommit: (Bitmap, RectF, () -> Unit) -> Unit
    ): Boolean {
        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (pointerId != null) {
                    true
                } else {
                    val pointerIndex = event.stylusPointerIndex() ?: return false
                    postponePendingCommits()
                    pointerId = event.getPointerId(pointerIndex)
                    resetActiveSamples()
                    addSample(event.currentPressureSample(pointerIndex))
                    publishLiveStroke(
                        baseStrokeWidthPx = baseStrokeWidthPx,
                        maxPredictionDistancePx = predictionMaxDistancePx,
                        color = color,
                        inkOverlayController = inkOverlayController
                    )
                    true
                }
            }

            MotionEvent.ACTION_MOVE -> {
                val pointerIndex = pointerIndex(event) ?: return pointerId != null
                addSamples(event = event, pointerIndex = pointerIndex)
                publishLiveStroke(
                    baseStrokeWidthPx = baseStrokeWidthPx,
                    maxPredictionDistancePx = predictionMaxDistancePx,
                    color = color,
                    inkOverlayController = inkOverlayController
                )
                true
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_POINTER_UP -> {
                val activePointerId = pointerId ?: return false
                val actionPointerId = event.getPointerId(event.actionIndex)
                if (event.actionMasked == MotionEvent.ACTION_POINTER_UP &&
                    actionPointerId != activePointerId
                ) {
                    return true
                }

                pointerIndex(event)?.let { pointerIndex ->
                    addSamples(event = event, pointerIndex = pointerIndex)
                }
                commitStroke(
                    baseStrokeWidthPx = baseStrokeWidthPx,
                    color = color,
                    inkOverlayController = inkOverlayController,
                    onCommit = onCommit
                )
                true
            }

            MotionEvent.ACTION_CANCEL -> {
                val wasDrawing = pointerId != null
                clearActiveStroke(inkOverlayController)
                wasDrawing
            }

            else -> pointerId != null
        }
    }

    fun clear(inkOverlayController: PressureInkOverlayController) {
        pendingCommitHandler.removeCallbacks(flushPendingCommitsRunnable)
        pendingCommits.clear()
        clearActiveStroke(inkOverlayController)
        inkOverlayController.clear()
    }

    fun cancelActiveStroke(inkOverlayController: PressureInkOverlayController) {
        clearActiveStroke(inkOverlayController)
    }

    private fun clearActiveStroke(inkOverlayController: PressureInkOverlayController) {
        pointerId = null
        resetActiveSamples()
        inkOverlayController.clearLiveStroke()
    }

    private fun resetActiveSamples() {
        samples.clear()
        minStrokePressure = 1f
        maxStrokePressure = 0f
    }

    private fun pointerIndex(event: MotionEvent): Int? {
        return pointerId
            ?.let { event.findPointerIndex(it) }
            ?.takeIf { it >= 0 }
    }

    private fun addSamples(event: MotionEvent, pointerIndex: Int) {
        for (historyIndex in 0 until event.historySize) {
            addSample(event.historicalPressureSample(pointerIndex, historyIndex))
        }
        addSample(event.currentPressureSample(pointerIndex))
    }

    private fun addSample(sample: PressureSample) {
        val last = samples.lastOrNull()
        if (last != null) {
            val distance = hypot(
                sample.point.x - last.point.x,
                sample.point.y - last.point.y
            )
            if (sample.eventTime == last.eventTime && distance < MIN_SAMPLE_DISTANCE_PX) {
                return
            }
            if (sample.eventTime < last.eventTime) {
                return
            }
        }
        val sanitizedPressure = sample.pressure.sanitizedPressure()
        minStrokePressure = minOf(minStrokePressure, sanitizedPressure)
        maxStrokePressure = maxOf(maxStrokePressure, sanitizedPressure)
        samples.add(sample.copy(pressure = sanitizedPressure))
    }

    private fun publishLiveStroke(
        baseStrokeWidthPx: Float,
        maxPredictionDistancePx: Float,
        color: Int,
        inkOverlayController: PressureInkOverlayController
    ) {
        inkOverlayController.setLiveStroke(
            samples = samples.toList(),
            pressureProfile = currentPressureProfile(),
            predictedSample = calculatePrediction(maxPredictionDistancePx),
            baseStrokeWidthPx = baseStrokeWidthPx,
            color = color
        )
    }

    private fun currentPressureProfile(): PressureProfile {
        return PressureProfile.from(
            samples = samples,
            fallbackMinPressure = minStrokePressure,
            fallbackMaxPressure = maxStrokePressure
        )
    }

    private fun commitStroke(
        baseStrokeWidthPx: Float,
        color: Int,
        inkOverlayController: PressureInkOverlayController,
        onCommit: (Bitmap, RectF, () -> Unit) -> Unit
    ) {
        val strokeSamples = samples.toList()
        val strokePressureProfile = currentPressureProfile()
        if (strokeSamples.isEmpty()) {
            clearActiveStroke(inkOverlayController)
            return
        }

        val committedGeneration = ++commitGeneration
        inkOverlayController.addCommittedPreview(
            CommittedPressureStroke(
                id = committedGeneration,
                samples = strokeSamples,
                pressureProfile = strokePressureProfile,
                baseStrokeWidthPx = baseStrokeWidthPx,
                color = color
            )
        )
        pendingCommits.add(
            PendingPressureInkCommit(
                id = committedGeneration,
                samples = strokeSamples,
                baseStrokeWidthPx = baseStrokeWidthPx,
                color = color,
                onCommit = onCommit,
                onCommittedPreviewReadyToClear = inkOverlayController::removeCommittedPreview
            )
        )
        clearActiveStroke(inkOverlayController)
        schedulePendingCommitsFlush()
    }

    private fun postponePendingCommits() {
        pendingCommitHandler.removeCallbacks(flushPendingCommitsRunnable)
    }

    private fun schedulePendingCommitsFlush() {
        pendingCommitHandler.removeCallbacks(flushPendingCommitsRunnable)
        pendingCommitHandler.postDelayed(
            flushPendingCommitsRunnable,
            INK_COMMIT_IDLE_DELAY_MS
        )
    }

    private fun flushPendingCommits() {
        if (pendingCommits.isEmpty()) {
            return
        }

        val commits = pendingCommits.toList()
        pendingCommits.clear()
        commits.forEach { commit ->
            val viewBounds = strokeBounds(
                samples = commit.samples,
                baseStrokeWidthPx = commit.baseStrokeWidthPx
            )
            val bitmap = renderStrokeBitmap(
                samples = commit.samples,
                viewBounds = viewBounds,
                baseStrokeWidthPx = commit.baseStrokeWidthPx,
                color = commit.color
            ) ?: run {
                commit.onCommittedPreviewReadyToClear(commit.id)
                return@forEach
            }
            commit.onCommit(bitmap, viewBounds) {
                commit.onCommittedPreviewReadyToClear(commit.id)
            }
        }
    }

    private fun calculatePrediction(maxPredictionDistance: Float): PressureSample? {
        if (samples.size < 3) {
            return null
        }

        val first = samples[samples.lastIndex - 2]
        val second = samples[samples.lastIndex - 1]
        val current = samples[samples.lastIndex]
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

        return current.copy(
            point = current.point + predictedDelta,
            eventTime = current.eventTime + predictionMillis.toLong().coerceAtLeast(1L)
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
            cosine < SOFT_TURN_COSINE -> 0.55f
            else -> 1f
        }
    }

    private fun MotionEvent.stylusPointerIndex(): Int? {
        if (actionMasked == MotionEvent.ACTION_POINTER_DOWN &&
            getToolType(actionIndex) == MotionEvent.TOOL_TYPE_STYLUS
        ) {
            return actionIndex
        }

        for (index in 0 until pointerCount) {
            if (getToolType(index) == MotionEvent.TOOL_TYPE_STYLUS) {
                return index
            }
        }
        return null
    }

    private fun MotionEvent.currentPressureSample(pointerIndex: Int): PressureSample {
        return PressureSample(
            point = Offset(getX(pointerIndex), getY(pointerIndex)),
            eventTime = eventTime,
            pressure = getPressure(pointerIndex)
        )
    }

    private fun MotionEvent.historicalPressureSample(
        pointerIndex: Int,
        historyIndex: Int
    ): PressureSample {
        return PressureSample(
            point = Offset(
                getHistoricalX(pointerIndex, historyIndex),
                getHistoricalY(pointerIndex, historyIndex)
            ),
            eventTime = getHistoricalEventTime(historyIndex),
            pressure = getHistoricalPressure(pointerIndex, historyIndex)
        )
    }

    private companion object {
        const val PREDICTION_MS = 22f
        const val MIN_SAMPLE_MS = 1f
        const val MAX_SAMPLE_MS = 48f
        const val MIN_SPEED_PX_PER_MS = 0.02f
        const val MIN_PREDICTION_DISTANCE_PX = 1.0f
        const val MIN_SAMPLE_DISTANCE_PX = 0.08f
        const val SHARP_TURN_COSINE = 0.15f
        const val SOFT_TURN_COSINE = 0.65f
    }
}

private fun drawPredictedPressureStroke(
    canvas: AndroidCanvas,
    samples: List<PressureSample>,
    predictedSample: PressureSample,
    baseStrokeWidthPx: Float,
    color: Int,
    pressureProfile: PressureProfile
) {
    if (samples.isEmpty()) {
        return
    }

    val paint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        alpha = PREDICTION_ALPHA
        style = AndroidPaint.Style.STROKE
        strokeCap = AndroidPaint.Cap.ROUND
        strokeJoin = AndroidPaint.Join.ROUND
        strokeWidth = strokeWidthForPredictedSample(
            samples = samples,
            predictedSample = predictedSample,
            baseStrokeWidthPx = baseStrokeWidthPx,
            pressureProfile = pressureProfile
        )
    }
    val current = samples.last()
    if (samples.size < 2) {
        canvas.drawLine(
            current.point.x,
            current.point.y,
            predictedSample.point.x,
            predictedSample.point.y,
            paint
        )
        return
    }

    val previous = samples[samples.lastIndex - 1]
    val tangent = current.point - previous.point
    val path = AndroidPath().apply {
        moveTo(current.point.x, current.point.y)
        cubicTo(
            current.point.x + tangent.x * PREDICTION_CONTROL_TENSION,
            current.point.y + tangent.y * PREDICTION_CONTROL_TENSION,
            predictedSample.point.x - tangent.x * PREDICTION_END_TENSION,
            predictedSample.point.y - tangent.y * PREDICTION_END_TENSION,
            predictedSample.point.x,
            predictedSample.point.y
        )
    }
    canvas.drawPath(path, paint)
}

private fun strokeBounds(samples: List<PressureSample>, baseStrokeWidthPx: Float): RectF {
    var left = Float.POSITIVE_INFINITY
    var top = Float.POSITIVE_INFINITY
    var right = Float.NEGATIVE_INFINITY
    var bottom = Float.NEGATIVE_INFINITY
    var maxStrokeWidth = baseStrokeWidthPx
    val pressureProfile = PressureProfile.from(samples)

    samples.forEachIndexed { index, sample ->
        left = minOf(left, sample.point.x)
        top = minOf(top, sample.point.y)
        right = maxOf(right, sample.point.x)
        bottom = maxOf(bottom, sample.point.y)
        maxStrokeWidth = max(
            maxStrokeWidth,
            strokeWidthForSample(samples, index, baseStrokeWidthPx, pressureProfile)
        )
    }

    val margin = maxStrokeWidth * 0.5f + BITMAP_STROKE_PADDING_PX
    return RectF(
        left - margin,
        top - margin,
        right + margin,
        bottom + margin
    )
}

private fun renderStrokeBitmap(
    samples: List<PressureSample>,
    viewBounds: RectF,
    baseStrokeWidthPx: Float,
    color: Int
): Bitmap? {
    if (viewBounds.width() <= 0f || viewBounds.height() <= 0f) {
        return null
    }

    val bitmapScale = bitmapScaleFor(viewBounds)
    val width = ceil(viewBounds.width() * bitmapScale).toInt().coerceAtLeast(1)
    val height = ceil(viewBounds.height() * bitmapScale).toInt().coerceAtLeast(1)
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = AndroidCanvas(bitmap)
    canvas.scale(bitmapScale, bitmapScale)
    canvas.translate(-viewBounds.left, -viewBounds.top)
    drawPressureStroke(
        canvas = canvas,
        samples = samples,
        baseStrokeWidthPx = baseStrokeWidthPx,
        color = color
    )
    return bitmap
}

private fun drawPressureStroke(
    canvas: AndroidCanvas,
    samples: List<PressureSample>,
    baseStrokeWidthPx: Float,
    color: Int,
    pressureProfile: PressureProfile = PressureProfile.from(samples)
) {
    if (samples.isEmpty()) {
        return
    }

    val paint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = AndroidPaint.Style.STROKE
        strokeCap = AndroidPaint.Cap.ROUND
        strokeJoin = AndroidPaint.Join.ROUND
    }

    if (samples.size == 1) {
        val width = strokeWidthForSample(samples, 0, baseStrokeWidthPx, pressureProfile)
        paint.style = AndroidPaint.Style.FILL
        canvas.drawCircle(samples[0].point.x, samples[0].point.y, width * 0.5f, paint)
        return
    }

    if (samples.size == 2) {
        paint.strokeWidth = strokeWidthForSample(samples, 1, baseStrokeWidthPx, pressureProfile)
        canvas.drawLine(
            samples[0].point.x,
            samples[0].point.y,
            samples[1].point.x,
            samples[1].point.y,
            paint
        )
        return
    }

    var lastMidpoint = midpoint(samples[0].point, samples[1].point)
    paint.strokeWidth = strokeWidthForSample(samples, 0, baseStrokeWidthPx, pressureProfile)
    canvas.drawLine(
        samples[0].point.x,
        samples[0].point.y,
        lastMidpoint.x,
        lastMidpoint.y,
        paint
    )

    val path = AndroidPath()
    for (index in 1 until samples.lastIndex) {
        val start = midpoint(samples[index - 1].point, samples[index].point)
        val end = midpoint(samples[index].point, samples[index + 1].point)
        path.reset()
        path.moveTo(start.x, start.y)
        path.quadTo(samples[index].point.x, samples[index].point.y, end.x, end.y)
        paint.strokeWidth = strokeWidthForSample(samples, index, baseStrokeWidthPx, pressureProfile)
        canvas.drawPath(path, paint)
    }

    lastMidpoint = midpoint(
        samples[samples.lastIndex - 1].point,
        samples[samples.lastIndex].point
    )
    paint.strokeWidth = strokeWidthForSample(
        samples,
        samples.lastIndex,
        baseStrokeWidthPx,
        pressureProfile
    )
    canvas.drawLine(
        lastMidpoint.x,
        lastMidpoint.y,
        samples[samples.lastIndex].point.x,
        samples[samples.lastIndex].point.y,
        paint
    )
}

private fun bitmapScaleFor(viewBounds: RectF): Float {
    val maxSide = max(viewBounds.width(), viewBounds.height())
    if (maxSide <= 0f) {
        return 1f
    }
    return minOf(BITMAP_QUALITY_SCALE, MAX_BAKED_STROKE_BITMAP_SIDE / maxSide)
        .coerceAtLeast(MIN_BAKED_STROKE_BITMAP_SCALE)
}

private fun strokeWidthForSample(
    samples: List<PressureSample>,
    index: Int,
    baseStrokeWidthPx: Float,
    pressureProfile: PressureProfile
): Float {
    val previous = samples.getOrNull(index - 1)?.pressure ?: samples[index].pressure
    val current = samples[index].pressure
    val next = samples.getOrNull(index + 1)?.pressure ?: samples[index].pressure
    val smoothedPressure = previous * 0.06f + current * 0.88f + next * 0.06f
    val smoothedSpeed = smoothedSpeedForSample(samples, index)
    return strokeWidthForDynamics(
        baseStrokeWidthPx = baseStrokeWidthPx,
        pressure = smoothedPressure,
        speedPxPerMs = smoothedSpeed,
        pressureProfile = pressureProfile
    )
}

private fun strokeWidthForPredictedSample(
    samples: List<PressureSample>,
    predictedSample: PressureSample,
    baseStrokeWidthPx: Float,
    pressureProfile: PressureProfile
): Float {
    val current = samples.lastOrNull()
    val predictedSpeed = if (current != null) {
        speedBetween(current, predictedSample)
    } else {
        null
    } ?: DEFAULT_SPEED_PX_PER_MS
    return strokeWidthForDynamics(
        baseStrokeWidthPx = baseStrokeWidthPx,
        pressure = predictedSample.pressure,
        speedPxPerMs = predictedSpeed,
        pressureProfile = pressureProfile
    )
}

private fun strokeWidthForDynamics(
    baseStrokeWidthPx: Float,
    pressure: Float,
    speedPxPerMs: Float,
    pressureProfile: PressureProfile
): Float {
    val pressureInput = pressureProfile.adjustedPressure(pressure)
    val speedInput = pressureProfile.adjustedSpeedPressure(speedPxPerMs)
    val speedInfluence = pressureProfile.speedInfluence()
    val dynamicPressure = (
            pressureInput * (1f - speedInfluence) +
                    speedInput * speedInfluence
            ).coerceIn(0f, 1f)
    return pressureToStrokeWidth(
        baseStrokeWidthPx = baseStrokeWidthPx,
        pressure = dynamicPressure
    )
}

private fun pressureToStrokeWidth(baseStrokeWidthPx: Float, pressure: Float): Float {
    val clampedPressure = pressure.sanitizedPressure()
    return baseStrokeWidthPx * (0.08f + sqrt(clampedPressure) * 3.02f)
}

private fun Float.sanitizedPressure(): Float {
    if (isNaN() || isInfinite()) {
        return DEFAULT_STYLUS_PRESSURE
    }
    return coerceIn(MIN_STYLUS_PRESSURE, MAX_STYLUS_PRESSURE)
}

private fun midpoint(first: Offset, second: Offset): Offset {
    return Offset(
        x = (first.x + second.x) * 0.5f,
        y = (first.y + second.y) * 0.5f
    )
}

internal data class PressureSample(
    val point: Offset,
    val eventTime: Long,
    val pressure: Float,
)

internal data class CommittedPressureStroke(
    val id: Int,
    val samples: List<PressureSample>,
    val pressureProfile: PressureProfile,
    val baseStrokeWidthPx: Float,
    val color: Int,
)

private data class PendingPressureInkCommit(
    val id: Int,
    val samples: List<PressureSample>,
    val baseStrokeWidthPx: Float,
    val color: Int,
    val onCommit: (Bitmap, RectF, () -> Unit) -> Unit,
    val onCommittedPreviewReadyToClear: (Int) -> Unit,
)

internal class PressureInkOverlayController {
    private var view: PressureInkOverlayView? = null

    fun attach(view: PressureInkOverlayView) {
        this.view = view
    }

    fun detach(view: PressureInkOverlayView) {
        if (this.view === view) {
            this.view = null
        }
    }

    fun setLiveStroke(
        samples: List<PressureSample>,
        pressureProfile: PressureProfile,
        predictedSample: PressureSample?,
        baseStrokeWidthPx: Float,
        color: Int
    ) {
        view?.setLiveStroke(
            samples = samples,
            pressureProfile = pressureProfile,
            predictedSample = predictedSample,
            baseStrokeWidthPx = baseStrokeWidthPx,
            color = color
        )
    }

    fun clearLiveStroke() {
        view?.clearLiveStroke()
    }

    fun addCommittedPreview(stroke: CommittedPressureStroke) {
        view?.addCommittedPreview(stroke)
    }

    fun removeCommittedPreview(id: Int) {
        view?.removeCommittedPreview(id)
    }

    fun clear() {
        view?.clear()
    }
}

internal class PressureInkOverlayView(context: Context) : View(context) {
    private var liveSamples: List<PressureSample> = emptyList()
    private var livePressureProfile: PressureProfile = PressureProfile.empty()
    private var livePredictedSample: PressureSample? = null
    private var liveBaseStrokeWidthPx: Float = 0f
    private var liveColor: Int = AndroidColor.BLACK
    private var liveBitmap: Bitmap? = null
    private var liveBitmapCanvas: AndroidCanvas? = null
    private var renderedLiveSampleCount = 0
    private var renderedLiveStrokeStartTime: Long? = null
    private var committedPreviewStrokes: List<CommittedPressureStroke> = emptyList()

    init {
        setWillNotDraw(false)
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun setLiveStroke(
        samples: List<PressureSample>,
        pressureProfile: PressureProfile,
        predictedSample: PressureSample?,
        baseStrokeWidthPx: Float,
        color: Int
    ) {
        val previousPredictedSample = livePredictedSample
        liveSamples = samples
        livePressureProfile = pressureProfile
        livePredictedSample = predictedSample
        liveBaseStrokeWidthPx = baseStrokeWidthPx
        liveColor = color
        val dirtyBounds = renderLiveStrokeIncrementally(
            samples = samples,
            pressureProfile = pressureProfile,
            baseStrokeWidthPx = baseStrokeWidthPx,
            color = color
        )
        invalidateStrokeBounds(dirtyBounds, previousPredictedSample, predictedSample)
    }

    fun clearLiveStroke() {
        if (liveSamples.isEmpty() && livePredictedSample == null) {
            return
        }
        liveSamples = emptyList()
        livePressureProfile = PressureProfile.empty()
        livePredictedSample = null
        clearLiveBitmap()
        postInvalidateOnAnimation()
    }

    fun addCommittedPreview(stroke: CommittedPressureStroke) {
        committedPreviewStrokes = committedPreviewStrokes + stroke
        postInvalidateOnAnimation()
    }

    fun removeCommittedPreview(id: Int) {
        val updatedStrokes = committedPreviewStrokes.filterNot { it.id == id }
        if (updatedStrokes.size == committedPreviewStrokes.size) {
            return
        }
        committedPreviewStrokes = updatedStrokes
        postInvalidateOnAnimation()
    }

    fun clear() {
        liveSamples = emptyList()
        livePressureProfile = PressureProfile.empty()
        livePredictedSample = null
        clearLiveBitmap()
        committedPreviewStrokes = emptyList()
        postInvalidateOnAnimation()
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        clearLiveBitmap()
        if (liveSamples.isNotEmpty()) {
            renderLiveStrokeIncrementally(
                samples = liveSamples,
                pressureProfile = livePressureProfile,
                baseStrokeWidthPx = liveBaseStrokeWidthPx,
                color = liveColor
            )
        }
    }

    override fun onDraw(canvas: AndroidCanvas) {
        super.onDraw(canvas)

        committedPreviewStrokes.forEach { previewStroke ->
            drawPressureStroke(
                canvas = canvas,
                samples = previewStroke.samples,
                baseStrokeWidthPx = previewStroke.baseStrokeWidthPx,
                color = previewStroke.color,
                pressureProfile = previewStroke.pressureProfile
            )
        }

        if (liveSamples.isEmpty()) {
            return
        }

        liveBitmap?.let { bitmap ->
            canvas.drawBitmap(bitmap, 0f, 0f, null)
        } ?: drawPressureStroke(
            canvas = canvas,
            samples = liveSamples,
            baseStrokeWidthPx = liveBaseStrokeWidthPx,
            color = liveColor,
            pressureProfile = livePressureProfile
        )
        livePredictedSample?.let { predictedSample ->
            drawPredictedPressureStroke(
                canvas = canvas,
                samples = liveSamples,
                predictedSample = predictedSample,
                baseStrokeWidthPx = liveBaseStrokeWidthPx,
                color = liveColor,
                pressureProfile = livePressureProfile
            )
        }
    }

    private fun renderLiveStrokeIncrementally(
        samples: List<PressureSample>,
        pressureProfile: PressureProfile,
        baseStrokeWidthPx: Float,
        color: Int
    ): RectF? {
        if (samples.isEmpty() || width <= 0 || height <= 0) {
            return null
        }

        ensureLiveBitmap()
        val canvas = liveBitmapCanvas ?: return null
        val strokeStartTime = samples.first().eventTime
        if (
            renderedLiveStrokeStartTime != strokeStartTime ||
            samples.size < renderedLiveSampleCount ||
            liveBitmap == null
        ) {
            liveBitmap?.eraseColor(AndroidColor.TRANSPARENT)
            renderedLiveSampleCount = 0
            renderedLiveStrokeStartTime = strokeStartTime
        }

        val startIndex = if (renderedLiveSampleCount == 0) {
            0
        } else {
            (renderedLiveSampleCount - 2).coerceAtLeast(0)
        }
        val samplesToRender = samples.subList(startIndex, samples.size)
        if (samplesToRender.isEmpty()) {
            return null
        }

        drawPressureStroke(
            canvas = canvas,
            samples = samplesToRender,
            baseStrokeWidthPx = baseStrokeWidthPx,
            color = color,
            pressureProfile = pressureProfile
        )
        renderedLiveSampleCount = samples.size
        renderedLiveStrokeStartTime = strokeStartTime
        return strokeBounds(samplesToRender, baseStrokeWidthPx)
    }

    private fun ensureLiveBitmap() {
        val existingBitmap = liveBitmap
        if (existingBitmap != null &&
            existingBitmap.width == width &&
            existingBitmap.height == height
        ) {
            return
        }
        if (width <= 0 || height <= 0) {
            return
        }
        liveBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        liveBitmapCanvas = AndroidCanvas(liveBitmap!!)
        renderedLiveSampleCount = 0
        renderedLiveStrokeStartTime = null
    }

    private fun clearLiveBitmap() {
        liveBitmap?.eraseColor(AndroidColor.TRANSPARENT)
        renderedLiveSampleCount = 0
        renderedLiveStrokeStartTime = null
    }

    private fun invalidateStrokeBounds(
        dirtyBounds: RectF?,
        previousPredictedSample: PressureSample?,
        predictedSample: PressureSample?
    ) {
        val bounds = dirtyBounds?.let { RectF(it) } ?: RectF()
        previousPredictedSample?.expandToInclude(bounds)
        predictedSample?.expandToInclude(bounds)
        if (bounds.isEmpty) {
            postInvalidateOnAnimation()
            return
        }
        val padding = (liveBaseStrokeWidthPx * 4f + BITMAP_STROKE_PADDING_PX).toInt()
        postInvalidateOnAnimation(
            bounds.left.toInt() - padding,
            bounds.top.toInt() - padding,
            bounds.right.toInt() + padding,
            bounds.bottom.toInt() + padding
        )
    }

    private fun PressureSample.expandToInclude(bounds: RectF) {
        if (bounds.isEmpty) {
            bounds.set(point.x, point.y, point.x, point.y)
            return
        }
        bounds.union(point.x, point.y)
    }
}

internal data class PressureProfile(
    private val minPressure: Float,
    private val maxPressure: Float,
    private val minSpeedPxPerMs: Float,
    private val maxSpeedPxPerMs: Float,
) {
    private val pressureRange = maxPressure - minPressure
    private val speedRange = maxSpeedPxPerMs - minSpeedPxPerMs

    fun adjustedPressure(pressure: Float): Float {
        val absolutePressure = pressure.sanitizedPressure()
        if (pressureRange < PRESSURE_ADAPTIVE_MIN_RANGE) {
            return absolutePressure
        }

        val relativePressure = ((absolutePressure - minPressure) / pressureRange)
            .coerceIn(0f, 1f)
        return (relativePressure * 0.99f + absolutePressure * 0.01f)
            .coerceIn(0f, 1f)
    }

    fun adjustedSpeedPressure(speedPxPerMs: Float): Float {
        if (speedRange < SPEED_ADAPTIVE_MIN_RANGE_PX_PER_MS) {
            return DEFAULT_SPEED_PSEUDO_PRESSURE
        }

        val normalizedSpeed = ((speedPxPerMs.sanitizedSpeed() - minSpeedPxPerMs) / speedRange)
            .coerceIn(0f, 1f)
        val slowStrokePressure = 1f - normalizedSpeed
        return (
                MIN_SPEED_PSEUDO_PRESSURE +
                        slowStrokePressure * (MAX_SPEED_PSEUDO_PRESSURE - MIN_SPEED_PSEUDO_PRESSURE)
                ).coerceIn(0f, 1f)
    }

    fun speedInfluence(): Float {
        return if (pressureRange < PRESSURE_ADAPTIVE_MIN_RANGE) {
            SPEED_INFLUENCE_WITH_FLAT_PRESSURE
        } else {
            SPEED_INFLUENCE_WITH_REAL_PRESSURE
        }
    }

    companion object {
        fun empty(): PressureProfile {
            return PressureProfile(
                minPressure = 0f,
                maxPressure = 0f,
                minSpeedPxPerMs = 0f,
                maxSpeedPxPerMs = 0f
            )
        }

        fun from(
            samples: List<PressureSample>,
            fallbackMinPressure: Float = 1f,
            fallbackMaxPressure: Float = 0f
        ): PressureProfile {
            var minPressure = fallbackMinPressure
            var maxPressure = fallbackMaxPressure
            var minSpeed = Float.POSITIVE_INFINITY
            var maxSpeed = 0f
            samples.forEach { sample ->
                val pressure = sample.pressure.sanitizedPressure()
                minPressure = minOf(minPressure, pressure)
                maxPressure = maxOf(maxPressure, pressure)
            }
            samples.forEachIndexed { index, _ ->
                val speed = smoothedSpeedForSample(samples, index)
                minSpeed = minOf(minSpeed, speed)
                maxSpeed = maxOf(maxSpeed, speed)
            }
            if (!minSpeed.isFinite()) {
                minSpeed = 0f
            }
            return PressureProfile(
                minPressure = minPressure,
                maxPressure = maxPressure,
                minSpeedPxPerMs = minSpeed,
                maxSpeedPxPerMs = maxSpeed
            )
        }
    }
}

private fun smoothedSpeedForSample(samples: List<PressureSample>, index: Int): Float {
    val current = sampleSpeedPxPerMs(samples, index)
    val previous = samples.getOrNull(index - 1)?.let {
        sampleSpeedPxPerMs(samples, index - 1)
    } ?: current
    val next = samples.getOrNull(index + 1)?.let {
        sampleSpeedPxPerMs(samples, index + 1)
    } ?: current
    return (previous * 0.18f + current * 0.64f + next * 0.18f).sanitizedSpeed()
}

private fun sampleSpeedPxPerMs(samples: List<PressureSample>, index: Int): Float {
    val previous = samples.getOrNull(index - 1)
    val current = samples[index]
    val next = samples.getOrNull(index + 1)
    return when {
        previous != null && next != null -> speedBetween(previous, next)
        previous != null -> speedBetween(previous, current)
        next != null -> speedBetween(current, next)
        else -> null
    } ?: DEFAULT_SPEED_PX_PER_MS
}

private fun speedBetween(first: PressureSample, second: PressureSample): Float? {
    val elapsedMs = (second.eventTime - first.eventTime).toFloat()
    if (elapsedMs <= 0f) {
        return null
    }
    return (hypot(second.point.x - first.point.x, second.point.y - first.point.y) / elapsedMs)
        .sanitizedSpeed()
}

private fun Float.sanitizedSpeed(): Float {
    if (isNaN() || isInfinite()) {
        return DEFAULT_SPEED_PX_PER_MS
    }
    return coerceIn(MIN_WIDTH_SPEED_PX_PER_MS, MAX_WIDTH_SPEED_PX_PER_MS)
}

private const val BITMAP_QUALITY_SCALE = 2f
private const val MAX_BAKED_STROKE_BITMAP_SIDE = 3072f
private const val MIN_BAKED_STROKE_BITMAP_SCALE = 0.5f
private const val BITMAP_STROKE_PADDING_PX = 4f
private const val DEFAULT_STYLUS_PRESSURE = 0.5f
private const val MIN_STYLUS_PRESSURE = 0f
private const val MAX_STYLUS_PRESSURE = 1f
private const val PRESSURE_ADAPTIVE_MIN_RANGE = 0.015f
private const val DEFAULT_SPEED_PX_PER_MS = 0.35f
private const val MIN_WIDTH_SPEED_PX_PER_MS = 0f
private const val MAX_WIDTH_SPEED_PX_PER_MS = 3.2f
private const val SPEED_ADAPTIVE_MIN_RANGE_PX_PER_MS = 0.06f
private const val MIN_SPEED_PSEUDO_PRESSURE = 0.14f
private const val MAX_SPEED_PSEUDO_PRESSURE = 0.96f
private const val DEFAULT_SPEED_PSEUDO_PRESSURE = 0.62f
private const val SPEED_INFLUENCE_WITH_REAL_PRESSURE = 0.22f
private const val SPEED_INFLUENCE_WITH_FLAT_PRESSURE = 0.52f
private const val INK_COMMIT_IDLE_DELAY_MS = 1200L
private const val PREDICTION_ALPHA = 97
private const val PREDICTION_CONTROL_TENSION = 0.55f
private const val PREDICTION_END_TENSION = 0.18f
