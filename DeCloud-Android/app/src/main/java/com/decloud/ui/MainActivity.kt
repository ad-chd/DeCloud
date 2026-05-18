package com.decloud.ui

import android.Manifest
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.util.TypedValue
import android.view.View
import android.view.ViewAnimationUtils
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.decloud.R
import com.decloud.databinding.ActivityMainBinding
import com.decloud.databinding.DialogBackupOptionsBinding
import com.decloud.model.SelectionManager
import com.decloud.model.FileItem
import com.decloud.util.BackupCategory
import com.decloud.util.BackupManager
import com.decloud.util.CategoryScanResult
import com.decloud.util.ContactsBackup
import com.decloud.util.MediaScanner
import com.decloud.util.StorageUtils
import com.decloud.util.ThemeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Home screen (landing page) - file selection and navigation
 */
class MainActivity : BaseActivity() {

    private lateinit var binding: ActivityMainBinding

    companion object {
        // Store mode globally for use across activities
        var currentTransferMode: String = ModeSelectionActivity.MODE_WIFI
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            onPermissionsGranted()
        } else {
            showPermissionDeniedDialog()
        }
    }

    // For content provider category exports (Contacts)
    private var pendingExportCategory: BackupCategory? = null
    private val exportingCategories = mutableSetOf<BackupCategory>()
    private val exportedCategories = mutableSetOf<BackupCategory>()
    private val deniedCategories = mutableSetOf<BackupCategory>()
    private var statusToast: Toast? = null

    private val contentPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            pendingExportCategory?.let { cat ->
                deniedCategories.remove(cat)
                updateCategoryCardState(cat)
                runExport(cat)
            }
        } else {
            pendingExportCategory?.let { cat ->
                deniedCategories.add(cat)
                updateCategoryCardState(cat)
            }
            showStatusToast("Permission denied - tap again to retry")
        }
        pendingExportCategory = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Apply saved theme before setting content view
        ThemeManager.applyTheme(this)

        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Apply the styled brand tagline — "Transfer. Protect. Repeat." with bold white
        // Transfer./Repeat. and red Protect.
        binding.tvTagline.text = BrandTagline.build(this)

        // Tap the "DeCloud" title to open the About sheet (developer info + tap-to-copy email).
        binding.tvAppName.setOnClickListener { showAboutSheet() }

        setupUI()
        setupThemeToggle()
        playThemeTransitionIfNeeded()
        checkPermissions()
    }

    override fun onResume() {
        super.onResume()
        updateSelectionStatus()
        // Refresh denied states — if user granted permission via Settings, clear denied
        val toRemove = deniedCategories.filter { cat ->
            val perm = cat.requiredPermission
            perm != null && ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED
        }
        toRemove.forEach { cat ->
            deniedCategories.remove(cat)
            updateCategoryCardState(cat)
        }
    }

    private fun setupUI() {
        // Full Backup button
        binding.btnFullBackup.setOnClickListener {
            if (hasStoragePermission()) {
                showBackupOptionsDialog()
            } else {
                checkPermissions()
            }
        }

        // Browse Files button - show storage selection dialog
        binding.btnBrowseFiles.setOnClickListener {
            if (hasStoragePermission()) {
                showStorageSelectionDialog()
            } else {
                checkPermissions()
            }
        }

        // Receive from PC button
        binding.btnReceiveFromPc.setOnClickListener {
            if (hasStoragePermission()) {
                startActivity(Intent(this, ReceiveActivity::class.java))
                overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)
            } else {
                checkPermissions()
            }
        }

        // Global search button — launches SearchActivity without a scoped root,
        // so it searches across the whole external storage.
        binding.btnGlobalSearch.setOnClickListener {
            if (hasStoragePermission()) {
                startActivity(Intent(this, SearchActivity::class.java))
                overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)
            } else {
                checkPermissions()
            }
        }

        // Category buttons - open CategoryActivity with respective category
        binding.btnImages.setOnClickListener {
            openCategory(MediaScanner.Category.IMAGES)
        }

        binding.btnVideos.setOnClickListener {
            openCategory(MediaScanner.Category.VIDEOS)
        }

        binding.btnAudio.setOnClickListener {
            openCategory(MediaScanner.Category.AUDIO)
        }

        binding.btnDocuments.setOnClickListener {
            openCategory(MediaScanner.Category.DOCUMENTS)
        }

        binding.btnDownloads.setOnClickListener {
            openCategory(MediaScanner.Category.DOWNLOADS)
        }

        binding.btnApps.setOnClickListener {
            openCategory(MediaScanner.Category.APPLICATIONS)
        }

        // Content provider category buttons
        binding.btnContacts.setOnClickListener {
            exportContentCategory(BackupCategory.CONTACTS)
        }

        // Clear Selection button
        binding.btnClearSelection.setOnClickListener {
            SelectionManager.deselectAll()
            exportedCategories.clear()
            deniedCategories.clear()
            // Reset all category card visuals
            updateCategoryCardState(BackupCategory.CONTACTS)
            updateSelectionStatus()
        }

        // Continue button - go to mode selection before transfer
        binding.btnContinue.setOnClickListener {
            if (SelectionManager.hasSelection()) {
                startActivity(Intent(this, ModeSelectionActivity::class.java))
                overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)
            }
        }

        // Clickable selection area - opens SelectionActivity to edit selection
        binding.selectionInfoArea.setOnClickListener {
            if (SelectionManager.hasSelection()) {
                startActivity(Intent(this, SelectionActivity::class.java))
                overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)
            }
        }
    }

    private fun showStorageSelectionDialog() {
        val storageVolumes = StorageUtils.getStorageVolumes(this)

        if (storageVolumes.isEmpty()) {
            // No storage volumes found, go directly to browse
            val intent = Intent(this, BrowseActivity::class.java)
            startActivity(intent)
            overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)
            return
        }

        if (storageVolumes.size == 1) {
            // Only one storage, go directly
            val intent = Intent(this, BrowseActivity::class.java)
            intent.putExtra(BrowseActivity.EXTRA_STORAGE_INDEX, 0)
            startActivity(intent)
            overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)
            return
        }

        // Multiple storages - show a styled bottom sheet with icons and storage info
        val dialog = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        val pad = resources.getDimensionPixelSize(R.dimen.space_5xl)
        val iconSize = resources.getDimensionPixelSize(R.dimen.icon_touch)

        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(0, pad, 0, pad)
        }

        // Title
        container.addView(android.widget.TextView(this).apply {
            text = "Select Storage"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.text_subtitle))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(getColor(R.color.text_primary))
            setPadding(pad, resources.getDimensionPixelSize(R.dimen.space_md), pad, resources.getDimensionPixelSize(R.dimen.space_3xl))
        })

        // Ripple background
        val rippleBg = android.util.TypedValue().let { tv ->
            theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
            tv.resourceId
        }

        // Storage options
        storageVolumes.forEachIndexed { index, storage ->
            val row = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                val vertPad = resources.getDimensionPixelSize(R.dimen.space_3xl)
                setPadding(pad, vertPad, pad, vertPad)
                isClickable = true
                isFocusable = true
                setBackgroundResource(rippleBg)
                setOnClickListener {
                    dialog.dismiss()
                    val intent = Intent(this@MainActivity, BrowseActivity::class.java)
                    intent.putExtra(BrowseActivity.EXTRA_STORAGE_INDEX, index)
                    startActivity(intent)
                    overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)
                }
            }

            // Storage icon
            row.addView(android.widget.ImageView(this).apply {
                setImageResource(if (storage.isRemovable) R.drawable.ic_sd_card else R.drawable.ic_storage)
                setColorFilter(getColor(R.color.primary))
                layoutParams = android.widget.LinearLayout.LayoutParams(iconSize, iconSize)
            })

            // Storage info text
            val info = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                ).apply { marginStart = resources.getDimensionPixelSize(R.dimen.space_3xl) }
            }
            info.addView(android.widget.TextView(this).apply {
                text = storage.getShortName()
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.text_heading))
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(getColor(R.color.text_primary))
            })
            info.addView(android.widget.TextView(this).apply {
                text = storage.getFormattedFreeSpace()
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.text_caption))
                setTextColor(getColor(R.color.text_secondary))
            })
            row.addView(info)

            // Arrow
            row.addView(android.widget.ImageView(this).apply {
                setImageResource(R.drawable.ic_arrow_right)
                setColorFilter(getColor(R.color.text_hint))
                val arrowSize = resources.getDimensionPixelSize(R.dimen.icon_standard)
                layoutParams = android.widget.LinearLayout.LayoutParams(arrowSize, arrowSize)
            })

            container.addView(row)
        }

        dialog.setContentView(container)
        dialog.show()
    }

    private fun openCategory(category: MediaScanner.Category) {
        if (hasStoragePermission()) {
            val intent = Intent(this, CategoryActivity::class.java)
            intent.putExtra(CategoryActivity.EXTRA_CATEGORY, category.name)
            startActivity(intent)
            overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)
        } else {
            checkPermissions()
        }
    }

    private fun exportContentCategory(category: BackupCategory) {
        // Block if already exporting this category right now
        if (category in exportingCategories) return

        // If already exported and still in selection, allow removing it
        if (category in exportedCategories) {
            // Remove the exported file from selection
            removeExportedCategory(category)
            return
        }

        val permission = category.requiredPermission
        if (permission != null &&
            ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingExportCategory = category
            contentPermissionLauncher.launch(permission)
        } else {
            deniedCategories.remove(category)
            updateCategoryCardState(category)
            runExport(category)
        }
    }

    private fun removeExportedCategory(category: BackupCategory) {
        exportedCategories.remove(category)
        // Remove the exported file from SelectionManager
        val backupDir = File(cacheDir, "backup_exports")
        val prefix = when (category) {
            BackupCategory.CONTACTS -> "contacts_backup_"
            else -> ""
        }
        if (prefix.isNotEmpty()) {
            backupDir.listFiles()?.filter { it.name.startsWith(prefix) }?.forEach { file ->
                SelectionManager.deselectFile(file.absolutePath)
            }
        }
        updateCategoryCardState(category)
        updateSelectionStatus()
        showStatusToast("${category.displayName} removed")
    }

    private fun updateCategoryCardState(category: BackupCategory) {
        val card = when (category) {
            BackupCategory.CONTACTS -> binding.btnContacts
            else -> return
        }

        val strokeDp = resources.getDimensionPixelSize(R.dimen.space_2xs)

        when {
            category in exportedCategories -> {
                // Selected state — green tint with green border
                card.setCardBackgroundColor(ContextCompat.getColor(this, R.color.state_selected_bg))
                card.strokeWidth = strokeDp
                card.strokeColor = ContextCompat.getColor(this, R.color.state_selected_stroke)
            }
            category in deniedCategories -> {
                // Denied state — red tint with red border
                card.setCardBackgroundColor(ContextCompat.getColor(this, R.color.state_denied_bg))
                card.strokeWidth = strokeDp
                card.strokeColor = ContextCompat.getColor(this, R.color.state_denied_stroke)
            }
            else -> {
                // Normal state
                card.setCardBackgroundColor(getColor(R.color.surface))
                card.strokeWidth = 0
            }
        }
    }

    private fun runExport(category: BackupCategory) {
        if (category in exportingCategories) return
        exportingCategories.add(category)

        showLoadingOverlay("Exporting ${category.displayName}", "Please wait...")

        binding.btnCancelLoading.setOnClickListener {
            exportingCategories.remove(category)
            hideLoadingOverlay()
        }

        lifecycleScope.launch {
            val backupDir = File(cacheDir, "backup_exports")

            val result: Triple<Boolean, String, Int> = withContext(Dispatchers.IO) {
                backupDir.mkdirs()

                // Delete any previous export for this category
                val prefix = when (category) {
                    BackupCategory.CONTACTS -> "contacts_backup_"
                    else -> ""
                }
                if (prefix.isNotEmpty()) {
                    backupDir.listFiles()?.filter { it.name.startsWith(prefix) }?.forEach { it.delete() }
                }

                when (category) {
                    BackupCategory.CONTACTS -> {
                        val r = ContactsBackup.exportToVcf(this@MainActivity, backupDir)
                        Triple(r.success && r.filePath != null, r.filePath ?: "", r.contactCount)
                    }
                    else -> Triple(false, "", 0)
                }
            }

            exportingCategories.remove(category)
            hideLoadingOverlay()

            if (result.first && result.second.isNotEmpty()) {
                val file = File(result.second)
                val fileItem = FileItem.fromFile(file).copy(backupPrefix = "Backup")
                SelectionManager.setBackupMode(true)
                SelectionManager.addBackupItems(
                    listOf(fileItem),
                    listOf("Backup"),
                    backupDir.absolutePath,
                    "Backup"
                )
                exportedCategories.add(category)
                updateCategoryCardState(category)
                updateSelectionStatus()
                showStatusToast("${category.displayName}: ${result.third} items exported")
            } else if (result.third == 0) {
                showStatusToast("No ${category.displayName.lowercase()} found on device")
            } else {
                showStatusToast("Failed to export ${category.displayName.lowercase()}")
            }
        }
    }

    private fun showStatusToast(message: String) {
        statusToast?.cancel()
        statusToast = Toast.makeText(this, message, Toast.LENGTH_SHORT)
        statusToast?.show()
    }

    private fun updateSelectionStatus() {
        val count = SelectionManager.getSelectedCount()
        if (count > 0) {
            binding.selectionBottomBar.visibility = android.view.View.VISIBLE
            binding.selectionStatus.text = SelectionManager.getSelectionSummary()
            binding.selectionStatus.setTextColor(getColor(R.color.text_primary))
            binding.btnContinue.isEnabled = true
            binding.btnClearSelection.isEnabled = true
            binding.ivEditSelection.visibility = android.view.View.VISIBLE
        } else {
            binding.selectionBottomBar.visibility = android.view.View.GONE
        }
    }


    private fun checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+
            if (!Environment.isExternalStorageManager()) {
                showManageStoragePermissionDialog()
            } else {
                onPermissionsGranted()
            }
        } else {
            // Android 10 and below
            val permissions = arrayOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            )

            val notGranted = permissions.filter {
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }

            if (notGranted.isNotEmpty()) {
                permissionLauncher.launch(notGranted.toTypedArray())
            } else {
                onPermissionsGranted()
            }
        }
    }

    private fun hasStoragePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun showManageStoragePermissionDialog() {
        AlertDialog.Builder(this)
            .setTitle("Storage Permission Required")
            .setMessage("DeCloud needs access to all files to browse and transfer your data. Please grant 'All files access' permission.")
            .setPositiveButton("Grant Permission") { _, _ ->
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                    intent.data = Uri.parse("package:$packageName")
                    startActivity(intent)
                } catch (e: Exception) {
                    val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    startActivity(intent)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showPermissionDeniedDialog() {
        AlertDialog.Builder(this)
            .setTitle("Permission Denied")
            .setMessage("Storage permission is required to browse and transfer files. Please grant the permission in Settings.")
            .setPositiveButton("Open Settings") { _, _ ->
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                intent.data = Uri.parse("package:$packageName")
                startActivity(intent)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun onPermissionsGranted() {
        // Permissions granted, app is ready
        updateSelectionStatus()
    }

    /**
     * Show the About bottom sheet — developer info + tap-to-copy email.
     */
    private fun showAboutSheet() {
        val sheet = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.dialog_about, null)
        sheet.setContentView(view)

        val tvTagline = view.findViewById<android.widget.TextView>(R.id.tvAboutTagline)
        tvTagline.text = BrandTagline.build(this)

        val emailAddress = "adityachaudhary703@gmail.com"
        val btnCopyEmail = view.findViewById<android.view.View>(R.id.btnCopyEmail)
        val tvCopyHint = view.findViewById<android.widget.TextView>(R.id.tvCopyHint)

        btnCopyEmail.setOnClickListener {
            val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                as android.content.ClipboardManager
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("DeCloud email", emailAddress))
            tvCopyHint.text = "Copied to clipboard ✓"
            tvCopyHint.setTextColor(ContextCompat.getColor(this, R.color.success))
            android.widget.Toast.makeText(this, "Email copied", android.widget.Toast.LENGTH_SHORT).show()
        }

        view.findViewById<android.widget.Button>(R.id.btnAboutClose)
            .setOnClickListener { sheet.dismiss() }

        sheet.show()
    }

    private fun setupThemeToggle() {
        updateThemeIcon()

        binding.btnThemeToggle.setOnClickListener { view ->
            // Capture screenshot of current theme
            val rootView = window.decorView.rootView
            val bitmap = Bitmap.createBitmap(rootView.width, rootView.height, Bitmap.Config.ARGB_8888)
            rootView.draw(Canvas(bitmap))

            // Store bitmap and button center for reveal animation
            val loc = IntArray(2)
            view.getLocationOnScreen(loc)
            ThemeManager.transitionBitmap = bitmap
            ThemeManager.revealCenterX = loc[0] + view.width / 2
            ThemeManager.revealCenterY = loc[1] + view.height / 2

            // Toggle theme — this triggers activity recreation
            ThemeManager.toggleTheme(this)
        }
    }

    private fun playThemeTransitionIfNeeded() {
        val oldBitmap = ThemeManager.transitionBitmap ?: return
        ThemeManager.transitionBitmap = null

        val overlay = binding.themeOverlay

        // The activity has already recreated with the NEW theme.
        // Capture a screenshot of the new theme (current window).
        val rootView = window.decorView.rootView
        val newBitmap = Bitmap.createBitmap(rootView.width, rootView.height, Bitmap.Config.ARGB_8888)
        rootView.draw(Canvas(newBitmap))

        // Put the OLD theme on the overlay (covers everything — user sees old theme)
        overlay.setImageBitmap(oldBitmap)
        overlay.visibility = View.VISIBLE

        // Put the NEW theme on a second overlay, initially invisible (clipped to zero)
        val newOverlay = binding.themeOverlayNew
        newOverlay.setImageBitmap(newBitmap)
        newOverlay.visibility = View.INVISIBLE

        // Wait for layout, then circular-reveal the new theme outward from button center
        newOverlay.post {
            val cx = ThemeManager.revealCenterX
            val cy = ThemeManager.revealCenterY
            val maxRadius = Math.hypot(
                Math.max(cx, newOverlay.width - cx).toDouble(),
                Math.max(cy, newOverlay.height - cy).toDouble()
            ).toFloat()

            newOverlay.visibility = View.VISIBLE
            val anim = ViewAnimationUtils.createCircularReveal(newOverlay, cx, cy, 0f, maxRadius)
            anim.duration = 400
            anim.addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    overlay.visibility = View.GONE
                    overlay.setImageDrawable(null)
                    oldBitmap.recycle()
                    newOverlay.visibility = View.GONE
                    newOverlay.setImageDrawable(null)
                    newBitmap.recycle()
                }
            })
            anim.start()
        }
    }

    private fun updateThemeIcon() {
        val isDark = ThemeManager.isDarkMode(this)
        val iconRes = if (isDark) R.drawable.ic_light_mode else R.drawable.ic_dark_mode
        binding.btnThemeToggle.setImageResource(iconRes)
    }

    private fun showBackupOptionsDialog() {
        // Check if external storage is available
        val hasExternal = BackupManager.hasExternalStorage(this)

        // If no external storage, skip the dialog and go directly to internal-only backup
        if (!hasExternal) {
            startActivity(Intent(this, BackupSummaryActivity::class.java).apply {
                putExtra(BackupSummaryActivity.EXTRA_BACKUP_MODE, BackupManager.BackupMode.INTERNAL_ONLY.name)
            })
            return
        }

        val dialogBinding = DialogBackupOptionsBinding.inflate(layoutInflater)
        val dialog = MaterialAlertDialogBuilder(this)
            .setView(dialogBinding.root)
            .create()

        // Setup radio button group behavior
        var selectedMode = BackupManager.BackupMode.INTERNAL_ONLY

        dialogBinding.cardInternalOnly.setOnClickListener {
            selectedMode = BackupManager.BackupMode.INTERNAL_ONLY
            dialogBinding.radioInternalOnly.isChecked = true
            dialogBinding.radioWithExternal.isChecked = false
        }

        dialogBinding.cardWithExternal.setOnClickListener {
            if (hasExternal) {
                selectedMode = BackupManager.BackupMode.INTERNAL_AND_EXTERNAL
                dialogBinding.radioInternalOnly.isChecked = false
                dialogBinding.radioWithExternal.isChecked = true
            }
        }

        dialogBinding.radioInternalOnly.setOnClickListener {
            selectedMode = BackupManager.BackupMode.INTERNAL_ONLY
            dialogBinding.radioInternalOnly.isChecked = true
            dialogBinding.radioWithExternal.isChecked = false
        }

        dialogBinding.radioWithExternal.setOnClickListener {
            if (hasExternal) {
                selectedMode = BackupManager.BackupMode.INTERNAL_AND_EXTERNAL
                dialogBinding.radioInternalOnly.isChecked = false
                dialogBinding.radioWithExternal.isChecked = true
            }
        }

        // Estimate storage sizes in background
        lifecycleScope.launch {
            val (internalInfo, externalInfo) = BackupManager.estimateStorageSize(
                this@MainActivity,
                hasExternal
            )

            dialogBinding.tvInternalSize.text = internalInfo.getSummary()

            if (externalInfo != null) {
                val totalFiles = internalInfo.fileCount + externalInfo.fileCount
                val totalDirs = internalInfo.dirCount + externalInfo.dirCount
                val totalSize = internalInfo.totalSize + externalInfo.totalSize
                val combined = BackupManager.StorageSizeInfo(totalFiles, totalDirs, totalSize)
                dialogBinding.tvExternalSize.text = combined.getSummary()
            } else {
                dialogBinding.tvExternalSize.text = "External storage not available"
            }
        }

        // Cancel button
        dialogBinding.btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        // Start Backup button
        dialogBinding.btnStartBackup.setOnClickListener {
            dialog.dismiss()
            startActivity(Intent(this, BackupSummaryActivity::class.java).apply {
                putExtra(BackupSummaryActivity.EXTRA_BACKUP_MODE, selectedMode.name)
            })
        }

        dialog.show()
    }

    private var backupJob: kotlinx.coroutines.Job? = null

    private fun startFullBackup(mode: BackupManager.BackupMode) {
        // Show loading overlay
        showLoadingOverlay("Scanning Storage", "Preparing backup...")

        // Setup cancel button
        binding.btnCancelLoading.setOnClickListener {
            backupJob?.cancel()
            BackupManager.cancelBackup()
            hideLoadingOverlay()
        }

        backupJob = lifecycleScope.launch {
            BackupManager.startFullBackup(
                context = this@MainActivity,
                mode = mode,
                scope = this,
                listener = object : BackupManager.BackupScanListener {
                    override fun onScanStarted() {
                        runOnUiThread {
                            updateLoadingOverlay("Scanning Storage", "Starting scan...")
                        }
                    }

                    override fun onCategoryStarted(category: BackupCategory) {
                        // Not used in full backup mode
                    }

                    override fun onCategoryProgress(category: BackupCategory, scannedFiles: Int, currentFile: String) {
                        // Not used in full backup mode
                    }

                    override fun onCategoryComplete(result: CategoryScanResult) {
                        // Not used in full backup mode
                    }

                    override fun onScanProgress(scannedFiles: Int, scannedDirs: Int, currentPath: String) {
                        runOnUiThread {
                            val shortPath = if (currentPath.length > 40) {
                                "..." + currentPath.takeLast(37)
                            } else {
                                currentPath
                            }
                            updateLoadingOverlay(
                                "Found $scannedFiles files, $scannedDirs folders",
                                shortPath
                            )
                        }
                    }

                    override fun onScanComplete(totalFiles: Int, totalDirs: Int, totalSize: Long, excludedFiles: Int, excludedSize: Long) {
                        runOnUiThread {
                            hideLoadingOverlay()

                            if (totalFiles > 0) {
                                // Go to Ready to Send screen
                                updateSelectionStatus()
                                val message = if (excludedFiles > 0) {
                                    "Backup prepared: $totalFiles files ready ($excludedFiles excluded)"
                                } else {
                                    "Backup prepared: $totalFiles files ready to transfer"
                                }
                                Toast.makeText(
                                    this@MainActivity,
                                    message,
                                    Toast.LENGTH_SHORT
                                ).show()
                                startActivity(Intent(this@MainActivity, ModeSelectionActivity::class.java))
                            } else {
                                Toast.makeText(
                                    this@MainActivity,
                                    "No accessible files found",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    }

                    override fun onScanError(error: String) {
                        runOnUiThread {
                            hideLoadingOverlay()
                            Toast.makeText(this@MainActivity, error, Toast.LENGTH_LONG).show()
                        }
                    }
                }
            )
        }
    }

    // Loading overlay helper methods
    private fun showLoadingOverlay(message: String, detail: String = "") {
        binding.loadingOverlay.visibility = android.view.View.VISIBLE
        binding.tvLoadingMessage.text = message
        binding.tvLoadingDetail.text = detail
        binding.loadingOverlay.alpha = 0f
        binding.loadingOverlay.animate()
            .alpha(1f)
            .setDuration(250)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .start()
    }

    private fun updateLoadingOverlay(message: String, detail: String = "") {
        binding.tvLoadingMessage.text = message
        binding.tvLoadingDetail.text = detail
    }

    private fun hideLoadingOverlay() {
        binding.loadingOverlay.animate()
            .alpha(0f)
            .setDuration(200)
            .setInterpolator(android.view.animation.AccelerateInterpolator())
            .withEndAction {
                binding.loadingOverlay.visibility = android.view.View.GONE
            }
            .start()
    }
}
