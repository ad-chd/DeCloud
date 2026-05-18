package com.decloud.receiver

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.decloud.R
import com.decloud.service.AdbTransferService
import com.decloud.ui.ReceiveActivity

/**
 * Broadcast Receiver to handle ADB commands from PC
 *
 * PC can trigger actions via:
 * adb shell am broadcast -a com.decloud.PREPARE
 * adb shell am broadcast -a com.decloud.CLEANUP
 * adb shell am broadcast -a com.decloud.STATUS
 *
 * USB receive progress (PC → Phone push):
 * adb shell am broadcast -a com.decloud.USB_RECEIVE_START --ei total_files N
 * adb shell am broadcast -a com.decloud.USB_FILE_RECEIVED --es file_name "name" --ei index I --ei total N
 * adb shell am broadcast -a com.decloud.USB_RECEIVE_DONE --ei total_files N --ei failed F
 */
class AdbCommandReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "AdbCommandReceiver"
        private const val CHANNEL_ID = "usb_receive_channel"
        private const val NOTIFICATION_ID = 2001

        const val ACTION_USB_RECEIVE_START = "com.decloud.USB_RECEIVE_START"
        const val ACTION_USB_FILE_RECEIVED = "com.decloud.USB_FILE_RECEIVED"
        const val ACTION_USB_RECEIVE_DONE = "com.decloud.USB_RECEIVE_DONE"

        // Local broadcast actions (internal, forwarded to ReceiveActivity)
        const val LOCAL_USB_FILE_RECEIVED = "local.USB_FILE_RECEIVED"
        const val LOCAL_USB_RECEIVE_DONE = "local.USB_RECEIVE_DONE"
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "Received broadcast: ${intent.action}")

        when (intent.action) {
            AdbTransferService.ACTION_PREPARE -> {
                val serviceIntent = Intent(context, AdbTransferService::class.java).apply {
                    action = AdbTransferService.ACTION_PREPARE
                }
                startService(context, serviceIntent)
            }

            AdbTransferService.ACTION_CLEANUP -> {
                val serviceIntent = Intent(context, AdbTransferService::class.java).apply {
                    action = AdbTransferService.ACTION_CLEANUP
                }
                startService(context, serviceIntent)
            }

            AdbTransferService.ACTION_STATUS -> {
                val serviceIntent = Intent(context, AdbTransferService::class.java).apply {
                    action = AdbTransferService.ACTION_STATUS
                }
                startService(context, serviceIntent)
            }

            ACTION_USB_RECEIVE_START -> {
                val totalFiles = intent.getIntExtra("total_files", 0)
                ensureNotificationChannel(context)
                showReceiveStartNotification(context, totalFiles)

                // Launch ReceiveActivity in USB mode
                val activityIntent = Intent(context, ReceiveActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    putExtra(ReceiveActivity.EXTRA_USB_MODE, true)
                    putExtra(ReceiveActivity.EXTRA_USB_TOTAL_FILES, totalFiles)
                }
                context.startActivity(activityIntent)
            }

            ACTION_USB_FILE_RECEIVED -> {
                val fileName = intent.getStringExtra("file_name") ?: ""
                val index = intent.getIntExtra("index", 0)
                val total = intent.getIntExtra("total", 0)
                showReceiveProgressNotification(context, fileName, index, total)

                // Forward to ReceiveActivity via local broadcast
                val localIntent = Intent(LOCAL_USB_FILE_RECEIVED).apply {
                    putExtra("file_name", fileName)
                    putExtra("index", index)
                    putExtra("total", total)
                }
                LocalBroadcastManager.getInstance(context).sendBroadcast(localIntent)
            }

            ACTION_USB_RECEIVE_DONE -> {
                val totalFiles = intent.getIntExtra("total_files", 0)
                val failed = intent.getIntExtra("failed", 0)
                showReceiveDoneNotification(context, totalFiles, failed)

                // Forward to ReceiveActivity via local broadcast
                val localIntent = Intent(LOCAL_USB_RECEIVE_DONE).apply {
                    putExtra("total_files", totalFiles)
                    putExtra("failed", failed)
                }
                LocalBroadcastManager.getInstance(context).sendBroadcast(localIntent)
            }
        }
    }

    private fun startService(context: Context, intent: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    private fun ensureNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "USB File Transfer",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows progress when receiving files via USB"
                setShowBadge(false)
            }
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.createNotificationChannel(channel)
        }
    }

    private fun getOpenAppIntent(context: Context): PendingIntent {
        val intent = Intent(context, ReceiveActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(ReceiveActivity.EXTRA_USB_MODE, true)
        }
        return PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun showReceiveStartNotification(context: Context, totalFiles: Int) {
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle("Receiving files via USB")
            .setContentText("Preparing to receive $totalFiles files...")
            .setProgress(totalFiles, 0, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(getOpenAppIntent(context))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .build()

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        nm?.notify(NOTIFICATION_ID, notification)
    }

    private fun showReceiveProgressNotification(context: Context, fileName: String, index: Int, total: Int) {
        val shortName = if (fileName.length > 35) "...${fileName.takeLast(32)}" else fileName

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle("Receiving files via USB ($index/$total)")
            .setContentText(shortName)
            .setProgress(total, index, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(getOpenAppIntent(context))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .build()

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        nm?.notify(NOTIFICATION_ID, notification)
    }

    private fun showReceiveDoneNotification(context: Context, totalFiles: Int, failed: Int) {
        val title = if (failed == 0) "Transfer complete" else "Transfer complete ($failed failed)"
        val text = "$totalFiles files received via USB"

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_success)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(false)
            .setAutoCancel(true)
            .setContentIntent(getOpenAppIntent(context))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        nm?.notify(NOTIFICATION_ID, notification)
    }
}
