package com.retrsoft.bilisponsorskip

import android.app.Activity
import android.app.AlertDialog
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

internal fun showServerConnectionDialog(activity: Activity, current: String, onSave: (String) -> Unit) {
    val padding = (20 * activity.resources.displayMetrics.density).toInt()
    val body = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(padding, padding / 2, padding, padding / 2)
    }
    body.addView(TextView(activity).apply {
        text = "选择官方地址或填写自定义服务器根地址。测试显示本机实际 HTTP API 请求耗时（含连接与响应），不自动切换服务器。"
    })
    val input = EditText(activity).apply {
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        hint = "https://服务器 或 http://地址:端口"
        contentDescription = "服务器地址"
        setSingleLine(true)
        setText(current)
    }
    val status = TextView(activity)
    ServerConnection.presets.forEach { (name, url) ->
        body.addView(Button(activity).apply {
            text = "$name\n$url"
            isAllCaps = false
            setOnClickListener { input.setText(url) }
        })
    }
    body.addView(input)
    body.addView(status)
    val test = Button(activity).apply { text = "测试连通性与延迟" }
    body.addView(test)
    var revision = 0
    input.addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
            revision++
            status.text = ""
        }
        override fun afterTextChanged(s: Editable?) = Unit
    })
    val dialog = AlertDialog.Builder(activity)
        .setTitle("服务器连接")
        .setView(ScrollView(activity).apply { addView(body) })
        .setPositiveButton("保存", null)
        .setNegativeButton("取消", null)
        .setNeutralButton("恢复主站", null)
        .create()
    dialog.setOnShowListener {
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { input.setText(ServerConnection.DEFAULT_URL) }
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val normalized = ServerConnection.normalize(input.text.toString())
            if (normalized == null) input.error = "请输入有效的 HTTP/HTTPS 根地址，不含账号、查询参数或片段"
            else {
                onSave(normalized)
                dialog.dismiss()
            }
        }
    }
    test.setOnClickListener {
        val url = ServerConnection.normalize(input.text.toString())
        if (url == null) {
            input.error = "请输入有效的 HTTP/HTTPS 地址"
            return@setOnClickListener
        }
        val testedRevision = revision
        test.isEnabled = false
        status.text = "正在请求 API…"
        Thread({
            val result = ServerConnection.probe(url)
            activity.runOnUiThread {
                if (!dialog.isShowing || activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                test.isEnabled = true
                if (revision == testedRevision) {
                    status.text = if (result.available) "可用 · ${result.elapsedMs} ms（完整 HTTP 请求）"
                    else "不可用 · ${result.message.take(160)}（耗时 ${result.elapsedMs} ms）"
                }
            }
        }, "BiliSponsorSkip-server-test").apply { isDaemon = true }.start()
    }
    dialog.show()
}
