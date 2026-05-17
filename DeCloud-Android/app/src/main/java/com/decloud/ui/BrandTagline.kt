package com.decloud.ui

import android.content.Context
import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import androidx.core.content.ContextCompat
import com.decloud.R

object BrandTagline {
    const val TEXT = "Transfer. Protect. Repeat."

    fun build(context: Context): SpannableString {
        val ss = SpannableString(TEXT)
        val primary = ContextCompat.getColor(context, R.color.text_primary)
        val accent = ContextCompat.getColor(context, R.color.tagline_accent)

        // Each triple: (startIndex, length, color, bold?)
        data class Part(val start: Int, val length: Int, val color: Int, val bold: Boolean)
        // All three words share the same weight (bold) and size — only Protect's color differs.
        val parts = listOf(
            Part(TEXT.indexOf("Transfer."), "Transfer.".length, primary, true),
            Part(TEXT.indexOf("Protect."), "Protect.".length, accent, true),
            Part(TEXT.indexOf("Repeat."), "Repeat.".length, primary, true)
        )
        for (p in parts) {
            val end = p.start + p.length
            ss.setSpan(ForegroundColorSpan(p.color), p.start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (p.bold) ss.setSpan(StyleSpan(Typeface.BOLD), p.start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return ss
    }
}
