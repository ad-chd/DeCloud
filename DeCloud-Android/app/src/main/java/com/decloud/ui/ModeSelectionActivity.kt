package com.decloud.ui

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater

import androidx.core.content.ContextCompat

import com.google.android.material.bottomsheet.BottomSheetDialog
import com.decloud.R
import com.decloud.databinding.ActivityModeSelectionBinding

/**
 * Mode Selection Activity - shown after file selection, before transfer.
 * Allows user to choose between WiFi and ADB transfer modes.
 */
class ModeSelectionActivity : BaseActivity() {

    private lateinit var binding: ActivityModeSelectionBinding

    // Selected mode
    private var selectedMode = MODE_WIFI

    companion object {
        const val EXTRA_TRANSFER_MODE = "transfer_mode"
        const val MODE_WIFI = "wifi"
        const val MODE_ADB = "adb"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityModeSelectionBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupModeSelection()
        setupContinueButton()

        // Default to WiFi mode
        selectWifiMode()
    }

    private fun setupModeSelection() {
        binding.cardWifiMode.setOnClickListener {
            selectWifiMode()
        }

        binding.cardAdbMode.setOnClickListener {
            selectAdbMode()
        }
    }

    private fun selectWifiMode() {
        selectedMode = MODE_WIFI

        binding.radioWifi.isChecked = true
        binding.radioAdb.isChecked = false

        binding.cardWifiMode.strokeWidth = resources.getDimensionPixelSize(R.dimen.space_xs)
        binding.cardWifiMode.setStrokeColor(ColorStateList.valueOf(ContextCompat.getColor(this, R.color.mode_wifi_accent)))

        binding.cardAdbMode.strokeWidth = resources.getDimensionPixelSize(R.dimen.space_2xs)
        binding.cardAdbMode.setStrokeColor(ColorStateList.valueOf(ContextCompat.getColor(this, R.color.divider)))
    }

    private fun selectAdbMode() {
        selectedMode = MODE_ADB

        binding.radioWifi.isChecked = false
        binding.radioAdb.isChecked = true

        binding.cardAdbMode.strokeWidth = resources.getDimensionPixelSize(R.dimen.space_xs)
        binding.cardAdbMode.setStrokeColor(ColorStateList.valueOf(ContextCompat.getColor(this, R.color.mode_adb_accent)))

        binding.cardWifiMode.strokeWidth = resources.getDimensionPixelSize(R.dimen.space_2xs)
        binding.cardWifiMode.setStrokeColor(ColorStateList.valueOf(ContextCompat.getColor(this, R.color.divider)))
    }

    private fun setupContinueButton() {
        binding.btnContinue.setOnClickListener {
            if (selectedMode == MODE_WIFI) {
                showWifiGuide()
            } else {
                proceedToTransfer()
            }
        }
    }

    private fun showWifiGuide() {
        val dialog = BottomSheetDialog(this)
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_wifi_guide, null)

        view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnGotIt)
            .setOnClickListener {
                dialog.dismiss()
                proceedToTransfer()
            }

        dialog.setContentView(view)
        dialog.show()
    }

    private fun proceedToTransfer() {
        MainActivity.currentTransferMode = selectedMode
        startActivity(Intent(this, ReadyToSendActivity::class.java))
        overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)
    }
}
