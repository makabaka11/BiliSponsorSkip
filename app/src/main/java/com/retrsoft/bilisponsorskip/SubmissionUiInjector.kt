@file:Suppress("DEPRECATION", "SetTextI18n")

package com.retrsoft.bilisponsorskip

import android.app.Activity
import android.app.Application
import android.app.Dialog
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.Window
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.lang.ref.WeakReference
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

internal class SubmissionUiInjector(
    private val application: Application,
    private val controller: SkipController,
) {
    private data class Draft(
        var video: SkipController.VideoKey? = null,
        var startMs: Int? = null,
        var endMs: Int? = null,
        var category: String = SettingsContract.CATEGORIES.first(),
    )

    private data class ButtonPlacement(
        val parent: ViewGroup,
        val index: Int,
        val regularPlayer: Boolean,
        val anchor: View? = null,
        val positionBeforeAnchor: Boolean = false,
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private val draft = Draft()
    private val votedUuids = mutableSetOf<String>()

    private var resumedActivity: WeakReference<Activity>? = null
    private var button: ImageView? = null
    private var buttonParent: ViewGroup? = null
    private var expandedRegularGroup: ViewGroup? = null
    private var originalRegularGroupWidth: Int? = null
    private var menu: Dialog? = null
    private var lastVisibilitySignature: String? = null
    private var observedDecor: WeakReference<View>? = null
    private var globalLayoutListener: ViewTreeObserver.OnGlobalLayoutListener? = null
    private var immediateRenderScheduled = false

    fun start() {
        mainHandler.post(renderRunnable)
        Log.d("submission UI injector started")
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
        runCatching { render() }.onFailure { Log.e("failed to render submission button", it) }
    }

    private fun requestImmediateRender() {
        if (immediateRenderScheduled) return
        immediateRenderScheduled = true
        mainHandler.post(immediateRenderRunnable)
    }

    private fun render() {
        val activity = resumedActivity?.get()
        val snapshot = controller.uiSnapshot()
        val playerPage = activity?.let(::isVideoPlayerPage) ?: false
        val placement = activity?.let(::findButtonPlacement)
        if (draft.video != snapshot.video) {
            draft.video = snapshot.video
            draft.startMs = null
            draft.endMs = null
            votedUuids.clear()
            menu?.dismiss()
        }
        val shouldShow = activity != null &&
            !activity.isFinishing &&
            !activity.isDestroyed &&
            snapshot.video != null &&
            snapshot.showSubmissionButton &&
            playerPage &&
            placement != null

        if (snapshot.video != null && snapshot.showSubmissionButton) {
            val target = when {
                placement?.regularPlayer == true -> "regular"
                placement?.parent?.let(::resourceName) == PORTRAIT_ACTIONS_RIGHT_ID -> "portrait-detail"
                placement != null -> "story"
                else -> "none"
            }
            val signature = "activity=${activity?.javaClass?.name}; playerPage=$playerPage; target=$target; show=$shouldShow"
            if (lastVisibilitySignature != signature) {
                lastVisibilitySignature = signature
                Log.d("submission UI visibility: $signature")
            }
        }

        if (!shouldShow) {
            val keepOpenMenu = activity != null &&
                !activity.isFinishing &&
                !activity.isDestroyed &&
                snapshot.video != null &&
                snapshot.showSubmissionButton &&
                playerPage
            removeButton(dismissMenu = !keepOpenMenu)
            return
        }
        val target = requireNotNull(placement)
        if (buttonParent !== target.parent || button?.parent !== target.parent) {
            removeButton(dismissMenu = false)
            attachButton(activity, target)
        }
        if (target.positionBeforeAnchor) positionBeforeAnchor(requireNotNull(button), requireNotNull(target.anchor))
    }

    private fun attachButton(activity: Activity, placement: ButtonPlacement) {
        val parent = placement.parent
        val size = placement.anchor?.let { min(it.width, it.height) }?.takeIf { it > 0 }
            ?: parent.height.takeIf { it in activity.dp(32)..activity.dp(64) }
            ?: activity.dp(44)
        if (placement.regularPlayer && activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            originalRegularGroupWidth = parent.layoutParams.width
            expandedRegularGroup = parent
            parent.layoutParams = parent.layoutParams.apply { width = parent.width.coerceAtLeast(size) + size }
        }
        val iconPadding = ((size - activity.dp(SUBMISSION_ICON_SIZE_DP)) / 2)
            .coerceAtLeast(activity.dp(MIN_SUBMISSION_ICON_PADDING_DP))
        val injectedButton = ImageView(activity).apply {
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            contentDescription = "片段提交与投票"
            isClickable = true
            isFocusable = true
            setPadding(iconPadding, iconPadding, iconPadding, iconPadding)
            setImageDrawable(loadUploadIcon())
            setOnClickListener { openMenu(activity) }
        }
        val params = when {
            placement.positionBeforeAnchor -> ViewGroup.LayoutParams(size, size)
            parent is LinearLayout && placement.anchor != null ->
                LinearLayout.LayoutParams(placement.anchor.layoutParams).apply {
                    width = size
                    height = size
                }
            placement.anchor != null -> anchoredBeforeLayoutParams(placement.anchor, size)
            parent is LinearLayout -> LinearLayout.LayoutParams(size, size)
            else -> ViewGroup.LayoutParams(size, size)
        }
        parent.addView(injectedButton, placement.index.coerceIn(0, parent.childCount), params)
        if (placement.positionBeforeAnchor) {
            parent.post { positionBeforeAnchor(injectedButton, requireNotNull(placement.anchor)) }
        }
        button = injectedButton
        buttonParent = parent
        Log.d("submission button injected into ${resourceName(parent)} at ${placement.index}")
    }

    private fun positionBeforeAnchor(view: View, anchor: View) {
        view.x = anchor.x - view.layoutParams.width
        view.y = anchor.y + (anchor.height - view.layoutParams.height) / 2f
    }

    private fun loadUploadIcon(): Drawable = UploadIconDrawable()

    private fun anchoredBeforeLayoutParams(anchor: View, size: Int): ViewGroup.LayoutParams {
        val source = anchor.layoutParams
        val copy = runCatching {
            source.javaClass.getConstructor(ViewGroup.LayoutParams::class.java)
                .newInstance(source) as ViewGroup.LayoutParams
        }.getOrElse { ViewGroup.MarginLayoutParams(source) }
        copy.width = size
        copy.height = size
        val horizontalConstraints = listOf(
            "leftToLeft", "leftToRight", "rightToLeft", "rightToRight",
            "startToStart", "startToEnd", "endToEnd", "circleConstraint",
        )
        horizontalConstraints.forEach { name -> setIntField(copy, name, View.NO_ID) }
        setIntField(copy, "endToStart", anchor.id)
        if (copy is ViewGroup.MarginLayoutParams) copy.marginEnd = 0
        return copy
    }

    private fun setIntField(target: Any, name: String, value: Int) {
        runCatching { target.javaClass.getField(name).setInt(target, value) }
            .onFailure { Log.e("failed to set layout constraint $name", it) }
    }

    private fun findButtonPlacement(activity: Activity): ButtonPlacement? {
        val decor = activity.window?.decorView ?: return null
        val views = findAllViews(decor)
        val storyMode = views.any { resourceName(it) in STORY_SEEKBAR_IDS && it.isShown }
        if (storyMode) findStoryPlacement(activity, views)?.let { return it }
        val regularGroup = views.firstOrNull {
            it is ViewGroup && resourceName(it) == REGULAR_TOP_GROUP_ID && it.isShown
        } as? ViewGroup
        // The white/Play build keeps the portrait toolbar attached behind the fullscreen
        // player. Prefer the actual fullscreen controls in landscape, otherwise the button
        // is injected into that still-"shown" portrait toolbar and never appears on screen.
        if (regularGroup != null &&
            activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        ) {
            return ButtonPlacement(regularGroup, 0, true)
        }
        findPortraitDetailPlacement(views)?.let { return it }
        if (regularGroup != null) return ButtonPlacement(regularGroup, 0, true)
        return findStoryPlacement(activity, views)
    }

    private fun findPortraitDetailPlacement(views: List<View>): ButtonPlacement? {
        val actions = views.firstOrNull {
            it is LinearLayout && resourceName(it) == PORTRAIT_ACTIONS_RIGHT_ID && it.isShown
        } as? LinearLayout ?: return null
        val listenIcon = views.firstOrNull {
            it.isShown && it.contentDescription?.toString() == PORTRAIT_LISTEN_DESCRIPTION &&
                isDescendant(it, actions)
        } ?: return ButtonPlacement(actions, actions.childCount, false)
        val listenContainer = directChildUnder(listenIcon, actions)
            ?: return ButtonPlacement(actions, actions.childCount, false)
        return ButtonPlacement(actions, actions.indexOfChild(listenContainer), false, listenContainer)
    }

    private fun findStoryPlacement(activity: Activity, views: List<View>): ButtonPlacement? {
        val whiteTop = views.firstOrNull {
            it is ViewGroup && resourceName(it) == STORY_WHITE_TOP_ID && it.isShown
        } as? ViewGroup
        val whiteMore = whiteTop?.let { group ->
            (0 until group.childCount).map(group::getChildAt).firstOrNull {
                resourceName(it) == STORY_MORE_ID
            }
        }
        if (whiteTop != null && whiteMore != null) {
            return ButtonPlacement(
                whiteTop,
                whiteTop.indexOfChild(whiteMore),
                regularPlayer = false,
                anchor = whiteMore,
                positionBeforeAnchor = true,
            )
        }
        val exactGroup = views.firstOrNull {
            it is ViewGroup && resourceName(it) == STORY_TOP_GROUP_ID && it.isShown
        } as? ViewGroup
        val exactSearch = exactGroup?.let { group ->
            (0 until group.childCount).map(group::getChildAt).firstOrNull { resourceName(it) == STORY_SEARCH_ID }
        }
        if (exactGroup != null && exactSearch != null) {
            return ButtonPlacement(exactGroup, exactGroup.indexOfChild(exactSearch), false, exactSearch)
        }
        val viewer = views.filterIsInstance<TextView>().firstOrNull {
            it.isShown && STORY_VIEWERS_REGEX.matches(it.text?.toString()?.trim().orEmpty())
        } ?: return null
        val search = views.firstOrNull {
            it.isShown && (resourceName(it).contains("search", true) ||
                it.contentDescription?.toString()?.contains("搜索") == true)
        } ?: return null
        var candidate = search.parent as? ViewGroup
        while (candidate != null) {
            if (isDescendant(viewer, candidate) && candidate.height in 1..activity.dp(96)) {
                val searchBranch = directChildUnder(search, candidate) ?: return null
                return ButtonPlacement(candidate, candidate.indexOfChild(searchBranch), false, searchBranch)
            }
            candidate = candidate.parent as? ViewGroup
        }
        return null
    }

    private fun isDescendant(view: View, ancestor: ViewGroup): Boolean {
        var parent = view.parent
        while (parent is View) {
            if (parent === ancestor) return true
            parent = parent.parent
        }
        return false
    }

    private fun directChildUnder(view: View, ancestor: ViewGroup): View? {
        var child = view
        var parent = child.parent
        while (parent is View && parent !== ancestor) {
            child = parent
            parent = child.parent
        }
        return child.takeIf { parent === ancestor }
    }

    private fun removeButton(dismissMenu: Boolean = false) {
        val current = button
        val parent = buttonParent
        if (current != null && parent != null) parent.removeView(current)
        expandedRegularGroup?.let { group ->
            originalRegularGroupWidth?.let { original ->
                group.layoutParams = group.layoutParams.apply { width = original }
            }
        }
        expandedRegularGroup = null
        originalRegularGroupWidth = null
        button = null
        buttonParent = null
        if (dismissMenu) menu?.dismiss()
    }

    private fun openMenu(activity: Activity) {
        menu?.dismiss()
        val dark = isNight(activity)
        val dialog = Dialog(activity).apply { requestWindowFeature(Window.FEATURE_NO_TITLE) }
        val outer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(activity.dp(18), activity.dp(16), activity.dp(18), activity.dp(14))
            background = roundedBackground(if (dark) DARK_SURFACE else LIGHT_SURFACE, activity.dp(18).toFloat())
        }
        val titleRow = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
        titleRow.addView(TextView(activity).apply {
            text = "片段提交与投票"
            textSize = 20f
            setTextColor(if (dark) Color.WHITE else Color.rgb(28, 28, 30))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        titleRow.addView(actionButton(activity, "关闭", dark).apply { setOnClickListener { dialog.dismiss() } })
        outer.addView(titleRow)

        if (activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            val split = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, activity.dp(10), 0, 0)
            }
            split.addView(
                menuSection(activity, "提交片段", dark, createSubmitPane(activity, dark, dialog)),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                    marginEnd = activity.dp(14)
                },
            )
            split.addView(View(activity).apply {
                setBackgroundColor(if (dark) Color.rgb(84, 84, 90) else Color.rgb(218, 219, 225))
            }, LinearLayout.LayoutParams(activity.dp(1), ViewGroup.LayoutParams.MATCH_PARENT))
            split.addView(
                menuSection(activity, "片段投票", dark, createVotePane(activity, dark)),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                    marginStart = activity.dp(14)
                },
            )
            outer.addView(split, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        } else {
            val tabs = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, activity.dp(10), 0, activity.dp(8))
            }
            val submitTab = actionButton(activity, "提交", dark)
            val voteTab = actionButton(activity, "投票", dark)
            tabs.addView(submitTab, LinearLayout.LayoutParams(0, activity.dp(44), 1f).apply { marginEnd = activity.dp(5) })
            tabs.addView(voteTab, LinearLayout.LayoutParams(0, activity.dp(44), 1f).apply { marginStart = activity.dp(5) })
            outer.addView(tabs)

            val content = FrameLayout(activity)
            outer.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            fun showVote() {
                styleSelectedTab(voteTab, true, dark)
                styleSelectedTab(submitTab, false, dark)
                content.removeAllViews()
                content.addView(createVotePane(activity, dark), FrameLayout.LayoutParams(-1, -1))
            }
            fun showSubmit() {
                styleSelectedTab(voteTab, false, dark)
                styleSelectedTab(submitTab, true, dark)
                content.removeAllViews()
                content.addView(createSubmitPane(activity, dark, dialog), FrameLayout.LayoutParams(-1, -1))
            }
            voteTab.setOnClickListener { showVote() }
            submitTab.setOnClickListener { showSubmit() }
            showSubmit()
        }

        dialog.setContentView(outer)
        dialog.setOnDismissListener { if (menu === dialog) menu = null }
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            attributes = attributes.apply { dimAmount = 0.42f }
            setLayout((activity.resources.displayMetrics.widthPixels * 0.92f).toInt(),
                (activity.resources.displayMetrics.heightPixels * 0.78f).toInt())
            setGravity(Gravity.CENTER)
        }
        menu = dialog
    }

    private fun menuSection(activity: Activity, title: String, dark: Boolean, content: View) =
        LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(activity).apply {
                text = title
                textSize = 16f
                setTextColor(if (dark) Color.rgb(245, 245, 247) else Color.rgb(32, 32, 35))
                setPadding(activity.dp(2), 0, 0, activity.dp(6))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }

    private fun createVotePane(activity: Activity, dark: Boolean): View {
        val root = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val toolbar = LinearLayout(activity).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, activity.dp(8))
        }
        val status = bodyText(activity, "片段列表", dark).apply { textSize = 13f }
        val refresh = actionButton(activity, "刷新", dark)
        toolbar.addView(status, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        toolbar.addView(refresh, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, activity.dp(40)))
        root.addView(toolbar)

        val list = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, activity.dp(4), 0, activity.dp(10))
        }
        val scroll = ScrollView(activity).apply { addView(list) }
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        fun renderSegments(segments: List<SponsorBlockClient.Segment>) {
            list.removeAllViews()
            status.text = "共 ${segments.size} 个片段"
            if (segments.isEmpty()) {
                list.addView(bodyText(activity, "当前分 P 暂无可投票片段", dark).apply {
                    gravity = Gravity.CENTER
                    setPadding(0, activity.dp(40), 0, activity.dp(40))
                })
                return
            }
            segments.forEachIndexed { index, segment ->
                val row = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(activity.dp(12), activity.dp(11), activity.dp(10), activity.dp(10))
                    background = roundedBackground(
                        if (dark) Color.rgb(48, 48, 51) else Color.rgb(245, 245, 247),
                        activity.dp(10).toFloat(),
                    )
                }
                val summary = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                summary.addView(View(activity).apply {
                    background = roundedBackground(segmentCategoryColor(segment.category), activity.dp(20).toFloat())
                }, LinearLayout.LayoutParams(activity.dp(10), activity.dp(10)).apply {
                    marginEnd = activity.dp(8)
                })
                summary.addView(bodyText(activity, segment.category.categoryLabel(), dark).apply {
                    textSize = 15f
                    setPadding(0, 0, 0, 0)
                })
                row.addView(summary)
                row.addView(bodyText(
                    activity,
                    "${formatPreciseTime(segment.startMs)} 到 ${formatPreciseTime(segment.endMs)}",
                    dark,
                ).apply {
                    textSize = 14f
                    setPadding(0, activity.dp(5), 0, activity.dp(5))
                })
                val actions = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL or Gravity.END }
                val votes = bodyText(activity, "票数 ${segment.votes}", dark)
                val like = voteIconButton(activity, up = true, dark = dark)
                val dislike = voteIconButton(activity, up = false, dark = dark)
                actions.addView(votes, LinearLayout.LayoutParams(0, -2, 1f))
                actions.addView(like, LinearLayout.LayoutParams(activity.dp(44), activity.dp(40)).apply {
                    marginEnd = activity.dp(6)
                })
                actions.addView(dislike, LinearLayout.LayoutParams(activity.dp(44), activity.dp(40)))
                row.addView(actions)
                val alreadyVoted = segment.uuid in votedUuids || segment.uuid.isBlank()
                like.isEnabled = !alreadyVoted
                dislike.isEnabled = !alreadyVoted
                like.alpha = if (alreadyVoted) 0.4f else 1f
                dislike.alpha = if (alreadyVoted) 0.4f else 1f
                fun vote(type: Int) {
                    val activeButton = if (type == 1) like else dislike
                    val activeUp = type == 1
                    like.isEnabled = false
                    dislike.isEnabled = false
                    like.alpha = 0.4f
                    dislike.alpha = 0.4f
                    activeButton.alpha = 1f
                    setIconLoading(activeButton, true, activeUp, dark)
                    controller.vote(segment, type) { result ->
                        setIconLoading(activeButton, false, activeUp, dark)
                        if (result.successful) {
                            votedUuids += segment.uuid
                            votes.text = "票数 ${segment.votes + if (type == 1) 1 else -1}"
                            toast(activity, "投票成功")
                        } else {
                            like.isEnabled = true
                            dislike.isEnabled = true
                            like.alpha = 1f
                            dislike.alpha = 1f
                            toast(activity, mutationError("投票失败", result))
                        }
                    }
                }
                like.setOnClickListener { vote(1) }
                dislike.setOnClickListener { vote(0) }
                list.addView(row, LinearLayout.LayoutParams(-1, -2).apply {
                    if (index > 0) topMargin = activity.dp(8)
                })
            }
        }

        refresh.setOnClickListener {
            refresh.isEnabled = false
            refresh.text = "刷新中…"
            setButtonLoading(refresh, true, dark)
            controller.refreshSegments { result ->
                setButtonLoading(refresh, false, dark)
                refresh.isEnabled = true
                refresh.text = "刷新"
                when (result) {
                    is SponsorBlockClient.Result.Success -> {
                        renderSegments(result.segments)
                        toast(activity, "片段列表已刷新")
                    }
                    is SponsorBlockClient.Result.Failure ->
                        toast(activity, "刷新失败：${result.message}", Toast.LENGTH_LONG)
                }
            }
        }
        renderSegments(controller.uiSnapshot().allSegments)
        return root
    }

    private fun createSubmitPane(activity: Activity, dark: Boolean, dialog: Dialog): View {
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(activity.dp(2), activity.dp(6), activity.dp(2), 0)
        }
        val startState = bodyText(activity, draft.startMs?.let { "开始：${formatTime(it)}" } ?: "开始：未设置", dark)
        val endState = bodyText(activity, draft.endMs?.let { "结束：${formatTime(it)}" } ?: "结束：未设置", dark)
        val startButton = accentButton(activity, "片段现在开始", dark)
        val endButton = accentButton(activity, "片段现在结束", dark).apply {
            visibility = if (draft.startMs == null) View.GONE else View.VISIBLE
        }
        endState.visibility = if (draft.startMs == null) View.GONE else View.VISIBLE
        root.addView(startButton, LinearLayout.LayoutParams(-1, activity.dp(48)))
        root.addView(startState)

        val labels = SettingsContract.CATEGORIES.map(String::categoryLabel)
        val categorySpinner = Spinner(activity).apply {
            adapter = themedSpinnerAdapter(activity, labels, dark)
            background = borderedBackground(
                if (dark) Color.rgb(45, 45, 48) else Color.WHITE,
                if (dark) Color.rgb(84, 84, 90) else Color.rgb(215, 216, 222),
                activity.dp(10).toFloat(),
                activity.dp(1),
            )
            setPopupBackgroundDrawable(roundedBackground(
                if (dark) Color.rgb(45, 45, 48) else Color.WHITE,
                activity.dp(10).toFloat(),
            ))
            setSelection(SettingsContract.CATEGORIES.indexOf(draft.category).coerceAtLeast(0))
        }
        root.addView(bodyText(activity, "片段类别", dark).apply { setPadding(0, activity.dp(12), 0, 0) })
        root.addView(categorySpinner, LinearLayout.LayoutParams(-1, activity.dp(48)))
        root.addView(endButton, LinearLayout.LayoutParams(-1, activity.dp(48)).apply { topMargin = activity.dp(8) })
        root.addView(endState)

        val submit = primaryButton(activity, "提交")
        val cancel = actionButton(activity, "取消本次提交", dark)
        val actions = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(cancel, LinearLayout.LayoutParams(0, activity.dp(48), 1f).apply { marginEnd = activity.dp(5) })
        actions.addView(submit, LinearLayout.LayoutParams(0, activity.dp(48), 1f).apply { marginStart = activity.dp(5) })
        root.addView(actions, LinearLayout.LayoutParams(-1, -2).apply { topMargin = activity.dp(16) })

        fun updateSubmitState() {
            draft.category = SettingsContract.CATEGORIES[categorySpinner.selectedItemPosition]
            submit.isEnabled = draft.startMs != null && draft.endMs != null && draft.category.isNotBlank()
            submit.alpha = if (submit.isEnabled) 1f else 0.45f
        }
        setHapticClickListener(startButton) {
            draft.startMs = controller.uiSnapshot().currentPositionMs
            draft.endMs = null
            startState.text = "开始：${formatTime(requireNotNull(draft.startMs))}"
            endState.text = "结束：未设置"
            endButton.visibility = View.VISIBLE
            endState.visibility = View.VISIBLE
            updateSubmitState()
        }
        setHapticClickListener(endButton) {
            if (draft.startMs == null) {
                toast(activity, "请先点击“片段现在开始”")
            } else {
                val now = controller.uiSnapshot().currentPositionMs
                val first = requireNotNull(draft.startMs)
                draft.startMs = min(first, now)
                draft.endMs = max(first, now)
                startState.text = "开始：${formatTime(requireNotNull(draft.startMs))}"
                endState.text = "结束：${formatTime(requireNotNull(draft.endMs))}"
                updateSubmitState()
            }
        }
        setHapticClickListener(cancel) {
            draft.startMs = null
            draft.endMs = null
            dialog.dismiss()
            toast(activity, "已取消本次提交")
        }
        setHapticClickListener(submit) submitClick@{
            updateSubmitState()
            val start = draft.startMs ?: return@submitClick
            val end = draft.endMs ?: return@submitClick
            if (end <= start) {
                toast(activity, "片段开始和结束时间不能相同")
                return@submitClick
            }
            submit.isEnabled = false
            submit.text = "提交中…"
            setButtonLoading(submit, true, dark, onPrimary = true)
            controller.submitSegment(draft.category, start, end) { result ->
                setButtonLoading(submit, false, dark, onPrimary = true)
                if (result.successful) {
                    draft.startMs = null
                    draft.endMs = null
                    dialog.dismiss()
                    toast(activity, "片段提交成功")
                } else {
                    submit.text = "提交"
                    updateSubmitState()
                    toast(activity, mutationError("提交失败", result), Toast.LENGTH_LONG)
                }
            }
        }
        categorySpinner.setOnItemSelectedListener(SimpleItemSelectedListener { updateSubmitState() })
        updateSubmitState()
        return ScrollView(activity).apply { addView(root) }
    }

    private fun isVideoPlayerPage(activity: Activity): Boolean {
        val decor = activity.window?.decorView ?: return false
        return findAllViews(decor).any { view ->
            val idName = if (view.id == View.NO_ID) "" else
                runCatching { view.resources.getResourceEntryName(view.id) }.getOrDefault("")
            val className = view.javaClass.name
            view.isAttachedToWindow && (
                idName in PLAYER_PAGE_SEEKBAR_IDS ||
                    ((className == PLAYER_SEEK_WIDGET_V3_CLASS || className == STORY_SEEK_BAR_CLASS) &&
                        view.width > 0 && view.height > 0)
                )
        }
    }

    private fun findAllViews(root: View): List<View> = buildList {
        fun visit(view: View) {
            add(view)
            if (view is ViewGroup) repeat(view.childCount) { visit(view.getChildAt(it)) }
        }
        visit(root)
    }

    private fun resourceName(view: View): String = if (view.id == View.NO_ID) "" else
        runCatching { view.resources.getResourceEntryName(view.id) }.getOrDefault("")

    private fun actionButton(activity: Activity, label: String, dark: Boolean) = Button(activity).apply {
        text = label
        isAllCaps = false
        textSize = 14f
        setTextColor(if (dark) Color.rgb(242, 242, 247) else Color.rgb(36, 36, 38))
        background = borderedBackground(
            if (dark) Color.rgb(58, 58, 62) else Color.rgb(242, 242, 246),
            if (dark) Color.rgb(88, 88, 94) else Color.rgb(218, 219, 225),
            activity.dp(10).toFloat(),
            activity.dp(1),
        )
        setPadding(activity.dp(12), 0, activity.dp(12), 0)
    }

    private fun accentButton(activity: Activity, label: String, dark: Boolean) = actionButton(activity, label, dark).apply {
        setTextColor(if (dark) Color.rgb(255, 160, 192) else Color.rgb(194, 55, 105))
        background = borderedBackground(
            if (dark) Color.rgb(68, 43, 53) else Color.rgb(255, 241, 246),
            Color.rgb(251, 114, 153),
            activity.dp(10).toFloat(),
            activity.dp(1),
        )
    }

    private fun primaryButton(activity: Activity, label: String) = Button(activity).apply {
        text = label
        isAllCaps = false
        textSize = 14f
        setTextColor(Color.WHITE)
        background = roundedBackground(Color.rgb(251, 114, 153), activity.dp(10).toFloat())
        setPadding(activity.dp(12), 0, activity.dp(12), 0)
    }

    private fun voteIconButton(activity: Activity, up: Boolean, dark: Boolean) = ImageView(activity).apply {
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        contentDescription = if (up) "赞同" else "反对"
        isClickable = true
        isFocusable = true
        setPadding(activity.dp(10), activity.dp(8), activity.dp(10), activity.dp(8))
        setImageDrawable(VoteIconDrawable(up, if (up) Color.rgb(52, 199, 89) else Color.rgb(255, 69, 58)))
        background = borderedBackground(
            if (dark) Color.rgb(58, 58, 62) else Color.WHITE,
            if (dark) Color.rgb(88, 88, 94) else Color.rgb(218, 219, 225),
            activity.dp(9).toFloat(),
            activity.dp(1),
        )
    }

    private fun setHapticClickListener(view: View, action: () -> Unit) {
        view.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            action()
        }
    }

    private fun setButtonLoading(button: Button, loading: Boolean, dark: Boolean, onPrimary: Boolean = false) {
        button.compoundDrawablesRelative.firstOrNull { it is LoadingDrawable }
            ?.let { (it as LoadingDrawable).stop() }
        if (!loading) {
            button.setCompoundDrawablesRelative(null, null, null, null)
            return
        }
        val color = when {
            onPrimary -> Color.WHITE
            dark -> Color.rgb(242, 242, 247)
            else -> Color.rgb(54, 54, 58)
        }
        val density = button.resources.displayMetrics.density
        val spinnerSize = (18f * density + 0.5f).toInt()
        val spinner = LoadingDrawable(color, density).apply {
            setBounds(0, 0, spinnerSize, spinnerSize)
        }
        button.compoundDrawablePadding = (8f * density + 0.5f).toInt()
        button.setCompoundDrawablesRelative(spinner, null, null, null)
        spinner.start()
    }

    private fun setIconLoading(button: ImageView, loading: Boolean, up: Boolean, dark: Boolean) {
        (button.drawable as? LoadingDrawable)?.stop()
        if (loading) {
            val color = if (dark) Color.WHITE else Color.rgb(54, 54, 58)
            val spinner = LoadingDrawable(color, button.resources.displayMetrics.density)
            button.setImageDrawable(spinner)
            spinner.start()
        } else {
            button.setImageDrawable(VoteIconDrawable(
                up,
                if (up) Color.rgb(52, 199, 89) else Color.rgb(255, 69, 58),
            ))
        }
    }

    private fun styleSelectedTab(button: Button, selected: Boolean, dark: Boolean) {
        button.background = roundedBackground(
            if (selected) Color.rgb(251, 114, 153) else if (dark) Color.rgb(56, 56, 59) else Color.rgb(238, 238, 242),
            button.resources.displayMetrics.density * 10,
        )
        button.setTextColor(if (selected || dark) Color.WHITE else Color.rgb(48, 48, 50))
    }

    private fun bodyText(activity: Activity, value: String, dark: Boolean) = TextView(activity).apply {
        text = value
        textSize = 14f
        setTextColor(if (dark) Color.rgb(225, 225, 230) else Color.rgb(58, 58, 60))
        setPadding(0, activity.dp(7), 0, activity.dp(7))
    }

    private fun roundedBackground(color: Int, radius: Float) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(color)
        cornerRadius = radius
    }

    private fun borderedBackground(color: Int, strokeColor: Int, radius: Float, strokeWidth: Int) =
        roundedBackground(color, radius).apply { setStroke(strokeWidth, strokeColor) }

    private fun themedSpinnerAdapter(activity: Activity, labels: List<String>, dark: Boolean) =
        object : ArrayAdapter<String>(activity, android.R.layout.simple_spinner_item, labels) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                spinnerLabel(activity, getItem(position).orEmpty(), dark, false)

            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View =
                spinnerLabel(activity, getItem(position).orEmpty(), dark, true)
        }

    private fun spinnerLabel(activity: Activity, label: String, dark: Boolean, dropdown: Boolean) = TextView(activity).apply {
        text = if (dropdown) label else "$label  ▾"
        textSize = 15f
        gravity = Gravity.CENTER_VERTICAL
        setTextColor(if (dark) Color.rgb(245, 245, 247) else Color.rgb(32, 32, 35))
        setPadding(activity.dp(14), 0, activity.dp(14), 0)
        minHeight = activity.dp(if (dropdown) 48 else 44)
        if (dropdown) {
            background = roundedBackground(
                if (dark) Color.rgb(45, 45, 48) else Color.WHITE,
                activity.dp(8).toFloat(),
            )
        }
    }

    private fun mutationError(prefix: String, result: SponsorBlockClient.MutationResult): String {
        if (result.statusCode == 405) return "$prefix：已经投过票"
        val detail = result.message.replace('\n', ' ').take(120)
        return "$prefix（${if (result.statusCode > 0) "HTTP ${result.statusCode}" else "网络错误"}）：$detail"
    }

    private fun toast(activity: Activity, message: String, duration: Int = Toast.LENGTH_SHORT) {
        if (!activity.isFinishing && !activity.isDestroyed) Toast.makeText(activity, message, duration).show()
    }

    private fun formatTime(milliseconds: Int): String {
        val totalSeconds = milliseconds.coerceAtLeast(0) / 1000
        val hours = totalSeconds / 3600
        val minutes = totalSeconds % 3600 / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        else String.format(Locale.US, "%d:%02d", minutes, seconds)
    }

    private fun formatPreciseTime(milliseconds: Int): String {
        val value = milliseconds.coerceAtLeast(0)
        val hours = value / 3_600_000
        val minutes = value % 3_600_000 / 60_000
        val seconds = value % 60_000 / 1000.0
        return if (hours > 0) String.format(Locale.US, "%d:%02d:%06.3f", hours, minutes, seconds)
        else String.format(Locale.US, "%d:%06.3f", minutes, seconds)
    }

    private fun segmentCategoryColor(category: String): Int = Color.parseColor(when (category) {
        "sponsor" -> "#00d400"
        "selfpromo" -> "#ffff00"
        "interaction" -> "#cc00ff"
        "intro" -> "#00ffff"
        "outro" -> "#0202ed"
        "preview" -> "#008fd6"
        "music_offtopic" -> "#ff9900"
        "filler" -> "#7300ff"
        "padding" -> "#222222"
        else -> "#999999"
    })

    private fun isNight(activity: Activity): Boolean =
        activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

    private fun Activity.dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    fun onActivityResumed(activity: Activity) {
        resumedActivity = WeakReference(activity)
        observeLayout(activity)
        requestImmediateRender()
    }
    fun onActivityPaused(activity: Activity) {
        if (resumedActivity?.get() === activity) {
            resumedActivity = null
            stopObservingLayout()
            removeButton(dismissMenu = true)
        }
    }
    fun onActivityDestroyed(activity: Activity) {
        if (resumedActivity?.get() === activity) {
            resumedActivity = null
            stopObservingLayout()
            removeButton(dismissMenu = true)
        }
    }
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

    private class SimpleItemSelectedListener(
        private val onSelected: () -> Unit,
    ) : android.widget.AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) = onSelected()
        override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
    }

    private class LoadingDrawable(color: Int, density: Float) : Drawable(), Runnable {
        private val intrinsicSize = (24f * density + 0.5f).toInt()
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.STROKE
            strokeWidth = 2.2f * density
            strokeCap = Paint.Cap.ROUND
        }
        private val arc = RectF()
        private var angle = 0f
        private var running = false

        fun start() {
            if (running) return
            running = true
            invalidateSelf()
            scheduleSelf(this, android.os.SystemClock.uptimeMillis() + 16L)
        }

        fun stop() {
            running = false
            unscheduleSelf(this)
        }

        override fun run() {
            if (!running) return
            angle = (angle + 12f) % 360f
            invalidateSelf()
            scheduleSelf(this, android.os.SystemClock.uptimeMillis() + 16L)
        }

        override fun draw(canvas: Canvas) {
            val inset = paint.strokeWidth / 2f + 1f
            arc.set(bounds.left + inset, bounds.top + inset, bounds.right - inset, bounds.bottom - inset)
            canvas.drawArc(arc, angle, 250f, false, paint)
        }

        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
        override fun getIntrinsicWidth(): Int = intrinsicSize
        override fun getIntrinsicHeight(): Int = intrinsicSize
        @Deprecated("Deprecated in Android")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    private class VoteIconDrawable(
        private val up: Boolean,
        color: Int,
    ) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.FILL
        }
        private val path = Path().apply {
            addRoundRect(1.5f, 9.5f, 6f, 22f, 1.2f, 1.2f, Path.Direction.CW)
            moveTo(7.5f, 10f)
            lineTo(10.1f, 10f)
            lineTo(13.7f, 3.2f)
            cubicTo(14.2f, 2.3f, 15.4f, 2.1f, 16.1f, 2.8f)
            cubicTo(16.6f, 3.3f, 16.8f, 4f, 16.6f, 4.7f)
            lineTo(15.4f, 9.2f)
            lineTo(20.4f, 9.2f)
            cubicTo(22.1f, 9.2f, 23.2f, 10.8f, 22.6f, 12.4f)
            lineTo(19.8f, 20.1f)
            cubicTo(19.4f, 21.2f, 18.4f, 22f, 17.2f, 22f)
            lineTo(7.5f, 22f)
            close()
        }

        override fun draw(canvas: Canvas) {
            val scale = min(bounds.width(), bounds.height()) / 24f
            val dx = bounds.left + (bounds.width() - 24f * scale) / 2f
            val dy = bounds.top + (bounds.height() - 24f * scale) / 2f
            val save = canvas.save()
            canvas.translate(dx, dy)
            canvas.scale(scale, scale)
            if (!up) canvas.rotate(180f, 12f, 12f)
            canvas.drawPath(path, paint)
            canvas.restoreToCount(save)
        }

        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
        @Deprecated("Deprecated in Android")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    private class UploadIconDrawable : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        private val path = Path().apply {
            moveTo(79.14f, 16.37f)
            cubicTo(69.07f, 10.92f, 57.8f, 8.05f, 46.35f, 8f)
            cubicTo(34.69f, 7.95f, 23.2f, 10.83f, 12.94f, 16.37f)
            cubicTo(11.88f, 16.95f, 11.23f, 18.06f, 11.24f, 19.27f)
            cubicTo(11.59f, 43.9f, 24.73f, 65.42f, 44.33f, 77.52f)
            cubicTo(45.38f, 78.16f, 46.69f, 78.16f, 47.74f, 77.52f)
            cubicTo(67.34f, 65.43f, 80.48f, 43.91f, 80.83f, 19.27f)
            cubicTo(80.84f, 18.06f, 80.19f, 16.95f, 79.14f, 16.37f)
            close()

            moveTo(61.26f, 50.11f)
            lineTo(54.68f, 50.11f)
            cubicTo(53.52f, 50.11f, 52.58f, 51.05f, 52.58f, 52.21f)
            lineTo(52.58f, 62.07f)
            cubicTo(52.58f, 63.23f, 51.64f, 64.17f, 50.48f, 64.17f)
            lineTo(42.22f, 64.17f)
            cubicTo(41.06f, 64.17f, 40.12f, 63.23f, 40.12f, 62.07f)
            lineTo(40.12f, 52.21f)
            cubicTo(40.12f, 51.05f, 39.18f, 50.11f, 38.02f, 50.11f)
            lineTo(31.44f, 50.11f)
            cubicTo(29.85f, 50.11f, 28.83f, 48.41f, 29.59f, 47.01f)
            lineTo(44.5f, 19.42f)
            cubicTo(45.29f, 17.95f, 47.4f, 17.95f, 48.2f, 19.42f)
            lineTo(63.11f, 47.01f)
            cubicTo(63.87f, 48.41f, 62.85f, 50.11f, 61.26f, 50.11f)
            close()
        }

        override fun draw(canvas: Canvas) {
            val scale = min(bounds.width(), bounds.height()) / 88f
            val dx = bounds.left + (bounds.width() - 88f * scale) / 2f
            val dy = bounds.top + (bounds.height() - 88f * scale) / 2f
            val save = canvas.save()
            canvas.translate(dx, dy)
            canvas.scale(scale, scale)
            canvas.drawPath(path, paint)
            canvas.restoreToCount(save)
        }

        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
        @Deprecated("Deprecated in Android")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    private companion object {
        const val RENDER_INTERVAL_MS = 750L
        const val SUBMISSION_ICON_SIZE_DP = 24
        const val MIN_SUBMISSION_ICON_PADDING_DP = 8
        const val HALF_SCREEN_SEEKBAR_ID = "bbplayer_halfscreen_seekbar"
        const val REGULAR_TOP_GROUP_ID = "top_end_widget_group"
        const val STORY_TOP_GROUP_ID = "top_default_group"
        const val STORY_SEARCH_ID = "container_top_search"
        const val STORY_WHITE_TOP_ID = "container_top"
        const val STORY_MORE_ID = "container_top_more"
        const val PORTRAIT_ACTIONS_RIGHT_ID = "actions_container_right"
        const val PORTRAIT_LISTEN_DESCRIPTION = "听视频按钮"
        val STORY_SEEKBAR_IDS = setOf("story_ctrl_seekbar", "story_landscape_ctrl_seekbar")
        val STORY_VIEWERS_REGEX = Regex(".*\\d+\\s*人正在看.*")
        val PLAYER_PAGE_SEEKBAR_IDS = setOf(
            HALF_SCREEN_SEEKBAR_ID,
            "gemini_halfscreen_seekbar",
            *STORY_SEEKBAR_IDS.toTypedArray(),
        )
        const val PLAYER_SEEK_WIDGET_V3_CLASS = "com.bilibili.playerbizcommonv2.widget.seek.v3.PlayerSeekWidget3"
        const val STORY_SEEK_BAR_CLASS = "com.bilibili.video.story.view.StorySeekBar"
        const val LIGHT_SURFACE = 0xFFFDFDFD.toInt()
        const val DARK_SURFACE = 0xFF1C1C1E.toInt()
    }
}
