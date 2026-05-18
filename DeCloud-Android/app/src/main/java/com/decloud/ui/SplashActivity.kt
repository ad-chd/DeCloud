package com.decloud.ui

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import androidx.appcompat.app.AppCompatActivity
import com.decloud.R
import com.decloud.databinding.ActivitySplashBinding

class SplashActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySplashBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySplashBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Styled brand tagline — bold white "Transfer." / "Repeat." with red "Protect.".
        binding.splashTagline.text = BrandTagline.build(this)

        binding.splashLogo.alpha = 0f
        binding.splashLogo.scaleX = 0.6f
        binding.splashLogo.scaleY = 0.6f
        binding.splashGlow.alpha = 0f
        binding.splashGlow.scaleX = 0.5f
        binding.splashGlow.scaleY = 0.5f
        binding.splashName.alpha = 0f
        binding.splashName.translationY = 40f
        binding.splashTagline.alpha = 0f
        binding.splashTagline.translationY = 30f

        val logoScaleX = ObjectAnimator.ofFloat(binding.splashLogo, View.SCALE_X, 0.6f, 1f)
        val logoScaleY = ObjectAnimator.ofFloat(binding.splashLogo, View.SCALE_Y, 0.6f, 1f)
        val logoFade = ObjectAnimator.ofFloat(binding.splashLogo, View.ALPHA, 0f, 1f)
        val logoSet = AnimatorSet().apply {
            playTogether(logoScaleX, logoScaleY, logoFade)
            duration = 600
            interpolator = OvershootInterpolator(1.2f)
        }

        // Red glow pulse — emanates outward as the logo settles
        val glowScaleX = ObjectAnimator.ofFloat(binding.splashGlow, View.SCALE_X, 0.5f, 1.4f)
        val glowScaleY = ObjectAnimator.ofFloat(binding.splashGlow, View.SCALE_Y, 0.5f, 1.4f)
        val glowFade = ObjectAnimator.ofFloat(binding.splashGlow, View.ALPHA, 0f, 0.55f, 0f)
        val glowSet = AnimatorSet().apply {
            playTogether(glowScaleX, glowScaleY, glowFade)
            duration = 800
            interpolator = DecelerateInterpolator()
            startDelay = 300
        }

        val nameFade = ObjectAnimator.ofFloat(binding.splashName, View.ALPHA, 0f, 1f)
        val nameRise = ObjectAnimator.ofFloat(binding.splashName, View.TRANSLATION_Y, 40f, 0f)
        val nameSet = AnimatorSet().apply {
            playTogether(nameFade, nameRise)
            duration = 500
            interpolator = DecelerateInterpolator()
            startDelay = 350
        }

        val taglineFade = ObjectAnimator.ofFloat(binding.splashTagline, View.ALPHA, 0f, 1f)
        val taglineRise = ObjectAnimator.ofFloat(binding.splashTagline, View.TRANSLATION_Y, 30f, 0f)
        val taglineSet = AnimatorSet().apply {
            playTogether(taglineFade, taglineRise)
            duration = 450
            interpolator = DecelerateInterpolator()
            startDelay = 650
        }

        AnimatorSet().apply {
            playTogether(logoSet, glowSet, nameSet, taglineSet)
            start()
        }

        binding.root.postDelayed({
            startActivity(Intent(this, MainActivity::class.java))
            overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
            finish()
        }, 1600)
    }

    override fun onBackPressed() {
        // Block back during splash
    }
}
