package com.retrsoft.bilisponsorskip

import android.app.Activity
import android.app.Application
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ReplacementSpan
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.SeekBar
import android.widget.TextView
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import kotlin.math.ceil
import kotlin.math.max

internal class PlayerUiInjector(
    private val application: Application,
    private val controller: SkipController,
) : Application.ActivityLifecycleCallbacks {
    private data class TitleState(
        val originalDrawables: Array<Drawable?>,
        val originalDrawablePadding: Int,
        val drawable: TitleLabelDrawable?,
        val originalText: CharSequence?,
        val inlineSpan: TitleLabelSpan?,
        val video: SkipController.VideoKey,
        val label: String,
    )

    private data class MarkerState(
        val drawable: SegmentMarkerDrawable,
        val layoutListener: View.OnLayoutChangeListener,
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private val titleStates = WeakHashMap<TextView, TitleState>()
    private val markerStates = WeakHashMap<View, MarkerState>()
    private val loggedProgressClasses = mutableSetOf<String>()

    private var cachedTitleVideo: SkipController.VideoKey? = null
    private var cachedVideoTitle: String? = null
    private var observedDecor: WeakReference<View>? = null
    private var globalLayoutListener: ViewTreeObserver.OnGlobalLayoutListener? = null
    private var immediateRenderScheduled = false
    private var suppressExpandableTitleHook = false

    @Volatile
    private var resumedActivity: WeakReference<Activity>? = null

    fun start() {
        installExpandableTitleHook()
        application.registerActivityLifecycleCallbacks(this)
        mainHandler.post(renderRunnable)
        Log.d("player UI injector started")
    }

    private val renderRunnable = object : Runnable {
        override fun run() {
            renderSafely()
            mainHandler.postDelayed(this, RENDER_INTERVAL_MS)
        }
    }

    private val immediateRenderRunnable = Runnable {
        immediateRenderScheduled = false
        renderSafely()
    }

    private fun renderSafely() {
        runCatching { render() }.onFailure { Log.e("failed to render player UI markers", it) }
    }

    private fun requestImmediateRender() {
        if (immediateRenderScheduled) return
        immediateRenderScheduled = true
        mainHandler.post(immediateRenderRunnable)
    }

    private fun installExpandableTitleHook() {
        val titleClass = runCatching {
            application.classLoader.loadClass(EXPANDABLE_TITLE_CLASS)
        }.onFailure { Log.e("failed to resolve expandable video title", it) }.getOrNull() ?: return
        XposedBridge.hookAllMethods(titleClass, "setText", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (suppressExpandableTitleHook || param.args.size < 2) return
                val title = param.thisObject as? TextView ?: return
                if (!isDetailTitleStructure(title)) return
                val text = param.args[0] as? CharSequence ?: return
                val snapshot = controller.uiSnapshot()
                if (!snapshot.showTitleLabel || snapshot.segments.isEmpty()) {
                    param.args[0] = stripInlineTitleLabels(text)
                    return
                }
                val categories = snapshot.segments.map { it.category }.distinct()
                val label = categories.first().categoryLabel() +
                    if (categories.size > 1) " +${categories.size - 1}" else ""
                param.args[0] = decorateInlineText(title, text, label, categories.first())
            }

            override fun afterHookedMethod(param: MethodHookParam) {
                if (!suppressExpandableTitleHook) requestImmediateRender()
            }
        })
        Log.d("expandable title text hook installed")
    }

    private fun render() {
        val activity = resumedActivity?.get()
        val snapshot = controller.uiSnapshot()
        if (activity == null || activity.isFinishing || activity.isDestroyed) {
            clearTitleLabels()
            clearProgressMarkers()
            return
        }

        if (snapshot.video == null || snapshot.segments.isEmpty()) {
            clearTitleLabels()
            clearProgressMarkers()
            return
        }

        if (snapshot.showTitleLabel) renderTitleLabel(activity, snapshot) else clearTitleLabels()
        if (snapshot.showProgressMarkers && snapshot.durationMs > 0) {
            renderProgressMarkers(activity, snapshot)
        } else {
            clearProgressMarkers()
        }
    }

    private fun renderTitleLabel(activity: Activity, snapshot: SkipController.UiSnapshot) {
        val video = snapshot.video ?: return
        if (cachedTitleVideo != video) {
            cachedTitleVideo = video
            cachedVideoTitle = null
        }
        val titleId = activity.resources.getIdentifier(TITLE_ID_NAME, "id", activity.packageName)
        val decor = activity.window?.decorView ?: return

        val detailTitles = if (titleId == 0) {
            emptyList()
        } else {
            findViewsById(decor, titleId).filterIsInstance<TextView>().filter(::isVideoTitle)
        }
        detailTitles.firstOrNull()?.text?.toString()?.normalizedTitle()?.takeIf(String::isNotBlank)?.let {
            cachedVideoTitle = it
        }

        val expectedTitle = cachedVideoTitle
        val allTextViews = findAllViews(decor).filterIsInstance<TextView>()
        val playerTitles = allTextViews.filter(::isKnownPlayerTitle).toSet()
        if (expectedTitle.isNullOrBlank() && detailTitles.isEmpty() && playerTitles.isEmpty()) {
            clearTitleLabels()
            return
        }

        val titleTargets = allTextViews
            .filter { it.isShown && it.text.isNotBlank() }
            .filter { view ->
                view in detailTitles ||
                    view in playerTitles ||
                    (!expectedTitle.isNullOrBlank() && view.text.toString().normalizedTitle() == expectedTitle) ||
                    (!expectedTitle.isNullOrBlank() &&
                        view.contentDescription?.toString()?.normalizedTitle() == expectedTitle)
            }
            .toSet()
        if (titleTargets.isEmpty()) {
            clearTitleLabels()
            return
        }

        restoreTitleLabelsExcept(titleTargets)
        val categories = snapshot.segments.map { it.category }.distinct()
        val label = categories.first().categoryLabel() + if (categories.size > 1) " +${categories.size - 1}" else ""
        titleTargets.forEach { title ->
            decorateTitle(
                title,
                video,
                label,
                categories.first(),
                title in detailTitles,
                title in playerTitles,
                activity,
            )
        }
    }

    private fun decorateTitle(
        title: TextView,
        video: SkipController.VideoKey,
        label: String,
        category: String,
        isDetailTitle: Boolean,
        isKnownPlayerTitle: Boolean,
        activity: Activity,
    ) {
        val existingState = titleStates[title]
        val useInlineSpan = isDetailTitle && !isKnownPlayerTitle
        if (
            existingState?.video == video &&
            existingState?.label == label &&
            isTitleStateApplied(title, existingState)
        ) return

        if (existingState?.video == video && existingState.label == label) {
            if (useInlineSpan) {
                restoreTitle(title)
                applyInlineTitleLabel(title, video, label, category)
            } else {
                title.setCompoundDrawablesRelativeWithIntrinsicBounds(
                    existingState.drawable,
                    existingState.originalDrawables[1],
                    existingState.originalDrawables[2],
                    existingState.originalDrawables[3],
                )
                title.compoundDrawablePadding = existingState.originalDrawablePadding +
                    (TITLE_DRAWABLE_GAP_DP * title.resources.displayMetrics.density).toInt()
            }
            return
        }

        restoreTitle(title)
        if (useInlineSpan) {
            applyInlineTitleLabel(title, video, label, category)
        } else {
            applyCompoundTitleLabel(title, video, label, category)
        }
        val orientation = if (activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) {
            "landscape-player"
        } else {
            "portrait-player"
        }
        Log.d(
            "title label attached: $label; " +
                "location=${if (isDetailTitle && !isKnownPlayerTitle) "detail" else orientation}; " +
                "id=${title.resourceEntryName() ?: "none"}; view=${title.javaClass.name}",
        )
    }

    private fun isTitleStateApplied(title: TextView, state: TitleState): Boolean {
        state.inlineSpan?.let { expected ->
            val text = title.text as? Spanned ?: return false
            return text.getSpans(0, text.length, TitleLabelSpan::class.java).any { it === expected }
        }
        return state.drawable != null && title.compoundDrawablesRelative[0] === state.drawable
    }

    private fun applyInlineTitleLabel(
        title: TextView,
        video: SkipController.VideoKey,
        label: String,
        category: String,
    ) {
        val originalText = stripInlineTitleLabels(title.text)
        title.text = decorateInlineText(title, originalText, label, category)
        val appliedText = title.text as? Spanned
        val appliedSpan = appliedText
            ?.getSpans(0, appliedText.length, TitleLabelSpan::class.java)
            ?.firstOrNull()
            ?: return
        titleStates[title] = TitleState(
            originalDrawables = title.compoundDrawablesRelative.copyOf(),
            originalDrawablePadding = title.compoundDrawablePadding,
            drawable = null,
            originalText = originalText,
            inlineSpan = appliedSpan,
            video = video,
            label = label,
        )
    }

    private fun applyCompoundTitleLabel(
        title: TextView,
        video: SkipController.VideoKey,
        label: String,
        category: String,
    ) {
        val originalDrawables = title.compoundDrawablesRelative.copyOf()
        val drawable = TitleLabelDrawable(
            host = title,
            label = label,
            backgroundColor = categoryColor(category, preview = true),
            leadingDrawable = originalDrawables[0],
        )
        val originalPadding = title.compoundDrawablePadding
        title.setCompoundDrawablesRelativeWithIntrinsicBounds(
            drawable,
            originalDrawables[1],
            originalDrawables[2],
            originalDrawables[3],
        )
        title.compoundDrawablePadding = originalPadding +
            (TITLE_DRAWABLE_GAP_DP * title.resources.displayMetrics.density).toInt()
        titleStates[title] = TitleState(
            originalDrawables = originalDrawables,
            originalDrawablePadding = originalPadding,
            drawable = drawable,
            originalText = null,
            inlineSpan = null,
            video = video,
            label = label,
        )
    }

    private fun isVideoTitle(view: TextView): Boolean {
        if (!view.isShown || view.text.isBlank()) return false
        if (!isDetailTitleStructure(view)) return false
        return view.hasAncestorWithIdName(VIDEO_INTRO_CONTAINER_ID_NAME) ||
            view.hasAncestorWithIdName(VIDEO_RECYCLER_ID_NAME)
    }

    private fun isDetailTitleStructure(view: TextView): Boolean =
        !isKnownPlayerTitle(view) && view.hasSiblingWithIdName(TITLE_ARROW_ID_NAME)

    private fun decorateInlineText(
        title: TextView,
        text: CharSequence,
        label: String,
        category: String,
    ): CharSequence {
        val cleanText = stripInlineTitleLabels(text)
        // Activity videos reserve their leading badge inside the title with whitespace/a span.
        // Inserting at index 0 makes both replacement spans draw at the same x coordinate while
        // layout still reserves both widths, producing an overlap followed by a large empty gap.
        val insertionIndex = cleanText.indexOfFirst { !it.isWhitespace() }
            .takeIf { it >= 0 }
            ?: cleanText.length
        val decoratedText = SpannableStringBuilder(cleanText).apply {
            insert(insertionIndex, INLINE_LABEL_PLACEHOLDER)
        }
        decoratedText.setSpan(
            TitleLabelSpan(title, label, categoryColor(category, preview = true)),
            insertionIndex,
            insertionIndex + INLINE_LABEL_PLACEHOLDER.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        return decoratedText
    }

    private fun stripInlineTitleLabels(text: CharSequence): CharSequence {
        val spanned = text as? Spanned ?: return text
        val spans = spanned.getSpans(0, spanned.length, TitleLabelSpan::class.java)
        if (spans.isEmpty()) return text
        val result = SpannableStringBuilder(text)
        spans.sortedByDescending(spanned::getSpanStart).forEach { span ->
            val start = result.getSpanStart(span)
            val end = result.getSpanEnd(span)
            result.removeSpan(span)
            if (start >= 0 && end > start) result.delete(start, end)
        }
        return result
    }

    private fun isKnownPlayerTitle(view: TextView): Boolean = view.javaClass.name in PLAYER_TITLE_CLASSES

    private fun renderProgressMarkers(activity: Activity, snapshot: SkipController.UiSnapshot) {
        val decor = activity.window?.decorView ?: return
        val activeViews = findAllViews(decor)
            .filter(::isPlayerProgressView)
            .toMutableSet()
        PROGRESS_ID_NAMES.forEach { idName ->
            val id = activity.resources.getIdentifier(idName, "id", activity.packageName)
            if (id == 0) return@forEach
            findViewsById(decor, id).filterTo(activeViews) { it.isAttachedToWindow }
        }

        activeViews.forEach { view ->
            val state = markerStates[view] ?: attachMarkerDrawable(view)
            state.drawable.update(snapshot.segments, snapshot.durationMs)
            state.drawable.bounds = android.graphics.Rect(0, 0, view.width, view.height)
            val signature = "attached:${view.javaClass.name}:${view.resourceEntryName()}"
            if (loggedProgressClasses.add(signature)) {
                Log.d(
                    "progress marker attached: id=${view.resourceEntryName() ?: "none"}; " +
                        "view=${view.javaClass.name}; size=${view.width}x${view.height}",
                )
            }
        }

        if (activeViews.isEmpty()) logProgressCandidates(decor)
        removeProgressMarkersExcept(activeViews)
    }

    private fun isPlayerProgressView(view: View): Boolean {
        if (!view.isAttachedToWindow) return false
        val className = view.javaClass.name
        return className == PLAYER_SEEK_WIDGET_V3_CLASS ||
            className == STORY_SEEK_BAR_CLASS ||
            (view is SeekBar && className.startsWith(PLAYER_SEEK_PACKAGE_PREFIX))
    }

    private fun logProgressCandidates(decor: View) {
        findAllViews(decor).forEach { view ->
            val className = view.javaClass.name
            val idName = view.resourceEntryName().orEmpty()
            if (
                !className.startsWith("com.bilibili") ||
                !(className.contains("seek", ignoreCase = true) || idName.contains("seek", ignoreCase = true))
            ) return@forEach
            val signature = "candidate:$className:$idName"
            if (loggedProgressClasses.add(signature)) {
                Log.d(
                    "progress candidate: id=${idName.ifBlank { "none" }}; view=$className; " +
                        "size=${view.width}x${view.height}; shown=${view.isShown}; alpha=${view.alpha}",
                )
            }
        }
    }

    private fun attachMarkerDrawable(view: View): MarkerState {
        val drawable = SegmentMarkerDrawable(view)
        val listener = View.OnLayoutChangeListener { changed, _, _, _, _, _, _, _, _ ->
            drawable.bounds = android.graphics.Rect(0, 0, changed.width, changed.height)
            drawable.invalidateSelf()
        }
        view.addOnLayoutChangeListener(listener)
        view.overlay.add(drawable)
        return MarkerState(drawable, listener).also { markerStates[view] = it }
    }

    private fun restoreTitleLabelsExcept(keep: Set<TextView>) {
        titleStates.keys.toList().filter { it !in keep }.forEach(::restoreTitle)
    }

    private fun clearTitleLabels() {
        titleStates.keys.toList().forEach(::restoreTitle)
    }

    private fun restoreTitle(view: TextView) {
        val state = titleStates.remove(view) ?: return
        state.inlineSpan?.let {
            if (isTitleStateApplied(view, state)) {
                suppressExpandableTitleHook = true
                try {
                    view.text = state.originalText
                } finally {
                    suppressExpandableTitleHook = false
                }
            }
            return
        }
        if (view.compoundDrawablesRelative[0] === state.drawable) {
            view.setCompoundDrawablesRelativeWithIntrinsicBounds(
                state.originalDrawables[0],
                state.originalDrawables[1],
                state.originalDrawables[2],
                state.originalDrawables[3],
            )
        }
        view.compoundDrawablePadding = state.originalDrawablePadding
    }

    private fun removeProgressMarkersExcept(keep: Set<View>) {
        markerStates.keys.toList().filter { it !in keep }.forEach(::removeProgressMarker)
    }

    private fun clearProgressMarkers() {
        markerStates.keys.toList().forEach(::removeProgressMarker)
    }

    private fun removeProgressMarker(view: View) {
        val state = markerStates.remove(view) ?: return
        view.removeOnLayoutChangeListener(state.layoutListener)
        view.overlay.remove(state.drawable)
    }

    private fun findViewsById(root: View, id: Int): List<View> = buildList {
        fun visit(view: View) {
            if (view.id == id) add(view)
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) visit(view.getChildAt(index))
            }
        }
        visit(root)
    }

    private fun findAllViews(root: View): List<View> = buildList {
        fun visit(view: View) {
            add(view)
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) visit(view.getChildAt(index))
            }
        }
        visit(root)
    }

    private fun String.normalizedTitle(): String = replace(Regex("\\s+"), " ").trim()

    private fun View.resourceEntryName(): String? =
        if (id == View.NO_ID) null else runCatching { resources.getResourceEntryName(id) }.getOrNull()

    private fun View.hasAncestorWithIdName(expected: String): Boolean {
        var current = parent
        while (current is View) {
            val name = runCatching { current.resources.getResourceEntryName(current.id) }.getOrNull()
            if (name == expected) return true
            current = current.parent
        }
        return false
    }

    private fun View.hasSiblingWithIdName(expected: String): Boolean {
        val container = parent as? ViewGroup ?: return false
        for (index in 0 until container.childCount) {
            val sibling = container.getChildAt(index)
            if (sibling !== this && sibling.resourceEntryName() == expected) return true
        }
        return false
    }

    override fun onActivityResumed(activity: Activity) {
        resumedActivity = WeakReference(activity)
        observeLayout(activity)
        requestImmediateRender()
    }

    override fun onActivityPaused(activity: Activity) {
        if (resumedActivity?.get() === activity) {
            resumedActivity = null
            stopObservingLayout()
        }
    }

    override fun onActivityDestroyed(activity: Activity) {
        if (resumedActivity?.get() === activity) {
            resumedActivity = null
            stopObservingLayout()
        }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    private fun observeLayout(activity: Activity) {
        val decor = activity.window?.decorView ?: return
        if (observedDecor?.get() === decor) return
        stopObservingLayout()
        val listener = ViewTreeObserver.OnGlobalLayoutListener { requestImmediateRender() }
        decor.viewTreeObserver.addOnGlobalLayoutListener(listener)
        observedDecor = WeakReference(decor)
        globalLayoutListener = listener
    }

    private fun stopObservingLayout() {
        val decor = observedDecor?.get()
        val listener = globalLayoutListener
        if (decor != null && listener != null && decor.viewTreeObserver.isAlive) {
            decor.viewTreeObserver.removeOnGlobalLayoutListener(listener)
        }
        observedDecor = null
        globalLayoutListener = null
    }

    private class TitleLabelSpan(
        host: TextView,
        private val label: String,
        private val backgroundColor: Int,
    ) : ReplacementSpan() {
        private val density = host.resources.displayMetrics.density
        private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = host.textSize * LABEL_TEXT_SCALE
            typeface = Typeface.create(host.typeface, Typeface.BOLD)
        }
        private val pillWidth = ceil(labelPaint.measureText(label) + HORIZONTAL_PADDING_DP * density * 2).toInt()
        private val pillHeight = ceil(labelPaint.textSize * 1.55f).toInt()
        private val totalWidth = pillWidth + (TITLE_DRAWABLE_GAP_DP * density).toInt()

        override fun getSize(
            paint: Paint,
            text: CharSequence?,
            start: Int,
            end: Int,
            fm: Paint.FontMetricsInt?,
        ): Int = totalWidth

        override fun draw(
            canvas: Canvas,
            text: CharSequence?,
            start: Int,
            end: Int,
            x: Float,
            top: Int,
            y: Int,
            bottom: Int,
            paint: Paint,
        ) {
            val centerY = (top + bottom) / 2f
            labelPaint.style = Paint.Style.FILL
            labelPaint.color = backgroundColor
            canvas.drawRoundRect(
                x,
                centerY - pillHeight / 2f,
                x + pillWidth,
                centerY + pillHeight / 2f,
                CORNER_RADIUS_DP * density,
                CORNER_RADIUS_DP * density,
                labelPaint,
            )
            labelPaint.color = contrastingTextColor(backgroundColor)
            val metrics = labelPaint.fontMetrics
            val labelBaseline = centerY - (metrics.ascent + metrics.descent) / 2f
            canvas.drawText(label, x + HORIZONTAL_PADDING_DP * density, labelBaseline, labelPaint)
        }
    }

    private class TitleLabelDrawable(
        host: TextView,
        private val label: String,
        private val backgroundColor: Int,
        private val leadingDrawable: Drawable?,
    ) : Drawable() {
        private val density = host.resources.displayMetrics.density
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = host.textSize * LABEL_TEXT_SCALE
            typeface = Typeface.create(host.typeface, Typeface.BOLD)
        }
        private val pillWidth = ceil(paint.measureText(label) + HORIZONTAL_PADDING_DP * density * 2).toInt()
        private val pillHeight = ceil(paint.textSize * 1.55f).toInt()
        private val leadingWidth = leadingDrawable?.intrinsicWidth?.coerceAtLeast(0) ?: 0
        private val leadingHeight = leadingDrawable?.intrinsicHeight?.coerceAtLeast(0) ?: 0
        private val leadingGap = if (leadingDrawable == null) 0 else (TITLE_DRAWABLE_GAP_DP * density).toInt()

        override fun getIntrinsicWidth(): Int = leadingWidth + leadingGap + pillWidth
        override fun getIntrinsicHeight(): Int = max(leadingHeight, pillHeight)

        override fun draw(canvas: Canvas) {
            val pillLeft = bounds.left.toFloat()
            val centerY = bounds.exactCenterY()
            val rect = RectF(
                pillLeft,
                centerY - pillHeight / 2f,
                pillLeft + pillWidth,
                centerY + pillHeight / 2f,
            )
            paint.style = Paint.Style.FILL
            paint.color = backgroundColor
            canvas.drawRoundRect(rect, CORNER_RADIUS_DP * density, CORNER_RADIUS_DP * density, paint)

            paint.color = contrastingTextColor(backgroundColor)
            val metrics = paint.fontMetrics
            val baseline = centerY - (metrics.ascent + metrics.descent) / 2
            canvas.drawText(label, pillLeft + HORIZONTAL_PADDING_DP * density, baseline, paint)

            leadingDrawable?.let { drawable ->
                val oldBounds = Rect(drawable.bounds)
                val top = bounds.centerY() - leadingHeight / 2
                val left = bounds.left + pillWidth + leadingGap
                drawable.setBounds(left, top, left + leadingWidth, top + leadingHeight)
                drawable.draw(canvas)
                drawable.bounds = oldBounds
            }
        }

        override fun setAlpha(alpha: Int) {
            paint.alpha = alpha
            leadingDrawable?.alpha = alpha
            invalidateSelf()
        }

        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {
            paint.colorFilter = colorFilter
            leadingDrawable?.colorFilter = colorFilter
            invalidateSelf()
        }

        @Suppress("OVERRIDE_DEPRECATION")
        override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
    }

    private class SegmentMarkerDrawable(private val host: View) : Drawable() {
        private data class Marker(val start: Float, val end: Float, val color: Int)

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val density = host.resources.displayMetrics.density
        private var markers = emptyList<Marker>()

        fun update(segments: List<SponsorBlockClient.Segment>, durationMs: Int) {
            markers = segments
                .sortedByDescending { it.endMs - it.startMs }
                .map { segment ->
                    Marker(
                        start = (segment.startMs.toFloat() / durationMs).coerceIn(0f, 1f),
                        end = (segment.endMs.toFloat() / durationMs).coerceIn(0f, 1f),
                        color = categoryColor(segment.category, preview = false),
                    )
                }
            invalidateSelf()
        }

        override fun draw(canvas: Canvas) {
            if (bounds.width() <= 0 || bounds.height() <= 0) return
            val left = host.paddingLeft.toFloat()
            val right = (bounds.width() - host.paddingRight).toFloat()
            val trackWidth = right - left
            if (trackWidth <= 0f) return
            val markerHeight = max(MARKER_HEIGHT_DP * density, bounds.height() * 0.08f)
                .coerceAtMost(MAX_MARKER_HEIGHT_DP * density)
            val centerY = bounds.exactCenterY()
            paint.alpha = MARKER_ALPHA

            markers.forEach { marker ->
                paint.color = marker.color
                paint.alpha = MARKER_ALPHA
                val markerLeft = left + trackWidth * marker.start
                val markerRight = max(markerLeft + MIN_MARKER_WIDTH_DP * density, left + trackWidth * marker.end)
                    .coerceAtMost(right)
                canvas.drawRoundRect(
                    markerLeft,
                    centerY - markerHeight / 2,
                    markerRight,
                    centerY + markerHeight / 2,
                    markerHeight / 2,
                    markerHeight / 2,
                    paint,
                )
            }
        }

        override fun setAlpha(alpha: Int) {
            paint.alpha = alpha
            invalidateSelf()
        }

        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {
            paint.colorFilter = colorFilter
            invalidateSelf()
        }

        @Suppress("OVERRIDE_DEPRECATION")
        override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
    }

    private companion object {
        const val RENDER_INTERVAL_MS = 750L
        const val INLINE_LABEL_PLACEHOLDER = "\uFFFC"
        const val EXPANDABLE_TITLE_CLASS =
            "tv.danmaku.bili.videopage.common.widget.view.ExpandableTextView"
        const val TITLE_ID_NAME = "title"
        const val VIDEO_INTRO_CONTAINER_ID_NAME = "fl_intro_container"
        const val VIDEO_RECYCLER_ID_NAME = "recycler"
        const val TITLE_ARROW_ID_NAME = "arrow"
        val PROGRESS_ID_NAMES = listOf("bbplayer_halfscreen_seekbar", "bbplayer_fullscreen_seekbar")
        const val PLAYER_SEEK_WIDGET_V3_CLASS =
            "com.bilibili.playerbizcommonv2.widget.seek.v3.PlayerSeekWidget3"
        const val PLAYER_SEEK_PACKAGE_PREFIX = "com.bilibili.playerbizcommonv2.widget.seek."
        const val STORY_SEEK_BAR_CLASS = "com.bilibili.video.story.view.StorySeekBar"
        val PLAYER_TITLE_CLASSES = setOf(
            "com.bilibili.app.gemini.player.widget.base.GeminiPlayerTitleWidget",
            "com.bilibili.video.story.action.widget.StoryTitleWidget",
            "com.bilibili.video.story.action.widget.StoryLandscapeTitleWidget",
        )

        const val LABEL_TEXT_SCALE = 0.72f
        const val HORIZONTAL_PADDING_DP = 5f
        const val CORNER_RADIUS_DP = 4f
        const val TITLE_DRAWABLE_GAP_DP = 4f
        const val MARKER_HEIGHT_DP = 3f
        const val MAX_MARKER_HEIGHT_DP = 5f
        const val MIN_MARKER_WIDTH_DP = 2f
        const val MARKER_ALPHA = 235

        fun categoryColor(category: String, preview: Boolean): Int = Color.parseColor(
            when (category) {
                "sponsor" -> if (preview) "#007800" else "#00d400"
                "selfpromo" -> if (preview) "#bfbf35" else "#ffff00"
                "interaction" -> if (preview) "#6c0087" else "#cc00ff"
                "intro" -> if (preview) "#008080" else "#00ffff"
                "outro" -> if (preview) "#000070" else "#0202ed"
                "preview" -> if (preview) "#005799" else "#008fd6"
                "music_offtopic" -> if (preview) "#a6634a" else "#ff9900"
                "filler" -> if (preview) "#2e0066" else "#7300ff"
                "padding" -> if (preview) "#111111" else "#222222"
                else -> if (preview) "#555555" else "#999999"
            },
        )

        fun contrastingTextColor(background: Int): Int {
            val luminance = 0.2126 * Color.red(background) +
                0.7152 * Color.green(background) +
                0.0722 * Color.blue(background)
            return if (luminance > 150) Color.BLACK else Color.WHITE
        }
    }
}
