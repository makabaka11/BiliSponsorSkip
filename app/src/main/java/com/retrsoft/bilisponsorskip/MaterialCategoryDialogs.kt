package com.retrsoft.bilisponsorskip

import android.content.Context
import android.graphics.Color
import android.view.View
import android.widget.ScrollView
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.radiobutton.MaterialRadioButton
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

/** This adapter is only loaded by the standalone Activity, never by the embedded host. */
internal fun showMaterialCategorySettings(
    context: Context, category: String, mode: CategoryMode, customColor: String?,
    onSave: (CategoryMode, String?) -> Unit,
) {
    val theme = ColorDialogTheme(
        MaterialColors.getColor(context, com.google.android.material.R.attr.colorSurface, Color.WHITE),
        MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnSurface, Color.BLACK),
        MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnSurfaceVariant, Color.GRAY),
        MaterialColors.getColor(context, com.google.android.material.R.attr.colorPrimary, Color.BLUE),
    )
    val body = CategorySettingsContent(context, category, mode, customColor, theme,
        radioFactory = { MaterialRadioButton(it) },
        buttonFactory = { MaterialButton(it, null, com.google.android.material.R.attr.materialButtonOutlinedStyle) },
        onPick = { content ->
            val input = TextInputEditText(context)
            val field = TextInputLayout(context).apply {
                hint = "HEX 颜色"
                addView(input)
            }
            val picker = ColorPickerContent(context, category, content.customColor, theme, field to input,
                hueFactory = { sliderContext, initialHue, change ->
                    object : HueControl {
                        private val slider = Slider(sliderContext).apply {
                            valueFrom = 0f
                            valueTo = 360f
                            value = initialHue
                            contentDescription = "色相"
                            addOnChangeListener { _, value, fromUser -> if (fromUser) change(value) }
                        }
                        override val view: View get() = slider
                        override fun setHue(hue: Float) { slider.value = hue.coerceIn(0f, 360f) }
                    }
                })
            val dialog = MaterialAlertDialogBuilder(context).setTitle("${category.categoryLabel()} · 颜色")
                .setView(ScrollView(context).apply { addView(picker) })
                .setNegativeButton("取消", null)
                .setNeutralButton("恢复默认") { _, _ -> content.updateColor(null) }
                .setPositiveButton("确定", null).create()
            dialog.setOnShowListener {
                dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    picker.selectedHex?.let { content.updateColor(it); dialog.dismiss() }
                }
            }
            dialog.show()
        })
    MaterialAlertDialogBuilder(context).setTitle(category.categoryLabel())
        .setView(ScrollView(context).apply { addView(body) }).setNegativeButton("取消", null)
        .setPositiveButton("保存") { _, _ -> onSave(body.mode, body.customColor) }.show()
}
