package com.decloud.ui

import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import com.decloud.R

/**
 * Base activity that wraps content in a max-width container on tablets.
 * On phones (content_max_width == 0dp), content is unchanged.
 */
open class BaseActivity : AppCompatActivity() {

    override fun finish() {
        super.finish()
        overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right)
    }

    override fun setContentView(layoutResID: Int) {
        val maxWidth = resources.getDimensionPixelSize(R.dimen.content_max_width)
        if (maxWidth > 0) {
            val wrapper = FrameLayout(this).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            }
            val content = layoutInflater.inflate(layoutResID, wrapper, false)
            content.layoutParams = FrameLayout.LayoutParams(
                maxWidth,
                FrameLayout.LayoutParams.MATCH_PARENT
            ).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            }
            wrapper.addView(content)
            super.setContentView(wrapper)
        } else {
            super.setContentView(layoutResID)
        }
    }

    override fun setContentView(view: View?) {
        val maxWidth = resources.getDimensionPixelSize(R.dimen.content_max_width)
        if (maxWidth > 0 && view != null) {
            val wrapper = FrameLayout(this).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            }
            view.layoutParams = FrameLayout.LayoutParams(
                maxWidth,
                FrameLayout.LayoutParams.MATCH_PARENT
            ).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            }
            wrapper.addView(view)
            super.setContentView(wrapper)
        } else {
            super.setContentView(view)
        }
    }
}
