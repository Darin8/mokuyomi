package eu.kanade.tachiyomi.data.mokuro

import android.content.Context
import androidx.core.app.NotificationCompat
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.util.system.notificationManager

class MokuroNotifier(private val context: Context) {

    // Give each chapter a unique notification ID derived from its ID
    private fun notifId(chapterId: Long): Int =
        Notifications.ID_MOKURO_PROGRESS - (chapterId % 500).toInt()

    fun showProgress(chapterId: Long, chapterName: String, progress: Float) {
        val notification = NotificationCompat.Builder(context, Notifications.CHANNEL_MOKURO_PROGRESS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(chapterName)
            .setContentText("Processing with Mokuro…")
            .setOngoing(true)
            .setProgress(100, (progress * 100).toInt(), progress == 0f)
            .build()
        context.notificationManager.notify(notifId(chapterId), notification)
    }

    fun showDone(chapterId: Long, chapterName: String) {
        val notification = NotificationCompat.Builder(context, Notifications.CHANNEL_MOKURO_PROGRESS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(chapterName)
            .setContentText("Ready to read in Mokuro reader")
            .setAutoCancel(true)
            .build()
        context.notificationManager.notify(notifId(chapterId), notification)
    }

    fun showFailed(chapterId: Long, chapterName: String, errorMessage: String?) {
        val notification = NotificationCompat.Builder(context, Notifications.CHANNEL_MOKURO_PROGRESS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(chapterName)
            .setContentText("Processing failed: ${errorMessage ?: "Unknown error"}")
            .setAutoCancel(true)
            .build()
        context.notificationManager.notify(notifId(chapterId), notification)
    }

    fun dismiss(chapterId: Long) {
        context.notificationManager.cancel(notifId(chapterId))
    }
}
