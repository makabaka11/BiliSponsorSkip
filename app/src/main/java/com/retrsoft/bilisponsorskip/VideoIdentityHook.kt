package com.retrsoft.bilisponsorskip

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.reflect.Proxy

internal class VideoIdentityHook(
    private val classLoader: ClassLoader,
    private val controller: SkipController,
) {
    fun install() {
        val mossClass = XposedHelpers.findClassIfExists(PLAYER_MOSS_CLASS, classLoader)
            ?: error("$PLAYER_MOSS_CLASS not found")
        val handlerClass = XposedHelpers.findClassIfExists(MOSS_HANDLER_CLASS, classLoader)

        hookRequestMethod(mossClass, "executePlayViewUnite", handlerClass)
        hookRequestMethod(mossClass, "playViewUnite", handlerClass)
    }

    private fun hookRequestMethod(mossClass: Class<*>, methodName: String, handlerClass: Class<*>?) {
        val methods = mossClass.declaredMethods.filter { it.name == methodName }
        if (methods.isEmpty()) {
            Log.d("$methodName is not present in this Bilibili version")
            return
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
    }

    private fun readRequest(request: Any) {
        val vod = request.callOrNull("getVod")
        val cid = vod?.longOrNull("getCid") ?: request.longOrNull("getCid") ?: return
        val directBvid = request.stringOrNull("getBvid").orEmpty()
        val aid = vod?.longOrNull("getAid") ?: request.longOrNull("getAid")
        val bvid = directBvid.takeIf(String::isNotBlank)
            ?: aid?.takeIf { it > 0 }?.let(BvId::fromAid)
            ?: return
        controller.updateVideo(bvid, cid.toString())
    }

    private fun readResponse(response: Any?) {
        val playArc = response?.callOrNull("getPlayArc") ?: return
        val cid = playArc.longOrNull("getCid") ?: return
        val bvid = playArc.stringOrNull("getBvid")?.takeIf(String::isNotBlank)
            ?: playArc.longOrNull("getAid")?.takeIf { it > 0 }?.let(BvId::fromAid)
            ?: return
        controller.updateVideo(bvid, cid.toString())
    }

    private fun wrapResponseHandler(original: Any, handlerClass: Class<*>): Any =
        Proxy.newProxyInstance(classLoader, arrayOf(handlerClass)) { _, method, args ->
            if (method.name == "onNext") readResponse(args?.firstOrNull())
            if (args == null) method.invokeUnwrapped(original) else method.invokeUnwrapped(original, *args)
        }

    private companion object {
        const val PLAYER_MOSS_CLASS = "com.bapis.bilibili.app.playerunite.v1.PlayerMoss"
        const val MOSS_HANDLER_CLASS = "com.bilibili.lib.moss.api.MossResponseHandler"
    }
}
