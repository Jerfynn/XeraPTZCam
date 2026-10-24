package com.xera.ptzcam

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat

/** Icon + tiny label button used in the glass control rail. */
@SuppressLint("ViewConstructor")
class RailButton(
    context: Context,
    @DrawableRes icon: Int,
    label: String,
) : LinearLayout(context) {

    private val iconView = ImageView(context)
    private val labelView = TextView(context)
    private val dp = resources.displayMetrics.density

    /** Colour used for icon + label when selected (cyan by default, red for REC). */
    var accent: Int = ContextCompat.getColor(context, R.color.accent)
        set(value) { field = value; applyTint() }

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER
        isClickable = true
        isFocusable = true
        setBackgroundResource(R.drawable.rail_item_bg)

        iconView.setImageResource(icon)
        addView(iconView, LayoutParams((22 * dp).toInt(), (22 * dp).toInt()))

        labelView.text = label
        labelView.textSize = 9.5f
        labelView.letterSpacing = 0.12f
        labelView.typeface = ResourcesCompat.getFont(context, R.font.rajdhani_bold)
        labelView.includeFontPadding = false
        labelView.setPadding(0, (3 * dp).toInt(), 0, 0)
        addView(labelView, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        applyTint()
    }

    fun setIcon(@DrawableRes res: Int) = iconView.setImageResource(res)

    fun setLabel(text: String) {
        labelView.text = text
    }

    override fun setSelected(selected: Boolean) {
        super.setSelected(selected)
        applyTint()
    }

    private fun applyTint() {
        // called from init and setters only (views exist by then)
        val c = if (isSelected) accent else Color.WHITE
        iconView.imageTintList = ColorStateList.valueOf(c)
        labelView.setTextColor(if (isSelected) accent else 0xCCFFFFFF.toInt())
    }
}
