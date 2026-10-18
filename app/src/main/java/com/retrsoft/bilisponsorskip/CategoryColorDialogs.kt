package com.retrsoft.bilisponsorskip

import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView

internal data class ColorDialogTheme(val surface: Int, val text: Int, val secondary: Int, val accent: Int)

internal val CATEGORY_BEHAVIOR_LABELS = listOf("禁用", "在进度条中显示", "手动跳过", "自动跳过")

internal fun Context.colorDp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

internal class CategorySettingsContent(
    context: Context,
    private val category: String,
    initialMode: CategoryMode,
    initialColor: String?,
    private val theme: ColorDialogTheme,
    radioFactory: (Context) -> RadioButton = { RadioButton(it) },
    buttonFactory: (Context) -> Button = { Button(it) },
    onPick: (CategorySettingsContent) -> Unit,
) : LinearLayout(context) {
    var mode = initialMode
        private set
    var customColor = CategoryColors.normalize(initialColor)
        private set
    private val colorButton = buttonFactory(context)

    init {
        orientation = VERTICAL
        setPadding(context.colorDp(20), context.colorDp(8), context.colorDp(20), context.colorDp(12))
        addView(RadioGroup(context).apply {
            orientation = VERTICAL
            CategoryMode.entries.forEachIndexed { index, value ->
                addView(radioFactory(context).apply {
                    id = View.generateViewId()
                    text = CATEGORY_BEHAVIOR_LABELS[index]
                    setTextColor(theme.text)
                    buttonTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                        intArrayOf(theme.accent, theme.secondary))
                    minHeight = context.colorDp(48)
                    isChecked = initialMode == value
                    setOnCheckedChangeListener { _, checked -> if (checked) mode = value }
                }, LayoutParams(-1, -2))
            }
        })
        colorButton.apply {
            minHeight = context.colorDp(56)
            isAllCaps = false
            setPaddingRelative(paddingStart, context.colorDp(12), paddingEnd, context.colorDp(12))
        }
        addView(colorButton, LayoutParams(-1, LayoutParams.WRAP_CONTENT))
        colorButton.setOnClickListener { onPick(this) }
        addView(TextView(context).apply {
            text = "颜色用于进度条及适用的标题标签，标签会自动调整深浅与文字颜色。"
            setTextColor(theme.secondary)
            textSize = 13f
            setPadding(0, context.colorDp(8), 0, 0)
        })
        updateColor(customColor)
    }

    fun updateColor(value: String?) {
        customColor = CategoryColors.normalize(value)
        val color = CategoryColors.resolve(category, customColor)
        colorButton.text = "片段颜色：${customColor ?: "默认 ${CategoryColors.hex(color)}"}"
        colorButton.setTextColor(theme.text)
        colorButton.setCompoundDrawablesRelativeWithIntrinsicBounds(GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            setStroke(context.colorDp(1), theme.secondary)
            setSize(context.colorDp(24), context.colorDp(24))
        }, null, null, null)
        colorButton.compoundDrawablePadding = context.colorDp(12)
    }
}

internal interface HueControl {
    val view: View
    fun setHue(hue: Float)
}

internal class ColorPickerContent(
    context: Context,
    category: String,
    initialColor: String?,
    private val theme: ColorDialogTheme,
    field: Pair<View, EditText>? = null,
    hueFactory: ((Context, Float, (Float) -> Unit) -> HueControl)? = null,
) : LinearLayout(context) {
    private val hsv = FloatArray(3).also { Color.colorToHSV(CategoryColors.resolve(category, initialColor), it) }
    private val hexInput = field?.second ?: EditText(context).apply {
        hint = "HEX 颜色，例如 #00D400"
        setTextColor(theme.text)
        setHintTextColor(theme.secondary)
        backgroundTintList = ColorStateList.valueOf(theme.accent)
    }
    private val preview = TextView(context)
    private val track = View(context)
    private val palette = SaturationValueView(context, hsv) { updatePreview() }
    private val hue: HueControl
    private var updating = false
    val selectedHex: String? get() = CategoryColors.normalize(hexInput.text.toString())

    init {
        orientation = VERTICAL
        setPadding(context.colorDp(20), context.colorDp(8), context.colorDp(20), context.colorDp(8))
        addView(preview.apply {
            text = "${category.categoryLabel()} · 颜色预览"
            textSize = 15f
            gravity = android.view.Gravity.CENTER
        }, LayoutParams(-1, context.colorDp(44)))
        addView(track, LayoutParams(-1, context.colorDp(6)).apply { topMargin = context.colorDp(10) })
        addView(TextView(context).apply {
            text = "色相"
            setTextColor(theme.secondary)
        }, LayoutParams(-1, -2).apply { topMargin = context.colorDp(12) })
        val changeHue: (Float) -> Unit = { value -> hsv[0] = value; palette.invalidate(); updatePreview() }
        hue = hueFactory?.invoke(context, hsv[0], changeHue) ?: object : HueControl {
            private val slider = SeekBar(context).apply {
                max = 360
                progress = hsv[0].toInt()
                progressTintList = ColorStateList.valueOf(theme.accent)
                thumbTintList = ColorStateList.valueOf(theme.accent)
                contentDescription = "色相"
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
                        if (fromUser) changeHue(value.toFloat())
                    }
                    override fun onStartTrackingTouch(bar: SeekBar?) = Unit
                    override fun onStopTrackingTouch(bar: SeekBar?) = Unit
                })
            }
            override val view: View get() = slider
            override fun setHue(hue: Float) { slider.progress = hue.toInt() }
        }
        addView(hue.view, LayoutParams(-1, context.colorDp(48)))
        addView(palette, LayoutParams(-1, context.colorDp(160)))
        addView(TextView(context).apply {
            text = "拖动色板调整饱和度与明度，或输入 HEX 色值。"
            setTextColor(theme.secondary)
            textSize = 12f
        }, LayoutParams(-1, -2).apply { topMargin = context.colorDp(6) })
        hexInput.isSingleLine = true
        hexInput.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
        addView(field?.first ?: hexInput, LayoutParams(-1, -2).apply { topMargin = context.colorDp(10) })
        hexInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (updating) return
                val normalized = selectedHex
                hexInput.error = if (normalized == null) "请输入 6 位 HEX 色值" else null
                if (normalized != null) selectColor(Color.parseColor(normalized), updateField = false)
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        updatePreview()
    }

    private fun selectColor(color: Int, updateField: Boolean = true) {
        Color.colorToHSV(color, hsv)
        hue.setHue(hsv[0])
        palette.invalidate()
        updatePreview(updateField)
    }

    private fun updatePreview(updateField: Boolean = true) {
        val hex = CategoryColors.hex(Color.HSVToColor(hsv))
        val labelColor = CategoryColors.resolve("", hex, preview = true)
        preview.background = GradientDrawable().apply { setColor(labelColor); cornerRadius = context.colorDp(8).toFloat() }
        preview.setTextColor(CategoryColors.foreground(labelColor))
        track.setBackgroundColor(Color.parseColor(hex))
        if (updateField) {
            updating = true
            hexInput.setText(hex)
            hexInput.error = null
            updating = false
        }
    }
}

private class SaturationValueView(context: Context, private val hsv: FloatArray, private val onChange: () -> Unit) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    init { contentDescription = "饱和度和明度色板，也可通过下方 HEX 输入精确设置颜色" }
    override fun onDraw(canvas: Canvas) {
        if (width == 0 || height == 0) return
        paint.shader = LinearGradient(0f, 0f, width.toFloat(), 0f, Color.WHITE,
            Color.HSVToColor(floatArrayOf(hsv[0], 1f, 1f)), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        paint.shader = LinearGradient(0f, 0f, 0f, height.toFloat(), Color.TRANSPARENT, Color.BLACK, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = context.colorDp(3).toFloat()
        paint.color = Color.BLACK
        canvas.drawCircle(hsv[1] * width, (1 - hsv[2]) * height, context.colorDp(7).toFloat(), paint)
        paint.strokeWidth = context.colorDp(1).toFloat()
        paint.color = Color.WHITE
        canvas.drawCircle(hsv[1] * width, (1 - hsv[2]) * height, context.colorDp(7).toFloat(), paint)
        paint.style = Paint.Style.FILL
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                hsv[1] = (event.x / width.coerceAtLeast(1)).coerceIn(0f, 1f)
                hsv[2] = 1f - (event.y / height.coerceAtLeast(1)).coerceIn(0f, 1f)
                invalidate()
                onChange()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
            }
        }
        return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }
}

internal fun showEmbeddedCategorySettings(
    context: Context, category: String, mode: CategoryMode, customColor: String?, theme: ColorDialogTheme,
    onSave: (CategoryMode, String?) -> Unit,
) {
    val body = CategorySettingsContent(context, category, mode, customColor, theme, onPick = { content ->
        val picker = ColorPickerContent(context, category, content.customColor, theme)
        val dialog = AlertDialog.Builder(context).setTitle("${category.categoryLabel()} · 颜色")
            .setView(ScrollView(context).apply { addView(picker) })
            .setNegativeButton("取消", null).setNeutralButton("恢复默认") { _, _ -> content.updateColor(null) }
            .setPositiveButton("确定", null).create()
        dialog.setOnShowListener {
            styleEmbeddedColorDialog(dialog, theme)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                picker.selectedHex?.let { content.updateColor(it); dialog.dismiss() }
            }
        }
        dialog.show()
    })
    val dialog = AlertDialog.Builder(context).setTitle(category.categoryLabel())
        .setView(ScrollView(context).apply { addView(body) }).setNegativeButton("取消", null)
        .setPositiveButton("确定") { _, _ -> onSave(body.mode, body.customColor) }.create()
    dialog.setOnShowListener { styleEmbeddedColorDialog(dialog, theme) }
    dialog.show()
}

private fun styleEmbeddedColorDialog(dialog: AlertDialog, theme: ColorDialogTheme) {
    dialog.window?.setBackgroundDrawable(GradientDrawable().apply {
        setColor(theme.surface); cornerRadius = dialog.context.colorDp(16).toFloat()
    })
    val titleId = dialog.context.resources.getIdentifier("alertTitle", "id", "android")
    if (titleId != 0) dialog.findViewById<TextView>(titleId)?.setTextColor(theme.text)
    listOf(AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL).forEach {
        dialog.getButton(it)?.setTextColor(theme.accent)
    }
}
