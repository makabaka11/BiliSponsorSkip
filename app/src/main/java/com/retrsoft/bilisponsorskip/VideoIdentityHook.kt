package com.retrsoft.bilisponsorskip

import android.app.Activity
import android.content.ContextWrapper
import android.view.View
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.util.WeakHashMap
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicLong

internal data class ResolvedVideoIdentity(val bvid: String, val cid: String)

internal fun resolveVideoIdentity(bvid: String?, aid: Long?, cid: Long?): ResolvedVideoIdentity? {
    val cleanCid = cid?.takeIf { it > 0 } ?: return null
    val cleanBvid = bvid?.trim()?.takeIf { it.startsWith("BV") }
        ?: aid?.takeIf { it > 0 }?.let(BvId::fromAid)
        ?: return null
    return ResolvedVideoIdentity(cleanBvid, cleanCid.toString())
}

internal class VideoIdentityHook(
    private val classLoader: ClassLoader,
    private val controller: SkipController,
) {
    private val storyControllers = WeakHashMap<Any, Any>()
    private val detailVideos = WeakHashMap<Activity, SkipController.VideoKey>()
    @Volatile private var resumedDetailActivity: WeakReference<Activity>? = null
    private val requestGeneration = AtomicLong()
    @Volatile private var activeStoryWidget: WeakReference<Any>? = null
    private var pausedStoryWidget: WeakReference<Any>? = null
    @Volatile private var pendingNetworkIdentity: ResolvedVideoIdentity? = null
    @Volatile private var lastStoryVideo: SkipController.VideoKey? = null

    @Volatile
    private var lastResolvedBvid: String? = null

    fun onActivityPaused(activity: Activity) {
        if (storyActivity(activeStoryWidget?.get()) === activity) {
            pausedStoryWidget = activeStoryWidget
            activeStoryWidget = null
            requestGeneration.incrementAndGet()
            controller.clearVideo("story activity paused")
        }
        if (resumedDetailActivity?.get() === activity) resumedDetailActivity = null
        if (activity.javaClass.name !in DETAIL_ACTIVITY_CLASSES || activeStoryWidget?.get() != null) return
        controller.currentVideo()?.let { synchronized(detailVideos) { detailVideos[activity] = it } }
    }

    fun onActivityResumed(activity: Activity) {
        val pausedWidget = pausedStoryWidget?.get()
        if (pausedWidget != null && storyActivity(pausedWidget) === activity) {
            synchronized(storyControllers) { storyControllers[pausedWidget] }?.let {
                activateStory(pausedWidget, it)
            }
        }
        if (activity.javaClass.name !in DETAIL_ACTIVITY_CLASSES) return
        resumedDetailActivity = WeakReference(activity)
        // Returning from Story to an existing details page does not necessarily send a request.
        // Restore only that page's own identity, and only if no newer identity is available.
        if (activeStoryWidget?.get() != null) return
        controller.currentVideo()?.let {
            synchronized(detailVideos) { detailVideos[activity] = it }
            return
        }
        val video = synchronized(detailVideos) { detailVideos[activity] }
        if (video != null) {
            // After swiping, the host may rebind the underlying details page to the last
            // Story instead of its original video. Keep it empty until that request arrives.
            if (lastStoryVideo != null && video != lastStoryVideo) return
            controller.updateVideo(video.bvid, video.cid)
        } else {
            // A deep link may request its video before the previous Story has stopped.
            // Only adopt that deferred request when the new page explicitly names its BVID.
            val expectedBvid = activity.intent?.data?.pathSegments?.firstOrNull { it.startsWith("BV") }
                ?: runCatching { activity.intent?.getStringExtra("bvid") }.getOrNull()
            pendingNetworkIdentity?.takeIf { it.bvid == expectedBvid }?.let {
                controller.updateVideo(it.bvid, it.cid)
                synchronized(detailVideos) {
                    detailVideos[activity] = SkipController.VideoKey(it.bvid, it.cid)
                }
            }
        }
    }

    fun onActivityDestroyed(activity: Activity) {
        if (resumedDetailActivity?.get() === activity) resumedDetailActivity = null
        synchronized(detailVideos) { detailVideos.remove(activity) }
    }

    fun install() {
        val installed = mutableListOf<String>()
        installPlayerUniteHooks()?.let(installed::add)
        installLegacyVideoDetailHook()?.let(installed::add)
        installLegacyVideoDetailCallbacks()?.let(installed::add)
        installLegacyVideoPageHooks()?.let(installed::add)
        installLegacyPlayerSourceHook()?.let(installed::add)
        installLegacyStoryHook()?.let(installed::add)
        if (installed.isEmpty()) {
            error("no supported video identity path found")
        }
        Log.d("video identity hooks installed: ${installed.joinToString()}")
    }

    private fun installPlayerUniteHooks(): String? {
        val mossClass = XposedHelpers.findClassIfExists(PLAYER_MOSS_CLASS, classLoader) ?: return null
        val handlerClass = XposedHelpers.findClassIfExists(MOSS_HANDLER_CLASS, classLoader)

        val count = hookRequestMethod(mossClass, "executePlayViewUnite", handlerClass) +
            hookRequestMethod(mossClass, "playViewUnite", handlerClass)
        return "player-unite($count)".takeIf { count > 0 }
    }

    private fun hookRequestMethod(mossClass: Class<*>, methodName: String, handlerClass: Class<*>?): Int {
        val methods = mossClass.declaredMethods.filter { it.name == methodName }
        if (methods.isEmpty()) {
            Log.d("$methodName is not present in this Bilibili version")
            return 0
        }

        methods.forEach { method ->
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val generation = requestGeneration.incrementAndGet()
                    param.args.firstOrNull()?.let(::readRequest)
                    if (methodName == "playViewUnite" && handlerClass != null && param.args.size > 1) {
                        val original = param.args[1] ?: return
                        param.args[1] = wrapResponseHandler(original, handlerClass, generation)
                    }
                }
            })
        }
        Log.d("hooked $methodName (${methods.size} overload(s))")
        return methods.size
    }

    private fun installLegacyVideoDetailHook(): String? {
        val detailClass = XposedHelpers.findClassIfExists(LEGACY_VIDEO_DETAIL_CLASS, classLoader) ?: return null
        val viewModelClass = XposedHelpers.findClassIfExists(LEGACY_VIDEO_DETAIL_VIEW_MODEL_CLASS, classLoader)
            ?: return null
        val methods = viewModelClass.declaredMethods.filter { method ->
            !Modifier.isStatic(method.modifiers) &&
                method.returnType == Void.TYPE &&
                method.parameterTypes.contentEquals(arrayOf(detailClass))
        }
        methods.forEach { method ->
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    param.args.firstOrNull()?.let(::readLegacyVideoDetail)
                }
            })
        }
        if (methods.isEmpty()) return null
        return "legacy-detail(${viewModelClass.name}.${methods.joinToString { it.name }})"
    }

    /**
     * White Bilibili 3.18.2 still exposes [LEGACY_VIDEO_DETAIL_CLASS], but no longer has the
     * old video-detail ViewModel used above. The loaded detail is instead delivered to callback
     * implementations declared directly inside VideoDetailsActivity. Resolve those callbacks by
     * their parameter type so the hook does not depend on their short obfuscated class names.
     */
    private fun installLegacyVideoDetailCallbacks(): String? {
        val detailClass = XposedHelpers.findClassIfExists(LEGACY_VIDEO_DETAIL_CLASS, classLoader) ?: return null
        val activityClass = XposedHelpers.findClassIfExists(LEGACY_VIDEO_DETAILS_ACTIVITY, classLoader) ?: return null
        val callbackClasses = LinkedHashSet<Class<*>>().apply {
            addAll(activityClass.declaredClasses)
            LEGACY_VIDEO_DETAIL_CALLBACK_CLASSES.mapNotNullTo(this) { className ->
                XposedHelpers.findClassIfExists(className, classLoader)
            }
        }
        val methods = callbackClasses.flatMap { callbackClass ->
            callbackClass.declaredMethods.filter { method ->
                !Modifier.isStatic(method.modifiers) &&
                    method.returnType == Void.TYPE &&
                    method.parameterTypes.contentEquals(arrayOf(detailClass))
            }
        }.distinctBy { it.toGenericString() }
        methods.forEach { method ->
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    param.args.firstOrNull()?.let(::readLegacyVideoDetail)
                }
            })
        }
        if (methods.isEmpty()) return null
        return "legacy-detail-callbacks(${methods.joinToString { "${it.declaringClass.simpleName}.${it.name}" }})"
    }

    /** Keep the active cid in sync when an old details page switches between parts. */
    private fun installLegacyVideoPageHooks(): String? {
        val pageClass = XposedHelpers.findClassIfExists(LEGACY_VIDEO_PAGE_CLASS, classLoader) ?: return null
        val activityClass = XposedHelpers.findClassIfExists(LEGACY_VIDEO_DETAILS_ACTIVITY, classLoader) ?: return null
        val methods = activityClass.declaredMethods.filter { method ->
            !Modifier.isStatic(method.modifiers) &&
                method.returnType == Void.TYPE &&
                method.parameterCount in 1..2 &&
                method.parameterTypes.all { it == pageClass }
        }
        methods.forEach { method ->
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val page = param.args.lastOrNull { it != null } ?: return
                    readLegacyVideoPage(page)
                }
            })
        }
        if (methods.isEmpty()) return null
        return "legacy-pages(${activityClass.name}.${methods.joinToString { it.name }})"
    }

    private fun installLegacyPlayerSourceHook(): String? {
        val sourceClass = XposedHelpers.findClassIfExists(LEGACY_PLAYER_SOURCE_CLASS, classLoader) ?: return null
        val methods = sourceClass.declaredMethods.filter { method ->
            method.name == LEGACY_PLAYER_SOURCE_RESOLVE_METHOD &&
                !Modifier.isStatic(method.modifiers) &&
                method.parameterCount == 0
        }
        methods.forEach { method ->
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    readLegacyPlayerSource(param.thisObject)
                }
            })
        }
        if (methods.isEmpty()) return null
        return "legacy-source(${sourceClass.name}.${LEGACY_PLAYER_SOURCE_RESOLVE_METHOD})"
    }

    private fun installLegacyStoryHook(): String? {
        val widgetClass = XposedHelpers.findClassIfExists(LEGACY_STORY_TITLE_WIDGET_CLASS, classLoader)
            ?: return null
        val bindMethods = widgetClass.declaredMethods.filter { method ->
            !Modifier.isStatic(method.modifiers) && method.returnType == Void.TYPE &&
                method.parameterCount == 1 && isStoryControllerType(method.parameterTypes.single())
        }
        val startMethods = widgetClass.declaredMethods.filter { method ->
            method.name == LEGACY_STORY_START_METHOD &&
                method.returnType == Void.TYPE &&
                !Modifier.isStatic(method.modifiers) &&
                method.parameterTypes.contentEquals(arrayOf(Int::class.javaPrimitiveType))
        }
        val stopMethods = widgetClass.declaredMethods.filter { method ->
            method.name == "onStop" && method.returnType == Void.TYPE &&
                !Modifier.isStatic(method.modifiers) &&
                method.parameterTypes.contentEquals(arrayOf(Int::class.javaPrimitiveType))
        }
        if (bindMethods.size != 1 || startMethods.size != 1 || stopMethods.size != 1) {
            Log.d("story identity unsupported or ambiguous: bind=${bindMethods.size}; start=${startMethods.size}; stop=${stopMethods.size}")
            return null
        }
        bindMethods.forEach { method ->
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val widget = param.thisObject ?: return
                    val storyController = param.args.firstOrNull() ?: return
                    synchronized(storyControllers) { storyControllers[widget] = storyController }
                    if (storyController.callOrNull(LEGACY_STORY_ACTIVE_METHOD) == true) {
                        activateStory(widget, storyController)
                    }
                }
            })
        }
        startMethods.forEach { method ->
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val widget = param.thisObject ?: return
                    val storyController = synchronized(storyControllers) { storyControllers[widget] } ?: return
                    activateStory(widget, storyController)
                }
            })
        }
        stopMethods.forEach { method ->
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (activeStoryWidget?.get() !== param.thisObject) return
                    activeStoryWidget = null
                    requestGeneration.incrementAndGet()
                    controller.clearVideo("story stopped")
                    // Some hosts resume the underlying Activity before stopping its Story
                    // widget. Handle both lifecycle orders without restoring during a swipe.
                    resumedDetailActivity?.get()?.let(::onActivityResumed)
                }
            })
        }
        return "story-lifecycle(${widgetClass.name}.${bindMethods.single().name}/onStart/onStop)"
    }

    private fun activateStory(widget: Any, storyController: Any) {
        pausedStoryWidget = null
        activeStoryWidget = WeakReference(widget)
        requestGeneration.incrementAndGet()
        val detail = storyController.callOrNull(LEGACY_STORY_DATA_METHOD)
        val identity = detail?.let {
            resolveVideoIdentity(it.stringOrNull("getBvid"), it.longOrNull("getAid"), it.longOrNull("getCid"))
        }
        if (identity == null) {
            controller.clearVideo("story identity unavailable")
            return
        }
        lastResolvedBvid = identity.bvid
        lastStoryVideo = SkipController.VideoKey(identity.bvid, identity.cid)
        controller.updateVideo(identity.bvid, identity.cid)
        Log.d("active story identity: ${identity.bvid}+${identity.cid}")
    }

    private fun storyActivity(widget: Any?): Activity? {
        var context = (widget as? View)?.context
        while (context is ContextWrapper) {
            if (context is Activity) return context
            val base = context.baseContext
            if (base === context) break
            context = base
        }
        return context as? Activity
    }

    private fun readLegacyVideoDetail(detail: Any) {
        updateVideo(
            bvid = detail.fieldOrNull(LEGACY_BVID_FIELD) as? String,
            aid = detail.numberFieldOrNull(LEGACY_AID_FIELD),
            cid = detail.numberFieldOrNull(LEGACY_CID_FIELD),
        )
    }

    private fun readLegacyVideoPage(page: Any) {
        val bvid = lastResolvedBvid ?: return
        val cid = page.numberFieldOrNull(LEGACY_CID_FIELD) ?: return
        updateVideo(bvid = bvid, aid = null, cid = cid)
    }

    private fun readLegacyPlayerSource(source: Any) {
        updateVideo(
            bvid = source.stringOrNull(LEGACY_SOURCE_BVID_METHOD),
            aid = source.longOrNull(LEGACY_SOURCE_AID_METHOD),
            cid = source.longOrNull(LEGACY_SOURCE_CID_METHOD),
        )
    }

    private fun updateVideo(bvid: String?, aid: Long?, cid: Long?) {
        // Story requests can belong to preloaded neighbours. The active widget owns identity.
        val identity = resolveVideoIdentity(bvid, aid, cid) ?: return
        pendingNetworkIdentity = identity
        if (activeStoryWidget?.get() != null) return
        lastResolvedBvid = identity.bvid
        controller.updateVideo(identity.bvid, identity.cid)
        resumedDetailActivity?.get()?.let { activity ->
            synchronized(detailVideos) {
                detailVideos[activity] = SkipController.VideoKey(identity.bvid, identity.cid)
            }
        }
    }

    private fun readRequest(request: Any) {
        val vod = request.callOrNull("getVod")
        val cid = vod?.longOrNull("getCid") ?: request.longOrNull("getCid") ?: return
        val directBvid = request.stringOrNull("getBvid").orEmpty()
        val aid = vod?.longOrNull("getAid") ?: request.longOrNull("getAid")
        updateVideo(directBvid, aid, cid)
    }

    private fun readResponse(response: Any?) {
        val playArc = response?.callOrNull("getPlayArc") ?: return
        val cid = playArc.longOrNull("getCid") ?: return
        updateVideo(
            playArc.stringOrNull("getBvid"),
            playArc.longOrNull("getAid"),
            cid,
        )
    }

    private fun wrapResponseHandler(original: Any, handlerClass: Class<*>, generation: Long): Any =
        Proxy.newProxyInstance(classLoader, arrayOf(handlerClass)) { _, method, args ->
            if (method.name == "onNext" && requestGeneration.get() == generation) readResponse(args?.firstOrNull())
            if (args == null) method.invokeUnwrapped(original) else method.invokeUnwrapped(original, *args)
        }

    private companion object {
        const val PLAYER_MOSS_CLASS = "com.bapis.bilibili.app.playerunite.v1.PlayerMoss"
        const val MOSS_HANDLER_CLASS = "com.bilibili.lib.moss.api.MossResponseHandler"
        const val LEGACY_VIDEO_DETAIL_CLASS =
            "tv.danmaku.bili.videopage.data.view.model.BiliVideoDetail"
        const val LEGACY_VIDEO_DETAIL_VIEW_MODEL_CLASS =
            "tv.danmaku.bili.videopage.player.viewmodel.d"
        const val LEGACY_VIDEO_DETAILS_ACTIVITY =
            "com.bilibili.video.videodetail.VideoDetailsActivity"
        val DETAIL_ACTIVITY_CLASSES = setOf(
            LEGACY_VIDEO_DETAILS_ACTIVITY,
            "com.bilibili.ship.theseus.detail.UnitedBizDetailsActivity",
        )
        const val LEGACY_VIDEO_PAGE_CLASS =
            "tv.danmaku.bili.videopage.data.view.model.BiliVideoDetail\$Page"
        val LEGACY_VIDEO_DETAIL_CALLBACK_CLASSES = listOf(
            "com.bilibili.video.videodetail.VideoDetailsActivity\$f",
            "com.bilibili.video.videodetail.VideoDetailsActivity\$g",
        )
        const val LEGACY_BVID_FIELD = "mBvid"
        const val LEGACY_AID_FIELD = "mAvid"
        const val LEGACY_CID_FIELD = "mCid"
        const val LEGACY_PLAYER_SOURCE_CLASS = "tv.danmaku.bili.videopage.player.n"
        const val LEGACY_PLAYER_SOURCE_RESOLVE_METHOD = "v"
        const val LEGACY_SOURCE_BVID_METHOD = "x"
        const val LEGACY_SOURCE_AID_METHOD = "U"
        const val LEGACY_SOURCE_CID_METHOD = "W"
        const val LEGACY_STORY_TITLE_WIDGET_CLASS =
            "com.bilibili.video.story.action.widget.StoryTitleWidget"
        const val LEGACY_STORY_START_METHOD = "onStart"
        const val LEGACY_STORY_ACTIVE_METHOD = "isActive"
        const val LEGACY_STORY_DATA_METHOD = "getData"
    }
}

private fun Any.fieldOrNull(name: String): Any? =
    runCatching { XposedHelpers.getObjectField(this, name) }.getOrNull()

private fun Any.numberFieldOrNull(name: String): Long? =
    runCatching { XposedHelpers.getLongField(this, name) }.getOrNull()
