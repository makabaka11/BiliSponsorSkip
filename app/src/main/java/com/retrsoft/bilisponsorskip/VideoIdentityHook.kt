package com.retrsoft.bilisponsorskip

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.util.WeakHashMap

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

    fun install() {
        val installed = mutableListOf<String>()
        installPlayerUniteHooks()?.let(installed::add)
        installLegacyVideoDetailHook()?.let(installed::add)
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
                    param.args.firstOrNull()?.let(::readRequest)
                    if (methodName == "playViewUnite" && handlerClass != null && param.args.size > 1) {
                        val original = param.args[1] ?: return
                        param.args[1] = wrapResponseHandler(original, handlerClass)
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
            method.name == LEGACY_STORY_BIND_METHOD &&
                !Modifier.isStatic(method.modifiers) &&
                method.parameterCount == 1
        }
        val startMethods = widgetClass.declaredMethods.filter { method ->
            method.name == LEGACY_STORY_START_METHOD &&
                !Modifier.isStatic(method.modifiers) &&
                method.parameterTypes.contentEquals(arrayOf(Int::class.javaPrimitiveType))
        }
        bindMethods.forEach { method ->
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val widget = param.thisObject ?: return
                    val storyController = param.args.firstOrNull() ?: return
                    synchronized(storyControllers) { storyControllers[widget] = storyController }
                    if (storyController.callOrNull(LEGACY_STORY_ACTIVE_METHOD) == true) {
                        readLegacyStoryController(storyController)
                    }
                }
            })
        }
        startMethods.forEach { method ->
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val widget = param.thisObject ?: return
                    val storyController = synchronized(storyControllers) { storyControllers[widget] } ?: return
                    readLegacyStoryController(storyController)
                }
            })
        }
        if (bindMethods.isEmpty() || startMethods.isEmpty()) return null
        return "legacy-story(${widgetClass.name}.${LEGACY_STORY_BIND_METHOD}/${LEGACY_STORY_START_METHOD})"
    }

    private fun readLegacyVideoDetail(detail: Any) {
        updateVideo(
            bvid = detail.fieldOrNull(LEGACY_BVID_FIELD) as? String,
            aid = detail.numberFieldOrNull(LEGACY_AID_FIELD),
            cid = detail.numberFieldOrNull(LEGACY_CID_FIELD),
        )
    }

    private fun readLegacyPlayerSource(source: Any) {
        updateVideo(
            bvid = source.stringOrNull(LEGACY_SOURCE_BVID_METHOD),
            aid = source.longOrNull(LEGACY_SOURCE_AID_METHOD),
            cid = source.longOrNull(LEGACY_SOURCE_CID_METHOD),
        )
    }

    private fun readLegacyStoryController(storyController: Any) {
        val detail = storyController.callOrNull(LEGACY_STORY_DATA_METHOD) ?: return
        updateVideo(
            bvid = detail.stringOrNull(LEGACY_STORY_BVID_METHOD),
            aid = detail.longOrNull(LEGACY_STORY_AID_METHOD),
            cid = detail.longOrNull(LEGACY_STORY_CID_METHOD),
        )
    }

    private fun updateVideo(bvid: String?, aid: Long?, cid: Long?) {
        val identity = resolveVideoIdentity(bvid, aid, cid) ?: return
        controller.updateVideo(identity.bvid, identity.cid)
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

    private fun wrapResponseHandler(original: Any, handlerClass: Class<*>): Any =
        Proxy.newProxyInstance(classLoader, arrayOf(handlerClass)) { _, method, args ->
            if (method.name == "onNext") readResponse(args?.firstOrNull())
            if (args == null) method.invokeUnwrapped(original) else method.invokeUnwrapped(original, *args)
        }

    private companion object {
        const val PLAYER_MOSS_CLASS = "com.bapis.bilibili.app.playerunite.v1.PlayerMoss"
        const val MOSS_HANDLER_CLASS = "com.bilibili.lib.moss.api.MossResponseHandler"
        const val LEGACY_VIDEO_DETAIL_CLASS =
            "tv.danmaku.bili.videopage.data.view.model.BiliVideoDetail"
        const val LEGACY_VIDEO_DETAIL_VIEW_MODEL_CLASS =
            "tv.danmaku.bili.videopage.player.viewmodel.d"
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
        const val LEGACY_STORY_BIND_METHOD = "m2"
        const val LEGACY_STORY_START_METHOD = "onStart"
        const val LEGACY_STORY_ACTIVE_METHOD = "isActive"
        const val LEGACY_STORY_DATA_METHOD = "getData"
        const val LEGACY_STORY_BVID_METHOD = "getBvid"
        const val LEGACY_STORY_AID_METHOD = "getAid"
        const val LEGACY_STORY_CID_METHOD = "getCid"
    }
}

private fun Any.fieldOrNull(name: String): Any? =
    runCatching { XposedHelpers.getObjectField(this, name) }.getOrNull()

private fun Any.numberFieldOrNull(name: String): Long? =
    runCatching { XposedHelpers.getLongField(this, name) }.getOrNull()
